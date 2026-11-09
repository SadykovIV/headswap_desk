package com.headswap.desk.dex

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.headswap.desk.AppLog

/** TestApi calls stay in the already instrumented main process, not in ordinary helper ART. */
internal class DelegatedInputGateway(context: Context) : IDelegatedInputGateway.Stub() {
    private val context = context.applicationContext
    private val registry = DelegatedInputRegistry()
    private val imeRoutes = DelegatedImeRoutes()
    private val displayWakeLocks = DesktopDisplayWakeLocks(this.context)
    @Volatile private var liveDisplayIds: Set<Int> = emptySet()
    @Volatile private var panelParent: DelegatedInputRegistry.Entry? = null
    private val phonePanel = DelegatedPanelController(this.context)
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    private val inputManager = checkNotNull(context.getSystemService(InputManager::class.java))
    private val inject by lazy { InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val displayEvents = DisplayEventAccess()
    // The Samsung firmware groups CarLife and the internal display together. Tag only
    // the events this gateway has validated and will inject into the active USB desktop.
    private val huActivity = if (android.os.Build.MANUFACTURER.equals("samsung", true) &&
        DexPrefs(this.context).desktopPanelControl &&
        this.context.checkSelfPermission(PhonePowerActivity.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        SamsungHuActivity(MotionEvent::class.java, KeyEvent::class.java) else null
    private var reportedActivityMarker = false
    private val setActionButton by lazy { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) }

    fun preflight() {
        try { inject; displayEvents.preflight(); setActionButton }
        catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Android не разрешил управление отдельным экраном в процессе активации: ${error.message}", error)
        }
    }

    @Synchronized fun beginGeneration(generation: Long, token: IBinder, allowFullscreen: Boolean) {
        displayWakeLocks.close()
        registry.begin(generation, token, allowFullscreen)
        imeRoutes.clear()
        panelParent = null
        DexState.independentPhoneLock = null
        liveDisplayIds = emptySet()
        activeGateway.set(this)
    }
    @Synchronized fun deactivate() {
        displayWakeLocks.close()
        imeRoutes.clear()
        panelParent = null
        DexState.independentPhoneLock = null
        liveDisplayIds = emptySet()
        activeGateway.compareAndSet(this, null)
        phonePanel.restoreBestEffort()
        registry.deactivate()
    }

    @Synchronized override fun registerParent(displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(displayId)
        registry.registerParent(displayId, token, width, height)
        val entry = registry.requireDisplay(displayId, token)
        panelParent = entry
        DexState.independentPhoneLock = displays.getDisplay(displayId)?.let(DesktopDisplayIsolation::confirmedIndependence)
        registerIme(entry)
        displayWakeLocks.hold(displayId)
        liveDisplayIds = registry.registeredDisplayIds()
    }

    override fun registerChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int) =
        registerChild(parentId, displayId, token, width, height, fullscreen = false)

