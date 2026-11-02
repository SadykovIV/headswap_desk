package com.headswap.desk.ime

/** Only a completed single tap that started and ended outside the keyboard and all editors can dismiss. */
internal class ImeDismissTap {
    private data class Start(val x: Float, val y: Float, val time: Long)
    private var start: Start? = null

    fun reset() { start = null }

    fun consumeUp(action: Int, count: Int, x: Float, y: Float, now: Long, slop: Float,
        longPressMs: Long, outside: () -> Boolean): Boolean {
        if (action == 0) {
            start = if (count == 1 && outside()) Start(x, y, now) else null
            return false
        }
        val began = start ?: return false
        val dx = x - began.x
        val dy = y - began.y
        if (count != 1 || action !in 1..2 || now - began.time !in 0 until longPressMs ||
            dx * dx + dy * dy > slop * slop) {
            reset()
            return false
        }
        if (action != 1) return false
        reset()
        return outside()
    }
}
