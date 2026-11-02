package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceControl
import com.headswap.desk.AppLog

/** Explicit --screen activation only. No global setting or runtime hidden-API exemption is changed. */
object DelegatedPanelAccess {
    private const val SURFACE_PERMISSION = "android.permission.ACCESS_SURFACE_FLINGER"
    private const val SAMSUNG_PERMISSION = "android.permission.DEVICE_POWER"
    const val ACTIVATION_ARGUMENT = "headswap_screen"
    private val lock = Any()
    private val controllers = mutableSetOf<DelegatedPanelController>()
    private var outputOwner: Any? = null
    private var outputFresh: (() -> Boolean)? = null

    fun requestFromArgument(value: String?): Boolean = value == "true"
    fun permission(): String = if (SamsungPanelOverride.isSupported()) SAMSUNG_PERMISSION else SURFACE_PERMISSION
    fun hasPermission(context: Context): Boolean = context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    fun isAvailable(context: Context): Boolean = DexPrefs(context).desktopPanelControl &&
        DelegatedDesktopAccess.isReady(context) && hasPermission(context) && runCatching { preflight(context); true }.getOrDefault(false)

    /** Read-only: resolves the scoped backend and exact physical display, never changes power. */
    fun preflight(context: Context) { PanelPlatform(context).prepare() }

    internal fun register(value: DelegatedPanelController) = synchronized(lock) { controllers.add(value); Unit }
    internal fun unregister(value: DelegatedPanelController) = synchronized(lock) { controllers.remove(value); Unit }
    fun attachOutput(owner: Any, fresh: () -> Boolean) {
        restoreAll()
        synchronized(lock) { outputOwner = owner; outputFresh = fresh }
    }
    fun detachOutput(owner: Any) {
        val changed = synchronized(lock) {
            if (outputOwner !== owner) false else { outputOwner = null; outputFresh = null; true }
        }
        if (changed) restoreAll()
    }
    internal fun outputIsFresh(): Boolean {
        val callback = synchronized(lock) { outputFresh }
        return runCatching { callback?.invoke() == true }.getOrDefault(false)
    }
    /** Called before dropping the instrumentation's adopted permission. */
    fun restoreAll() {
        val snapshot = synchronized(lock) { controllers.toList() }
        snapshot.forEach { it.restoreBestEffort() }
    }
}

/** Only instantiated in the instrumented main process, never in the ordinary producer helper. */
internal class DelegatedPanelController(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var lease: PhonePanelLease? = null
    private var owner: IBinder? = null
    private var displayId = -1
    private var restoreFailures = 0
    private var touchWindow: PhonePanelWakeWindow? = null
    private val tick = object : Runnable {
        override fun run() { synchronized(this@DelegatedPanelController) {
            val active = lease ?: return
            val token = owner ?: return
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            val eligible = DexPrefs(context).desktopPanelControl && DelegatedDesktopAccess.isReady(context) &&
                DelegatedPanelAccess.hasPermission(context) && DelegatedPanelAccess.outputIsFresh() &&
                display != null && display.isValid && display.flags and 128 != 0
            try { active.poll(token, eligible, SystemClock.elapsedRealtime()) }
            catch (error: Throwable) { report(error) }
            if (!active.hasLease) finish() else if (restoreFailures < 3) main.postDelayed(this, 250)
        } }
    }

    @Synchronized fun setOff(id: Int, token: IBinder, off: Boolean) {
        if (!off) {
            restoreFailures = 0 // explicit retry has its own bounded cleanup attempts
            try { lease?.restore(token); if (lease?.hasLease != true) finish() }
            catch (error: Throwable) {
                report(error); main.removeCallbacks(tick); main.post(tick)
                throw error
            }
            return
        }
        check(id > 0 && DelegatedPanelAccess.isAvailable(context) && DelegatedPanelAccess.outputIsFresh()) {
            "Для выключения панели нужен активный USB-сеанс и отдельная активация с --screen"
        }
        if (lease != null) { check(owner == token && displayId == id); return }
        val platform = PanelPlatform(context)
        platform.prepare()
        lateinit var window: PhonePanelWakeWindow
        window = PhonePanelWakeWindow(context,
            onDoubleTap = { main.post { restoreFromWindow(window, true) } },
            onWindowLost = { main.post { restoreFromWindow(window, false) } })
        val value = PhonePanelLease(platform::mayBlank, platform::mayRestore,
            acquire = { platform.acquire(); window.show() },
            release = { try { window.close() } finally { platform.release() } },
            power = platform::power, refresh = platform::refresh)
        touchWindow = window
        lease = value; owner = token; displayId = id; restoreFailures = 0
        DelegatedPanelAccess.register(this)
        try { value.blank(token, SystemClock.elapsedRealtime()) }
        catch (error: Throwable) { if (!value.hasLease) finish(); else main.post(tick); throw error }
        DexState.phoneScreenOff = true
        main.post(tick)
        AppLog.i("DESKTOP PANEL: internal display blanked; keyguard unchanged", false)
    }

    @Synchronized private fun restoreFromWindow(window: PhonePanelWakeWindow, doubleTap: Boolean) {
        if (touchWindow !== window || lease?.isBlank != true) return
        if (doubleTap) AppLog.i("DESKTOP PANEL: phone double tap; restoring internal panel", false)
        restoreBestEffort()
    }

    @Synchronized fun restoreBestEffort() {
        try { lease?.restore(); finish() }
        catch (error: Throwable) { report(error); main.removeCallbacks(tick); if (restoreFailures < 3) main.post(tick) }
    }
    private fun finish() {
        val wasActive = lease != null
        touchWindow?.close()
        touchWindow = null
        lease = null; owner = null; displayId = -1
        main.removeCallbacks(tick)
        DelegatedPanelAccess.unregister(this)
        if (wasActive) {
            DexState.phoneScreenOff = false
            AppLog.i("DESKTOP PANEL: internal panel lease released", false)
        }
    }
    private fun report(error: Throwable) {
        restoreFailures++
        DexState.phoneScreenError = "Не удалось восстановить панель: ${error.cause?.message ?: error.message}"
        AppLog.e("DESKTOP PANEL: restore failed", error)
    }
}

