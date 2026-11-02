package com.headswap.desk.dex

/** Two completed single-finger taps. A hold, drag, extra pointer or cancellation starts over. */
internal class PhonePanelTaps(
    private val minInterval: Long, private val maxInterval: Long, private val maxPress: Long,
    private val touchSlop: Int, private val doubleTapSlop: Int,
) {
    private var downAt = -1L
    private var downX = 0f
    private var downY = 0f
    private var firstUp = -1L
    private var firstX = 0f
    private var firstY = 0f
    private var second = false

    fun down(time: Long, x: Float, y: Float) {
        second = firstUp >= 0 && time - firstUp in minInterval..maxInterval &&
            near(x, y, firstX, firstY, doubleTapSlop)
        firstUp = -1
        downAt = time
        downX = x
        downY = y
    }
    fun move(x: Float, y: Float) {
        if (downAt >= 0 && !near(x, y, downX, downY, touchSlop)) cancel()
    }
    fun up(time: Long, x: Float, y: Float): Boolean {
        move(x, y)
        if (downAt < 0 || time - downAt !in 0..maxPress) { cancel(); return false }
        if (second) { cancel(); return true }
        firstUp = time
        firstX = downX
        firstY = downY
        downAt = -1
        return false
    }
    fun cancel() { downAt = -1; firstUp = -1; second = false }
    private fun near(x: Float, y: Float, originX: Float, originY: Float, slop: Int): Boolean {
        val dx = x - originX
        val dy = y - originY
        return dx * dx + dy * dy <= slop.toFloat() * slop
    }
}
