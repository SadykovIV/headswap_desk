package com.headswap.desk.aa

import org.junit.Assert.*
import org.junit.Test

class SurfaceFrameClockTest {
    @Test fun delayedFreshBufferKeepsCaptureTimeThroughTheGate() {
        val clock = SurfaceFrameClock()
        assertEquals(SurfaceFrameClock.Latch.FRESH, clock.latch(ms(1000), ms(1200)))
        assertEquals(ms(1000), clock.presentationNs(ms(1250)))
        assertEquals(SurfaceFrameClock.Latch.FRESH, clock.latch(ms(1033), ms(1233)))
        assertEquals(ms(1033), clock.presentationNs(ms(1300)))
        assertEquals(0, clock.fallbacks)
    }

    @Test fun newestGatedBufferReplacesEarlierPendingBufferWithoutRestamping() {
        val clock = SurfaceFrameClock()
        clock.latch(ms(1000), ms(1010))
        clock.latch(ms(1033), ms(1040))
        assertEquals(ms(1033), clock.presentationNs(ms(1200)))
        assertEquals(ms(1300), clock.presentationNs(ms(1300))) // idle redraw, not a new capture
    }

    @Test fun lateFreshFrameAfterRepeatOnlyClampsItselfAndDoesNotShiftTheClock() {
        val clock = SurfaceFrameClock()
        clock.latch(ms(1000), ms(1100))
        clock.presentationNs(ms(1100))
        assertEquals(ms(1200), clock.presentationNs(ms(1200)))
        clock.latch(ms(1150), ms(1250))
        assertEquals(ms(1200) + 1000, clock.presentationNs(ms(1250)))
        clock.latch(ms(1280), ms(1300))
        assertEquals(ms(1280), clock.presentationNs(ms(1300)))
        assertEquals(1, clock.monotonicClamps)
    }

    @Test fun backwardTextureDoesNotComeBackAsAnIdleRedraw() {
        val clock = SurfaceFrameClock()
        clock.latch(ms(1000), ms(1010))
        clock.presentationNs(ms(1010))
        assertEquals(SurfaceFrameClock.Latch.REJECTED, clock.latch(ms(990), ms(1100)))
        assertNull(clock.presentationNs(ms(1500)))
        assertEquals(SurfaceFrameClock.Latch.REJECTED, clock.latch(ms(999), ms(1510)))
        assertNull(clock.presentationNs(ms(1600)))
        assertEquals(SurfaceFrameClock.Latch.FRESH, clock.latch(ms(1700), ms(1710)))
        assertEquals(ms(1700), clock.presentationNs(ms(1710)))
        assertEquals(2, clock.rejected)
    }

    @Test fun absentForeignAndFutureClocksUseExplicitFallbackThenRecover() {
        val clock = SurfaceFrameClock()
        for ((source, arrival) in listOf(0L to ms(50_000), -1L to ms(50_100),
            ms(1) to ms(50_200), ms(80_000) to ms(50_300))) {
            assertEquals(SurfaceFrameClock.Latch.FRESH, clock.latch(source, arrival))
            assertEquals(arrival, clock.presentationNs(arrival + ms(200)))
        }
        assertEquals(4, clock.fallbacks)
        clock.latch(ms(50_600), ms(50_610))
        assertEquals(ms(50_600), clock.presentationNs(ms(50_800)))
    }

    @Test fun smallFuturePresentTimeCannotMakeCodecClockRunAhead() {
        val clock = SurfaceFrameClock()
        clock.latch(ms(1010), ms(1000))
        assertEquals(ms(1000), clock.presentationNs(ms(1000)))
        assertNull(clock.presentationNs(ms(1000)))
        assertEquals(ms(1001), clock.presentationNs(ms(1001)))
        assertEquals(0, clock.fallbacks)
    }

    @Test fun coalescedCallbacksKeepThePendingFrameAndItsIdleRepeat() {
        val clock = SurfaceFrameClock()
        clock.latch(ms(1000), ms(1010))
        assertEquals(SurfaceFrameClock.Latch.SAME_BUFFER, clock.latch(ms(1000), ms(1020)))
        assertEquals(ms(1000), clock.presentationNs(ms(1200)))
        assertEquals(SurfaceFrameClock.Latch.SAME_BUFFER, clock.latch(ms(1000), ms(1210)))
        assertEquals(ms(1300), clock.presentationNs(ms(1300)))
        assertEquals(0, clock.rejected)
    }

    private fun ms(value: Long) = value * 1_000_000L
}
