package com.headswap.desk.dex

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/** TestApi calls stay in the already instrumented main process, not in ordinary helper ART. */
internal class DelegatedInputGateway(context: Context) : IDelegatedInputGateway.Stub() {
    private val context = context.applicationContext
    private val registry = DelegatedInputRegistry()
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

    @Synchronized fun beginGeneration(generation: Long, token: IBinder) {
        registry.begin(generation, token)
        panelParent = null
        liveDisplayIds = emptySet()
        activeGateway.set(this)
    }
    @Synchronized fun deactivate() {
        panelParent = null
        liveDisplayIds = emptySet()
        activeGateway.compareAndSet(this, null)
        phonePanel.restoreBestEffort()
        registry.deactivate()
    }

    @Synchronized override fun registerParent(displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(displayId)
        registry.registerParent(displayId, token, width, height)
        panelParent = registry.requireDisplay(displayId, token)
        liveDisplayIds = registry.registeredDisplayIds()
    }

    @Synchronized override fun registerChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(parentId); requireTrustedDisplay(displayId)
        registry.registerChild(parentId, displayId, token, width, height)
        liveDisplayIds = registry.registeredDisplayIds()
    }

    @Synchronized override fun resizeDisplay(displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(displayId)
        registry.resize(displayId, token, width, height)
    }

    @Synchronized override fun unregisterDisplay(displayId: Int, token: IBinder) {
        requireCaller() // Cleanup remains possible after activation has ended.
        val owned = runCatching { registry.requireDisplay(displayId, token) }.getOrNull()
        if (owned?.parent == null && owned != null) {
            panelParent = null
            phonePanel.restoreBestEffort()
        }
        registry.unregister(displayId, token)
        liveDisplayIds = registry.registeredDisplayIds()
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
    @Synchronized override fun childTaskState(displayId: Int, token: IBinder, packageName: String): Int {
        requireActive()
        check(registry.requireDisplay(displayId, token).parent != null) { "Ожидался экран виджета" }
        requireTrustedDisplay(displayId)
        EmbeddedDisplayPolicy.requirePackage(packageName)
        val tasks = runCatching {
            context.getSystemService(ActivityManager::class.java).getRunningTasks(100).map {
                val identity = FrameworkTaskIdentityReader.read(it) ?: return@runCatching null
                DelegatedDesktopPolicy.Task(identity.displayId, identity.userId,
                    it.baseActivity?.packageName, it.topActivity?.packageName)
            }
        }.getOrNull()
        return DelegatedDesktopPolicy.taskState(displayId, context.applicationInfo.uid / 100000, packageName,
            DelegatedDesktopAccess.hasPermissions(context), tasks)
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
        fun ownsFocusedDisplay(id: Int): Boolean = id > 0 && activeGateway.get()?.liveDisplayIds?.contains(id) == true
    }
}
