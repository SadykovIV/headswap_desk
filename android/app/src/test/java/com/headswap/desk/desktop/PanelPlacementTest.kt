package com.headswap.desk.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPlacementTest {
    @Test fun temporaryFailureAfterVerifiedPlacementDoesNotCloseALiveProfile() {
        for (state in listOf(null, -1, 1, 2)) {
            assertFalse(PanelPlacement.lost(true, state, true, initialDeadlineReached = true))
            assertTrue(PanelPlacement.lost(true, state, false, initialDeadlineReached = true))
        }
        assertTrue(PanelPlacement.lost(true, 0, true, initialDeadlineReached = false))
    }

    @Test fun initialStartHasABoundedWaitAndRequiresForegroundPlacement() {
        for (state in listOf(null, -1, 0, 1)) {
            assertFalse(PanelPlacement.lost(false, state, true, false))
            assertTrue(PanelPlacement.lost(false, state, true, true))
        }
        assertFalse(PanelPlacement.lost(false, 2, true, true))
        assertTrue(PanelPlacement.lost(false, 2, false, false))
    }

    @Test fun confirmedUnknownPollsBackOffAndResumeNormalRateOnRecovery() {
        var delay = 1_000L
        for (expected in listOf(2_000L, 4_000L, 5_000L, 5_000L)) {
            delay = PanelPlacement.pollDelay(true, -1, delay)
            assertEquals(expected, delay)
        }
        assertEquals(1_000L, PanelPlacement.pollDelay(true, 2, delay))
        assertEquals(300L, PanelPlacement.pollDelay(false, null, delay))
    }
}
