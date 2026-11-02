package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PhoneIdleTimeoutTest {
    @Test fun existingOrNewInstallationsDefaultToTenSecondsEvenWithALongerSystemTimeout() {
        for (saved in listOf(null, "legacy", "TEN_SECONDS")) {
            assertEquals(10_000L, PhoneIdleTimeout.restore(saved).effectiveMs(30_000L))
        }
        assertEquals(10_000L, PhoneIdleTimeout.TEN_SECONDS.effectiveMs(0))
    }

    @Test fun explicitSystemChoiceTracksSystemTimeoutAndPreservesItsUnavailableState() {
        val choice = PhoneIdleTimeout.restore("SYSTEM")
        assertEquals(30_000L, choice.effectiveMs(30_000L))
        assertEquals(60_000L, choice.effectiveMs(60_000L))
        assertEquals(0L, choice.effectiveMs(0))
    }

    @Test fun tenSecondSelectionRearmsCurtainAndDoesNotRunOutsideCarLife() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 0, PhoneIdleTimeout.SYSTEM.effectiveMs(30_000))
        policy.onUserInteraction(20_000)
        val shorter = PhoneIdleTimeout.TEN_SECONDS.effectiveMs(30_000)
        assertEquals(10_000L, policy.refresh(true, 20_000, shorter).delayMs)
        assertFalse(policy.refresh(true, 29_999, shorter).curtainVisible)
        assertTrue(policy.refresh(true, 30_000, shorter).curtainVisible)
        val offline = policy.refresh(false, 30_100, shorter)
        assertFalse(offline.curtainVisible)
        assertFalse(offline.keepAwake)
        assertNull(offline.delayMs)
    }

    @Test fun tenSecondBackgroundDeadlineRestartsOnChoiceAndFreshPhoneActivity() {
        val timeout = PhoneIdleTimeout.TEN_SECONDS.effectiveMs(60_000)
        assertEquals(10_000L, PhoneIdleDeadline.remaining(20_000, 20_000, 100, timeout))
        assertEquals(1L, PhoneIdleDeadline.remaining(29_999, 20_000, 100, timeout))
        assertEquals(0L, PhoneIdleDeadline.remaining(30_000, 20_000, 100, timeout))
        assertEquals(8_000L, PhoneIdleDeadline.remaining(30_000, 20_000, 28_000, timeout))
    }
}
