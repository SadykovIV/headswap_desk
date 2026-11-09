package com.headswap.desk.dex

/** A saved selection is not evidence of a live privileged process. */
enum class DesktopAccessMode {
    SHIZUKU, COMPUTER, LEGACY_ADB;

    companion object {
        /** The first backend is the default for the selected desktop. */
        fun optionsFor(profile: Int): List<DesktopAccessMode> = when (profile) {
            DesktopModePolicy.SAMSUNG_DEX -> listOf(SHIZUKU, LEGACY_ADB)
            DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK -> listOf(COMPUTER, SHIZUKU)
            else -> error("Неизвестный профиль рабочего стола")
        }

        fun restore(profile: Int, saved: String?, legacyUseShizuku: Boolean?): DesktopAccessMode {
            val allowed = optionsFor(profile)
            val requested = if (saved != null) entries.firstOrNull { it.name == saved }
                else legacyUseShizuku?.let { if (it) SHIZUKU else LEGACY_ADB }
            return requested?.takeIf(allowed::contains) ?: allowed.first()
        }
    }
}

/** One live activation owns the desktop resources until its cleanup acknowledges completion. */
internal object DesktopBackendTransitionPolicy {
    fun requiresActivationEnd(ownsActivation: Boolean, current: DesktopAccessMode, next: DesktopAccessMode): Boolean =
        current != next && blocksProjection(ownsActivation, next)

    fun blocksProjection(ownsActivation: Boolean, requested: DesktopAccessMode): Boolean =
        ownsActivation && requested != DesktopAccessMode.COMPUTER
}
