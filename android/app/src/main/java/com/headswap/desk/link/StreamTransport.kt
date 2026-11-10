package com.headswap.desk.link

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.headswap.desk.AppLog
import com.headswap.desk.Ch
import com.headswap.desk.FrameParser
import com.headswap.desk.InnerAssembler
import com.headswap.desk.ProtocolDesync
import com.headswap.desk.Wire
import com.headswap.desk.aa.DrainMeter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Один байтовый поток с внешним заголовком [chan u32][len u32]: USB AOA и debug-TCP.
 * Код чтения и записи перенесён из CarLifeSession без изменений поведения: одна FIFO-очередь на все каналы,
 * единственный писатель, заголовок отдельным write и тело кусками <= 16384 (Wire.writeSequence), flush на сообщение.
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    override val caps: TransportCaps,
    override val label: String,
    /** Закрыть исходный дескриптор (pfd.close); зовётся первым в close(), как раньше в сессии. */
    private val closeFd: () -> Unit = {},
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : CarTransport {

    private val queue = LinkedBlockingQueue<QueuedTransportPacket>()
    private val videoBytes = AtomicLong()
    private val videoFrames = AtomicInteger()
    private val audioExpired = AtomicLong()
    private val audioOverflow = AtomicLong()
    override val audioOverflowPackets: Long get() = audioOverflow.get()
    override val audioExpiredPackets: Long get() = audioExpired.get()
    @Volatile private var videoWriteStartedMs = -1L
    private val drain = DrainMeter()
    private val parser = FrameParser()
    private val assembler = InnerAssembler()
    private val closed = AtomicBoolean(false)
    private var reader: Thread? = null
    private var writer: Thread? = null
    @Volatile private var desync = false
    @Volatile private var wholeWrites = false
    @Volatile override var usbEndpointLost = false
        private set

    override fun setWholeWrites(on: Boolean) {
        wholeWrites = on
    }

    @Volatile private var rx = 0L
    @Volatile var lastRxMs = 0L
        private set

    override val rxBytes: Long get() = rx
    override val queuedVideoBytes: Long get() = videoBytes.get()
    override val queuedVideoFrames: Int get() = videoFrames.get()
    override val videoWriteAgeMs: Long get() = videoWriteStartedMs.let { if (it < 0) 0L else (clock() - it).coerceAtLeast(0) }
    override val drainBytesPerSec: Double get() = drain.bytesPerSec
    override fun queueSize(chan: Int): Int = queue.size

    override fun dropQueued(chan: Int, pred: (ByteArray) -> Boolean): Int {
        var n = 0
        queue.removeIf { o ->
            val drop = o.chan == chan && o.chan != Ch.VIDEO && pred(o.inner)
            if (drop) n++
            drop
        }
        return n
    }

    override fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    ) {
        lastRxMs = clock()
        writer = Thread({ writeLoop(onWritten, onClosed) }, "cl-writer").also { it.start() }
        reader = Thread({ readLoop(sink, onClosed) }, "cl-reader").also { it.start() }
    }

    override fun send(chan: Int, inner: ByteArray) = enqueue(chan, inner, Long.MAX_VALUE)

    override fun sendTimed(chan: Int, inner: ByteArray, maxAgeMs: Long) =
        enqueue(chan, inner, AudioPacketDeadline.deadline(chan, inner, clock(), maxAgeMs))

    override fun sendVideoFragment(inner: ByteArray, frame: VideoWrite, last: Boolean) =
        enqueue(Ch.VIDEO, inner, Long.MAX_VALUE, frame, last)

    private fun enqueue(chan: Int, inner: ByteArray, deadlineMs: Long, frame: VideoWrite? = null, last: Boolean = true) {
        if (closed.get()) return
        if (chan == Ch.VIDEO) {
            videoBytes.addAndGet(inner.size.toLong())
            videoFrames.incrementAndGet()
        }
        AudioQueueAdmission.offer(queue, QueuedTransportPacket(chan, inner, deadlineMs, frame, last), clock(),
            { audioExpired.addAndGet(it) }, { audioOverflow.addAndGet(it) })
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            closeFd()
        } catch (_: Exception) {
        }
        writer?.interrupt()
        reader?.interrupt()
        try {
            input.close()
        } catch (_: Exception) {
        }
        try {
            output.close()
        } catch (_: Exception) {
        }
    }

    private fun readLoop(sink: (Int, ByteArray) -> Unit, onClosed: (String) -> Unit) {
        val buf = ByteArray(Wire.CHUNK)
        try {
            while (!closed.get()) {
                val n = endpointIo { input.read(buf) }
                if (n < 0) {
                    usbEndpointLost = caps.nativeDetach
                    if (!closed.get()) onClosed("EOF чтения (ГУ отключилось)")
                    return
                }
                lastRxMs = clock()
                rx += n
                // A raw read can start in the middle of PCM; never dump an unclassified buffer.
                AppLog.detail("RAW rx read=$n bytes")
                if (desync) continue
                try {
                    parser.feed(buf, 0, n) { chan, inner -> assembler.feed(chan, inner, sink) }
                } catch (e: ProtocolDesync) {
                    desync = true
                    AppLog.e("РАССИНХРОН потока: ${e.message}; дальше только счётчик прочитанных байт")
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                AppLog.e("ошибка чтения", e)
                onClosed("ошибка чтения: ${e.message}")
            }
        }
    }

    private fun writeLoop(onWritten: (Int, ByteArray) -> Unit, onClosed: (String) -> Unit) {
        try {
            while (!closed.get()) {
                val o = queue.take()
                if (o.deadlineMs != Long.MAX_VALUE && clock() >= o.deadlineMs) {
                    audioExpired.incrementAndGet()
                    continue
                }
                val video = o.chan == Ch.VIDEO
                if (o.frame?.begin() == false) {
                    videoBytes.addAndGet(-o.inner.size.toLong())
                    videoFrames.decrementAndGet()
                    continue
                }
                if (video) videoWriteStartedMs = clock()
                // заголовок отдельным write, тело следом кусками <= 16384 (спека §1.4); Mobis CCNC - тело одним write, как Baidu
                val t0 = System.nanoTime()
                val parts = if (wholeWrites) Wire.writeWhole(o.chan, o.inner) else Wire.writeSequence(o.chan, o.inner)
                try {
                    endpointIo {
                        for (part in parts) output.write(part)
                        output.flush()
                    }
                } finally {
                    if (video) {
                        videoWriteStartedMs = -1L
                        videoBytes.addAndGet(-o.inner.size.toLong())
                        videoFrames.decrementAndGet()
                    }
                }
                if (o.lastFragment) o.frame?.written()
                drain.onWrite(clock(), video, o.inner.size, System.nanoTime() - t0)
                onWritten(o.chan, o.inner)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) {
                AppLog.e("ошибка записи", e)
                onClosed("ошибка записи: ${e.message}")
            }
        }
    }

    // Parser, sink and logging exceptions can also end a session. Only fd I/O is
    // evidence for an early USB unplug; a cancelled/timed-out operation is ambiguous.
    private inline fun <T> endpointIo(action: () -> T): T = try {
        action()
    } catch (error: IOException) {
        if (caps.nativeDetach && error !is InterruptedIOException) usbEndpointLost = true
        throw error
    }

    companion object {
        /** USB-аксессуар (AOA) или debug-TCP, открытый как ParcelFileDescriptor. */
        fun fromPfd(pfd: ParcelFileDescriptor, caps: TransportCaps, label: String) = StreamTransport(
            FileInputStream(pfd.fileDescriptor), FileOutputStream(pfd.fileDescriptor), caps, label,
            closeFd = { pfd.close() },
        )
    }
}
