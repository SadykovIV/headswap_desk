package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.headswap.desk.*
import com.headswap.desk.aa.*
import com.headswap.desk.link.*
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** A real EGL flash, AVC encoder/decoder and PCM pulse travel through the multiplexed CarLife
 * writer. Measure decoded luminance vs received PCM; this does not model a car's DAC or LCD. */
internal object AvPlayoutProbe {
    private fun us() = System.nanoTime() / 1000
    private data class Markers(val wire: List<Long>, val decoded: List<Long>)
    private fun median(v: List<Long>): Long = v.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
    @JvmStatic fun run(test: Instrumentation) {
        val report = Bundle()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish"))
            val before = measure(false)
            report.putString("unpacedLeadMs", before.decoded.joinToString(","))
            val after = measure(true)
            report.putString("pacedLeadMs", after.decoded.joinToString(","))
            report.putString("wireLeadMs", after.wire.joinToString(","))
            // Sender alignment has a one-frame resolution. The decoder itself may buffer
            // additional frames; measure that separately, then validate a new run using the
            // same receiver calibration that a user can enter. Never feed marker timestamps
            // back into the automatic sender.
            check(median(before.decoded) - median(after.decoded) > 40 && median(after.wire.map(::abs)) <= 45) {
                "No reliable sender improvement: before=$before after=$after"
            }
            val correction = median(after.decoded).toInt().coerceIn(0, 2000)
            val calibrated = measure(true, correction)
            report.putInt("receiverTrimMs", correction)
            report.putString("calibratedLeadMs", calibrated.decoded.joinToString(","))
            check(median(calibrated.decoded.map(::abs)) <= 45) { "Independent receiver calibration run: $calibrated" }
        } catch (e: Throwable) { report.putString("error", android.util.Log.getStackTraceString(e)) }
        test.finish(if (report.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, report)
    }