    override fun registerFullscreenChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int) =
        registerChild(parentId, displayId, token, width, height, fullscreen = true)

    @Synchronized private fun registerChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int, fullscreen: Boolean) {
        requireActive(); requireTrustedDisplay(parentId); requireTrustedDisplay(displayId)
        registry.registerChild(parentId, displayId, token, width, height, fullscreen)
        registerIme(registry.requireDisplay(displayId, token))
        displayWakeLocks.hold(displayId)
        liveDisplayIds = registry.registeredDisplayIds()
    }

    private fun registerIme(entry: DelegatedInputRegistry.Entry) {
        imeRoutes.register(entry)
        if (imeBackend() == null) {
            AppLog.i("DESKTOP IME: дисплей ${entry.id} ожидает связи со службой клавиатуры", false)
        } else refreshIme(entry)
    }

    // USB handoff can retire the whole ADB process group. The active app still has its
    // desktop lease and can set LOCAL itself before the first app is launched on a new display.
    private fun imeBackend(): Any? =
        ShellPanelClient.imeConnection ?: ShellDisplayIme.takeIf { it.availableInActivation(context) }

    private fun refreshIme(only: DelegatedInputRegistry.Entry? = null) {
        if (activeGateway.get() !== this) return
        val backend = imeBackend()
        imeRoutes.refresh(backend, SystemClock.elapsedRealtime(), only) { entry ->
            val parent = panelParent ?: return@refresh false
            val token = entry.token as IBinder
            if (activeGateway.get() !== this || imeBackend() !== backend ||
                !(parent.token as IBinder).isBinderAlive || !token.isBinderAlive ||
                !DelegatedDesktopAccess.isReady(context)) return@refresh false
            val source = try {
                requireTrustedDisplay(entry.id)
                val fromHelper = backend !== ShellDisplayIme &&
                    ShellPanelClient.enableLocalIme(entry.id) && ShellPanelClient.imeConnection === backend
                when {
                    fromHelper -> "helper"
                    ShellDisplayIme.availableInActivation(context) -> {
                        ShellDisplayIme.enable(entry.id, Process.myUid())
                        "activation"
                    }
                    else -> null
                }
            } catch (e: Exception) {
                android.util.Log.w("HeadSwapDesk", "IME policy unavailable on display ${entry.id}", e)
                null
            } catch (_: LinkageError) { null }
            val state = if (source != null) "LOCAL подтверждён ($source)" else "LOCAL недоступен, запланирован повтор"
            AppLog.i("DESKTOP IME: дисплей ${entry.id} — $state", false)
            source != null
        }
    }

    override fun childImeVisibility(displayId: Int, token: IBinder): Int {
        val child = synchronized(this) {
            requireActive()
            requireTrustedDisplay(displayId)
            registry.requireDisplay(displayId, token).also { check(it.parent != null) }
        }
        val visible = ShellPanelClient.imeVisible(displayId) ?: if (ShellDisplayIme.availableInActivation(context)) {
            try { ShellDisplayIme.isVisible(displayId, Process.myUid()) }
            catch (_: Exception) { null }
            catch (_: LinkageError) { null }
        } else null
        return synchronized(this) {
            requireActive()
            check(registry.requireDisplay(displayId, token) === child)
            when (visible) { true -> 1; false -> 0; null -> -1 }
        }
    }

    @Synchronized override fun resizeDisplay(displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(displayId)
        registry.resize(displayId, token, width, height)
    }

    @Synchronized override fun unregisterDisplay(displayId: Int, token: IBinder) {
        requireCaller() // Cleanup remains possible after activation has ended.
        val owned = runCatching { registry.requireDisplay(displayId, token) }.getOrNull()
        if (owned != null) imeRoutes.unregister(owned)
        if (owned?.parent == null && owned != null) {
            panelParent = null
            DexState.independentPhoneLock = null
            phonePanel.restoreBestEffort()
        }
        registry.unregister(displayId, token)
        liveDisplayIds = registry.registeredDisplayIds()
        displayWakeLocks.retain(liveDisplayIds)
    }

    @Synchronized override fun injectInput(displayId: Int, token: IBinder, event: InputEvent, actionButton: Int) {
        try {
            requireActive()
            val owned = registry.requireDisplay(displayId, token)
            requireTrustedDisplay(displayId)
            when (event) {
                is MotionEvent -> {
                    require(event.source == InputDevice.SOURCE_MOUSE || event.source == InputDevice.SOURCE_TOUCHSCREEN)
                    require(event.pointerCount in 1..2 &&
                        (event.source != InputDevice.SOURCE_MOUSE || event.pointerCount == 1))
                    require((0 until event.pointerCount).all { i ->
                        event.getX(i).isFinite() && event.getY(i).isFinite() &&
                            event.getX(i) >= 0 && event.getX(i) < owned.width &&
                            event.getY(i) >= 0 && event.getY(i) < owned.height
                    })
                    if (event.source == InputDevice.SOURCE_TOUCHSCREEN) {
                        require(actionButton == 0 && event.buttonState == 0)
                        DesktopTouchState.validate(event.actionMasked, event.actionIndex,
                            IntArray(event.pointerCount) { event.getPointerId(it) },
                            FloatArray(event.pointerCount) { event.getX(it) },
                            FloatArray(event.pointerCount) { event.getY(it) })
                    }
                    require(actionButton == 0 || actionButton == MotionEvent.BUTTON_PRIMARY)
                    if (actionButton != 0) setActionButton.invoke(event, actionButton)
                }
                is KeyEvent -> require(actionButton == 0 && event.keyCode in 0..1000 &&
                    event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                else -> error("Недопустимый тип ввода")
            }
            displayEvents.setDisplayId(event, displayId)
            if (huActivity != null && DelegatedPanelAccess.outputIsFresh()) {
                val marked = huActivity.mark(event, displayId, usbOutput = true)
                if (!reportedActivityMarker) {
                    reportedActivityMarker = true
                    android.util.Log.i("HeadSwapInput", "Samsung USB user-activity suppression available=$marked")
                }
            }
            check(inject.invoke(inputManager, event, 0) == true) { "Android отклонил ввод на отдельный экран" }
        } finally { (event as? MotionEvent)?.recycle() }
    }

    @Suppress("DEPRECATION")
    override fun childTaskState(displayId: Int, token: IBinder, packageName: String): Int {
        val owned = synchronized(this) {
            requireActive()
            requireTrustedDisplay(displayId)
            registry.requireDisplay(displayId, token).also { check(it.parent != null) { "Ожидался экран виджета" } }
        }
        EmbeddedDisplayPolicy.requirePackage(packageName)
        val tasks = runCatching {
            context.getSystemService(ActivityManager::class.java).getRunningTasks(100).map {
                val identity = FrameworkTaskIdentityReader.read(it) ?: return@runCatching null
                DelegatedDesktopPolicy.Task(identity.displayId, identity.userId,
                    it.baseActivity?.packageName, it.topActivity?.packageName)
            }
        }.getOrNull()
        return synchronized(this) {
            requireActive()
            check(registry.requireDisplay(displayId, token) === owned) { "Экран виджета изменился" }
            DelegatedDesktopPolicy.taskState(displayId, context.applicationInfo.uid / 100000, packageName,
                DelegatedDesktopAccess.hasPermissions(context), tasks)
        }
    }

    internal fun setLocalPanelOff(generation: Long, off: Boolean) {
        val parent = synchronized(this) { registry.requireParent(generation) }
        setPhonePanelOff(parent.id, parent.token as IBinder, off)
    }

    override fun setPhonePanelOff(parentId: Int, token: IBinder, off: Boolean) {
        requireCaller()
        if (off) requireActive() // restoring a lease remains possible after grant/liveness loss
        val parent = synchronized(this) {
            registry.requireDisplay(parentId, token).also {
                check(it === panelParent && it.parent == null) { "Панель доступна только родительскому рабочему столу" }
                if (off) requireTrustedDisplay(parentId)
            }
        }
        phonePanel.setOff(parentId, token, off) { panelParent === parent }
    }

    private fun requireCaller() {
        check(Binder.getCallingUid() == Process.myUid()) { "Шлюз ввода доступен только HeadSwap" }
    }
    private fun requireActive() { requireCaller(); check(DelegatedDesktopAccess.isReady(context)) { "Доступ завершён" } }
    private fun requireTrustedDisplay(id: Int) {
        require(id > 0) { "Ввод на основной экран телефона запрещён" }
        val display = checkNotNull(displays.getDisplay(id)) { "Экран уже закрыт" }
        check(display.isValid && display.flags and 128 != 0) { "Ожидался доверенный отдельный экран" }
    }

    companion object {
        const val EXTRA_BINDER = "headswap_delegated_input_gateway"
        private val activeGateway = java.util.concurrent.atomic.AtomicReference<DelegatedInputGateway?>()
        /** Instrumentation heartbeat, after attempting to reconnect the authenticated helper. */
        fun refreshImeRoutes() { activeGateway.get()?.refreshIme() }
        fun imeRoutesReady(): Boolean = activeGateway.get()?.imeRoutes?.allApplied() != false
        fun ownedDisplayIds(): Set<Int> = activeGateway.get()?.liveDisplayIds.orEmpty()
        fun ownsFocusedDisplay(id: Int): Boolean = id > 0 && activeGateway.get()?.liveDisplayIds?.contains(id) == true
    }
}
