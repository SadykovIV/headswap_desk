package com.headswap.desk.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGuidanceCoordinatorTest {
    private val background = ClusterNavigationHint(3, "", 220)
    private val visible = ClusterNavigationHint(7, "", 40)
    private val requests = mutableListOf<(ForegroundGuidance) -> Unit>()
    private var now = 0L
    private val timeouts = mutableListOf<Pair<Long, () -> Unit>>()
    private val sent = mutableListOf<ClusterNavigationHint?>()
    private fun after(delay: Long, run: () -> Unit) { timeouts.add(now + delay to run) }
    private fun advance(to: Long) {
        while (true) {
            val next = timeouts.minByOrNull { it.first } ?: break
            if (next.first > to) break
            timeouts.remove(next)
            now = next.first
            next.second()
        }
        now = to
    }
    private val coordinator = RouteGuidanceCoordinator(requests::add, { it() }, ::after, sent::add)

    @Test fun withdrawalSwitchesDirectlyToLiveManeuverAndEndClearsTheHud() {
        coordinator.notifications(background, true)
        requests.single()(ForegroundGuidance.Unavailable)
        coordinator.notifications(null, true)
        coordinator.notifications(null, true) // unrelated update during the same read
        assertEquals(listOf(background), sent)
        assertEquals(2, requests.size)
        requests.last()(ForegroundGuidance.Maneuver(visible))
        assertEquals(listOf(background, visible), sent)
        coordinator.notifications(background, true)
        coordinator.poll()
        requests.last()(ForegroundGuidance.VisibleWithoutManeuver)
        assertEquals(listOf(background, visible, background, null), sent)
        coordinator.notifications(background, true) // old notification is still active when Yandex leaves foreground
        coordinator.poll()
        requests.last()(ForegroundGuidance.Unavailable)
        assertEquals(listOf(background, visible, background, null), sent)
        val newCard = background.copy(distanceMeters = 60)
        coordinator.notifications(newCard, true)
        assertEquals(newCard, sent.last())
    }

    @Test fun aNewBackgroundNotificationSupersedesAnOldOrInFlightForegroundReading() {
        coordinator.notifications(null, true)
        requests.single()(ForegroundGuidance.Maneuver(visible))
        coordinator.poll()
        coordinator.notifications(background, true)
        requests.last()(ForegroundGuidance.Maneuver(visible))
        assertEquals(listOf(null, visible, background), sent)
        coordinator.poll()
        requests.last()(ForegroundGuidance.Unavailable)
        assertEquals(background, sent.last())
    }

    @Test fun retryAfterAnEmptyWindowSwitchesWithoutSendingAnIntermediateEnd() {
        coordinator.notifications(background, true)
        requests.single()(ForegroundGuidance.Unavailable)
        coordinator.notifications(null, true)
        requests.last()(ForegroundGuidance.Unavailable)
        advance(499)
        coordinator.poll()
        assertEquals(2, requests.size)
        advance(500)
        assertEquals(3, requests.size)
        requests.last()(ForegroundGuidance.Maneuver(visible))
        advance(3_000)
        assertEquals(listOf(background, visible), sent)
    }

    @Test fun missingRouteExpiresAtTheOriginalDeadlineRegardlessOfAdditionalEvents() {
        coordinator.notifications(background, true)
        requests.single()(ForegroundGuidance.Unavailable)
        coordinator.notifications(null, true)
        advance(1_500) // first read timed out, retry scheduled for 2,000
        advance(2_000)
        val lastRequest = requests.last()
        coordinator.notifications(null, true)
        coordinator.poll()
        advance(2_999)
        assertEquals(3, requests.size)
        assertEquals(listOf(background), sent)
        advance(3_000)
        lastRequest(ForegroundGuidance.Maneuver(visible))
        assertEquals(listOf(background, null), sent)
        coordinator.poll()
        requests.last()(ForegroundGuidance.Maneuver(visible))
        assertEquals(listOf(background, null, visible), sent)
    }

    @Test fun losingAccessOrConfirmingRouteEndClearsDuringGraceImmediately() {
        coordinator.notifications(background, true)
        requests.single()(ForegroundGuidance.Unavailable)
        coordinator.notifications(null, true)
        requests.last()(ForegroundGuidance.Unavailable)
        coordinator.notifications(null, false)
        advance(500)
        assertEquals(listOf(background, null), sent)
        assertEquals(2, requests.size)
        coordinator.notifications(background, true)
        requests.last()(ForegroundGuidance.Unavailable)
        coordinator.notifications(null, true)
        requests.last()(ForegroundGuidance.VisibleWithoutManeuver)
        assertEquals(listOf(background, null, background, null), sent)
        advance(3_500)
        assertEquals(4, requests.size)
    }

    @Test fun disconnectStopsReadsAndInvalidatesTheirResults() {
        coordinator.notifications(background, true)
        requests.single()(ForegroundGuidance.Maneuver(visible))
        coordinator.poll()
        coordinator.notifications(null, false)
        requests.last()(ForegroundGuidance.Maneuver(visible))
        coordinator.poll()
        assertEquals(2, requests.size)
        assertEquals(listOf(background, visible, null), sent)
    }

    @Test fun notificationsAloneDoNotWaitForATimer() {
        val route = RouteGuidanceCoordinator(null, { it() }, ::after, sent::add)
        route.notifications(background, true)
        route.notifications(null, true)
        assertEquals(listOf(background, null), sent)
        assertTrue(timeouts.isEmpty())
    }

    @Test fun titleOnlyStatusCannotReplaceTheForegroundTurnAndRapidStopRejectsItsOldReply() {
        coordinator.notifications(null, true)
        requests.last()(ForegroundGuidance.Maneuver(visible))
        coordinator.notifications(null, true)
        assertEquals(visible, sent.last())
        coordinator.poll()
        val stale = requests.last()
        coordinator.interestChanged(continueBootstrap = false)
        coordinator.notifications(null, false)
        coordinator.interestChanged(continueBootstrap = false)
        coordinator.notifications(null, true)
        stale(ForegroundGuidance.Maneuver(visible))
        assertNull(sent.last())
        requests.last()(ForegroundGuidance.Maneuver(background))
        assertEquals(background, sent.last())
    }
}
