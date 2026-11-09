package com.headswap.desk.aa

/** Input-side rate control. Encoded reference frames are never discarded to lower FPS. */
internal class DesktopFrameRatePolicy {
    private var congestedSince: Long? = null
    private var clearSince: Long? = null
    private var lastChange: Long? = null
    @Volatile var current = 30
        private set

    @Synchronized fun reset(maximum: Int) {
        current = maximum.coerceIn(1, 60)
        congestedSince = null
        clearSince = null
        lastChange = null
    }

    @Synchronized fun update(nowMs: Long, backlogMs: Double, maximum: Int, overflow: Boolean = false): Int? {
        val limit = maximum.coerceIn(1, 60)
        if (current > limit) return change(limit, nowMs)
        if (!backlogMs.isFinite() || backlogMs < 0) return null
        if (overflow || backlogMs >= 150) {
            clearSince = null
            val since = congestedSince ?: nowMs.also { congestedSince = it }
            if ((overflow || nowMs - since >= 500) && (lastChange == null || nowMs - lastChange!! >= 1000)) {
                val lower = STEPS.lastOrNull { it < current } ?: minOf(10, limit)
                if (lower < current) return change(lower, nowMs)
            }
        } else {
            congestedSince = null
            if (backlogMs <= 50) {
                val since = clearSince ?: nowMs.also { clearSince = it }
                if (nowMs - since >= 8000 && (lastChange == null || nowMs - lastChange!! >= 8000)) {
                    val higher = minOf(limit, STEPS.firstOrNull { it > current } ?: limit)
                    if (higher > current) return change(higher, nowMs)
                }
            } else clearSince = null
        }
        return null
    }

    private fun change(value: Int, nowMs: Long): Int {
        current = value
        lastChange = nowMs
        congestedSince = null
        clearSince = null
        return value
    }

    companion object { private val STEPS = intArrayOf(10, 15, 20, 24, 30, 45, 60) }
}
