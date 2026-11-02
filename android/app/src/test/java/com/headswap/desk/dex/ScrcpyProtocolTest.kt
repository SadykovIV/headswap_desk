package com.headswap.desk.dex

import com.headswap.desk.aa.TouchPoint
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class ScrcpyProtocolTest {
    private fun stream(write: DataOutputStream.() -> Unit): ScrcpyProtocol.VideoReader {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return ScrcpyProtocol.VideoReader(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test fun readsSessionConfigAndKeyFrameWithoutMixingHeadersWithH264() {
        val reader = stream {
            writeInt(0x68323634)
            writeInt(0x80000000.toInt()); writeInt(1280); writeInt(720)
            writeLong(1L shl 62); writeInt(5); write(byteArrayOf(0, 0, 0, 1, 0x67))
            writeLong((1L shl 61) or 123456789L); writeInt(5); write(byteArrayOf(0, 0, 0, 1, 0x65))
        }
        reader.readCodec()
        assertEquals(ScrcpyProtocol.Packet.Session(1280, 720), reader.next())
        val config = reader.next() as ScrcpyProtocol.Packet.Video
        assertTrue(config.config)
        assertFalse(config.key)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67), config.bytes)
        val frame = reader.next() as ScrcpyProtocol.Packet.Video
        assertFalse(frame.config)
        assertTrue(frame.key)
        assertEquals(123456789L, frame.ptsUs)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), frame.bytes)
    }

    @Test(expected = IOException::class) fun rejectsOversizedFrameBeforeAllocation() {
        stream { writeLong(0); writeInt(Int.MAX_VALUE) }.next()
    }

    @Test(expected = IOException::class) fun rejectsNegativeLength() {
        stream { writeLong(0); writeInt(-1) }.next()
    }

    @Test(expected = EOFException::class) fun rejectsTruncatedPayload() {
        stream { writeLong(0); writeInt(5); writeByte(1) }.next()
    }

    @Test(expected = IOException::class) fun refusesNonH264Codec() {
        stream { writeInt(0x68323635) }.readCodec()
    }

    @Test fun touchPacketsUseBigEndianCoordinatesAndReleasePressure() {
        val packet = ScrcpyProtocol.touch(1, 42, 9999, -4, 1280, 720)
        assertEquals(32, packet.size)
        val input = DataInputStream(ByteArrayInputStream(packet))
        assertEquals(2, input.readUnsignedByte())
        assertEquals(1, input.readUnsignedByte())
        assertEquals(42L, input.readLong())
        assertEquals(1279, input.readInt())
        assertEquals(0, input.readInt())
        assertEquals(1280, input.readUnsignedShort())
        assertEquals(720, input.readUnsignedShort())
        assertEquals(0, input.readUnsignedShort())
        assertEquals(0, input.readInt())
        assertEquals(0, input.readInt())
    }

    @Test fun translatesPointerActionsAndReleasesAllFingersOnCancel() {
        val mapper = DexTouchMapper(1280, 720)
        val first = TouchPoint(7, 100, 200)
        val second = TouchPoint(8, 200, 300)
        assertEquals(0, mapper.encode(0, 0, listOf(first)).single()[1].toInt())
        val pointerDown = mapper.encode(5, 1, listOf(first, second)).single()
        assertEquals(0, pointerDown[1].toInt())
        assertEquals(8L, DataInputStream(ByteArrayInputStream(pointerDown.copyOfRange(2, 10))).readLong())
        assertEquals(2, mapper.encode(2, 0, listOf(first, second)).size)
        val releases = mapper.encode(3, 0, emptyList())
        assertEquals(3, releases.size)
        assertEquals(3, releases.first()[1].toInt())
        assertTrue(releases.drop(1).all { it[1] == 1.toByte() })
        assertTrue(mapper.encode(2, 0, listOf(first)).isEmpty())
        assertTrue(mapper.encode(3, 0, emptyList()).isEmpty())
    }

    @Test fun duplicatePointerIdsAndInvalidIndicesDoNotCreateTouches() {
        val mapper = DexTouchMapper(1280, 720)
        val p = TouchPoint(7, 100, 200)
        assertTrue(mapper.encode(0, 0, listOf(p, p)).isEmpty())
        assertTrue(mapper.encode(5, 8, listOf(p)).isEmpty())
    }
}
