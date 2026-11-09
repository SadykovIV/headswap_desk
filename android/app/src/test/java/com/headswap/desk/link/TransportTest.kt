package com.headswap.desk.link

import com.headswap.desk.Ch
import com.headswap.desk.CarLifeSession
import com.headswap.desk.Msg
import com.headswap.desk.SessionConfig
import com.headswap.desk.Wire
import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.link.diag.WLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TransportTest {
    private val clock = { System.currentTimeMillis() }
    private val wl = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        WLog.clock = clock
        WLog.sink = { wl += it }
    }

    // ---------------- StreamTransport: то, что уходит в USB, не меняется ни на байт ----------------

    /** Пишет каждый write() отдельной записью, flush отмечает отдельно. */
    private class RecordingOut : OutputStream() {
        val calls = Collections.synchronizedList(ArrayList<ByteArray>())
        @Volatile var flushes = 0
        override fun write(b: Int) {
            calls += byteArrayOf(b.toByte())
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            calls += b.copyOfRange(off, off + len)
        }
        override fun flush() {
            flushes++
        }
    }

    private class BlockingIn : InputStream() {
        val latch = CountDownLatch(1)
        override fun read(): Int {
            latch.await()
            return -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            latch.await()
            return -1
        }
        override fun close() = latch.countDown()
    }

    @Test
    fun streamWritesExactlyWriteSequence() {
        val out = RecordingOut()
        val t = StreamTransport(BlockingIn(), out, TransportCaps.USB_AOA, "test", clock = clock)
        val written = CountDownLatch(3)
        t.start({ _, _ -> }, { _, _ -> written.countDown() }, { })
        val cmd = Wire.cmdInner(Msg.MD_INFO, ByteArray(300) { it.toByte() })
        val video = Wire.dataInner(Msg.VIDEO_DATA, 7, ByteArray(40_000) { (it * 31).toByte() })
        val media = Wire.dataInner(0x00030006, 1, ByteArray(4096))
        t.send(Ch.CMD, cmd)
        t.send(Ch.VIDEO, video)
        t.send(Ch.MEDIA, media)
        assertTrue(written.await(5, TimeUnit.SECONDS))
        val expected = Wire.writeSequence(Ch.CMD, cmd) + Wire.writeSequence(Ch.VIDEO, video) + Wire.writeSequence(Ch.MEDIA, media)
        assertEquals("число write()", expected.size, out.calls.size)
        for (i in expected.indices) assertArrayEquals("write #$i", expected[i], out.calls[i])
        assertEquals("flush на сообщение", 3, out.flushes)
        assertEquals(0L, t.queuedVideoBytes)
        assertEquals(0, t.queuedVideoFrames)
        t.close()
    }

    @Test
    fun blockedVideoWriteRemainsVisibleToBackpressureUntilFlush() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = CountDownLatch(2)
        val clockMs = java.util.concurrent.atomic.AtomicLong(1_000L)
        val output = object : OutputStream() {
            override fun write(value: Int) { entered.countDown(); release.await() }
            override fun write(data: ByteArray, offset: Int, length: Int) { entered.countDown(); release.await() }
        }
        val transport = StreamTransport(BlockingIn(), output, TransportCaps.USB_AOA, "blocked", clock = { clockMs.get() })
        val first = Wire.dataInner(Msg.VIDEO_DATA, 1, ByteArray(800_000))
        val second = Wire.dataInner(Msg.VIDEO_DATA, 2, ByteArray(1_000))
        try {
            transport.start({ _, _ -> }, { _, _ -> written.countDown() }, { })
            transport.send(Ch.VIDEO, first)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            clockMs.set(1_750L)
            transport.send(Ch.VIDEO, second)
            transport.send(Ch.MEDIA, Wire.dataInner(0x00030006, 1, ByteArray(100)))
            assertEquals(2, transport.queuedVideoFrames)
            assertEquals((first.size + second.size).toLong(), transport.queuedVideoBytes)
            assertEquals(750L, transport.videoWriteAgeMs)
            assertEquals(1, transport.dropQueued(Ch.MEDIA) { true })
            assertEquals((first.size + second.size).toLong(), transport.queuedVideoBytes)
            release.countDown()
            assertTrue(written.await(3, TimeUnit.SECONDS))
            assertEquals(0, transport.queuedVideoFrames)
            assertEquals(0L, transport.queuedVideoBytes)
            assertEquals(0L, transport.videoWriteAgeMs)
        } finally { release.countDown(); transport.close() }
    }

    @Test
    fun failedVideoWriteRetiresOnlyItsOwnBacklog() {
        val failed = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int): Unit = throw java.io.IOException("device disconnected")
        }
        val transport = StreamTransport(BlockingIn(), output, TransportCaps.USB_AOA, "failed", clock = clock)
        try {
            transport.start({ _, _ -> }, { _, _ -> fail("failed write cannot complete") }, { failed.countDown() })
            transport.send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, 1, ByteArray(800_000)))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(0L, transport.queuedVideoBytes)
            assertEquals(0, transport.queuedVideoFrames)
            assertEquals(0L, transport.videoWriteAgeMs)
        } finally { transport.close() }
    }

    @Test
    fun stalePcmBehindBlockedWriteExpiresButAudioControlAndFreshPcmSurvive() {
        for (wireless in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(3)
            val now = java.util.concurrent.atomic.AtomicLong(1_000L)
            val sent = Collections.synchronizedList(mutableListOf<ByteArray>())
            val output = object : OutputStream() {
                override fun write(value: Int) { entered.countDown(); release.await() }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { entered.countDown(); release.await() }
            }
            val input = BlockingIn()
            val transport: CarTransport = if (!wireless) {
                StreamTransport(input, output, TransportCaps.USB_AOA, "timed", clock = { now.get() })
            } else {
                val socket = object : Socket() {
                    override fun getInputStream(): InputStream = input
                    override fun getOutputStream(): OutputStream = output
                    override fun close() { input.close(); release.countDown() }
                }
                MultiSocketTransport(mapOf(Ch.MEDIA to socket), mapOf(Ch.MEDIA to 9240), "timed", {}, { now.get() })
            }
            val init = Wire.dataInner(0x00030001, 0, ByteArray(8))
            val stale = Wire.dataInner(0x00030006, 1, byteArrayOf(1, 2))
            val pause = Wire.dataInner(0x00030003, 0, ByteArray(0))
            val fresh = Wire.dataInner(0x00030006, 2, byteArrayOf(3, 4))
            try {
                transport.start({ _, _ -> }, { _, packet -> sent += packet; completed.countDown() }, { })
                transport.send(Ch.MEDIA, init)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                transport.sendTimed(Ch.MEDIA, stale, 150)
                transport.sendTimed(Ch.MEDIA, pause, 150)
                now.set(2_000L)
                transport.sendTimed(Ch.MEDIA, fresh, 150)
                release.countDown()
                assertTrue(completed.await(3, TimeUnit.SECONDS))
                assertEquals(3, sent.size)
                assertArrayEquals(init, sent[0])
                assertArrayEquals(pause, sent[1])
                assertArrayEquals(fresh, sent[2])
                assertEquals(1L, transport.audioExpiredPackets)
            } finally { release.countDown(); transport.close() }
        }
    }

    @Test
    fun blockedWirelessMediaStaysBoundedWhileVideoDrainsAndAudioControlsSurvive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val mediaSent = Collections.synchronizedList(mutableListOf<ByteArray>())
        val videoDrained = CountDownLatch(500)
        val mediaInput = BlockingIn()
        val videoInput = BlockingIn()
        val blocked = object : OutputStream() {
            override fun write(value: Int) { entered.countDown(); release.await() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { entered.countDown(); release.await() }
        }
        fun socket(input: InputStream, output: OutputStream) = object : Socket() {
            override fun getInputStream() = input
            override fun getOutputStream() = output
            override fun close() { input.close(); release.countDown() }
        }
        val transport = MultiSocketTransport(mapOf(Ch.MEDIA to socket(mediaInput, blocked),
            Ch.VIDEO to socket(videoInput, ByteArrayOutputStream())), mapOf(Ch.MEDIA to 9240, Ch.VIDEO to 8240),
            "audio stalled", {}, { now.get() })
        val init = Wire.dataInner(0x00030001, 0, ByteArray(8))
        val pause = Wire.dataInner(0x00030003, 0, ByteArray(0))
        val pcm = Wire.dataInner(0x00030006, 0, ByteArray(3840))
        try {
            transport.start({ _, _ -> }, { channel, packet ->
                if (channel == Ch.VIDEO) videoDrained.countDown() else mediaSent += packet
            }, {})
            transport.send(Ch.MEDIA, init)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            transport.sendTimed(Ch.MEDIA, pause, 150)
            repeat(500) {
                now.addAndGet(20)
                transport.sendTimed(Ch.MEDIA, pcm, 150)
                transport.send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, it, byteArrayOf(1)))
                assertTrue("PCM must expire on admission, not wait for blocked writer", transport.queueSize(Ch.MEDIA) <= 9)
            }
            assertTrue(videoDrained.await(3, TimeUnit.SECONDS))
            assertTrue(transport.audioExpiredPackets >= 490)
            // Even a burst with a non-advancing clock is bounded by bytes as well as age.
            repeat(200) { transport.sendTimed(Ch.MEDIA, pcm, 150) }
            assertTrue(transport.queueSize(Ch.MEDIA) <= 1 + AudioQueueAdmission.MAX_PENDING_PCM_BYTES / pcm.size)
            assertTrue(transport.audioOverflowPackets > 100)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (mediaSent.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertArrayEquals(init, mediaSent[0])
            assertArrayEquals(pause, mediaSent[1])
        } finally { release.countDown(); transport.close() }
    }

    @Test
    fun nativeSessionSerializesOriginalVideoAndAudioTimestampScales() {
        val packets = ArrayList<Pair<Int, ByteArray>>()
        val transport = object : CarTransport {
            override val caps = TransportCaps.TCP6
            override val label = "wire timestamp fixture"
            override val rxBytes = 0L
            override val queuedVideoBytes = 0L
            override val queuedVideoFrames = 0
            override val drainBytesPerSec = 0.0
            override fun queueSize(chan: Int) = 0
            override fun close() = Unit
            override fun start(sink: (Int, ByteArray) -> Unit, onWritten: (Int, ByteArray) -> Unit,
                               onClosed: (String) -> Unit) = Unit
            override fun send(chan: Int, inner: ByteArray) { packets += chan to inner }
        }
        val cfg = SessionConfig(desktopVideo = com.headswap.desk.aa.DesktopVideoSelection(
            com.headswap.desk.aa.DesktopResolution.R1280_720, 128))
        val session = CarLifeSession(transport, cfg, AaBridgeCore(log = { _, _ -> }), onClosed = {})
        try {
            val beforeMs = System.currentTimeMillis()
            val capturedUs = System.nanoTime() / 1000
            assertTrue(session.sendVideoAt(byteArrayOf(0, 0, 0, 1, 0x65, 1), capturedUs))
            assertTrue(session.sendAudioAt(Ch.MEDIA, 0x00030006, byteArrayOf(1, 2), capturedUs))
            val afterMs = System.currentTimeMillis()
            val video = packets.single { it.first == Ch.VIDEO }.second
            val audio = packets.single { it.first == Ch.MEDIA }.second
            assertTrue(Wire.u32(video, 4).toLong() in (beforeMs / 1000 - 1)..(afterMs / 1000 + 1))
            val audioEpoch = Wire.u32(audio, 4).toLong() and 0xffffffffL
            val expected = beforeMs and 0xffffffffL
            assertTrue("audio must keep millisecond units", kotlin.math.abs(audioEpoch - expected) < 1000)
            assertEquals(Msg.VIDEO_DATA, Wire.u32(video, 8))
            assertEquals(0x00030006, Wire.u32(audio, 8))
        } finally { session.close("wire timestamp fixture") }
    }

    @Test
    fun streamDropQueuedRemovesOnlyMatchingMediaData() {
        // писатель не запущен: всё лежит в очереди. h425: из неё уходит только MEDIA_DATA, остальное на месте
        val out = RecordingOut()
        val t = StreamTransport(BlockingIn(), out, TransportCaps.USB_AOA, "test", clock = clock)
        val data = Wire.dataInner(0x00030006, 1, ByteArray(4096))
        val pause = Wire.dataInner(0x00030003, 0, ByteArray(0))
        val tts = Wire.dataInner(0x00040006, 1, ByteArray(512))
        val video = Wire.dataInner(Msg.VIDEO_DATA, 7, ByteArray(1000))
        t.send(Ch.MEDIA, data)
        t.send(Ch.VIDEO, video)
        t.send(Ch.MEDIA, data)
        t.send(4, tts)
        t.send(Ch.MEDIA, pause)
        val dropped = t.dropQueued(Ch.MEDIA) { it.size >= 12 && Wire.u32(it, 8) == 0x00030006 }
        assertEquals(2, dropped)
        assertEquals(3, t.queueSize(Ch.MEDIA))
        assertEquals(1, t.queuedVideoFrames)
        t.close()
    }

    @Test
    fun usbReaderEofSignalsPlayerBeforeTeardownButManualAndTcpCloseDoNot() {
        for ((caps, peerDisconnect) in listOf(TransportCaps.USB_AOA to true, TransportCaps.USB_AOA to false,
                TransportCaps.TCP_DEBUG to true)) {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val closed = CountDownLatch(1)
            val input = PipedInputStream()
            val peer = PipedOutputStream(input)
            val transport = StreamTransport(input, ByteArrayOutputStream(), caps, "test", closeFd = { events += "fd closed" }, clock = clock)
            val session = CarLifeSession(transport, SessionConfig(), AaBridgeCore(log = { _, _ -> }),
                onDesktopOutputLost = { _, hadPicture -> events += "lost (picture=$hadPicture)" },
                onClosed = { closed.countDown() })
            try {
                session.start()
                if (peerDisconnect) peer.close() else session.close("Stop")
                assertTrue(closed.await(3, TimeUnit.SECONDS))
                val expected = if (peerDisconnect && caps == TransportCaps.USB_AOA) listOf("lost (picture=false)", "fd closed")
                    else listOf("fd closed")
                assertEquals(expected, events)
            } finally {
                peer.close()
                session.close("test finished")
            }
        }
    }

    @Test
    fun onlyEndpointIoCanSignalEarlyUsbLoss() {
        val frame = Wire.cmdInner(Msg.MD_INFO, byteArrayOf())
        val protocolInput = ByteArrayInputStream(Wire.outerHeader(Ch.CMD, frame.size) + frame)
        for ((input, expected) in listOf(
            object : InputStream() { override fun read(): Int = throw java.io.IOException("ENODEV") } to true,
            object : InputStream() { override fun read(): Int = throw java.net.SocketTimeoutException("timeout") } to false,
            protocolInput to false,
        )) {
            val transport = StreamTransport(input, ByteArrayOutputStream(), TransportCaps.USB_AOA, "test", clock = clock)
            val ended = CountDownLatch(1)
            try {
                transport.start({ _, _ -> throw java.io.IOException("frame consumer failed") }, { _, _ -> }, { ended.countDown() })
                assertTrue(ended.await(3, TimeUnit.SECONDS))
                assertEquals(expected, transport.usbEndpointLost)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun streamReadsFramesAndReportsEof() {
        val pin = PipedInputStream(1 shl 16)
        val pout = PipedOutputStream(pin)
        var fdClosed = false
        val t = StreamTransport(pin, ByteArrayOutputStream(), TransportCaps.USB_AOA, "test", closeFd = { fdClosed = true }, clock = clock)
        val got = Collections.synchronizedList(ArrayList<Pair<Int, ByteArray>>())
        val reason = AtomicReference<String>()
        val closedLatch = CountDownLatch(1)
        t.start({ c, i -> got += c to i }, { _, _ -> }, { reason.set(it); closedLatch.countDown() })
        val ver = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, byteArrayOf(8, 2, 16, 0))
        val hb = Wire.dataInner(Msg.VIDEO_HEARTBEAT, 0, null)
        val wire = Wire.outerHeader(Ch.CMD, ver.size) + ver + Wire.outerHeader(Ch.VIDEO, hb.size) + hb
        for (b in wire) pout.write(b.toInt())   // по байту: границы чтения не важны
        pout.close()
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS))
        assertEquals("EOF чтения (ГУ отключилось)", reason.get())
        assertEquals(2, got.size)
        assertEquals(Ch.CMD, got[0].first)
        assertArrayEquals(ver, got[0].second)
        assertEquals(Ch.VIDEO, got[1].first)
        assertEquals(wire.size.toLong(), t.rxBytes)
        t.close()
        assertTrue("close() закрывает дескриптор", fdClosed)
    }

    @Test
    fun streamCloseDoesNotCallOnClosed() {
        val inp = BlockingIn()
        val t = StreamTransport(inp, ByteArrayOutputStream(), TransportCaps.USB_AOA, "test", clock = clock)
        var calls = 0
        t.start({ _, _ -> }, { _, _ -> }, { calls++ })
        t.close()
        t.close()
        Thread.sleep(100)
        assertEquals(0, calls)
    }

    // ---------------- InnerCodec: inner без внешнего заголовка ----------------

    /** Отдаёт по одному байту за read: проверка сборки заголовка и тела из кусков. */
    private class Trickle(private val b: ByteArray) : InputStream() {
        private var p = 0
        override fun read(): Int = if (p < b.size) b[p++].toInt() and 0xFF else -1
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (p >= b.size) return -1
            buf[off] = b[p++]
            return 1
        }
    }

    @Test
    fun codecReadsCmdStream() {
        val a = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, byteArrayOf(8, 2, 16, 0))
        val b = Wire.cmdInner(Msg.HU_INFO, ByteArray(700) { 1 })
        val c = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        val got = readInners(Ch.CMD, Trickle(a + b + c))
        assertEquals(3, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
        assertArrayEquals(c, got[2])
    }

    @Test
    fun codecReadsDataStream() {
        val hb = Wire.dataInner(Msg.VIDEO_HEARTBEAT, 0, null)
        val frame = Wire.dataInner(Msg.VIDEO_DATA, 5, ByteArray(100_000) { it.toByte() })
        val got = readInners(Ch.VIDEO, ByteArrayInputStream(hb + frame + hb))
        assertEquals(3, got.size)
        assertArrayEquals(frame, got[1])
        val mic = Wire.dataInner(0x00058001, 0, ByteArray(640))
        assertArrayEquals(mic, readInners(Ch.VR, Trickle(mic)).single())
    }

    @Test
    fun codecRejectsForeignType() {
        // CMD-тип на TOUCH-сокете: заголовок чужого канала = разрыв, а не резинк
        val wrong = Wire.cmdInner(Msg.HU_INFO, byteArrayOf(1, 2))
        try {
            readInners(Ch.TOUCH, ByteArrayInputStream(wrong))
            fail()
        } catch (e: BadInnerHeader) {
            assertTrue(e.message!!.contains("TOUCH"))
        }
    }

    @Test
    fun codecSkipsHeartbeatOnCmd() {
        // h129, VW CNS3 Tiguan L Pro: 0x20002 с длиной 0 в CMD-сокете, CMD-кадр 8 байт
        val hb = Wire.cmdInner(Msg.VIDEO_HEARTBEAT, null)
        val a = Wire.cmdInner(0x0001800F, ByteArray(12) { 3 })
        val b = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        val want = listOf(hb, a, hb, b, hb).map { it.toList() }
        val wire = hb + a + hb + b + hb
        for (s in listOf<InputStream>(ByteArrayInputStream(wire), Trickle(wire))) {
            assertEquals(want, readInners(Ch.CMD, BufferedInputStream(s)).map { it.toList() })
        }
        // 12-байтный видео-heartbeat [0][тип][метка] в CMD-сокете: хвост метки пропущен, поток не сбит
        val hb12 = ByteArray(12)
        Wire.putU32(hb12, 4, Msg.VIDEO_HEARTBEAT)
        Wire.putU32(hb12, 8, 0x12345678)
        val got = readInners(Ch.CMD, BufferedInputStream(ByteArrayInputStream(hb12 + a + b)))
        assertEquals(3, got.size)
        assertArrayEquals(a, got[1])
        assertArrayEquals(b, got[2])
    }

    @Test
    fun codecRejectsHeartbeatWithBodyOnCmd() {
        val bad = Wire.cmdInner(Msg.VIDEO_HEARTBEAT, ByteArray(4))
        try {
            readInners(Ch.CMD, ByteArrayInputStream(bad))
            fail()
        } catch (e: BadInnerHeader) {
            assertTrue(e.message!!.contains("каналу CMD"))
            assertFalse(e.message!!.contains("00 04")) // A malformed buffer can be raw audio.
        }
    }

    @Test
    fun codecRejectsHugeLength() {
        val h = ByteArray(12)
        Wire.putU32(h, 0, Wire.MAX_OUTER_LEN + 1)
        Wire.putU32(h, 8, Msg.VIDEO_DATA)
        try {
            readInners(Ch.VIDEO, ByteArrayInputStream(h))
            fail()
        } catch (e: BadInnerHeader) {
            assertTrue(e.message!!.contains("длина"))
        }
        Wire.putU32(h, 0, -5)
        try {
            readInners(Ch.VIDEO, ByteArrayInputStream(h))
            fail()
        } catch (_: BadInnerHeader) {
        }
    }

    @Test
    fun codecEofSemantics() {
        assertNull(InnerCodec.read(Ch.CMD, DataInputStream(ByteArrayInputStream(ByteArray(0)))))
        val m = Wire.cmdInner(Msg.HU_INFO, ByteArray(50))
        for (cut in listOf(3, 8, 20)) {
            try {
                InnerCodec.read(Ch.CMD, DataInputStream(ByteArrayInputStream(m.copyOf(cut))))
                fail("cut=$cut")
            } catch (_: EOFException) {
            }
        }
    }

    // ---------------- Tcp6Listener + MultiSocketTransport на loopback ----------------

    private val zeroPorts = Tcp6Ports.DEFAULT.mapValues { 0 }

    private class Hu(val socks: Map<Int, Socket>) {
        fun send(chan: Int, inner: ByteArray) {
            socks.getValue(chan).getOutputStream().apply { write(inner); flush() }
        }
        fun readExactly(chan: Int, n: Int): ByteArray {
            val b = ByteArray(n)
            DataInputStream(socks.getValue(chan).getInputStream()).readFully(b)
            return b
        }
        fun close() = socks.values.forEach { try { it.close() } catch (_: Exception) {} }
    }

    private fun connect(l: Tcp6Listener, chans: Collection<Int>): Map<Int, Socket> =
        chans.associateWith { c -> Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), l.boundPort(c)), 2000) } }

    private fun up(): Triple<Tcp6Listener, MultiSocketTransport, Hu> {
        val l = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = 0, clock = clock).open()
        val hu = Hu(connect(l, Tcp6Ports.DEFAULT.keys))
        val t = l.await(5000, "test")
        return Triple(l, t, hu)
    }

    @Test
    fun tcp6RoundTripWithoutOuterHeader() {
        val (_, t, hu) = up()
        val got = Collections.synchronizedList(ArrayList<Pair<Int, ByteArray>>())
        val two = CountDownLatch(2)
        t.start({ c, i -> got += c to i; two.countDown() }, { _, _ -> }, { })
        try {
            val ver = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, byteArrayOf(8, 2, 16, 0))
            val touch = Wire.cmdInner(Msg.TOUCH_ACTION, byteArrayOf(8, 0, 16, 10, 24, 20))
            hu.send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_HEARTBEAT, 0, null))   // вычитывается и не доходит до протокола
            hu.send(Ch.CMD, ver)
            hu.send(Ch.TOUCH, touch)
            assertTrue(two.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(2, got.size)
            assertEquals(1L, t.heartbeats)
            val byChan = got.associate { it.first to it.second }
            assertArrayEquals(ver, byChan[Ch.CMD])
            assertArrayEquals(touch, byChan[Ch.TOUCH])

            val match = Wire.cmdInner(Msg.PROTOCOL_VERSION_MATCH_STATUS, byteArrayOf(8, 1))
            val frame = Wire.dataInner(Msg.VIDEO_DATA, 3, ByteArray(70_000) { it.toByte() })
            t.send(Ch.CMD, match)
            t.send(Ch.VIDEO, frame)
            assertArrayEquals("CMD-сокет: голый inner", match, hu.readExactly(Ch.CMD, match.size))
            assertArrayEquals("VIDEO-сокет: голый inner", frame, hu.readExactly(Ch.VIDEO, frame.size))
        } finally {
            t.close()
            hu.close()
        }
    }

    @Test
    fun tcp6SecondConnectionOnSamePortIsRejected() {
        val (l, t, hu) = up()
        t.start({ _, _ -> }, { _, _ -> }, { })
        try {
            val dup = Socket()
            dup.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), l.boundPort(Ch.CMD)), 2000)
            dup.soTimeout = 3000
            assertEquals("второй CMD закрыт сразу", -1, try { dup.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            dup.close()
            // первый CMD жив
            t.send(Ch.CMD, Wire.cmdInner(Msg.FOREGROUND, null))
            assertEquals(8, hu.readExactly(Ch.CMD, 8).size)
            assertTrue(wl.any { it.startsWith("WL tcp reject") && "why=dup" in it })
        } finally {
            t.close()
            hu.close()
        }
    }

    @Test
    fun tcp6OneSocketClosedClosesSessionWithReason() {
        val (_, t, hu) = up()
        val reason = AtomicReference<String>()
        val done = CountDownLatch(1)
        var calls = 0
        t.start({ _, _ -> }, { _, _ -> }, { calls++; reason.set(it); done.countDown() })
        try {
            hu.socks.getValue(Ch.TOUCH).close()
            assertTrue("сессия не зависла", done.await(5, TimeUnit.SECONDS))
            assertTrue(reason.get(), reason.get().startsWith("tcp6 TOUCH("))
            assertTrue("wireless output loss is visible before teardown", t.outputEndpointLost)
            // остальные сокеты транспорт закрыл сам
            val cmd = hu.socks.getValue(Ch.CMD)
            cmd.soTimeout = 3000
            assertEquals(-1, try { cmd.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            t.close()
            Thread.sleep(100)
            assertEquals("onClosed ровно один раз", 1, calls)
            assertTrue(wl.any { it.startsWith("WL tcp close by=link chan=TOUCH") })
        } finally {
            hu.close()
        }
    }

    @Test
    fun tcp6OutputLossNotifiesPlayerButExplicitStopDoesNot() {
        for (disconnect in listOf(true, false)) {
            val (_, transport, hu) = up()
            val ended = CountDownLatch(1)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val session = CarLifeSession(transport, SessionConfig(), AaBridgeCore(log = { _, _ -> }),
                onDesktopOutputLost = { _, picture -> events += "lost:$picture" },
                onClosed = { events += "closed"; ended.countDown() })
            try {
                session.start()
                if (disconnect) hu.socks.getValue(Ch.VIDEO).close() else session.close("Stop")
                assertTrue(ended.await(3, TimeUnit.SECONDS))
                assertEquals(if (disconnect) listOf("lost:false", "closed") else listOf("closed"), events)
                assertEquals(disconnect, transport.outputEndpointLost)
            } finally {
                session.close("test finished")
                hu.close()
            }
        }
    }

    @Test
    fun cancellingIncompleteWirelessConnectionClosesAcceptedChannels() {
        val listener = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = null, clock = clock).open()
        val hu = Hu(connect(listener, listOf(Ch.CMD, Ch.VIDEO)))
        try {
            listener.abort()
            try { listener.await(500, "cancelled"); fail("an aborted attempt must never connect") }
            catch (_: LinkSetupException) { }
            for (socket in hu.socks.values) {
                socket.soTimeout = 1000
                assertEquals(-1, try { socket.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            }
        } finally { listener.close(); hu.close() }
    }

    @Test
    fun partialAssemblyExpiresAllChannelsThenAcceptsNewEpochInSameListener() {
        var filterCalls = 0
        val listener = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = 0,
            clock = clock, peerOk = { filterCalls++; true }, assemblyTimeoutMs = 350).open()
        val partial = Hu(connect(listener, listOf(Ch.CMD, Ch.VIDEO, 0)))
        val ready = AtomicReference<MultiSocketTransport?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val waiter = Thread {
            try { ready.set(listener.await(5000, "retry")) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }.apply { start() }
        var replacement: Hu? = null
        try {
            // Read actual socket EOF/reset: the original channels and optional DATA all close.
            for (socket in partial.socks.values) assertPeerClosed(socket)
            assertNull(listener.peerAddress)
            assertEquals("first-peer waiting continues after a failed assembly", 1L, done.count)
            val fresh = Hu(connect(listener, Tcp6Ports.DEFAULT.keys)).also { replacement = it }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals("new epoch must pass peer filtering again", 2, filterCalls)
            val transport = checkNotNull(ready.get())
            transport.start({ _, _ -> }, { _, _ -> }, { })
            val command = Wire.cmdInner(Msg.FOREGROUND, null)
            transport.send(Ch.CMD, command)
            assertArrayEquals(command, fresh.readExactly(Ch.CMD, command.size))
            assertTrue(wl.any { it.startsWith("WL tcp assembly-timeout") && "data=true" in it })
        } finally {
            ready.get()?.close(); listener.abort(); partial.close(); replacement?.close(); waiter.join(1000)
        }
    }

    @Test
    fun dataOnlyCannotPinPeerForEntireDiscoveryWindowAndCancellationClosesNextEpoch() {
        val listener = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = 0,
            clock = clock, assemblyTimeoutMs = 200).open()
        val data = Hu(connect(listener, listOf(0)))
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val waiter = Thread {
            try { listener.await(5000, "data-only"); failure.set(AssertionError("Unexpected ready transport")) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }.apply { start() }
        var next: Hu? = null
        try {
            assertPeerClosed(data.socks.getValue(0))
            assertNull(listener.peerAddress)
            val sockets = Hu(connect(listener, listOf(0, Ch.CMD))).also { next = it }
            listener.abort()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get() is LinkSetupException)
            sockets.socks.values.forEach(::assertPeerClosed)
        } finally { listener.abort(); data.close(); next?.close(); waiter.join(1000) }
    }

    @Test
    fun completedTransportSurvivesAssemblyDeadlineAndRetainsOriginalPeerSockets() {
        val listener = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = null,
            clock = clock, assemblyTimeoutMs = 350).open()
        val hu = Hu(connect(listener, Tcp6Ports.DEFAULT.keys))
        val transport = listener.await(3000, "complete")
        try {
            transport.start({ _, _ -> }, { _, _ -> }, { })
            Thread.sleep(400)
            // onAccept invokes expiry checking; a handed-off transport must remain untouched.
            val duplicate = Hu(connect(listener, listOf(Ch.CMD)))
            try { assertPeerClosed(duplicate.socks.getValue(Ch.CMD)) } finally { duplicate.close() }
            val command = Wire.cmdInner(Msg.FOREGROUND, null)
            transport.send(Ch.CMD, command)
            assertArrayEquals(command, hu.readExactly(Ch.CMD, command.size))
            assertFalse(wl.any { it.startsWith("WL tcp assembly-timeout") })
        } finally { transport.close(); listener.abort(); hu.close() }
    }

    private fun assertPeerClosed(socket: Socket) {
        socket.soTimeout = 2000
        assertEquals(-1, try { socket.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
    }

    @Test
    fun tcp6BadHeaderClosesWithReason() {
        val (_, t, hu) = up()
        val reason = AtomicReference<String>()
        val done = CountDownLatch(1)
        t.start({ _, _ -> }, { _, _ -> }, { reason.set(it); done.countDown() })
        try {
            hu.send(Ch.MEDIA, Wire.dataInner(Msg.VIDEO_DATA, 0, ByteArray(4)))   // тип видео на MEDIA-сокете
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(reason.get(), reason.get().startsWith("tcp6 MEDIA(") && "плохой заголовок" in reason.get())
        } finally {
            t.close()
            hu.close()
        }
    }

    @Test
    fun tcp6TimeoutNamesMissingSockets() {
        val l = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = null, clock = clock).open()
        val hu = Hu(connect(l, listOf(Ch.CMD, Ch.VIDEO, Ch.MEDIA)))
        try {
            l.await(700, "test")
            fail()
        } catch (e: LinkSetupException) {
            assertTrue(e.message, "3/6" in e.message!!)
            assertTrue(e.message, "TTS(0)" in e.message!! && "VR(0)" in e.message!! && "TOUCH(0)" in e.message!!)
            assertTrue(wl.any { it.startsWith("WL tcp timeout ready=3/6") })
        } finally {
            hu.close()
        }
    }

    @Test
    fun tcp6PeerFilterRejectsForeignSubnet() {
        // ветка B: пускаем только адреса из подсетей маяка; loopback сюда не входит
        val l = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = null, clock = clock, peerOk = { false }).open()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), l.boundPort(Ch.CMD)), 2000)
            s.soTimeout = 3000
            assertEquals(-1, try { s.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            assertTrue(wl.any { it.startsWith("WL tcp reject chan=CMD") && "why=subnet" in it })
        } finally {
            s.close()
            l.abort()
        }
    }

    // ---------------- WLog ----------------

    @Test
    fun wlogFormat() {
        assertEquals(
            "WL tcp accept chan=CMD port=7240 peer=192.168.43.2 ready=1/6 t=15",
            WLog.format("tcp", "accept", 15, listOf("chan" to "CMD", "port" to 7240, "peer" to "192.168.43.2", "ready" to "1/6")),
        )
        assertEquals("WL link end why=\"EOF чтения (ГУ отключилось)\" x=- t=0",
            WLog.format("link", "end", 0, listOf("why" to "EOF чтения (ГУ отключилось)", "x" to null)))
        assertEquals("AA:BB:CC:xx:xx:xx", WLog.maskMac("AA:BB:CC:DD:EE:FF"))
        assertEquals("?", WLog.maskMac("garbage"))
    }

    @Test
    fun wlogHasNoHexDumps() {
        // транспортные WL-строки не содержат байтов рукопожатия: ни одной последовательности из 4+ hex-пар
        tcp6RoundTripWithoutOuterHeader()
        val hex = Regex("""(\b[0-9a-fA-F]{2}\s){4,}""")
        for (line in wl) assertTrue(line, !hex.containsMatchIn(line))
    }
}
