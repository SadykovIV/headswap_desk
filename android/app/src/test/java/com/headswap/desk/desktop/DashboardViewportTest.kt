package com.headswap.desk.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardViewportTest {
    @Test fun boundsAtHeadUnitSizeReserveMusicAndActualAuthorDock() {
        assertEquals(DashboardViewport(897, 689, true), DashboardViewport.measure(1281, 720, true, true, 31, 77))
        assertEquals(DashboardViewport(1280, 643, true), DashboardViewport.measure(1280, 720, false, true, 900, 77))
        assertEquals(DashboardViewport(560, 160, true), DashboardViewport.measure(800, 200, true, true, 80, 96))
    }

    @Test fun authorCannotIssueActionsWhileItsWindowIsEntirelyCovered() {
        assertEquals(DashboardViewport(1280, 720, false), DashboardViewport.measure(1280, 720, false, true, 0, 96))
        assertEquals(DashboardViewport(1280, 720, true), DashboardViewport.measure(1280, 720, false, false, 0, 96))
        assertEquals(DashboardViewport(1280, 719, true), DashboardViewport.measure(1280, 720, false, true, 1, 96))
    }
}
