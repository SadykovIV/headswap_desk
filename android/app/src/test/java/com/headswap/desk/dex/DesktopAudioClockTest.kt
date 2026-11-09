package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopAudioClockTest {
    @Test fun boundedRingAlsoRecoversWithoutDriverTimestamps() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 1920)
        clock.read(960, 1_000_000)
        val afterStall = clock.read(960, 4_000_000)
        assertEquals(3_940_000L, afterStall)
        assertEquals(3_960_000L, clock.read(960, 4_000_000))
        assertEquals(3_980_000L, clock.read(960, 4_000_000))
        assertEquals(4_000_000L, clock.read(960, 4_020_000))
    }
    @Test fun readerRecoversAfterAudioRecordOverwritesItsRing() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 1920)
        clock.read(960, 1_000_000, 960, 1_000_000)
        val afterStall = clock.read(960, 4_000_000, 144960, 4_000_000)
        assertTrue("overwritten samples must not leave audio permanently stale", afterStall >= 3_940_000)
        val next = clock.read(960, 4_020_000, 145920, 4_020_000)
        assertTrue(next >= afterStall + 20_000)
    }
    @Test fun HardwareTimestampLossUsesLastCalibratedSampleClock() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 1920)
        val a = clock.read(960, 1_000_000, 1440, 1_000_000)
        val b = clock.read(960, 1_040_000)
        assertEquals(a + 20_000, b)
    }
    @Test fun captureClockDoesNotFollowDelayedReader() {
        val clock = DesktopAudioClock()
        assertEquals(980_000L, clock.read(960, 1_000_000))
        assertEquals(1_000_000L, clock.read(960, 1_500_000))
        assertEquals(1_020_000L, clock.read(960, 1_500_000))
    }
    @Test fun hardwareFramePositionLocatesStartOfRead() {
        val clock = DesktopAudioClock()
        assertEquals(900_000L, clock.read(960, 1_000_000, 4800, 1_000_000))
        assertEquals(920_000L, clock.read(960, 1_020_000, 5760, 1_020_000))
    }
    @Test fun invalidOrBackwardHardwareTimestampNeverReversesTime() {
        val clock = DesktopAudioClock()
        val a = clock.read(960, 1_000_000)
        val b = clock.read(960, 1_020_000, 999_999, 1)
        assertTrue(b >= a)
        val reset = DesktopAudioClock().read(960, 2_000_000)
        assertEquals(1_980_000L, reset)
    }
}
