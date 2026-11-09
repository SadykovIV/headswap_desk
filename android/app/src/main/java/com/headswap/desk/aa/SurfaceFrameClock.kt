package com.headswap.desk.aa

/**
 * Presentation clock for the native virtual-display producer, whose buffers use MONOTONIC time.
 * A newly latched buffer retains its source PTS through the FPS gate; only an idle redraw uses now.
 * Source time is not enabled for AA decoder surfaces: their media timeline has a different origin.
 * All decisions are made on the GL thread. Counters may be read by diagnostics on another thread.
 */
internal class SurfaceFrameClock {
    enum class Latch { FRESH, SAME_BUFFER, REJECTED }
    private var lastSourceNs: Long? = null
    private var pendingNs: Long? = null
    private var repeatAllowed = false
    private var lastOutputNs: Long? = null
    @Volatile var fallbacks = 0L; private set
    @Volatile var rejected = 0L; private set
    @Volatile var monotonicClamps = 0L; private set

    /** REJECTED disables redraw of this texture until a valid current buffer is latched. */
    fun latch(sourceNs: Long, nowNs: Long): Latch {
        val valid = sourceNs > 0 && sourceNs <= nowNs + FUTURE_TOLERANCE_NS &&
            nowNs - sourceNs <= MAX_SOURCE_AGE_NS
        if (valid && sourceNs == lastSourceNs) {
            // SurfaceTexture can coalesce callbacks: updateTexImage may return the buffer
            // already latched. Keep its pending capture time, or keep repeating it if shown.
            repeatAllowed = true
            return Latch.SAME_BUFFER
        }
        if (valid && lastSourceNs?.let { sourceNs < it } == true) {
            pendingNs = null
            repeatAllowed = false
            rejected++
            return Latch.REJECTED
        }
        pendingNs = if (valid) {
            lastSourceNs = sourceNs
            minOf(sourceNs, nowNs)
        } else {
            // Broken or foreign firmware clocks must not freeze the desktop. The fallback is
            // explicit in diagnostics; it is not a claim that capture timing was recovered.
            fallbacks++
            nowNs
        }
        repeatAllowed = true
        return Latch.FRESH
    }

    fun presentationNs(nowNs: Long): Long? {
        if (!repeatAllowed) return null
        val candidate = pendingNs ?: nowNs
        // Codec output is in microseconds. A valid source frame arriving after an idle redraw
        // may predate that redraw; clamp only this frame, never shift the source timeline.
        val monotonic = lastOutputNs?.let { maxOf(candidate, it + 1_000) } ?: candidate
        if (monotonic > nowNs) return null
        if (monotonic != candidate) monotonicClamps++
        pendingNs = null
        lastOutputNs = monotonic
        return monotonic
    }

    companion object {
        private const val FUTURE_TOLERANCE_NS = 20_000_000L
        private const val MAX_SOURCE_AGE_NS = 30_000_000_000L
    }
}