    private fun measure(paced: Boolean, trimMs: Int = 0): Markers {
        val input = PipedInputStream(); val keepOpen = PipedOutputStream(input)
        val frames = LinkedBlockingQueue<Pair<Long, ByteArray>>()
        val tones = mutableListOf<Long>(); val flashes = mutableListOf<Long>(); val arrivals = mutableListOf<Long>()
        val errors = AtomicReference<Throwable?>()
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                val raw = toByteArray(); reset()
                check(raw.size >= 20)
                val chan = Wire.u32(raw, 0); val inner = raw.copyOfRange(8, raw.size)
                val packet = checkNotNull(Wire.parseData(inner))
                if (chan == Ch.VIDEO) frames.offer(us() to packet.data)
                if (chan == Ch.MEDIA && packet.data[0].toInt() != 0) synchronized(tones) { tones.add(us()) }
            }
        }
        val wire = StreamTransport(input, output, TransportCaps.TCP_DEBUG, "av-fixture", { keepOpen.close() })
        val timeline = DesktopAvTimeline(::us, { trimMs }, wire::sendTimed)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val csd = AtomicReference<ByteArray>()
        // Inspect decoded luma in a CPU-readable image. Guest goldfish host-accelerated
        // decoders may accept YUV420Flexible yet only implement output to a Surface.
        val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
            !it.isEncoder && it.isSoftwareOnly && it.supportedTypes.any { t -> t.equals("video/avc", true) }
        }
        val receiver = MediaCodec.createByCodecName(software.name)
        val fmt = MediaFormat.createVideoFormat("video/avc", 640, 480).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        receiver.configure(fmt, null, null, 0); receiver.start()
        val codecRef = AtomicReference<AaTranscoder?>()
        var requestedAt = 0L
        val callback = object : TranscodeOutput {
            override fun onEncoderConfig(c: ByteArray) { csd.set(c) }
            override fun onEncoderFrame(data: ByteArray, key: Boolean) = Unit
            override fun onEncoderFrameAt(data: ByteArray, key: Boolean, presentationUs: Long) {
                val bytes = if (key && !AnnexB.startsWithSps(data)) AnnexB.glue(csd.get() ?: return, data) else data
                // Identical repeatable video-path delay in both arms; never set an audio delay in the test.
                if (scheduler.isShutdown) return
                scheduler.schedule({
                    val inner = Wire.dataInner(Msg.VIDEO_DATA, 0, bytes)
                    if (!paced) wire.send(Ch.VIDEO, inner) else
                        timeline.frame(presentationUs, key, 25).let { ticket ->
                            if (ticket != null) wire.sendVideoFragment(inner, ticket, true)
                            else if (us() - requestedAt > 250_000) { requestedAt = us(); codecRef.get()?.requestSyncFrame() }
                        }
                }, 85, TimeUnit.MILLISECONDS)
            }
            override fun onNeedAaKeyFrame() = Unit
            override fun onTranscodeError(msg: String) { errors.set(IllegalStateException(msg)) }
        }
        val encoder = AaTranscoder(640, 480, 25, 700_000, callback, directSurface = true)
        codecRef.set(encoder)
        val decoded = java.util.concurrent.atomic.AtomicInteger()
        val decoding = Thread {
            try {
                var white = false
                var pending: Pair<Long, ByteArray>? = null
                val info = MediaCodec.BufferInfo()
                while (!Thread.currentThread().isInterrupted) {
                    val packet = pending ?: frames.poll(3, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        val i = receiver.dequeueInputBuffer(1000)
                        if (i < 0) pending = packet else {
                        pending = null
                        receiver.getInputBuffer(i)!!.apply { clear(); put(packet.second) }
                        receiver.queueInputBuffer(i, 0, packet.second.size, packet.first, 0)
                        }
                    }
                    while (true) {
                        val o = receiver.dequeueOutputBuffer(info, 0)
                        if (o < 0) break
                        val at = us()
                        val y = receiver.getOutputImage(o)!!.use { it.planes[0].buffer.get(0).toInt() and 255 }
                        decoded.incrementAndGet()
                        if (y > 160 && !white) synchronized(flashes) { flashes.add(at); arrivals.add(info.presentationTimeUs) }
                        white = y > 160
                        receiver.releaseOutputBuffer(o, false)
                    }
                }
            } catch (_: InterruptedException) {} catch (e: Throwable) { errors.set(e) }
        }
        var egl: SurfaceTimingProbe.Producer? = null
        try {
            wire.start({ _, _ -> }, { _, _ -> }, { errors.set(IllegalStateException(it)) })
            timeline.reset(true)
            decoding.start(); encoder.start()
            val source = SurfaceTimingProbe.Producer(checkNotNull(encoder.surfaceInput).surface); egl = source
            // Start measurements only after reference pictures have reached the decoder.
            val warmUntil = us() + 6_000_000
            while (decoded.get() < 12 && us() < warmUntil) {
                errors.get()?.let { throw it }
                source.draw(us() * 1000, 0f, 0f, 0f)
                val packet = Wire.dataInner(CarlifeAudio.MEDIA_DATA, 0, ByteArray(3840))
                if (paced) timeline.pcm(Ch.MEDIA, packet, us()) else wire.sendTimed(Ch.MEDIA, packet, 150)
                SystemClock.sleep(20)
            }
            check(decoded.get() >= 12) { "No decodable warm-up: ${timeline.diagnostics()}" }
            var due = us()
            // 600ms warm-up, six 200ms-wide flashes spaced by 600ms. PCM capture block = 20ms.
            for (i in 0 until 220) {
                while (us() < due) SystemClock.sleep(1)
                val capture = us()
                due = capture + 20_000L // never replay missed display vsyncs in a burst after guest preemption
                val n = i - 30
                val bright = if (n in 0 until 180 && n % 30 < 10) 1f else 0f
                if (i % 2 == 0) source.draw(capture * 1000, bright, bright, bright)
                val pcm = ByteArray(3840)
                if (n in 0 until 180 && n % 30 == 0) pcm[0] = 127
                val data = Wire.dataInner(CarlifeAudio.MEDIA_DATA, 0, pcm)
                if (paced) timeline.pcm(Ch.MEDIA, data, capture) else wire.sendTimed(Ch.MEDIA, data, 150)
            }
            SystemClock.sleep(250)
            errors.get()?.let { throw it }
            synchronized(tones) { synchronized(flashes) {
                check(tones.size == 6 && flashes.size == 6) { "Markers audio=${tones.size}, decoded=${flashes.size}, ${timeline.diagnostics()}" }
                return Markers(arrivals.zip(tones) { v, a -> (v - a) / 1000 }, flashes.zip(tones) { v, a -> (v - a) / 1000 })
            } }
        } finally {
            egl?.close(); encoder.stop()
            scheduler.shutdownNow(); timeline.close(); wire.close()
            decoding.interrupt(); decoding.join(1500)
            receiver.stop(); receiver.release()
        }
    }
}
