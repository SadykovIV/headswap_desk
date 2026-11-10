package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class ActivationMethodTest {
    @Test fun wifiAndComputerShareAccessWithoutEndingAnActiveLease() {
        for (profile in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
            val wifi = checkNotNull(ActivationMethod.WIFI.backend(profile))
            val pc = checkNotNull(ActivationMethod.COMPUTER.backend(profile))
            assertEquals(DesktopAccessMode.COMPUTER, wifi)
            assertEquals(wifi, pc)
            assertFalse(DesktopBackendTransitionPolicy.requiresActivationEnd(true, wifi, pc))
            assertTrue(DesktopBackendTransitionPolicy.requiresActivationEnd(true, wifi, DesktopAccessMode.SHIZUKU))
        }
    }

    @Test fun migrationDoesNotMistakeDelegatedAccessForComputerOnlySetup() {
        assertEquals(ActivationMethod.WIFI, ActivationMethod.restore(DesktopModePolicy.ANDROID_DESKTOP, null, DesktopAccessMode.COMPUTER))
        assertEquals(ActivationMethod.COMPUTER, ActivationMethod.restore(DesktopModePolicy.ANDROID_DESKTOP, "COMPUTER", DesktopAccessMode.COMPUTER))
        assertEquals(ActivationMethod.SHIZUKU, ActivationMethod.restore(DesktopModePolicy.ANDROID_DESKTOP, "WIFI", DesktopAccessMode.SHIZUKU))
        assertEquals(ActivationMethod.WIFI, ActivationMethod.restore(DesktopModePolicy.ANDROID_DESKTOP, "unknown", DesktopAccessMode.COMPUTER))
    }

    @Test fun samsungDexRetainsItsExistingLegacyAdbAndShizukuPaths() {
        val dex = DesktopModePolicy.SAMSUNG_DEX
        assertEquals(DesktopAccessMode.LEGACY_ADB, ActivationMethod.WIFI.backend(dex))
        assertEquals(DesktopAccessMode.SHIZUKU, ActivationMethod.SHIZUKU.backend(dex))
        assertNull(ActivationMethod.COMPUTER.backend(dex))
        assertEquals(ActivationMethod.WIFI, ActivationMethod.restore(dex, "COMPUTER", DesktopAccessMode.LEGACY_ADB))
    }
}
