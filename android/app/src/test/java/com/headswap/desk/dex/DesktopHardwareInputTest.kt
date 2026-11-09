package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopHardwareInputTest {
    @Test fun secondCompletedBackGoesHomeAndNextPressStartsNewSequence() {
        val keys = DesktopBackSequence()
        assertFalse(keys.down(1000)); assertFalse(keys.up(1050))
        assertTrue(keys.down(1300)); assertTrue(keys.up(1350))
        assertFalse(keys.down(1400)); assertFalse(keys.up(1450))
    }

    @Test fun duplicateDownAndOrphanedUpCannotBecomeDoublePress() {
        val keys = DesktopBackSequence()
        assertFalse(keys.up(100))
        assertFalse(keys.down(200)); assertFalse(keys.down(220)); assertFalse(keys.up(250))
        assertFalse(keys.up(260))
        assertFalse(keys.down(900)); assertFalse(keys.up(950))
    }

    @Test fun interveningInputAndClockRegressionCancelTheDoublePress() {
        val keys = DesktopBackSequence()
        keys.down(100); keys.up(200); keys.reset()
        assertFalse(keys.down(300)); assertFalse(keys.up(350))
        assertFalse(keys.down(250)); assertFalse(keys.up(260))
    }

    @Test fun rotaryUsesUpstreamDirectionAndBoundsMalformedBursts() {
        assertEquals(listOf(20, 20), DesktopRotaryInput.keys(2))
        assertEquals(listOf(19), DesktopRotaryInput.keys(-1))
        assertTrue(DesktopRotaryInput.keys(0).isEmpty())
        assertEquals(8, DesktopRotaryInput.keys(Int.MAX_VALUE).size)
        assertEquals(List(8) { 19 }, DesktopRotaryInput.keys(Int.MIN_VALUE))
    }

    @Test fun focusCanOnlySelectOwnedLiveParentOrChild() {
        val children = setOf(8, 12)
        assertEquals(12, DesktopInputTarget.resolve(5, children, 12))
        for (focus in listOf(null, 0, -1, 99)) assertEquals(5, DesktopInputTarget.resolve(5, children, focus))
        assertEquals(5, DesktopInputTarget.resolve(5, emptySet(), 12))
    }

    @Test fun assistantWithPartialTaskVisibilityCannotReuseSingletonOrPhoneTask() {
        for (mode in 1..4) assertNull(DesktopAssistantLaunchPolicy.plan(mode, false, false, 8, 5))
        assertEquals(DesktopAssistantLaunchPolicy.Plan(5, true),
            DesktopAssistantLaunchPolicy.plan(0, false, false, null, 5))
        assertEquals(DesktopAssistantLaunchPolicy.Plan(8, true),
            DesktopAssistantLaunchPolicy.plan(0, false, false, 8, 5))
    }

    @Test fun assistantReusesOnlyVerifiedOwnedTaskAndSeparatesStandardPhoneTask() {
        assertEquals(DesktopAssistantLaunchPolicy.Plan(8, false),
            DesktopAssistantLaunchPolicy.plan(2, true, false, 8, 5))
        assertNull(DesktopAssistantLaunchPolicy.plan(2, true, true, 8, 5))
        assertNull(DesktopAssistantLaunchPolicy.plan(2, true, false, null, 5))
        assertEquals(DesktopAssistantLaunchPolicy.Plan(8, true),
            DesktopAssistantLaunchPolicy.plan(0, true, true, 8, 5))
    }
}
