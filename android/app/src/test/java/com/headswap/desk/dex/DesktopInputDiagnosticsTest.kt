package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopInputDiagnosticsTest {
    @Test fun accumulatesQueueAndRpcSeparatelyWithoutPerEventReports() {
        val stats = DesktopInputDiagnostics("test")
        assertNull(stats.completed(1, 0, 2_000_000, 5_000_000, 3, true))
        val report = stats.completed(1, 4_999_000_000, 5_003_000_000, 5_008_000_000, 7, false)!!
        assertTrue(report.contains("count=2 errors=1"))
        assertTrue(report.contains("queueMs=3.00/max4.00"))
        assertTrue(report.contains("injectMs=4.00/max5.00"))
        assertTrue(report.contains("observedDepthMax=7"))
        assertNull(stats.completed(1, 5_010_000_000, 5_011_000_000, 5_012_000_000, 1, true))
    }

    @Test fun aNewGenerationCannotInheritOldQueueSpikes() {
        val stats = DesktopInputDiagnostics("test")
        assertNull(stats.completed(1, 0, 4_000_000_000, 4_001_000_000, 128, false))
        assertNull(stats.completed(2, 4_500_000_000, 4_501_000_000, 4_502_000_000, 1, true))
        val report = stats.completed(2, 9_500_000_000, 9_501_000_000, 9_502_000_000, 1, true)!!
        assertTrue(report.contains("generation=2 count=2 errors=0"))
        assertTrue(report.contains("queueMs=1.00/max1.00"))
        assertTrue(report.contains("observedDepthMax=1"))
    }

    @Test fun oneAbnormallyLongCallIsReportedOnCompletion() {
        val report = DesktopInputDiagnostics("test").completed(4, 0, 1_000_000,
            6_001_000_000, 1, false)!!
        assertTrue(report.contains("count=1 errors=1"))
        assertTrue(report.contains("injectMs=6000.00/max6000.00"))
    }
}
