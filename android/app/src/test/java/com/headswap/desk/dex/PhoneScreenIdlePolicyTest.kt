package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PhoneScreenIdlePolicyTest {
    @Test fun connectionStartsFullTimeoutAndRepeatedRefreshDoesNotPostponeIt() {
        val policy = PhoneScreenIdlePolicy()
        assertEquals(PhoneScreenIdlePolicy.State(true, false, 30_000), policy.refresh(true, 100, 30_000))
        assertEquals(1L, policy.refresh(true, 30_099, 30_000).delayMs)
        assertEquals(PhoneScreenIdlePolicy.State(true, true, null), policy.refresh(true, 30_100, 30_000))
    }

    @Test fun phoneInteractionResetsTimeoutButSingleTapCannotDismissCurtain() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 0, 1_000)
        assertEquals(1_000L, policy.onUserInteraction(900).delayMs)
        assertFalse(policy.refresh(true, 1_899, 1_000).curtainVisible)
        assertTrue(policy.refresh(true, 1_900, 1_000).curtainVisible)
        assertTrue(policy.onUserInteraction(2_000).curtainVisible)
        assertEquals(PhoneScreenIdlePolicy.State(true, false, 1_000), policy.dismiss(2_100))
        assertFalse(policy.refresh(true, 3_099, 1_000).curtainVisible)
    }

    @Test fun losingEligibilityReleasesCurtainAndNextSessionStartsAtFullTimeout() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 0, 1_000)
        assertTrue(policy.showNow(1).curtainVisible)
        assertEquals(PhoneScreenIdlePolicy.State(false, false, null), policy.refresh(false, 2, 1_000))
        assertFalse(policy.showNow(3).curtainVisible)
        assertFalse(policy.onUserInteraction(4).keepAwake)
        assertEquals(1_000L, policy.refresh(true, 5, 1_000).delayMs)
    }

    @Test fun changedSystemTimeoutKeepsRealElapsedIdleTime() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 100, 30_000)
        assertEquals(50_000L, policy.refresh(true, 10_100, 60_000).delayMs)
        assertTrue(policy.refresh(true, 10_101, 10_000).curtainVisible)
    }

    @Test fun invalidTimeoutCannotBlackOutOrKeepScreenAwake() {
        for (invalid in listOf(-1L, 0L, Long.MAX_VALUE, Int.MAX_VALUE.toLong() + 1)) {
            val policy = PhoneScreenIdlePolicy()
            policy.refresh(true, 0, 1_000)
            policy.showNow(1)
            assertEquals(PhoneScreenIdlePolicy.State(false, false, null), policy.refresh(true, 2, invalid))
            assertFalse(policy.showNow(3).curtainVisible)
        }
    }

    @Test fun monotonicClockRollbackRestoresInsteadOfKeepingAStaleCurtain() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 10_000, 1_000)
        policy.showNow(10_001)
        assertEquals(PhoneScreenIdlePolicy.State(true, false, 1_000), policy.refresh(true, 5_000, 1_000))
        assertEquals(PhoneScreenIdlePolicy.State(false, false, null), policy.refresh(true, -1, 1_000))
    }

    @Test fun longUptimeAndMaximumTimeoutDoNotOverflowDeadlineArithmetic() {
        val policy = PhoneScreenIdlePolicy()
        val start = Long.MAX_VALUE - 100
        assertEquals(Int.MAX_VALUE.toLong(), policy.refresh(true, start, Int.MAX_VALUE.toLong()).delayMs)
        assertEquals(Int.MAX_VALUE.toLong() - 100, policy.refresh(true, Long.MAX_VALUE, Int.MAX_VALUE.toLong()).delayMs)
    }

    @Test fun acceptedPhysicalOffIsSentOnceAndWaitsForThePanelLease() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 0, 1_000, physicalMode = true)
        val request = policy.refresh(true, 1_000, 1_000, physicalMode = true)
        assertTrue(request.requestPhysicalOff)
        assertFalse(request.curtainVisible)
        assertFalse(policy.showNow(1_000).requestPhysicalOff) // Reentrant dispatch.
        assertTrue(policy.physicalRequestResult(true, 1_001).keepAwake)
        assertTrue(policy.refresh(true, 1_100, 1_000, true, panelBusy = true).keepAwake)
        assertFalse(policy.onUserInteraction(1_110, panelUnavailable = true).requestPhysicalOff)
        assertEquals(PhoneScreenIdlePolicy.State(false, false, null), policy.refresh(true, 1_200, 1_000, true, panelOff = true))
        assertEquals(PhoneScreenIdlePolicy.State(false, false, null), policy.refresh(true, 5_000, 1_000, true))
        assertEquals(1_000L, policy.onUserInteraction(6_000).delayMs)
        assertTrue(policy.refresh(true, 7_000, 1_000, true).requestPhysicalOff)
    }

    @Test fun rejectedDispatchDropsKeepAwakeWithoutRetryUntilInteraction() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 0, 1_000, true)
        assertTrue(policy.showNow(10).requestPhysicalOff)
        assertEquals(PhoneScreenIdlePolicy.State(false, false, null), policy.physicalRequestResult(false, 11))
        assertFalse(policy.refresh(true, 9_000, 1_000, true).requestPhysicalOff)
        assertFalse(policy.showNow(9_001).requestPhysicalOff)
        assertTrue(policy.onUserInteraction(10_000).keepAwake)
        assertFalse(policy.refresh(true, 10_999, 1_000, true).requestPhysicalOff)
        assertTrue(policy.refresh(true, 11_000, 1_000, true).requestPhysicalOff)
    }

    @Test fun missingOffAckAndFailedBusyTransitionReleaseTheHandoff() {
        for (busy in listOf(false, true)) {
            val policy = PhoneScreenIdlePolicy()
            policy.refresh(true, 0, 1_000, true)
            assertTrue(policy.showNow(1).requestPhysicalOff)
            policy.physicalRequestResult(true, 2)
            if (busy) policy.refresh(true, 3, 1_000, true, panelBusy = true)
            val ended = if (busy) 4L else 1 + PhoneScreenIdlePolicy.HANDOFF_MS
            assertEquals(PhoneScreenIdlePolicy.State(false, false, null), policy.refresh(true, ended, 1_000, true))
            assertFalse(policy.refresh(true, ended + 60_000, 1_000, true).requestPhysicalOff)
        }
    }

    @Test fun externallyControlledPanelDoesNotTriggerAnotherAutomaticOff() {
        val policy = PhoneScreenIdlePolicy()
        assertFalse(policy.refresh(true, 0, 1_000, true, panelOff = true).keepAwake)
        assertFalse(policy.refresh(true, 5_000, 1_000, true).requestPhysicalOff)
        assertFalse(policy.onUserInteraction(5_100, panelUnavailable = true).keepAwake)
        assertEquals(1_000L, policy.onUserInteraction(5_200, panelUnavailable = false).delayMs)
        assertTrue(policy.refresh(true, 6_200, 1_000, true).requestPhysicalOff)
    }

    @Test fun endingSessionOrChangingModeClearsPhysicalAttempt() {
        val policy = PhoneScreenIdlePolicy()
        policy.refresh(true, 0, 1_000, true)
        policy.showNow(100)
        policy.physicalRequestResult(true, 101)
        assertFalse(policy.refresh(false, 102, 1_000, true).keepAwake)
        assertEquals(1_000L, policy.refresh(true, 200, 1_000, true).delayMs)
        assertTrue(policy.refresh(true, 1_200, 1_000, true).requestPhysicalOff)
        assertEquals(1_000L, policy.refresh(true, 1_201, 1_000, false).delayMs)
        assertTrue(policy.refresh(true, 2_201, 1_000, false).curtainVisible)
    }
}
