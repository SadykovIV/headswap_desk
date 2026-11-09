package com.headswap.desk.dex

import android.content.Context
import com.headswap.desk.aa.DesktopResolution
import com.headswap.desk.aa.DesktopGeometryPolicy
import com.headswap.desk.aa.DesktopVideoSelection
import com.headswap.desk.aa.FpsSetting

internal enum class PhoneIdleMode(val label: String) {
    PANEL("Выключать экран"), CURTAIN("Затемнять экран"), DISABLED("Без таймера")
}

/** Preferences are separate from upstream HeadSwap/Android Auto settings. */
class DexPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE)
    internal var phoneScreenMode: PhoneIdleMode
        get() = PhonePanelPolicy.screenMode(prefs.getString("phone_screen_mode", null),
            prefs.getString("phone_idle_mode", null), PhonePanelPolicy.prefersCurtain)
        set(value) {
            require(value != PhoneIdleMode.DISABLED)
            prefs.edit().putString("phone_screen_mode", value.name).apply()
        }
    internal var phoneTimerEnabled: Boolean
        get() = prefs.getBoolean("phone_idle_timer_enabled", prefs.getString("phone_idle_mode", null) != PhoneIdleMode.DISABLED.name)
        set(value) { prefs.edit().putBoolean("phone_idle_timer_enabled", value).apply() }
    internal val phoneIdleMode: PhoneIdleMode get() = if (phoneTimerEnabled) phoneScreenMode else PhoneIdleMode.DISABLED
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
    private fun backendKey(profile: Int) =
        if (profile == DesktopModePolicy.SAMSUNG_DEX) "dex_access_backend" else "desktop_access_backend"

    var accessMode: DesktopAccessMode
        get() {
            val profile = desktopMode
            val saved = prefs.getString(backendKey(profile), null) ?: prefs.getString("access_backend", null)
            val legacy = if (prefs.contains("use_shizuku")) prefs.getBoolean("use_shizuku", true) else null
            return DesktopAccessMode.restore(profile, saved, legacy)
        }
        set(value) {
            val profile = desktopMode
            require(value in DesktopAccessMode.optionsFor(profile)) { "Этот способ доступа недоступен для выбранного профиля" }
            prefs.edit().putString(backendKey(profile), value.name).apply()
        }
    var useShizuku: Boolean
        get() = accessMode == DesktopAccessMode.SHIZUKU
        set(value) {
            accessMode = if (value) DesktopAccessMode.SHIZUKU
                else DesktopAccessMode.optionsFor(desktopMode).single { it != DesktopAccessMode.SHIZUKU }
        }
    var useDelegated: Boolean
        get() = accessMode == DesktopAccessMode.COMPUTER
        set(value) {
            if (value) accessMode = DesktopAccessMode.COMPUTER
            else if (useDelegated) accessMode = DesktopAccessMode.SHIZUKU
        }
    val useDesktopService: Boolean get() = accessMode != DesktopAccessMode.LEGACY_ADB
    /** Called on the setup worker before instrumentation deliberately restarts this package. */
    internal fun persistActivationSelection(audio: Boolean, screen: Boolean): Boolean {
        val profile = desktopMode
        if (profile == DesktopModePolicy.SAMSUNG_DEX || !useDelegated ||
            usbPlaybackAudio != audio || desktopPanelControl != screen) return false
        return prefs.edit().putString(backendKey(profile), DesktopAccessMode.COMPUTER.name)
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
        get() = prefs.getInt("shizuku_density", prefs.getInt("density", 128)).coerceIn(120, 320)
        set(value) { require(value in 120..320); prefs.edit().putInt("shizuku_density", value).apply() }
    var desktopResolution: DesktopResolution
        get() = DesktopResolution.restore(prefs.getString("desktop_resolution", null))
        set(value) { prefs.edit().putString("desktop_resolution", value.name).apply() }
    private fun customDesktopSize(): Pair<Int, Int> {
        val parts = prefs.getString("desktop_custom_size", null)?.split('x')
        val width = parts?.takeIf { it.size == 2 }?.get(0)?.toIntOrNull()
        val height = parts?.takeIf { it.size == 2 }?.get(1)?.toIntOrNull()
        return if (width != null && height != null && DesktopGeometryPolicy.accepts(width, height)) width to height
            else DesktopResolution.DEFAULT.width to DesktopResolution.DEFAULT.height
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
    /** Desktop frame rate is stored independently of the Android Auto preference. */
    var desktopFps: FpsSetting
        get() = prefs.getString("desktop_fps", null).let { name -> FpsSetting.entries.firstOrNull { it.name == name } ?: FpsSetting.FPS15 }
        set(value) { prefs.edit().putString("desktop_fps", value.name).apply() }
    /** A transport budget, not a forced frame rate. Existing FPS values remain readable for migration. */
    var desktopBitrateMbps: Int
        get() = prefs.getInt("desktop_bitrate_mbps", 8).coerceIn(1, 20)
        set(value) { require(value in 1..20); prefs.edit().putInt("desktop_bitrate_mbps", value).apply() }
    var autoMusicPlayback: Boolean
        get() = prefs.getBoolean("auto_music_playback", true)
        set(value) { prefs.edit().putBoolean("auto_music_playback", value).apply() }
    var huNotificationsEnabled: Boolean
        get() = prefs.getBoolean("hu_notifications_enabled", false)
        set(value) { prefs.edit().putBoolean("hu_notifications_enabled", value).apply() }
    var initialSetupCompleted: Boolean
        get() = prefs.getBoolean("initial_setup_completed", hasPaired)
        set(value) { prefs.edit().putBoolean("initial_setup_completed", value).apply() }

    var dpiCorrectionPackages: Set<String>
        get() = prefs.getStringSet("dpi_correction_packages", null)?.toSet()
            ?: com.headswap.desk.shared.DesktopRenderPolicy.DEFAULT_PACKAGES
        set(value) { prefs.edit().putStringSet("dpi_correction_packages", value.toSet()).apply() }

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
    /** Measured on the actual current display, never inferred solely from a permission grant. */
    @Volatile var independentPhoneLock: Boolean? = null
    /** Last acknowledged override for this session; never persisted or enabled automatically. */
    @Volatile var phoneScreenOff: Boolean = false
    @Volatile var phoneScreenBusy: Boolean = false
    @Volatile var phoneScreenError: String? = null
}
