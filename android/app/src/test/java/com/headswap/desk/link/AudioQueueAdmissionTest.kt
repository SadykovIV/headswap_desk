package com.headswap.desk.link

import com.headswap.desk.Ch
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

class AudioQueueAdmissionTest {
    @Test fun shortPacketsAreCountBoundedPerChannelWithoutRemovingControlOrVideo() {
        val queue = LinkedBlockingQueue<QueuedTransportPacket>()
        var overflow = 0L
        val init = QueuedTransportPacket(Ch.MEDIA, ByteArray(20), Long.MAX_VALUE)
        val video = QueuedTransportPacket(Ch.VIDEO, ByteArray(100), Long.MAX_VALUE)
        fun add(packet: QueuedTransportPacket) = AudioQueueAdmission.offer(queue, packet, 0, {}, { overflow += it })
        add(init); add(video)
        repeat(100) { add(QueuedTransportPacket(Ch.MEDIA, byteArrayOf(it.toByte()), 100)) }
        repeat(100) { add(QueuedTransportPacket(Ch.TTS, byteArrayOf(it.toByte()), 100)) }
        assertEquals(2 + 2 * AudioQueueAdmission.MAX_PENDING_PCM_PACKETS, queue.size)
        assertSame(init, queue.poll()); assertSame(video, queue.poll())
        assertEquals(136L, overflow)
        assertEquals(68, queue.peek().inner[0].toInt())
    }

    @Test fun oversizedPcmCannotEvictFreshAudioOrEnterQueue() {
        val queue = LinkedBlockingQueue<QueuedTransportPacket>()
        var overflow = 0L
        val fresh = QueuedTransportPacket(Ch.MEDIA, ByteArray(20), 100)
        AudioQueueAdmission.offer(queue, fresh, 0, {}, { overflow += it })
        AudioQueueAdmission.offer(queue, QueuedTransportPacket(Ch.MEDIA,
            ByteArray(AudioQueueAdmission.MAX_PENDING_PCM_BYTES + 1), 100), 0, {}, { overflow += it })
        assertEquals(1L, overflow)
        assertEquals(1, queue.size)
        assertSame(fresh, queue.peek())
    }
}
