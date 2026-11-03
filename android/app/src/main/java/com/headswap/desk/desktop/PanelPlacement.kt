package com.headswap.desk.desktop

/** Unknown task state can result from ActivityManager overload. It never grants input, media or
 * foreground authorization; it only keeps a previously observed, still-owned window alive. */
internal object PanelPlacement {
    fun lost(confirmed: Boolean, state: Int?, leaseAlive: Boolean, initialDeadlineReached: Boolean): Boolean {
        if (!leaseAlive) return true
        return if (confirmed) state == 0 else initialDeadlineReached && state != 2
    }

    fun pollDelay(confirmed: Boolean, state: Int?, previous: Long): Long = when {
        !confirmed -> 300L
        state == 1 || state == 2 -> 1_000L
        else -> (previous * 2).coerceIn(1_000L, 5_000L)
    }
}
