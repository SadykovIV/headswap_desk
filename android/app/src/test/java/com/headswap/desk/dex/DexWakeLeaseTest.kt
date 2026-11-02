package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DexWakeLeaseTest {
    @Test fun releasesOnceAfterSuccessfulAcquireAndRepeatedStopIsHarmless() {
        var releases = 0
        val lease = DexWakeLease({}, { releases++ })
        assertFalse(lease.isHeld)
        lease.acquire()
        assertTrue(lease.isHeld)
        lease.release()
        lease.release()
        assertFalse(lease.isHeld)
        assertEquals(1, releases)
    }

    @Test fun uncertainAcquireStillReleasesRemoteResource() {
        var remoteHeld = false
        val failure = IllegalStateException("reply lost after acquire")
        val lease = DexWakeLease({ remoteHeld = true; throw failure }, { remoteHeld = false })
        try { lease.acquire(); fail("Expected acquire failure") } catch (error: IllegalStateException) { assertSame(failure, error) }
        assertFalse(remoteHeld)
        assertFalse(lease.isHeld)
    }

    @Test fun failedUncertainCleanupIsRetriedDespiteNoAcquireAck() {
        var releases = 0
        val failure = IllegalStateException("uncertain acquire")
        val lease = DexWakeLease({ throw failure }, { if (++releases == 1) error("temporary release failure") })
        try { lease.acquire(); fail("Expected failure") } catch (error: IllegalStateException) { assertSame(failure, error) }
        assertFalse(lease.isHeld)
        assertEquals(1, failure.suppressed.size)
        lease.release()
        lease.release()
        assertEquals(2, releases)
    }

    @Test fun failedReleasePreservesOwnershipUntilRetrySucceeds() {
        var attempts = 0
        val lease = DexWakeLease({}, { if (++attempts == 1) error("release failed") })
        lease.acquire()
        try { lease.release(); fail("Expected release failure") } catch (_: IllegalStateException) { }
        assertTrue(lease.isHeld)
        lease.release()
        assertFalse(lease.isHeld)
        assertEquals(2, attempts)
    }

    @Test fun noReleaseCallBeforeAnyAcquireAttempt() {
        val lease = DexWakeLease({}, { fail("Unowned token must not be released") })
        lease.release()
        assertFalse(lease.isHeld)
    }
}
