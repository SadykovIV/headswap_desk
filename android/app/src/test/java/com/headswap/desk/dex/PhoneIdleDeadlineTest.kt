package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PhoneIdleDeadlineTest {
    @Test fun onlyInternalPanelEventsAffectTheTimer() {
        val parser = PhoneIdleDeadline.GroupParser()
        val lines = listOf("lastUserActivityTime=998 (2 ms ago)", "Power Group User Activity:", "groupId: 12",
            "lastUserActivityTime=990 (10 ms ago)", "lastUserActivityTimeNoChangeLights=995 (5 ms ago)",
            "groupId: 0", "lastUserActivityTime=800 (200 ms ago)", "lastUserActivityTimeNoChangeLights=810 (190 ms ago)")
        assertEquals(listOf(810L), lines.mapNotNull { parser.line(it, 1000) })
        assertEquals(110L, PhoneIdleDeadline.remaining(1000, 700, 810, 300))
    }

    @Test fun missingOrInvalidInputCannotTriggerPhysicalBlank() {
        assertNull(PhoneIdleDeadline.powerTime("123", "", 1000))
        assertNull(PhoneIdleDeadline.powerTime("0", "0", 1000))
        assertNull(PhoneIdleDeadline.powerTime("1001", "0", 1000))
        assertNull(PhoneIdleDeadline.remaining(50, 51, 1, 1))
        val parser = PhoneIdleDeadline.GroupParser()
        assertTrue(listOf("Power Group User Activity:", "groupId: 0", "lastUserActivityTime=55 (x)",
            "groupId: 11", "lastUserActivityTimeNoChangeLights=60 (x)").none { parser.line(it, 100) != null })
    }

    @Test fun reactivationWakeAndRealPhoneInteractionRespectAFullInterval() {
        assertEquals(30L, PhoneIdleDeadline.remaining(100, 100, 10, 30))
        assertEquals(16L, PhoneIdleDeadline.remaining(140, 100, 126, 30))
        assertEquals(0L, PhoneIdleDeadline.remaining(156, 100, 126, 30))
        assertEquals(20L, PhoneIdleDeadline.remaining(170, 160, 126, 30))
    }
}
