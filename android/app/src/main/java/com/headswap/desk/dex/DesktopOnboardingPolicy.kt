package com.headswap.desk.dex

/** Existing installations keep their implicit Android Desktop as well as any explicit profile. */
internal object DesktopOnboardingPolicy {
    fun initialProfile(saved: Int?, existingPreferences: Boolean): Int = when (saved) {
        DesktopModePolicy.SAMSUNG_DEX, DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK -> saved
        else -> if (existingPreferences) DesktopModePolicy.ANDROID_DESKTOP else DesktopModePolicy.DRIVEDECK
    }

    fun canComplete(reviewed: Boolean, profileAvailable: Boolean, accessReady: Boolean): Boolean =
        reviewed && profileAvailable && accessReady
}
