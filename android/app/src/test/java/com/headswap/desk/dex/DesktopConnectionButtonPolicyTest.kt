package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopConnectionButtonPolicyTest {
    private fun state(stopped: Boolean = false, detached: Boolean = false, service: Boolean = false,
        desktop: Boolean = false, carLife: Boolean = false, video: Boolean = false, failed: Boolean = false,
        working: Boolean = false, pending: Boolean = false) = DesktopConnectionButtonPolicy.state(
        stopped, detached, service, desktop, carLife, video, failed, working, pending)

    @Test fun onlyLiveCarLifeAndDesktopTogetherOfferConnectedStop() {
        for (missing in 0..3) {
            val ready = BooleanArray(4) { it != missing }
            assertNotEquals(DesktopConnectionButtonPolicy.State.CONNECTED,
                state(service = ready[0], desktop = ready[1], carLife = ready[2], video = ready[3]))
        }
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTED,
            state(service = true, desktop = true, carLife = true, video = true))
    }
    @Test fun stopAndDetachedGraceOverrideStaleRunningFlags() {
        assertEquals(DesktopConnectionButtonPolicy.State.IDLE,
            state(stopped = true, service = true, desktop = true, carLife = true, video = true, working = true, pending = true))
        assertEquals(DesktopConnectionButtonPolicy.State.IDLE,
            state(detached = true, service = true, desktop = true, carLife = true, video = true, pending = true))
    }
    @Test fun setupPermissionAndLiveReconnectCanBeCancelledWithoutClaimingSuccess() {
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTING, state(working = true))
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTING, state(pending = true))
        assertTrue(DesktopConnectionButtonPolicy.State.CONNECTING.stopsSession)
    }
    @Test fun onlyUnarmedIdleOffersConnectAndBothWaitingStatesAreCancellable() {
        assertEquals(DesktopConnectionButtonPolicy.State.IDLE, state(pending = false))
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTING, state(pending = true))
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTING,
            state(service = true, desktop = true, carLife = true, video = false, pending = true))
    }
    @Test fun failureKeepsStopAvailableUntilTransportAndWaitHaveActuallyStopped() {
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTING,
            state(service = true, desktop = true, carLife = true, video = true, failed = true))
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTING,
            state(service = true, failed = true, working = true))
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTING, state(failed = true, pending = true))
        assertEquals(DesktopConnectionButtonPolicy.State.IDLE, state(failed = true))
        assertEquals(DesktopConnectionButtonPolicy.State.IDLE,
            state(stopped = true, service = true, failed = true, pending = true))
    }
    @Test fun successfulFrameSupersedesAnEarlierProgressMessage() {
        assertEquals(DesktopConnectionButtonPolicy.State.CONNECTED,
            state(service = true, desktop = true, carLife = true, video = true, working = true, pending = true))
    }
    @Test fun idleConnectAndActiveStopHaveDistinctActions() {
        assertEquals(DesktopConnectionButtonPolicy.State.IDLE, state())
        assertFalse(DesktopConnectionButtonPolicy.State.IDLE.stopsSession)
        assertTrue(DesktopConnectionButtonPolicy.State.CONNECTED.stopsSession)
        assertEquals("Подключить", DesktopConnectionButtonPolicy.State.IDLE.label)
        assertEquals("Остановить ожидание", DesktopConnectionButtonPolicy.State.CONNECTING.label)
        assertEquals("Отключить", DesktopConnectionButtonPolicy.State.CONNECTED.label)
    }
}
