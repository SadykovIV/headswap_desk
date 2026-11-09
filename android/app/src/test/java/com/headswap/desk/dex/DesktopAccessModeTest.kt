package com.headswap.desk.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAccessModeTest {
    @Test fun androidDesktopsUseHeadSwapOrShizuku() {
        val allowed = listOf(DesktopAccessMode.COMPUTER, DesktopAccessMode.SHIZUKU)
        for (profile in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
            assertEquals(allowed, DesktopAccessMode.optionsFor(profile))
            assertEquals(DesktopAccessMode.COMPUTER, DesktopAccessMode.restore(profile, null, null))
            for (name in listOf("LEGACY_ADB", "UNSUPPORTED", "")) {
                assertEquals(DesktopAccessMode.COMPUTER, DesktopAccessMode.restore(profile, name, true))
            }
            for (mode in allowed) assertEquals(mode, DesktopAccessMode.restore(profile, mode.name, false))
        }
    }

    @Test fun samsungDexUsesShizukuOrLegacyAdb() {
        val profile = DesktopModePolicy.SAMSUNG_DEX
        val allowed = listOf(DesktopAccessMode.SHIZUKU, DesktopAccessMode.LEGACY_ADB)
        assertEquals(allowed, DesktopAccessMode.optionsFor(profile))
        for (name in listOf(null, "COMPUTER", "UNSUPPORTED", "")) {
            assertEquals(DesktopAccessMode.SHIZUKU, DesktopAccessMode.restore(profile, name, null))
        }
        for (mode in allowed) assertEquals(mode, DesktopAccessMode.restore(profile, mode.name, false))
    }

    @Test fun backendChangeWaitsForActivationCleanupButSameBackendProfilesRemainAvailable() {
        val owner = DelegatedBootstrapState()
        val lease = owner.begin { }
        for (next in listOf(DesktopAccessMode.SHIZUKU, DesktopAccessMode.LEGACY_ADB)) {
            assertTrue(DesktopBackendTransitionPolicy.requiresActivationEnd(owner.hasOwner(), DesktopAccessMode.COMPUTER, next))
        }
        for (same in DesktopAccessMode.entries) {
            assertFalse(DesktopBackendTransitionPolicy.requiresActivationEnd(owner.hasOwner(), same, same))
        }
        owner.requestStop()
        assertTrue(DesktopBackendTransitionPolicy.blocksProjection(owner.hasOwner(), DesktopAccessMode.SHIZUKU))
        assertFalse(DesktopBackendTransitionPolicy.blocksProjection(owner.hasOwner(), DesktopAccessMode.COMPUTER))
        owner.end(lease)
        for (next in DesktopAccessMode.entries) {
            assertFalse(DesktopBackendTransitionPolicy.requiresActivationEnd(owner.hasOwner(), DesktopAccessMode.COMPUTER, next))
        }
    }
}
