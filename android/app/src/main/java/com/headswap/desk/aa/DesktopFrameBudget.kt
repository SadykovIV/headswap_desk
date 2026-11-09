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

/** One epoch for video and PCM. Never restamp delayed samples as newly captured media. */
internal class DesktopMediaClock(private val epochMs: Long, private val originUs: Long) {
    fun wireTime(presentationUs: Long?, nowUs: Long): Int {
        val time = presentationUs?.takeIf { it > 0 && it <= nowUs + 20_000 && nowUs - it <= 30_000_000 } ?: nowUs
        return ((epochMs + (time - originUs) / 1000) and 0xFFFFFFFFL).toInt()
    }
}
