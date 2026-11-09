package com.headswap.desk.aa

/** Compatibility budget derived from the reference native CarLife receiver's 600 KiB buffer. */
internal object DesktopFrameBudget {
    const val MAX_ACCESS_UNIT_BYTES = 600 * 1024 - 12
    fun reducedBitrate(frameBytes: Int, currentBitrate: Int): Int? {
        if (frameBytes <= MAX_ACCESS_UNIT_BYTES) return null
        return maxOf(BitratePolicy.FLOOR_BPS,
            (currentBitrate.toLong() * MAX_ACCESS_UNIT_BYTES * 8 / (frameBytes.toLong() * 10)).toInt())
            .coerceAtMost(currentBitrate)
    }
}

/**
 * One capture clock internally; channel units were independently verified in Baidu CarLife 8.8.2:
 * Recorder.sendVideoData uses unix seconds, PCMPackageHead uses milliseconds truncated to u32.
 * See docs/MEDIA-PIPELINE-0.6.0.md. These wire fields do not provide HU presentation feedback.
 */
internal class DesktopMediaClock(private val epochMs: Long, private val originUs: Long) {
    fun wireVideoSeconds(presentationUs: Long?, nowUs: Long): Int =
        ((captureEpochMs(presentationUs, nowUs) / 1000) and 0xFFFFFFFFL).toInt()

    fun wireAudioMillis(presentationUs: Long?, nowUs: Long): Int =
        (captureEpochMs(presentationUs, nowUs) and 0xFFFFFFFFL).toInt()

    private fun captureEpochMs(presentationUs: Long?, nowUs: Long): Long {
        val time = presentationUs?.takeIf { it > 0 && it <= nowUs + 20_000 && nowUs - it <= 30_000_000 } ?: nowUs
        return epochMs + (time - originUs) / 1000
    }
}
