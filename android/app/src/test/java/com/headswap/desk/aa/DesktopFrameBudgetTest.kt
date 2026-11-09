package com.headswap.desk.aa

import org.junit.Assert.*
import org.junit.Test

class DesktopFrameBudgetTest {
    @Test fun oversizeIdrReducesRateBeforeTransportReceivesIt() {
        assertNull(DesktopFrameBudget.reducedBitrate(512 * 1024, 8_000_000))
        assertTrue(DesktopFrameBudget.reducedBitrate(800 * 1024, 8_000_000)!! < 6_000_000)
        assertEquals(BitratePolicy.FLOOR_BPS, DesktopFrameBudget.reducedBitrate(Int.MAX_VALUE, BitratePolicy.FLOOR_BPS))
    }
    @Test fun audioAndVideoShareEpochWithSubSecondPrecision() {
        val clock = DesktopMediaClock(5000, 1_000_000)
        assertEquals(5040, clock.wireTime(1_040_000, 1_100_000))
        assertEquals(5080, clock.wireTime(1_080_000, 1_120_000))
        assertEquals(5120, clock.wireTime(0, 1_120_000))
        assertEquals(0x10, DesktopMediaClock(0xfffffff0L, 1_000_000).wireTime(1_032_000, 1_032_000))
    }
}
