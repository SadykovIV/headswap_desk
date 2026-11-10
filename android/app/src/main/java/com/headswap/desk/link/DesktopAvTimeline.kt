package com.headswap.desk.link

/** One access unit can span multiple writes. Once it begins, finish every fragment. */
interface VideoWrite {
    fun begin(): Boolean
    fun written()
}

/**
 * One MONOTONIC capture timeline per projection. Learn the actual capture-to-wire time of a
 * whole video frame, not the (second-resolution) CarLife header or the encoder queue size.
 * All callbacks only enqueue into the transport; no I/O or bridge callbacks under this lock.
 */
internal class DesktopAvTimeline(private val nowUs: () -> Long,
                                 private val adjustmentMs: () -> Int = { 0 },
                                 private val emit: (Int, ByteArray, Long) -> Unit) {
    private data class Pcm(val pts: Long, val chan: Int, val bytes: ByteArray)
    private val audio = ArrayDeque<Pcm>()
    private var audioBytes = 0
    private var epoch = 0L
    private var seq = 0L
    private var retired = 0L
    private var lastPicture: Long? = null
    private var decoding = false
    private var needKey = false
    private var projecting = false
    private var closed = false
    private var period = 33_334L
    private var trim = 0L
    private var lag = 0L
    private var expiredVideo = 0L
    private var skippedAudio = 0L
    // Allow ordinary codec/transport latency plus jitter. A fixed cutoff measured from
    // capture would repeatedly break a healthy reference chain on slower encoders.
    private val videoBudget get() = (maxOf(AUDIO_SLACK, 2 * period) + lag).coerceAtMost(MAX_FRAME_AGE)
    // A reference frame can establish a new baseline after a mode change or a stall.
    // Comparing it only to the *old* latency can reject every recovery frame forever.
    private fun admissionBudget(key: Boolean) = if (key) MAX_FRAME_AGE else videoBudget

    @Synchronized fun reset(active: Boolean) {
        epoch++; seq = 0; retired = 0; lastPicture = null; decoding = false; needKey = false
        projecting = active; lag = 0; clearAudio()
    }
    @Synchronized fun close() { closed = true; reset(false) }
    @Synchronized fun clearAudio() { audio.clear(); audioBytes = 0 }

    @Synchronized fun frame(pts: Long, key: Boolean, fps: Int): VideoWrite? {
        if (closed || !projecting) return null
        period = 1_000_000L / fps.coerceIn(1, 60)
        val age = nowUs() - pts
        if (age > admissionBudget(key) || (needKey && !key)) { needKey = true; expiredVideo++; return null }
        val generation = epoch
        val number = ++seq
        if (key) needKey = false
        return object : VideoWrite {
            private var accepted: Boolean? = null
            override fun begin(): Boolean = synchronized(this@DesktopAvTimeline) {
                accepted?.let { return@synchronized it }
                if (generation != epoch || closed) false else {
                    val valid = nowUs() - pts <= admissionBudget(key) && (key || decoding)
                    if (!valid) { decoding = false; needKey = true; retired = number; expiredVideo++ }
                    else if (key) { decoding = true; needKey = false }
                    valid
                }.also { accepted = it }
            }
            override fun written() = synchronized(this@DesktopAvTimeline) {
                if (generation == epoch && !closed && accepted == true) {
                    retired = number
                    val now = nowUs()
                    // A blocked, already-started write cannot be cancelled. Do not use a multi-
                    // second outlier as a new playback delay; recover at a fresh reference frame.
                    if (now - pts > admissionBudget(key)) { decoding = false; needKey = true; expiredVideo++ }
                    else {
                        val sample = (now - pts).coerceAtLeast(0)
                        lag = if (lastPicture == null || sample > lag) sample else (7 * lag + sample) / 8
                        lastPicture = pts
                    }
                    flush(now)
                }
            }
        }
    }

    @Synchronized fun pcm(channel: Int, inner: ByteArray, pts: Long): Boolean {
        if (closed) return false
        val now = nowUs()
        val correction = adjustmentMs().coerceIn(0, ReceiverAudioCalibration.MAX_MS) * 1000L
        if (correction != trim) { clearAudio(); trim = correction }
        val budgetBytes = MAX_BYTES + trim * 224 / 1000 // 48 kHz stereo 16-bit, plus packet headers
        val budgetCount = MAX_PACKETS + trim / 500
        if (now - pts >= AUDIO_SLACK + lag + trim || inner.size > budgetBytes) { skippedAudio++; return false }
        while (audio.isNotEmpty() && (audioBytes + inner.size > budgetBytes || audio.size >= budgetCount)) drop()
        audio.addLast(Pcm(pts, channel, inner)); audioBytes += inner.size
        flush(now)
        return true
    }

    private fun drop() { audioBytes -= audio.removeFirst().bytes.size; skippedAudio++ }
    private fun flush(now: Long) {
        while (audio.isNotEmpty()) {
            val first = audio.first()
            val remaining = first.pts + lag + trim + AUDIO_SLACK - now
            if (remaining <= 0) { drop(); continue }
            // If an actual video write is behind or the reference chain broke, do not let an
            // independent audio socket run ahead. A still image with no outstanding writes
            // continues music at its ordinary cadence, as does AUDIO-only / VIDEO_PAUSE.
            if (projecting && lastPicture == null) break
            if (projecting && (seq > retired || needKey || !decoding && seq > 0) &&
                first.pts > (lastPicture?.plus(period) ?: Long.MIN_VALUE)) break
            if (first.pts + lag + trim > now) break
            audio.removeFirst(); audioBytes -= first.bytes.size
            emit(first.chan, first.bytes, (remaining + 999) / 1000)
        }
    }
    @Synchronized fun diagnostics() = "captureToWireMs=${lag / 1000} receiverTrimMs=${trim / 1000} staleVideo=$expiredVideo stalePcm=$skippedAudio pendingPcmBytes=$audioBytes"
    companion object {
        private const val MAX_FRAME_AGE = 500_000L
        private const val AUDIO_SLACK = 150_000L
        private const val MAX_BYTES = 128 * 1024
        private const val MAX_PACKETS = 96
    }
}