@SuppressLint("PrivateApi", "BlockedPrivateApi") // Enabled only by explicit am instrument --no-hidden-api-checks --screen.
private class PanelPlatform(private val context: Context) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var token: IBinder? = null
    private var physicalId: Long? = null
    @Suppress("DEPRECATION")
    private val wake = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "HeadSwap:PanelOff").apply { setReferenceCounted(false) }
    private val samsung = if (SamsungPanelOverride.isSupported()) SamsungPanelOverride() else null
    private val powerMethod by lazy { SurfaceControl::class.java.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType) }
    private val tokenMethod by lazy { SurfaceControl::class.java.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType) }

    private fun primary(): Display = checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY)).also {
        check(it.isValid && Display::class.java.getMethod("getType").invoke(it) == 1) { "Основной экран не является внутренней панелью" }
    }
    private fun physical(): Long {
        val address = checkNotNull(Display::class.java.getMethod("getAddress").invoke(primary()))
        check(Class.forName("android.view.DisplayAddress").isInstance(address)) { "Неизвестный адрес панели" }
        return (address.javaClass.getMethod("getPhysicalDisplayId").invoke(address) as Long).also {
            check(it != -1L) { "У внутренней панели нет физического ID" }
        }
    }
    fun prepare() {
        check(DelegatedPanelAccess.hasPermission(context)) { "Активация не разрешает управление панелью; используйте --screen" }
        physicalId = physical()
        if (samsung == null) {
            token = checkNotNull(tokenMethod.invoke(null, physicalId) as? IBinder) { "SurfaceFlinger не предоставил токен внутренней панели" }
            powerMethod // Resolve before advertising support, without changing power.
        }
    }
    fun mayBlank(): Boolean = DelegatedDesktopAccess.isReady(context) && DexPrefs(context).desktopPanelControl &&
        DelegatedPanelAccess.hasPermission(context) && DelegatedPanelAccess.outputIsFresh() && primary().state == Display.STATE_ON && powerManager.isInteractive &&
        !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked && physical() == physicalId
    // Samsung removes only our override: this is safe even after the user locks/sleeps the phone.
    fun mayRestore(): Boolean = physical() == physicalId && (samsung != null || primary().state == Display.STATE_ON)
    fun acquire() { wake.acquire(); check(wake.isHeld) { "Не удалось сохранить активность рабочего стола" } }
    fun release() { if (wake.isHeld) wake.release() }
    fun power(on: Boolean) {
        if (samsung != null) samsung.setOff(!on)
        else powerMethod.invoke(null, checkNotNull(token), if (on) 2 else 0)
    }
    fun refresh() { samsung?.refresh() }
}

/** Samsung DMS owns the timeout/death cleanup. No guessed Binder transactions or global sleep. */
@SuppressLint("PrivateApi", "BlockedPrivateApi")
private class SamsungPanelOverride {
    private val token = Binder()
    private val method = method()
    private val service = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        .getMethod("asInterface", IBinder::class.java).invoke(null,
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, Context.DISPLAY_SERVICE) as? IBinder ?: error("DisplayManager недоступен"))
    private var lastRenewedAt: Long? = null

    fun setOff(off: Boolean) {
        if (!off) {
            lastRenewedAt = null // An explicit restore can never be renewed, even when its IPC fails.
            method.invoke(service, token, Display.STATE_UNKNOWN, Display.DEFAULT_DISPLAY, 0)
        } else {
            check(lastRenewedAt == null) { "Таймер панели уже запущен" }
            lastRenewedAt = SystemClock.elapsedRealtime()
            method.invoke(service, token, Display.STATE_OFF, Display.DEFAULT_DISPLAY, SERVER_TIMEOUT_MS)
        }
    }
    fun refresh() {
        val then = lastRenewedAt ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - then
        // Leave a full second before the system timeout. A stalled client must release,
        // never create a fresh OFF override after the server has already restored it.
        check(elapsed in 0 until RENEW_DEADLINE_MS) { "Истёк безопасный срок управления панелью" }
        if (elapsed < RENEW_INTERVAL_MS) return
        method.invoke(service, token, Display.STATE_OFF, Display.DEFAULT_DISPLAY, SERVER_TIMEOUT_MS)
        lastRenewedAt = now
    }
    companion object {
        private const val SERVER_TIMEOUT_MS = 5_000
        private const val RENEW_DEADLINE_MS = 4_000L
        private const val RENEW_INTERVAL_MS = 1_000L
        private fun method() = Class.forName("android.hardware.display.IDisplayManager")
            .getMethod("setDisplayStateOverrideWithDisplayId", IBinder::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        fun isSupported(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            runCatching { method(); true }.getOrDefault(false)
    }
}
