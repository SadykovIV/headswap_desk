package com.headswap.desk.dex

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** Shared owned-display lease engine. Each backend must run in its own producer process. */
@Keep
class DexUserService private constructor(private val context: Context, private val delegated: Boolean,
    private val delegatedInputGateway: IDelegatedInputGateway?) : IDexUserService.Stub() {
    /** Keep this exact public constructor: Shizuku instantiates it in a dedicated shell process. */
    @Keep constructor(context: Context) : this(context, false, null)
    private val delegatedShutdown = DelegatedShutdownGate()
    private val clientUid = context.applicationInfo.uid
    private val worker = HandlerThread(if (delegated) "headswap-app-display" else "headswap-shell-display").apply { start() }
    private val handler = Handler(worker.looper)
    private val observations = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "headswap-task-placement").apply { isDaemon = true } })
    private val generation = AtomicLong()
    private val currentCommand = AtomicReference<java.lang.Process?>()
    // Never return the service Binder to a companion; this token has no callable operations.
    private val producerLifetime = Binder()
    // Also readable by the last-resort process-exit path if the worker is stuck in Binder.
    private val panelRestore = AtomicReference<PanelRestore?>()
    // Everything below is accessed only on worker, including destruction and input state.
    private var session: Session? = null
    private var runtime: DesktopRuntimeAccess? = null
    private var mouse = DexMouseState()
    private val keyDownTimes = HashMap<Int, Long>()
    private var mouseDownTime = 0L

    private class Session(val epoch: Long, val surface: Surface, val token: IBinder, val death: IBinder.DeathRecipient,
        val density: Int, val desktopMode: Int, val width: Int, val height: Int) {
        var display: VirtualDisplay? = null
        var linked = false
        val children = LinkedHashMap<IBinder, ChildSession>()
        val contacts = DesktopTouchState()
        var touchDownTime = 0L
        var backTarget: IBinder? = null // Keep hardware BACK's UP on the same lease as its DOWN.
    }

    private class ChildSession(val parentEpoch: Long, val surface: Surface, val token: IBinder,
        val death: IBinder.DeathRecipient, var width: Int, var height: Int, var density: Int, val fullscreen: Boolean,
        val displayName: String) {
        var display: VirtualDisplay? = null
        var linked = false
        var touch = EmbeddedTouchState(width, height)
        val contacts = DesktopTouchState()
        var downTime = 0L
        var launchedPackage: String? = null
        var taskStateAt = 0L
        var taskState = -1
        var revision = 0L
    }

    private class Placement(val child: ChildSession, val revision: Long, val cached: Int?, val read: (() -> Int)?)

    override fun protocolVersion(): Int {
        enforceClient()
        return DexUserServicePolicy.PROTOCOL_VERSION
    }

    override fun start(surface: Surface, lifetimeToken: IBinder, density: Int): Int =
        startDesktop(surface, lifetimeToken, density, DesktopModePolicy.SAMSUNG_DEX)

    override fun startDesktop(surface: Surface, lifetimeToken: IBinder, density: Int, mode: Int): Int =
        startDesktopSized(surface, lifetimeToken, DexUserServicePolicy.WIDTH, DexUserServicePolicy.HEIGHT, density, mode)

    override fun startDesktopSized(surface: Surface, lifetimeToken: IBinder, width: Int, height: Int, density: Int, mode: Int): Int {
        enforceClient()
        DesktopModePolicy.requireMode(mode)
        if (delegated) DelegatedDesktopPolicy.requireMode(mode)
        DexUserServicePolicy.requireDensity(density)
        DexUserServicePolicy.requireDimensions(width, height)
        require(surface.isValid) { "Поверхность видеокодера недоступна" }
        require(lifetimeToken.isBinderAlive) { "Приложение уже остановлено" }
        val epoch = generation.incrementAndGet()
        currentCommand.get()?.destroyForcibly()
        try { return serialized(START_TIMEOUT_MS) {
            releaseCurrent()
            check(generation.get() == epoch && lifetimeToken.isBinderAlive) { "DeX остановлен" }
            lateinit var lease: Session
            val death = IBinder.DeathRecipient {
                if (generation.compareAndSet(epoch, epoch + 1)) {
                    currentCommand.get()?.destroyForcibly()
                    Thread({
                        runCatching {
                            serialized(STOP_TIMEOUT_MS, front = true, cancelOnTimeout = false) {
                                if (session === lease) releaseCurrent()
                            }
                        }.onFailure { Log.w(TAG, "owner death cleanup failed", it) }
                    }, "headswap-owner-death").apply { isDaemon = true; start() }
                }
            }
            lease = Session(epoch, surface, lifetimeToken, death, density, mode, width, height)
            mouse = DexMouseState(width, height)
            session = lease
            try {
                lifetimeToken.linkToDeath(death, 0)
                lease.linked = true
                ensureActive(epoch)
                val access = runtime ?: (if (delegated) DelegatedDesktopRuntime(context, checkNotNull(delegatedInputGateway)) else RuntimeAccess(context)).also { runtime = it }
                ensureActive(epoch)
                lease.display = access.createDisplay(surface, width, height, density, mode)
                // A stop/death that arrived during create must release its late result.
                ensureActive(epoch)
                val display = checkNotNull(lease.display) { "Не удалось создать экран DeX" }.display
                val id = display.displayId
                validateDisplay(lease)
                if (mode == DesktopModePolicy.SAMSUNG_DEX) {
                    command(epoch, "/system/bin/wm", "set-display-engagement-mode", "-d", id.toString(), "3")
                    command(epoch, "/system/bin/wm", "set-display-windowing-mode", "-d", id.toString(), "5")
                    ensureActive(epoch)
                    check(access.eligible(id)) { "One UI не разрешил системный рабочий стол на созданном экране" }
                } else if (!delegated) {
                    // Generic Android Desktop does not depend on Samsung's desktop eligibility,
                    // content-mode flag, secondary launcher, freeform windows or phone HOME setting.
                    command(epoch, "/system/bin/wm", "set-display-windowing-mode", "-d", id.toString(), "1", required = false)
                }
                validateDisplay(lease)
                access.registerParentDisplay(id, lease.token, width, height)
                ensureActive(epoch)
                launchDesktop(lease)
                ensureActive(epoch)
                Log.i(TAG, "started display=$id mode=${DesktopModePolicy.label(mode)} native=${width}x${height} density=$density flags=${DesktopModePolicy.displayFlags(mode, android.os.Build.VERSION.SDK_INT)}")
                id
            } catch (e: Throwable) {
                releaseCurrent()
                throw e
            }
        } } catch (e: Throwable) {
            // Includes a request cancelled while still queued, before Session was installed.
            surface.release()
            throw e
        }
    }

    override fun injectPointer(action: Int, x: Int, y: Int, buttons: Int) {
        enforceClient()
        serialized(INPUT_TIMEOUT_MS) {
            val active = requireSession()
            val events = mouse.events(action, x, y, buttons)
            val access = checkNotNull(runtime)
            for (event in events) {
                ensureActive(active.epoch)
                if (event.action == MotionEvent.ACTION_DOWN) mouseDownTime = SystemClock.uptimeMillis()
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                val coords = MotionEvent.PointerCoords().apply {
                    this.x = event.x.toFloat(); this.y = event.y.toFloat()
                    pressure = if (event.buttons != 0) 1f else 0f
                    size = 1f
                }
                val motion = MotionEvent.obtain(mouseDownTime, SystemClock.uptimeMillis(), event.action, 1,
                    arrayOf(properties), arrayOf(coords), 0, event.buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                try {
                    access.inject(motion, checkNotNull(active.display).display.displayId, event.actionButton)
                } finally { motion.recycle() }
            }
        }
    }

    override fun injectKey(action: Int, keycode: Int) {
        enforceClient()
        require(action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) { "Недопустимое действие клавиши" }
        // CarLife NAVI is a vendor code, not an Android key event.
        if (keycode > 1000) return
        require(keycode > 0) { "Недопустимая клавиша" }
        serialized(if (keycode == KeyEvent.KEYCODE_HOME || keycode == KeyEvent.KEYCODE_APP_SWITCH) START_TIMEOUT_MS else INPUT_TIMEOUT_MS) {
            val active = requireSession()
            if (DesktopModePolicy.consumesNavigationKey(active.desktopMode, keycode)) {
                if (action == KeyEvent.ACTION_UP) launchDesktop(active)
            } else if (keycode == KeyEvent.KEYCODE_BACK) {
                hardwareBack(active, action)
            } else injectKeyInto(active, action, keycode)
        }
    }

    override fun injectTouchPoints(action: Int, actionIndex: Int, pointerIds: IntArray,
        xs: FloatArray, ys: FloatArray) {
        enforceClient()
        DesktopTouchState.validate(action, actionIndex, pointerIds, xs, ys)
        serialized(INPUT_TIMEOUT_MS) {
            val active = requireSession()
            check(active.desktopMode != DesktopModePolicy.SAMSUNG_DEX) { "DeX uses mouse input" }
            for (event in active.contacts.events(action, actionIndex, pointerIds, xs, ys, active.width, active.height)) {
                ensureActive(active.epoch)
                if (event.action == MotionEvent.ACTION_DOWN) active.touchDownTime = SystemClock.uptimeMillis()
                injectContacts(checkNotNull(active.display).display.displayId, active.touchDownTime, event)
            }
        }
    }

    override fun injectChildTouchPoints(childToken: IBinder, action: Int, actionIndex: Int,
        pointerIds: IntArray, xs: FloatArray, ys: FloatArray) {
        enforceClient()
        DesktopTouchState.validate(action, actionIndex, pointerIds, xs, ys)
        serialized(INPUT_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            child.touch.cancel()?.let { injectChildTouchEvent(child, it) }
            for (event in child.contacts.events(action, actionIndex, pointerIds, xs, ys, child.width, child.height)) {
                ensureChildActive(parent, child)
                if (event.action == MotionEvent.ACTION_DOWN) child.downTime = SystemClock.uptimeMillis()
                injectContacts(checkNotNull(child.display).display.displayId, child.downTime, event)
            }
        }
    }

    private fun injectContacts(displayId: Int, downTime: Long, event: DesktopTouchState.Event) {
        val props = event.points.map { point -> MotionEvent.PointerProperties().apply {
            id = point.id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coords = event.points.mapIndexed { i, point -> MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y; size = 1f
            pressure = if (event.action == 1 || event.action == 3 || (event.action == 6 && event.actionIndex == i)) 0f else 1f
        } }.toTypedArray()
        val action = event.action or (if (event.action == 5 || event.action == 6) event.actionIndex shl 8 else 0)
        val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, props.size,
            props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { checkNotNull(runtime).inject(motion, displayId) } finally { motion.recycle() }
    }

    override fun showDesktop() {
        enforceClient()
        serialized(START_TIMEOUT_MS) { launchDesktop(requireSession()) }
    }

    private fun launchDesktop(active: Session) {
        ensureActive(active.epoch)
        val id = checkNotNull(active.display).display.displayId
        if (delegated) checkNotNull(runtime).launchDesktop(active.desktopMode, id)
        else command(active.epoch, *DesktopModePolicy.homeCommand(active.desktopMode, id, context.packageName))
        ensureActive(active.epoch)
    }

    private fun hardwareBack(active: Session, action: Int) {
        val parentId = checkNotNull(active.display).display.displayId
        if (action == KeyEvent.ACTION_DOWN) {
            if (active.backTarget != null) return
            val children = active.children.values.mapNotNull { child ->
                child.display?.display?.takeIf { it.isValid }?.let { it.displayId to child }
            }.toMap()
            val queries = children.keys.associateWith { checkNotNull(runtime).childImeVisibilityQuery(it) }
            val target = if (queries.isEmpty()) parentId else DesktopReadDeadline.call(
                { observations.execute(it); true }, 450, { parentId }) {
                DesktopImePolicy.backTarget(parentId, queries.mapValues { runCatching(it.value).getOrNull() })
            }
            active.backTarget = children[target]?.token ?: active.token
            try { injectKeyInto(active, action, KeyEvent.KEYCODE_BACK, target) }
            catch (e: Exception) { active.backTarget = null; keyDownTimes.remove(KeyEvent.KEYCODE_BACK); throw e }
        } else {
            val token = active.backTarget ?: return
            active.backTarget = null
            val target = if (active.token == token) parentId else active.children[token]?.display?.display?.takeIf { it.isValid }?.displayId
            // A closed child cannot redirect half of a gesture into the desktop or a new child.
            if (target != null) injectKeyInto(active, action, KeyEvent.KEYCODE_BACK, target)
            else keyDownTimes.remove(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun injectKeyInto(active: Session, action: Int, keycode: Int, id: Int = checkNotNull(active.display).display.displayId) {
        ensureActive(active.epoch)
        val now = SystemClock.uptimeMillis()
        val down = if (action == KeyEvent.ACTION_DOWN) keyDownTimes.getOrPut(keycode) { now }
            else keyDownTimes.remove(keycode) ?: now
        val key = KeyEvent(down, now, action, keycode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        checkNotNull(runtime).inject(key, id)
    }

    override fun createChildDisplay(surface: Surface, childToken: IBinder, width: Int, height: Int, density: Int): Int =
        createChild(surface, childToken, width, height, density, fullscreen = false)

    override fun createFullscreenChildDisplay(surface: Surface, childToken: IBinder, width: Int, height: Int, density: Int): Int =
        createChild(surface, childToken, width, height, density, fullscreen = true)

    private fun createChild(surface: Surface, childToken: IBinder, width: Int, height: Int, density: Int, fullscreen: Boolean): Int {
        enforceClient()
        try {
            EmbeddedDisplayPolicy.requireSize(width, height, density)
            require(surface.isValid && childToken.isBinderAlive) { "Поверхность виджета уже закрыта" }
            return serialized(START_TIMEOUT_MS) {
                val parent = requireDriveDeckSession()
                if (fullscreen) check(parent.desktopMode == DesktopModePolicy.DRIVEDECK) { "Полноэкранное окно требует DriveDeck" }
                check(!parent.children.containsKey(childToken)) { "Поверхность виджета уже зарегистрирована" }
                val siblings = parent.children.values.filter { it.fullscreen == fullscreen }
                EmbeddedDisplayPolicy.requireCapacity(siblings.size, fullscreen)
                EmbeddedDisplayPolicy.requirePixelBudget(siblings.sumOf { it.width.toLong() * it.height }, width, height, fullscreen)
                lateinit var child: ChildSession
                val death = IBinder.DeathRecipient {
                    // Never increment the parent's epoch when just one companion Surface dies.
                    handler.postAtFrontOfQueue {
                        if (session === parent && parent.children[childToken] === child) releaseChild(parent, child)
                    }
                }
                val name = DesktopImePolicy.childName(fullscreen, parent.children.values.map { it.displayName })
                child = ChildSession(parent.epoch, surface, childToken, death, width, height, density, fullscreen, name)
                parent.children[childToken] = child
                try {
                    childToken.linkToDeath(death, 0)
                    child.linked = true
                    ensureChildActive(parent, child, requireDisplay = false)
                    child.display = checkNotNull(runtime).createChildDisplay(name, surface, width, height, density)
                    ensureChildActive(parent, child)
                    val id = checkNotNull(child.display).display.displayId
                    validateChildDisplay(parent, child)
                    checkNotNull(runtime).registerChildDisplay(checkNotNull(parent.display).display.displayId,
                        id, child.token, width, height, fullscreen)
                    ensureChildActive(parent, child)
                    Log.i(TAG, "child display=$id parent=${parent.display?.display?.displayId} size=${width}x$height density=$density")
                    id
                } catch (error: Throwable) {
                    releaseChild(parent, child)
                    throw error
                }
            }
        } catch (error: Throwable) {
            // Also disposes the received Binder Surface when admission fails before a lease exists.
            surface.release()
            throw error
        }
    }

    override fun launchChildApp(childToken: IBinder, packageName: String, className: String) {
        enforceClient()
        serialized(START_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            val desktopPane = parent.desktopMode == DesktopModePolicy.ANDROID_DESKTOP || child.fullscreen
            val arguments = EmbeddedDisplayPolicy.launchCommand(checkNotNull(child.display).display.displayId,
                checkNotNull(parent.display).display.displayId, clientUid / 100000, packageName, className, desktopPane)
            // The signature-protected app gateway resolves an enabled/exported MAIN+LAUNCHER
            // activity. This private shell API additionally accepts only an explicit component.
            if (delegated) checkNotNull(runtime).launchChild(checkNotNull(parent.display).display.displayId,
                checkNotNull(child.display).display.displayId, packageName, className, desktopPane)
            else command(parent.epoch, *arguments)
            ensureChildActive(parent, child)
            child.launchedPackage = packageName
            child.revision++
            child.taskStateAt = 0L
        }
    }

    override fun resizeChildDisplay(childToken: IBinder, width: Int, height: Int, density: Int) {
        enforceClient()
        EmbeddedDisplayPolicy.requireSize(width, height, density)
        serialized(START_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            EmbeddedDisplayPolicy.requirePixelBudget(
                parent.children.values.filter { it !== child && it.fullscreen == child.fullscreen }
                    .sumOf { it.width.toLong() * it.height }, width, height, child.fullscreen)
            cancelChildTouch(child)
            checkNotNull(child.display).resize(width, height, density)
            ensureChildActive(parent, child)
            child.width = width
            child.height = height
            child.density = density
            child.touch = EmbeddedTouchState(width, height)
            checkNotNull(runtime).resizeRegisteredDisplay(checkNotNull(child.display).display.displayId,
                child.token, width, height)
            ensureChildActive(parent, child)
            // DisplayManager publishes the resized DisplayInfo asynchronously; subsequent
            // input uses the acknowledged requested size, without querying a stale cached mode.
        }
    }

    override fun injectChildTouch(childToken: IBinder, action: Int, x: Float, y: Float) {
        enforceClient()
        serialized(INPUT_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            child.contacts.cancel()?.let { injectContacts(checkNotNull(child.display).display.displayId, child.downTime, it) }
            for (event in child.touch.events(action, x, y)) {
                ensureChildActive(parent, child)
                injectChildTouchEvent(child, event)
            }
        }
    }

    override fun injectChildBack(childToken: IBinder) {
        enforceClient()
        serialized(INPUT_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            sendChildBack(parent, child)
        }
    }

    private fun sendChildBack(parent: Session, child: ChildSession) {
        val id = checkNotNull(child.display).display.displayId
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            ensureChildActive(parent, child)
            checkNotNull(runtime).inject(KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
        }
        child.revision++
        child.taskStateAt = 0L
    }

    override fun closeChildDisplay(childToken: IBinder) {
        enforceClient(requirePrivileges = false)
        serialized(STOP_TIMEOUT_MS, front = true, cancelOnTimeout = false) {
            val parent = session ?: return@serialized
            parent.children[childToken]?.let { releaseChild(parent, it) }
        }
    }

    override fun childTaskState(childToken: IBinder, packageName: String): Int {
        enforceClient()
        EmbeddedDisplayPolicy.requirePackage(packageName)
        val observation = DesktopReadDeadline.call(handler::post, INPUT_TIMEOUT_MS, { null }) {
            val (_, child) = requireChild(childToken)
            when {
                child.launchedPackage != packageName -> Placement(child, child.revision, 0, null)
                child.taskStateAt != 0L && SystemClock.uptimeMillis() - child.taskStateAt < 150 ->
                    Placement(child, child.revision, child.taskState, null)
                else -> Placement(child, child.revision, null, checkNotNull(runtime).childTaskQuery(
                    checkNotNull(child.display).display.displayId, packageName))
            }
        } ?: return -1
        observation.cached?.let { return it }
        // A slow ActivityManager must neither block input nor tear down an otherwise healthy stream.
        val result = DesktopReadDeadline.call({ observations.execute(it); true }, INPUT_TIMEOUT_MS, { -1 },
            checkNotNull(observation.read))
        if (result < 0) return -1
        return DesktopReadDeadline.call(handler::post, INPUT_TIMEOUT_MS, { -1 }) {
            val child = session?.children?.get(childToken)
            if (child !== observation.child || child.revision != observation.revision || child.launchedPackage != packageName) -1
            else { child.taskState = result; child.taskStateAt = SystemClock.uptimeMillis(); result }
        }
    }

    override fun childProducerLifetime(childToken: IBinder): IBinder {
        enforceClient()
        return DesktopReadDeadline.call(handler::post, INPUT_TIMEOUT_MS, { error("Служба дисплея занята") }) {
            requireChild(childToken)
            producerLifetime
        }
    }

    private fun injectChildTouchEvent(child: ChildSession, event: EmbeddedTouchState.Event) {
        val now = SystemClock.uptimeMillis()
        if (event.action == MotionEvent.ACTION_DOWN) child.downTime = now
        val motion = MotionEvent.obtain(child.downTime, now, event.action, event.x, event.y,
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) 1f else 0f,
            1f, 0, 1f, 1f, 0, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { checkNotNull(runtime).inject(motion, checkNotNull(child.display).display.displayId) }
        finally { motion.recycle() }
    }

    private fun cancelChildTouch(child: ChildSession) {
        child.touch.cancel()?.let { injectChildTouchEvent(child, it) }
        child.contacts.cancel()?.let { injectContacts(checkNotNull(child.display).display.displayId, child.downTime, it) }
        child.downTime = 0L
    }

    /** Only the physical internal panel is blanked. Android stays interactive; keyguard is untouched. */
    override fun setPhoneScreenOff(off: Boolean) {
        enforceClient()
        serialized(INPUT_TIMEOUT_MS) {
            val active = requireSession()
            if (delegated) {
                checkNotNull(runtime).setPhonePanelOff(checkNotNull(active.display).display.displayId, off)
                ensureActive(active.epoch)
                return@serialized
            }
            if (!off) {
                restorePanel(bestEffort = false)
                return@serialized
            }
            panelRestore.get()?.takeIf { it.epoch == active.epoch }?.let {
                if (it.confirmedOff && it.wakeLock.isHeld) return@serialized
                restorePanel(bestEffort = false) // Retry a failed partial cleanup before taking a new lease.
            }
            val access = checkNotNull(runtime)
            // Do not wake/alter an already sleeping or locked panel, or override its doze state.
            check(access.primaryPanelState() == Display.STATE_ON && access.phoneUnlocked()) { "Сначала включите и разблокируйте экран телефона" }
            val restore = PanelRestore(active.epoch, access, access.internalPanelToken(), Display.STATE_ON, access.newPanelWakeLock())
            ensureActive(active.epoch)
            // Arm recovery before changing power, including a failure after a native call took effect.
            panelRestore.set(restore)
            try {
                restore.wakeLock.acquire()
                check(restore.wakeLock.isHeld) { "Не удалось сохранить активность DeX при выключенной панели" }
                ensureActive(active.epoch)
                check(access.primaryPanelState() == Display.STATE_ON && access.phoneUnlocked()) { "Телефон был заблокирован; экран не изменён" }
                restore.powerTouched = true
                access.setPanelPower(restore.token, false)
                ensureActive(active.epoch)
                restore.confirmedOff = true
                Log.i(TAG, "internal phone panel blanked; keyguard unchanged")
            } catch (error: Throwable) {
                restorePanel(bestEffort = true)
                throw error
            }
        }
    }

    override fun stop() {
        enforceClient(requirePrivileges = false)
        stopOwned()
    }

    override fun destroy() {
        if (delegated) {
            shutdownDelegated()
            return
        }
        val caller = Binder.getCallingUid()
        check(caller == clientUid || caller == 2000 || caller == 0) { "Неавторизованный вызов UserService" }
        try { stopOwned() } finally {
            observations.shutdownNow()
            worker.quitSafely()
            // Official Shizuku destroy contract terminates this dedicated UserService process.
            restorePanelBeforeExit()
            exitProcess(0)
        }
    }

    /** Ordinary Service.onDestroy: release owned producers, without ending the bootstrap process. */
    fun shutdownDelegated() {
        check(delegated) { "Завершение этого режима выполняет Shizuku" }
        DelegatedDesktopPolicy.requireOwner(Process.myUid(), clientUid, Binder.getCallingUid())
        delegatedShutdown.close {
            try { stopOwned() } finally { observations.shutdownNow(); worker.quitSafely() }
        }
    }

    private fun enforceClient(requirePrivileges: Boolean = true) {
        if (delegated) {
            DelegatedDesktopPolicy.requireOwner(Process.myUid(), clientUid, Binder.getCallingUid())
            delegatedShutdown.requireOpen()
            // Cleanup must still release displays when permission adoption has ended.
            if (requirePrivileges) requireDelegatedPermissions()
        } else {
            check(Process.myUid() == 2000) { "Запустите Shizuku через USB или беспроводную отладку (UID 2000)" }
            if (Binder.getCallingUid() != clientUid) throw SecurityException("UserService доступен только HeadSwap DeX")
        }
    }

    private fun requireDelegatedPermissions() {
        DelegatedDesktopPolicy.requirePermissions(
            context.checkSelfPermission(DelegatedDesktopPolicy.ADD_TRUSTED_DISPLAY) == PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(DelegatedDesktopPolicy.INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED)
    }

    private fun ensureActive(epoch: Long) {
        if (delegated) {
            // A start may have passed its Binder admission before terminal shutdown began.
            // Recheck on worker so it can never create a late producer after shutdown ACK.
            delegatedShutdown.requireOpen()
            requireDelegatedPermissions()
        }
        check(generation.get() == epoch && session?.epoch == epoch && session?.token?.isBinderAlive == true) { "DeX остановлен" }
    }

    private fun requireSession(): Session = checkNotNull(session) { "Экран DeX не запущен" }.also {
        ensureActive(it.epoch)
        check(it.display?.display?.displayId?.let { id -> id > 0 } == true) { "Нет собственного экрана DeX" }
    }

    private fun requireDriveDeckSession(): Session = requireSession().also {
        EmbeddedDisplayPolicy.requireParent(it.desktopMode, checkNotNull(it.display).display.displayId)
    }

    private fun requireChild(token: IBinder): Pair<Session, ChildSession> {
        val parent = requireDriveDeckSession()
        val child = checkNotNull(parent.children[token]) { "Экран виджета уже закрыт" }
        ensureChildActive(parent, child)
        return parent to child
    }

    private fun ensureChildActive(parent: Session, child: ChildSession, requireDisplay: Boolean = true) {
        ensureActive(parent.epoch)
        check(child.parentEpoch == parent.epoch && parent.children[child.token] === child && child.token.isBinderAlive) {
            "Поверхность виджета уже закрыта"
        }
        if (requireDisplay) {
            val display = checkNotNull(child.display).display
            EmbeddedDisplayPolicy.requireChildId(display.displayId, checkNotNull(parent.display).display.displayId)
            check(display.isValid) { "Экран виджета недоступен" }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateChildDisplay(parent: Session, child: ChildSession) {
        val display = checkNotNull(child.display).display
        EmbeddedDisplayPolicy.requireChildId(display.displayId, checkNotNull(parent.display).display.displayId)
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val mode = display.mode
        check(metrics.widthPixels == child.width && metrics.heightPixels == child.height &&
            mode.physicalWidth == child.width && mode.physicalHeight == child.height && metrics.densityDpi == child.density &&
            display.flags and DexUserServicePolicy.DISPLAY_TRUSTED != 0) { "Android изменил геометрию или доверие экрана виджета" }
    }

    /** Only worker releases children; keep producer-before-Surface ordering on every path. */
    private fun releaseChild(parent: Session, child: ChildSession) {
        if (parent.children[child.token] !== child) return
        parent.children.remove(child.token)
        if (child.display != null) runCatching { cancelChildTouch(child) }
        child.display?.display?.displayId?.let { unregisterInputDisplay(it, child.token) }
        try {
            child.display?.release()
        } catch (error: Throwable) {
            // Do not acknowledge disposal of a Surface that might still have a native producer.
            Log.e(TAG, "child display release failed; retiring owned producer process", error)
            terminateStuckService()
        }
        child.display = null
        try { child.surface.release() }
        finally { if (child.linked) runCatching { child.token.unlinkToDeath(child.death, 0) } }
        Log.i(TAG, "child display released; remaining=${parent.children.size}")
    }

    private fun unregisterInputDisplay(id: Int, token: IBinder) {
        // Revocation is best effort when the main process/gateway has already died;
        // native display release must still run and is the actual producer ACK.
        runCatching { runtime?.unregisterDisplay(id, token) }
            .onFailure { Log.w(TAG, "input lease revoke failed for display=$id", it) }
    }

    @Suppress("DEPRECATION")
    private fun validateDisplay(lease: Session) {
        val display = checkNotNull(lease.display).display
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val mode = display.mode
        DexUserServicePolicy.validateGeometry(display.displayId, metrics.widthPixels, metrics.heightPixels,
            mode.physicalWidth, mode.physicalHeight, metrics.densityDpi, display.flags,
            expectedDensity = lease.density, desktopMode = lease.desktopMode, expectedWidth = lease.width, expectedHeight = lease.height)
    }

    /** Only worker may release. A successful stop response guarantees display release precedes Surface release. */
    private fun releaseCurrent() {
        val lease = session
        if (lease == null) {
            restorePanel(bestEffort = true)
            return
        }
        session = null
        var releaseFailure: Throwable? = null
        try {
            for (child in lease.children.values.toList()) releaseChild(lease, child)
            lease.display?.display?.displayId?.let { unregisterInputDisplay(it, lease.token) }
            lease.display?.release()
        } catch (e: Throwable) {
            releaseFailure = e
        } finally {
            lease.display = null
            // Preserve the VD-before-Surface release order, and restore only power changed by our button.
            restorePanel(bestEffort = true)
            // If VD release failed, keep this Surface reference until process death removes the VD.
            try { if (releaseFailure == null) lease.surface.release() } finally {
                if (lease.linked) runCatching { lease.token.unlinkToDeath(lease.death, 0) }
                mouse.reset()
                keyDownTimes.clear()
                mouseDownTime = 0
            }
        }
        if (releaseFailure != null) terminateStuckService()
        Log.i(TAG, "owned display released")
    }

    private fun stopOwned() {
        generation.incrementAndGet()
        currentCommand.get()?.destroyForcibly()
        serialized(STOP_TIMEOUT_MS, front = true, cancelOnTimeout = false) { releaseCurrent() }
    }

    private fun <T> serialized(timeout: Long, front: Boolean = false, cancelOnTimeout: Boolean = true, block: () -> T): T {
        val task = FutureTask(block)
        check(if (front) handler.postAtFrontOfQueue(task) else handler.post(task)) { "UserService уже завершён" }
        try {
            return task.get(timeout, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            val cause = unwrap(checkNotNull(e.cause))
            Log.e(TAG, "UserService operation failed", cause)
            if (cause is SecurityException) throw cause
            if (cause is IllegalArgumentException) throw cause
            if (cause is IllegalStateException) throw cause
            throw IllegalStateException(cause.message ?: "Ошибка системного экрана DeX", cause)
        } catch (e: Exception) {
            if (e !is TimeoutException && e !is InterruptedException) throw e
            task.cancel(false)
            generation.incrementAndGet()
            currentCommand.get()?.destroyForcibly()
            if (cancelOnTimeout) {
                val cleanup = FutureTask { releaseCurrent() }
                handler.postAtFrontOfQueue(cleanup)
                try { cleanup.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                catch (_: Exception) { terminateStuckService() }
            } else terminateStuckService()
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw IllegalStateException("Системная служба DeX не ответила вовремя; экран остановлен", e)
        }
    }

    private fun terminateStuckService(): Nothing {
        // Binder death destroys the owned virtual display even if a framework call never returns.
        Log.e(TAG, "terminating unresponsive dedicated UserService to release display")
        restorePanelBeforeExit()
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }

    private class PanelRestore(val epoch: Long, val access: DesktopRuntimeAccess, val token: IBinder, val originalState: Int,
        val wakeLock: DexWakeLease) {
        @Volatile var powerTouched = false
        @Volatile var confirmedOff = false
    }

    private fun restorePanel(bestEffort: Boolean) {
        val restore = panelRestore.get() ?: return
        var failure: Throwable? = null
        try {
            // Like scrcpy cleanup, respect a later framework sleep/lock instead of forcing a wake.
            // SurfaceControl blanking leaves logical STATE_ON; framework OFF/DOZE means it took over.
            if (restore.powerTouched && restore.originalState == Display.STATE_ON && restore.access.primaryPanelState() == Display.STATE_ON) {
                restore.access.setPanelPower(restore.token, true)
                Log.i(TAG, "internal phone panel restored")
            }
            restore.powerTouched = false
            restore.confirmedOff = false
        } catch (error: Throwable) {
            failure = error
        } finally {
            // No ON_AFTER_RELEASE or wake-up flag: later explicit user sleep/lock remains authoritative.
            try { restore.wakeLock.release() }
            catch (error: Throwable) { if (failure == null) failure = error }
        }
        if (failure == null) panelRestore.compareAndSet(restore, null)
        else {
            // Keep recovery information until both resources have been restored/released.
            if (!bestEffort) throw failure
            Log.w(TAG, "best-effort internal panel/wake lock restore failed", unwrap(failure))
        }
    }

    private fun restorePanelBeforeExit() {
        if (panelRestore.get() == null) return
        val done = CountDownLatch(1)
        Thread({
            try { restorePanel(bestEffort = true) } finally { done.countDown() }
        }, "headswap-panel-restore").apply { isDaemon = true; start() }
        try { done.await(350, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun command(epoch: Long, vararg arguments: String, required: Boolean = true) {
        ensureActive(epoch)
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
        check(currentCommand.compareAndSet(null, process)) { "Команда DeX уже выполняется" }
        val output = StringBuilder()
        val drain = Thread({
            runCatching { process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(512)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(output) { if (output.length < 8192) output.append(buffer, 0, minOf(count, 8192 - output.length)) }
                }
            } }
        }, "headswap-shell-command").apply { isDaemon = true; start() }
        try {
            ensureActive(epoch)
            check(process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "One UI не ответил на настройку собственного экрана" }
            drain.join(200)
            ensureActive(epoch)
            val text = synchronized(output) { output.toString() }
            val succeeded = process.exitValue() == 0 && text.lineSequence().none {
                it.trimStart().startsWith("Error", true) || it.contains("Exception")
            }
            if (required) check(succeeded) { "Не удалось настроить рабочий стол: ${text.take(300)}" }
            else if (!succeeded) Log.i(TAG, "Optional display configuration unavailable: ${text.take(300)}")
        } finally {
            currentCommand.compareAndSet(process, null)
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    /** Only hash-pinned code shipped in this APK is loaded, directly from memory (no writable DEX files). */
    private class RuntimeAccess(context: Context) : DesktopRuntimeAccess {
        private val userId = context.applicationInfo.uid / 100000
        private val loader: ClassLoader
        private val displayManager: Any
        private val create: Method
        private val windowManager: Any
        private val eligible: Method?
        private val inputManager: Any
        private val input: Method
        private val displayEvents = DisplayEventAccess()
        private val setActionButton: Method
        private val taskQuery: Pair<Any, Method>? by lazy {
            runCatching {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java).invoke(null, "activity_task")
                val manager = Class.forName("android.app.IActivityTaskManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                val getTasks = Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                checkNotNull(manager) to getTasks
            }.getOrNull()
        }

        init {
            loader = ScrcpyRuntime.loader(context)
            loader.loadClass("com.genymobile.scrcpy.Workarounds").getMethod("apply").invoke(null)
            displayManager = checkNotNull(loader.loadClass("com.genymobile.scrcpy.wrappers.ServiceManager").getMethod("getDisplayManager").invoke(null))
            create = displayManager.javaClass.getMethod("createNewVirtualDisplay", String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Surface::class.java, Int::class.javaPrimitiveType)
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window")
            windowManager = checkNotNull(Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder))
            eligible = runCatching {
                Class.forName("android.view.IWindowManager").getMethod("isEligibleForDesktopMode", Int::class.javaPrimitiveType)
            }.getOrNull()
            val inputClass = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
                .getOrElse { Class.forName("android.hardware.input.InputManager") }
            inputManager = checkNotNull(inputClass.getMethod("getInstance").invoke(null))
            input = inputClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            displayEvents.preflight()
            setActionButton = MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
        }

        override fun createDisplay(surface: Surface, width: Int, height: Int, density: Int, mode: Int): VirtualDisplay = checkNotNull(create.invoke(displayManager,
            "HeadSwap ${DesktopModePolicy.label(mode)}", width, height, density,
            surface, DesktopModePolicy.displayFlags(mode, android.os.Build.VERSION.SDK_INT)) as? VirtualDisplay) { "Android отклонил виртуальный экран" }

        override fun createChildDisplay(name: String, surface: Surface, width: Int, height: Int, density: Int): VirtualDisplay =
            checkNotNull(create.invoke(displayManager, name, width, height, density,
                surface, EmbeddedDisplayPolicy.displayFlags(android.os.Build.VERSION.SDK_INT)) as? VirtualDisplay) { "Android отклонил экран виджета" }

        override fun registerParentDisplay(id: Int, token: IBinder, width: Int, height: Int) = enableLocalIme(id)
        override fun registerChildDisplay(parentId: Int, id: Int, token: IBinder, width: Int, height: Int, fullscreen: Boolean) = enableLocalIme(id)

        private fun enableLocalIme(id: Int) {
            try { ShellDisplayIme.enable(id, Process.myUid()) }
            catch (e: Exception) { Log.w(TAG, "Local IME unavailable for HeadSwap display $id", e) }
            catch (e: LinkageError) { Log.w(TAG, "Local IME API unavailable", e) }
        }

        override fun childImeVisibilityQuery(id: Int): () -> Boolean? = { try { ShellDisplayIme.isVisible(id, Process.myUid()) }
            catch (e: Exception) { Log.w(TAG, "Cannot read IME on child $id", e); null }
            catch (e: LinkageError) { Log.w(TAG, "Child IME visibility API unavailable", e); null } }

        override fun childTaskState(id: Int, packageName: String): Int {
            check(id > 0) { "Проверка приложений телефона запрещена" }
            val (manager, getTasks) = taskQuery ?: return -1
            return runCatching {
                val raw = getTasks.invoke(manager, 100, false, false, id) as? List<*> ?: return -1
                if (raw.any { it !is ActivityManager.RunningTaskInfo }) return -1
                // Check both identities rather than trusting an OEM's display filter.
                // An unreadable task invalidates the entire observation.
                val tasks = raw.map { it as ActivityManager.RunningTaskInfo }.map { task ->
                    task to (FrameworkTaskIdentityReader.read(task) ?: return -1)
                }.filter { (_, identity) -> identity.displayId == id && identity.userId == userId }
                    .map { (task, _) -> task }
                when {
                    tasks.firstOrNull()?.topActivity?.packageName == packageName -> 2
                    tasks.any { it.baseActivity?.packageName == packageName || it.topActivity?.packageName == packageName } -> 1
                    else -> 0
                }
            }.getOrDefault(-1)
        }

        override fun eligible(id: Int): Boolean = eligible?.invoke(windowManager, id) == true

        private fun primaryDisplayInfo(): Any {
            val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val manager = global.getMethod("getInstance").invoke(null)
            val info = checkNotNull(global.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(manager, 0)) {
                "Внутренний экран телефона не найден"
            }
            check(info.javaClass.getField("type").getInt(info) == 1) { "Основной экран не является внутренней панелью" }
            return info
        }

        override fun primaryPanelState(): Int = primaryDisplayInfo().let { it.javaClass.getField("state").getInt(it) }

        override fun phoneUnlocked(): Boolean {
            // Same read-only Binder checks as KeyguardManager, without constructing its app-only
            // LockPatternUtils/cache dependencies in the Shizuku app_process runtime.
            val wm = Class.forName("android.view.IWindowManager")
            if (wm.getMethod("isKeyguardLocked").invoke(windowManager) == true) return false
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "trust")
            val trust = checkNotNull(Class.forName("android.app.trust.ITrustManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)) { "Служба состояния блокировки недоступна" }
            return Class.forName("android.app.trust.ITrustManager")
                .getMethod("isDeviceLocked", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(trust, userId, 0) == false
        }

        override fun newPanelWakeLock(): DexWakeLease {
            // Match the target framework's PowerManager.WakeLock Binder calls directly: its SDK
            // wrapper assumes application caches/context attribution unavailable in app_process.
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = checkNotNull(Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)) { "Служба питания недоступна" }
            val api = Class.forName("android.os.IPowerManager")
            val acquire = api.getMethod("acquireWakeLock", IBinder::class.java, Int::class.javaPrimitiveType,
                String::class.java, String::class.java, android.os.WorkSource::class.java, String::class.java,
                Int::class.javaPrimitiveType, Class.forName("android.os.IWakeLockCallback"))
            val release = api.getMethod("releaseWakeLock", IBinder::class.java, Int::class.javaPrimitiveType)
            val token = Binder() // PMS releases this token automatically if this UserService dies.
            return DexWakeLease(
                acquireRemote = {
                    // SCREEN_BRIGHT_WAKE_LOCK=10; no ACQUIRE_CAUSES_WAKEUP or ON_AFTER_RELEASE.
                    acquire.invoke(power, token, 10, "HeadSwapDeX:PanelOff", "com.android.shell", null, null, 0, null)
                    Unit
                },
                releaseRemote = { release.invoke(power, token, 0); Unit },
            )
        }

        override fun internalPanelToken(): IBinder {
            val info = primaryDisplayInfo()
            val surfaceControl = loader.loadClass("com.genymobile.scrcpy.wrappers.SurfaceControl")
            if (surfaceControl.getMethod("hasGetBuildInDisplayMethod").invoke(null) == true) {
                val builtin = surfaceControl.getMethod("getBuiltInDisplay").invoke(null) as? IBinder
                if (builtin != null) return builtin
            }
            // Android 14+ moved token lookup to DisplayControl. Resolve ONLY display 0's physical
            // address, unlike Device.setDisplayPower which loops over every attached physical display.
            val address = checkNotNull(info.javaClass.getField("address").get(info)) { "Нет физического адреса внутреннего экрана" }
            // Android 17 StablePhysical and the older Physical are sibling DisplayAddress types.
            // Use their physical-ID contract instead of requiring one concrete class name.
            check(Class.forName("android.view.DisplayAddress").isInstance(address)) { "Неизвестный адрес внутреннего экрана" }
            val physicalId = address.javaClass.getMethod("getPhysicalDisplayId").invoke(address) as Long
            check(physicalId != -1L) { "Основной экран не имеет физического ID" }
            val tokens = if (surfaceControl.getMethod("hasGetPhysicalDisplayIdsMethod").invoke(null) == true) surfaceControl
                else loader.loadClass("com.genymobile.scrcpy.wrappers.DisplayControl")
            return checkNotNull(tokens.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, physicalId) as? IBinder) {
                "Не удалось получить внутреннюю панель телефона"
            }
        }

        override fun setPanelPower(token: IBinder, on: Boolean) {
            val surfaceControl = loader.loadClass("com.genymobile.scrcpy.wrappers.SurfaceControl")
            check(surfaceControl.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
                .invoke(null, token, if (on) 2 else 0) == true) { "One UI не разрешил изменение питания внутренней панели" }
        }

        override fun inject(event: InputEvent, id: Int, actionButton: Int) {
            check(id > 0) { "Ввод на основной экран запрещён" }
            if (actionButton != 0) {
                check(event is MotionEvent) { "Кнопка указателя требует события мыши" }
                setActionButton.invoke(event, actionButton)
            }
            displayEvents.setDisplayId(event, id)
            // ASYNC avoids waiting for another app's UI thread; the queue itself remains ordered.
            check(input.invoke(inputManager, event, 0) == true) { "One UI отклонил ввод на экран DeX" }
        }
    }

    companion object {
        /** Only instantiate in a dedicated ordinary app process, never in the UI/bootstrap process.
         * A stuck framework Binder/release may kill this producer process to release its Surface.
         */
        @JvmStatic
        fun forDelegated(context: Context): DexUserService =
            throw IllegalStateException("Службе рабочего стола нужен канал ввода активированного приложения ${context.packageName}")

        @JvmStatic
        fun forDelegated(context: Context, inputGateway: IDelegatedInputGateway): DexUserService {
            check(inputGateway.asBinder().isBinderAlive) { "Канал ввода рабочего стола недоступен" }
            val app = context.applicationContext ?: context
            DelegatedDesktopPolicy.requireOwner(Process.myUid(), app.applicationInfo.uid, Binder.getCallingUid())
            DelegatedDesktopPolicy.requirePermissions(
                app.checkSelfPermission(DelegatedDesktopPolicy.ADD_TRUSTED_DISPLAY) == PackageManager.PERMISSION_GRANTED,
                app.checkSelfPermission(DelegatedDesktopPolicy.INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED)
            return DexUserService(app, true, inputGateway)
        }

        private const val TAG = "HeadSwapDexService"
        private const val START_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val INPUT_TIMEOUT_MS = 2_000L
        private const val COMMAND_TIMEOUT_MS = 4_000L
        private fun unwrap(error: Throwable): Throwable = if (error is InvocationTargetException && error.cause != null) unwrap(error.cause!!) else error
    }
}
