package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopOnboardingPolicyTest {
    @Test fun completionRequiresReviewedStepsLiveAccessAndAnAvailableProfile() {
        for (reviewed in listOf(false, true)) for (profile in listOf(false, true)) for (access in listOf(false, true))
            assertEquals(reviewed && profile && access, DesktopOnboardingPolicy.canComplete(reviewed, profile, access))
    }
    @Test fun onlyFreshUnknownProfileDefaultsToDriveDeck() {
        assertEquals(DesktopModePolicy.DRIVEDECK, DesktopOnboardingPolicy.initialProfile(null, false))
        assertEquals(DesktopModePolicy.ANDROID_DESKTOP, DesktopOnboardingPolicy.initialProfile(null, true))
        for (mode in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK, DesktopModePolicy.SAMSUNG_DEX)) {
            assertEquals(mode, DesktopOnboardingPolicy.initialProfile(mode, false))
            assertEquals(mode, DesktopOnboardingPolicy.initialProfile(mode, true))
        }
    }
}
