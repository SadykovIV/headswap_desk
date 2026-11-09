package com.headswap.desk.dex

/** PCM timestamps describe captured samples, not the moment a polling thread reads them. */
internal class DesktopAudioClock(private val sampleRate: Int = DesktopAudioPolicy.SAMPLE_RATE,
                                private val bufferCapacityFrames: Int = sampleRate) {
    private var framesRead = 0L
    private var fallbackOriginUs: Long? = null
    private var lastUs: Long? = null

    fun read(frames: Int, nowUs: Long, hardwareFrame: Long? = null, hardwareTimeUs: Long? = null): Long {
        require(frames >= 0)
        val durationUs = frames * 1_000_000L / sampleRate
        val first = fallbackOriginUs ?: (nowUs - durationUs).also { fallbackOriginUs = it }
        if (hardwareFrame != null && hardwareTimeUs != null && hardwareTimeUs in 0..nowUs) {
            // AudioRecord may drop unread samples on overflow. Its bounded ring cannot contain
            // an arbitrarily old frame, so move the read cursor past frames already overwritten.
            val oldestPossible = hardwareFrame - bufferCapacityFrames.coerceAtLeast(frames) - frames
            if (oldestPossible > framesRead) framesRead = oldestPossible
        }
        val estimate = if (hardwareFrame != null && hardwareTimeUs != null && hardwareFrame >= framesRead &&
            hardwareTimeUs in 0..nowUs) {
            hardwareTimeUs + (framesRead - hardwareFrame) * 1_000_000L / sampleRate
        } else first + framesRead * 1_000_000L / sampleRate
        // Some loopback drivers never publish AudioTimestamp. Even then the bounded
        // ring cannot retain seconds of samples across a stall; resynchronize at its
        // oldest possible sample, rather than declaring a delayed read fresh.
        val oldestTimeUs = nowUs - (bufferCapacityFrames.coerceAtLeast(frames).toLong() + frames) * 1_000_000L / sampleRate
        val value = maxOf(lastUs ?: Long.MIN_VALUE, oldestTimeUs, minOf(nowUs, estimate))
        framesRead += frames
        lastUs = value
        if (value != estimate || (hardwareFrame != null && hardwareTimeUs != null && hardwareTimeUs in 0..nowUs))
            fallbackOriginUs = value - (framesRead - frames) * 1_000_000L / sampleRate
        return value
    }
}
