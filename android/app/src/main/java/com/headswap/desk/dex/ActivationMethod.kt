package com.headswap.desk.dex

/** A way to obtain access, independent of the process that supplies it. */
enum class ActivationMethod(val label: String) {
    WIFI("Wi-Fi"), SHIZUKU("Shizuku"), COMPUTER("С компьютера");

    fun backend(profile: Int): DesktopAccessMode? = when (this) {
        WIFI -> if (profile == DesktopModePolicy.SAMSUNG_DEX) DesktopAccessMode.LEGACY_ADB else DesktopAccessMode.COMPUTER
        SHIZUKU -> DesktopAccessMode.SHIZUKU
        COMPUTER -> DesktopAccessMode.COMPUTER.takeUnless { profile == DesktopModePolicy.SAMSUNG_DEX }
    }

    companion object {
        fun restore(profile: Int, saved: String?, backend: DesktopAccessMode): ActivationMethod =
            entries.firstOrNull { it.name == saved && it.backend(profile) == backend }
                ?: if (backend == DesktopAccessMode.SHIZUKU) SHIZUKU else WIFI
    }
}
