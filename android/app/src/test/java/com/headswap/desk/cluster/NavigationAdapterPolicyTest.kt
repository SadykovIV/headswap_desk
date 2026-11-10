package com.headswap.desk.cluster

import org.junit.Assert.*
import org.junit.Test
import com.headswap.desk.aa.AaMeta

class NavigationAdapterPolicyTest {
    private val sample = NavigationAdapterSample(1, "session", 1, 10_000, 42, "guidance", 1, "RIGHT:position", "RIGHT", 300, "ул. Ленина")

    @Test fun scopeAndFreshnessCannotBeBypassed() {
        val p = NavigationAdapterPolicy()
        assertFalse(p.accepts(sample.copy(display = 0), 10_100, setOf(0, 42)))
        assertFalse(p.accepts(sample, 10_100, setOf(43)))
        assertFalse(p.accepts(sample, 12_001, setOf(42)))
        assertFalse(p.accepts(sample, 9_999, setOf(42)))
        assertFalse(p.accepts(sample.copy(protocol = 2), 10_100, setOf(42)))
        assertTrue(p.accepts(sample, 10_100, setOf(42)))
        assertFalse(p.accepts(sample, 10_101, setOf(42)))
    }
    @Test fun newStepWithEmptyRoadDoesNotReusePreviousStreet() {
        val p = NavigationAdapterPolicy()
        assertEquals("ул. Ленина", p.hint(sample, true)?.road)
        val next = sample.copy(sequence = 2, routeEpoch = 2, step = "LEFT:new", action = "LEFT", distance = 50, road = "")
        assertTrue(p.accepts(sample, 10_010, setOf(42)))
        assertTrue(p.accepts(next, 10_020, setOf(42)))
        assertEquals(ClusterNavigationHint(AaMeta.T_LEFT, "", 50), p.hint(next, true))
        assertFalse(p.accepts(sample.copy(sequence = 3), 10_030, setOf(42)))
    }
    @Test fun endedReroutingAndUnknownManeuversClearOutput() {
        val p = NavigationAdapterPolicy()
        for (state in listOf("ended", "rerouting", "waiting-location", "unavailable", "no-maneuver"))
            assertNull(p.hint(sample.copy(state = state), true))
        assertNull(p.hint(sample.copy(action = "NEW_UNSUPPORTED_ACTION"), true))
        assertNull(p.hint(sample.copy(distance = -1), true))
        assertNull(p.hint(sample.copy(routeEpoch = 0), true))
        assertEquals("", p.hint(sample, false)?.road)
    }
    @Test fun processRestartHasFreshIndependentSequence() {
        val p = NavigationAdapterPolicy()
        assertTrue(p.accepts(sample.copy(sequence = 200), 10_010, setOf(42)))
        assertTrue(p.accepts(sample.copy(session = "new-process"), 10_020, setOf(42)))
    }
    @Test fun malformedTextIsRejectedWithoutLosingLastSequence() {
        val p = NavigationAdapterPolicy()
        assertFalse(p.accepts(sample.copy(road = "street\nother"), 10_010, setOf(42)))
        assertFalse(p.accepts(sample.copy(road = "x".repeat(101)), 10_010, setOf(42)))
        assertTrue(p.accepts(sample, 10_010, setOf(42)))
    }
}
