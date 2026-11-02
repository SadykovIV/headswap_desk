package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PhonePanelLeaseTest {
    private class Fixture {
        var unlocked = true
        var restoreAllowed = true
        var held = false
        var failOff = false
        var failRestore = false
        var failRefresh = false
        var refreshed = 0
        val calls = mutableListOf<String>()
        val lease = PhonePanelLease({ unlocked }, { restoreAllowed },
            { held = true; calls.add("acquire") }, { held = false; calls.add("release") },
            { on -> calls.add(if (on) "on" else "off"); if (!on && failOff || on && failRestore) error("native") },
            { refreshed++; if (failRefresh) error("refresh") })
    }
    @Test fun blankKeepsDeviceAwakeAndRestoreReleasesAfterPowerOn() {
        val f = Fixture(); val token = Any()
        f.lease.blank(token, 0)
        assertTrue(f.held); assertTrue(f.lease.isBlank)
        f.lease.restore(token)
        assertFalse(f.held); assertFalse(f.lease.hasLease)
        assertEquals(listOf("acquire", "off", "on", "release"), f.calls)
    }
    @Test fun lockedPhoneNeverChangesPowerOrAcquiresWakeLock() {
        val f = Fixture(); f.unlocked = false
        assertThrows(IllegalStateException::class.java) { f.lease.blank(Any(), 0) }
        assertTrue(f.calls.isEmpty()); assertFalse(f.lease.hasLease)
    }
    @Test fun uncertainOffFailureRestoresPanelAndReleasesWakeLock() {
        val f = Fixture(); f.failOff = true
        assertThrows(IllegalStateException::class.java) { f.lease.blank(Any(), 0) }
        assertEquals(listOf("acquire", "off", "on", "release"), f.calls)
        assertFalse(f.lease.hasLease); assertFalse(f.held)
    }
    @Test fun frameworkSleepDoesNotGetWokenDuringRestore() {
        val f = Fixture(); f.lease.blank(Any(), 0); f.restoreAllowed = false
        f.lease.restore()
        assertEquals(listOf("acquire", "off", "release"), f.calls)
        assertFalse(f.held)
    }
    @Test fun staleTransportRestoresOnceAndRecoveryCannotBlankAgain() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0)
        f.lease.poll(token, true, 1_000)
        f.lease.poll(token, false, 1_250)
        f.lease.poll(token, true, 1_500)
        assertFalse(f.lease.hasLease)
        assertEquals(1, f.calls.count { it == "off" })
        assertEquals(1, f.calls.count { it == "on" })
    }
    @Test fun lateWatchdogExpiresInsteadOfRenewingOldLease() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0)
        f.lease.poll(token, true, PhonePanelLease.TIMEOUT_MS + 1)
        assertFalse(f.lease.hasLease)
    }
    @Test fun oldOwnerCannotRestoreOrRenewNewOwner() {
        val f = Fixture(); val old = Any(); val current = Any()
        f.lease.blank(old, 0); f.lease.restore(old); f.lease.blank(current, 100)
        f.lease.restore(old); f.lease.poll(old, false, 150)
        assertTrue(f.lease.isBlank)
        f.lease.poll(current, true, 5_101)
        assertFalse(f.lease.hasLease)
    }
    @Test fun restoreFailureRetainsLeaseForRetryButReleasesWakeLock() {
        val f = Fixture(); f.lease.blank(Any(), 0); f.failRestore = true
        assertThrows(IllegalStateException::class.java) { f.lease.restore() }
        assertTrue(f.lease.hasLease); assertFalse(f.held)
        f.failRestore = false; f.lease.restore()
        assertFalse(f.lease.hasLease)
    }
    @Test fun failedRestoreIsRetriedEvenWhileTransportIsHealthy() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0); f.failRestore = true
        assertThrows(IllegalStateException::class.java) { f.lease.restore(token) }
        assertThrows(IllegalStateException::class.java) { f.lease.blank(token, 100) }
        f.failRestore = false
        f.lease.poll(token, true, 250)
        assertFalse(f.lease.hasLease)
        assertEquals(2, f.calls.count { it == "on" })
        assertEquals(1, f.calls.count { it == "off" })
    }
    @Test fun differentOwnerCannotTakeExistingLease() {
        val f = Fixture(); f.lease.blank(Any(), 0)
        assertThrows(IllegalStateException::class.java) { f.lease.blank(Any(), 1) }
        assertEquals(1, f.calls.count { it == "off" })
    }
    @Test fun serverRenewalRequiresCurrentOwnerAndStopsPermanentlyAfterRestore() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0)
        f.lease.poll(Any(), true, 100)
        assertEquals(0, f.refreshed)
        f.lease.poll(token, true, 1_000)
        assertEquals(1, f.refreshed)
        f.lease.restore(token)
        f.lease.poll(token, true, 1_250)
        assertEquals(1, f.refreshed)
        assertEquals(1, f.calls.count { it == "off" })
    }
    @Test fun renewalFailureRemovesOverrideAndReleasesWakeLock() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0); f.failRefresh = true
        assertThrows(IllegalStateException::class.java) { f.lease.poll(token, true, 1_000) }
        assertEquals(listOf("acquire", "off", "on", "release"), f.calls)
        assertFalse(f.lease.hasLease); assertFalse(f.held)
    }
    @Test fun failedExplicitRestoreCannotRenewServerOffOverride() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0); f.failRestore = true
        assertThrows(IllegalStateException::class.java) { f.lease.restore(token) }
        assertThrows(IllegalStateException::class.java) { f.lease.poll(token, true, 1_000) }
        assertEquals(0, f.refreshed)
        f.failRestore = false; f.lease.poll(token, true, 1_250)
        assertFalse(f.lease.hasLease); assertEquals(0, f.refreshed)
    }
    @Test fun phoneLockImmediatelyRemovesOverrideWithoutRenewal() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0); f.unlocked = false
        f.lease.poll(token, true, 250)
        assertFalse(f.lease.hasLease); assertFalse(f.held); assertEquals(0, f.refreshed)
    }
    @Test fun exactDeadlineCannotReblankServerExpiredLease() {
        val f = Fixture(); val token = Any(); f.lease.blank(token, 0)
        f.lease.poll(token, true, PhonePanelLease.TIMEOUT_MS)
        assertFalse(f.lease.hasLease); assertEquals(0, f.refreshed)
    }
    @Test fun modernSamsungRequiresBothStandardDesktopFeatures() {
        assertTrue(SamsungDexCapabilityPolicy.desktopFeature(true, false, false))
        assertTrue(SamsungDexCapabilityPolicy.desktopFeature(false, true, true))
        assertFalse(SamsungDexCapabilityPolicy.desktopFeature(false, false, true))
        assertFalse(SamsungDexCapabilityPolicy.desktopFeature(false, true, false))
        assertFalse(SamsungDexCapabilityPolicy.desktopFeature(false, false, false))
    }
    @Test fun dexCapabilityRequiresAllEvidence() {
        assertTrue(SamsungDexCapabilityPolicy.available(true, true, true, true, true))
        for (missing in 0..4) {
            val b = BooleanArray(5) { it != missing }
            assertFalse(SamsungDexCapabilityPolicy.available(b[0], b[1], b[2], b[3], b[4]))
        }
    }
}
