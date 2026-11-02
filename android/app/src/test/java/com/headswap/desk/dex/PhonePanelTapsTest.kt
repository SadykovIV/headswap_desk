package com.headswap.desk.dex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePanelTapsTest {
    private fun taps() = PhonePanelTaps(40, 300, 450, 12, 80)

    @Test fun secondCompletedTapWakesOnceAndConsumesTheGesture() {
        val taps = taps()
        taps.down(10, 20f, 50f)
        assertFalse(taps.up(55, 23f, 47f))
        taps.down(163, 22f, 49f)
        assertTrue(taps.up(210, 22f, 50f))
        taps.down(277, 22f, 49f)
        assertFalse(taps.up(292, 22f, 50f))
    }
    @Test fun dragHoldCancellationAndDistantTapsAreRejected() {
        val taps = taps()
        fun tap(at: Long, x: Float): Boolean {
            taps.down(at, x, 0f)
            return taps.up(at + 35, x, 0f)
        }
        assertFalse(tap(0, 0f))
        taps.down(110, 0f, 0f)
        taps.move(21f, 0f)
        assertFalse(taps.up(150, 0f, 0f))
        assertFalse(tap(240, 0f))
        taps.cancel() // Extra pointer, non-phone source, or ACTION_CANCEL.
        assertFalse(tap(330, 0f))
        assertFalse(tap(455, 120f))
        taps.down(590, 120f, 0f)
        assertFalse(taps.up(1100, 120f, 0f))
    }
    @Test fun tooFastOrExpiredContactDoesNotWake() {
        val taps = taps()
        for (at in listOf(10L, 37L, 500L)) {
            taps.down(at, 20f, 0f)
            assertFalse(taps.up(at + 10, 20f, 0f))
        }
    }
}
