package com.headswap.desk.ime

import org.junit.Assert.*
import org.junit.Test

class ImeDismissTapTest {
    private val tap = ImeDismissTap()
    private fun event(action: Int, x: Float, ms: Long, outside: Boolean, count: Int = 1) =
        tap.consumeUp(action, count, x, 10f, ms, 8f, 400) { outside }

    @Test fun outsideDownAndUpConsumesExactlyOneRelease() {
        assertFalse(event(0, 20f, 100, true))
        assertTrue(event(1, 24f, 230, true))
        assertFalse(event(1, 24f, 245, true))
    }

    @Test fun editorKeyboardAndNewFocusKeepNativeTouch() {
        assertFalse(event(0, 20f, 100, false))
        assertFalse(event(1, 21f, 120, true))
        assertFalse(event(0, 20f, 200, true))
        assertFalse(event(1, 20f, 260, false))
    }

    @Test fun panMultiTouchLongPressAndCancellationAreNotTaps() {
        assertFalse(event(0, 20f, 10, true)); assertFalse(event(2, 60f, 60, true))
        assertFalse(event(1, 20f, 80, true))
        assertFalse(event(0, 20f, 100, true)); assertFalse(event(5, 20f, 120, true, 2))
        assertFalse(event(1, 20f, 150, true))
        assertFalse(event(0, 20f, 200, true)); assertFalse(event(1, 20f, 600, true))
        assertFalse(event(0, 20f, 700, true)); assertFalse(event(3, 20f, 720, true))
        assertFalse(event(1, 20f, 750, true))
    }
}
