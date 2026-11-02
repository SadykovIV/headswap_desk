package com.headswap.desk.dex

import android.content.Context
import com.headswap.desk.aa.DesktopResolution
import com.headswap.desk.aa.DesktopGeometryPolicy
import com.headswap.desk.aa.DesktopVideoSelection
import com.headswap.desk.aa.FpsSetting

internal enum class PhoneIdleMode(val label: String) {
    CURTAIN("Чёрный экран с двойным касанием"), PANEL("Выключать физическую панель"), DISABLED("Не использовать таймер")
}

/** Preferences are separate from upstream HeadSwap/Android Auto settings. */
class DexPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE)
    /** Only controls the phone's HeadSwap window; never changes the system timeout or keyguard. */
    internal var phoneIdleMode: PhoneIdleMode
        get() = prefs.getString("phone_idle_mode", null)?.let { saved -> PhoneIdleMode.entries.firstOrNull { it.name == saved } } ?: PhoneIdleMode.PANEL
        set(value) { prefs.edit().putString("phone_idle_mode", value.name).apply() }
    internal var phoneIdleTimeout: PhoneIdleTimeout
        get() = PhoneIdleTimeout.restore(prefs.getString("phone_idle_timeout", null))
        set(value) { prefs.edit().putString("phone_idle_timeout", value.name).apply() }
    /** First activation offers panel control. An explicit choice is kept across updates. */
    var desktopPanelControl: Boolean
        get() = prefs.getBoolean("desktop_panel_control", true)
        set(value) { prefs.edit().putBoolean("desktop_panel_control", value).apply() }
    /** First activation offers USB playback. Changing the selection takes effect on the next activation. */
    var usbPlaybackAudio: Boolean
        get() = prefs.getBoolean("desktop_usb_playback_audio", true)
        set(value) { prefs.edit().putBoolean("desktop_usb_playback_audio", value).apply() }
    /** Existing installations keep their choice; loss of access never switches a backend. */
    var accessMode: DesktopAccessMode
        get() = DesktopAccessMode.restore(prefs.getString("access_backend", null),
            if (prefs.contains("use_shizuku")) prefs.getBoolean("use_shizuku", true) else null)
        set(value) { prefs.edit().putString("access_backend", value.name).apply() }
    var useShizuku: Boolean
        get() = accessMode == DesktopAccessMode.SHIZUKU
        set(value) { accessMode = if (value) DesktopAccessMode.SHIZUKU else DesktopAccessMode.LEGACY_ADB }
    var useDelegated: Boolean
        get() = accessMode == DesktopAccessMode.COMPUTER
        set(value) {
            if (value) accessMode = DesktopAccessMode.COMPUTER
            else if (useDelegated) accessMode = DesktopAccessMode.SHIZUKU
        }
    val useDesktopService: Boolean get() = accessMode != DesktopAccessMode.LEGACY_ADB
    /** Called on the setup worker before instrumentation deliberately restarts this package. */
    internal fun persistActivationSelection(audio: Boolean, screen: Boolean): Boolean {
        if (!useDelegated || usbPlaybackAudio != audio || desktopPanelControl != screen) return false
        return prefs.edit().putString("access_backend", DesktopAccessMode.COMPUTER.name)
            .putBoolean("desktop_usb_playback_audio", audio)
            .putBoolean("desktop_panel_control", screen).commit()
    }
    /** Existing choices survive updates; the independent Android launcher remains the default. */
    var desktopMode: Int
        get() = prefs.getInt("desktop_mode", DesktopModePolicy.ANDROID_DESKTOP).let {
            when (it) {
                DesktopModePolicy.SAMSUNG_DEX, DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK -> it
                else -> DesktopModePolicy.ANDROID_DESKTOP
            }
        }
        set(value) {
            DesktopModePolicy.requireMode(value)
            prefs.edit().putInt("desktop_mode", value).apply()
        }
    /** Pre-unification values, in order of precedence when the new setting is absent. */
    var shizukuDensity: Int
        get() = prefs.getInt("shizuku_density", prefs.getInt("density", 160)).coerceIn(120, 320)
        set(value) { require(value in 120..320); prefs.edit().putInt("shizuku_density", value).apply() }
    var desktopResolution: DesktopResolution
        get() = DesktopResolution.restore(prefs.getString("desktop_resolution", null))
        set(value) { prefs.edit().putString("desktop_resolution", value.name).apply() }
    private fun customDesktopSize(): Pair<Int, Int> {
        val parts = prefs.getString("desktop_custom_size", null)?.split('x')
        val width = parts?.takeIf { it.size == 2 }?.get(0)?.toIntOrNull()
        val height = parts?.takeIf { it.size == 2 }?.get(1)?.toIntOrNull()
        return if (width != null && height != null && DesktopGeometryPolicy.accepts(width, height)) width to height
            else 1600 to 900
    }
    val customDesktopWidth: Int get() = customDesktopSize().first
    val customDesktopHeight: Int get() = customDesktopSize().second
    /** One preference value keeps width and height atomic; invalid input changes nothing. */
    fun setCustomDesktopSize(width: Int, height: Int) {
        DesktopGeometryPolicy.requireDimensions(width, height)
        prefs.edit().putString("desktop_custom_size", "${width}x$height")
            .putString("desktop_resolution", DesktopResolution.CUSTOM.name).apply()
    }
    val desktopResolutionLabel: String get() = if (desktopResolution == DesktopResolution.CUSTOM)
        customDesktopSize().let { "${it.first} × ${it.second}" } else desktopResolution.title
    fun desktopVideoSelection(): DesktopVideoSelection = customDesktopSize().let {
        DesktopVideoSelection(desktopResolution, desktopDensity, it.first, it.second)
    }
    /** Zero selects proportional DPI; older explicit DPI is retained on upgrade. */
    var desktopDensity: Int
        get() = prefs.getInt("desktop_density", shizukuDensity).let {
            if (it == 0) 0 else it.coerceIn(DesktopGeometryPolicy.MIN_DENSITY, DesktopGeometryPolicy.MAX_DENSITY)
        }
        set(value) { if (value != 0) DesktopGeometryPolicy.requireDensity(value); prefs.edit().putInt("desktop_density", value).apply() }
    var compactMapSymbols: Boolean
        get() = prefs.getBoolean("compact_map_symbols", true)
        set(value) { prefs.edit().putBoolean("compact_map_symbols", value).apply() }
    /** Direct desktop has an independent 30 fps default; upstream AA preferences are unchanged. */
    var desktopFps: FpsSetting
        get() = prefs.getString("desktop_fps", null).let { name -> FpsSetting.entries.firstOrNull { it.name == name } ?: FpsSetting.FPS30 }
        set(value) { prefs.edit().putString("desktop_fps", value.name).apply() }
    /** UI hint only; a successful live probe is still required before starting projection. */
    var hasPaired: Boolean
        get() = prefs.getBoolean("has_paired", pairingPort > 0 || connectPort > 0)
        set(value) { prefs.edit().putBoolean("has_paired", value).apply() }
    var host: String
        get() = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        set(value) { prefs.edit().putString("host", value.trim()).apply() }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var pairingPort: Int
        get() = prefs.getInt("pairing_port", 0)
        set(value) { require(value == 0 || value in 1..65535); prefs.edit().putInt("pairing_port", value).apply() }
    var connectPort: Int
        get() = prefs.getInt("connect_port", 0)
        set(value) { require(value == 0 || value in 1..65535); prefs.edit().putInt("connect_port", value).apply() }
}

object DexState {
    @Volatile var status: String = "DeX не запущен"
    @Volatile var error: String? = null
    @Volatile var running: Boolean = false
    @Volatile var displayId: Int = -1
    /** Last acknowledged override for this session; never persisted or enabled automatically. */
    @Volatile var phoneScreenOff: Boolean = false
    @Volatile var phoneScreenBusy: Boolean = false
    @Volatile var phoneScreenError: String? = null
}
