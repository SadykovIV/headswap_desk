package com.headswap.desk

import com.headswap.desk.aa.AapMessages
import com.headswap.desk.link.BadInnerHeader
import com.headswap.desk.link.InnerCodec
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class ProtocolLogPolicyTest {
    private val marker = byteArrayOf(0x51, 0x41, 0x5a, 0x21, 0x7f, 0x11, 0x33, 0x55)
    private fun entry(channel: Int, type: Int, body: ByteArray): ProtocolLogPolicy.Entry {
        val inner = if (Ch.isCmdStyle(channel)) Wire.cmdInner(type, body) else Wire.dataInner(type, 0, body)
        return ProtocolLogPolicy.message("TX", channel, inner.size, type, body.size,
            "QAZ! 51 41 5a 21", inner, "QAZ! 51 41 5a 21")
    }
    private fun metadataOnly(result: ProtocolLogPolicy.Entry) {
        assertNull(result.detail)
        assertFalse(result.header, result.header.contains("QAZ!"))
        assertFalse(result.header, result.header.contains("51 41"))
        assertFalse(result.header, result.header.contains("\\x"))
    }

    @Test fun dashboardLogsOnlyNumbersAndNeverPrivateTrackOrRoadText() {
        val media = PbWriter().string(2, "QAZ! track").string(3, "QAZ! artist").int32(6, 181).toByteArray()
        val route = PbWriter().int32(1, 1).int32(2, 7).string(3, "QAZ! road").int32(5, 450).toByteArray()
        val song = entry(Ch.CMD, Msg.MEDIA_INFO, media)
        val turn = entry(Ch.CMD, Msg.NAVI_NEXT_TURN_INFO, route)
        metadataOnly(song)
        metadataOnly(turn)
        assertTrue(song.header.contains("duration=181"))
        assertTrue(turn.header.contains("remainDistance=450"))
        assertTrue(turn.header.contains("roadNameBytes=9"))
    }

    @Test fun roadLengthUsesUtf8WireBytesWithoutRevealingTextOrIconData() {
        val road = "Нахимовский"
        val route = PbWriter().int32(1, 2).int32(2, 21).string(3, road).int32(5, 3811)
            .bytes(6, marker).toByteArray()
        val turn = entry(Ch.CMD, Msg.NAVI_NEXT_TURN_INFO, route)
        metadataOnly(turn)
        assertFalse(turn.header, turn.header.contains("Нахимовский"))
        assertTrue(turn.header, turn.header.contains("roadNameBytes=22"))
        assertTrue(turn.header, turn.header.contains("roadNameSpaces=0"))
        assertTrue(turn.header, turn.header.contains("nextTurn=21"))
        val empty = entry(Ch.CMD, Msg.NAVI_NEXT_TURN_INFO, PbWriter().string(3, "").toByteArray())
        assertTrue(empty.header, empty.header.contains("roadNameBytes=0"))
        assertTrue(empty.header, empty.header.contains("roadNameSpaces=0"))
        val words = entry(Ch.CMD, Msg.NAVI_NEXT_TURN_INFO, PbWriter().string(3, "ул Мира").toByteArray())
        assertTrue(words.header, words.header.contains("roadNameSpaces=1"))
        assertFalse(words.header, words.header.contains("Мира"))
    }

    @Test fun everyShortAudioPayloadAndLongPcmAreMetadataOnly() {
        for ((channel, type) in listOf(Ch.MEDIA to 0x30006, Ch.TTS to 0x40003, Ch.VR to 0x50003, Ch.VR to Msg.VR_MIC_DATA)) {
            for (size in (0..16).toList() + listOf(3840, 10240)) {
                val body = ByteArray(size) { marker[it % marker.size] }
                val result = entry(channel, type, body)
                metadataOnly(result)
                assertEquals("TX ${Ch.name(channel)} ${Msg.hex(type)} ${Msg.name(type)} len=$size", result.header)
            }
        }
    }

    @Test fun audioControlPayloadAndMislabeledAudioCannotBypassRedaction() {
        for (channel in listOf(Ch.MEDIA, Ch.TTS, Ch.VR)) metadataOnly(entry(channel, Msg.HU_INFO, marker))
        for (type in listOf(Msg.MEDIA_INIT, Msg.TTS_INIT, Msg.VR_INIT, 0x30006, Msg.VR_MIC_DATA)) {
            metadataOnly(entry(Ch.CMD, type, marker))
        }
    }

    @Test fun unknownChannelAndUnknownTypeInsideKnownFamilyAreMetadataOnly() {
        for ((channel, type) in listOf(99 to 0x630001, Ch.CMD to 0x1ffff, Ch.TOUCH to 0x6ffff, Ch.VIDEO to 0x2ffff)) {
            metadataOnly(entry(channel, type, marker))
        }
    }

    @Test fun knownCommandWithInvalidProtobufNeverUsesHexFallback() {
        val result = entry(Ch.CMD, Msg.HU_INFO, marker)
        metadataOnly(result)
        assertEquals("TX CMD ${Msg.hex(Msg.HU_INFO)} ${Msg.name(Msg.HU_INFO)} len=8", result.header)
    }

    @Test fun unknownBinaryFieldsAndWrongWireTypesAreOmitted() {
        val body = PbWriter().int32(1, 1).bytes(99, marker).bytes(2, marker).toByteArray()
        val text = ProtocolLogPolicy.protobuf(body, Schemas.protocolVersion)!!
        assertEquals("{majorVersion=1 minorVersion=<omitted>}", text)
        assertFalse(text.contains("QAZ"))
    }

    @Test fun nestedUnknownAndInvalidBodiesNeverUseRawFallback() {
        val parent = Schema("parent", mapOf(1 to FieldDef("child", Kind.MSG, Schemas.protocolVersion)))
        assertEquals("{child=<omitted>}", ProtocolLogPolicy.protobuf(PbWriter().bytes(1, marker).toByteArray(), parent))
        assertEquals("{child={}}", ProtocolLogPolicy.protobuf(PbWriter().bytes(1, PbWriter().bytes(99, marker).toByteArray()).toByteArray(), parent))
    }

    @Test fun validControlMetadataIsPreservedWithoutRawPacketDump() {
        val result = entry(Ch.CMD, Msg.VIDEO_ENCODER_INIT, Msgs.videoEncoderInfo(1280, 720, 30))
        assertTrue(result.header, result.header.endsWith("{width=1280 height=720 frameRate=30}"))
        assertNull(result.detail)
        assertFalse(result.header.contains("QAZ!")) // Caller-supplied decoded/note values are ignored.
    }

    @Test fun binaryAndNonTextStringFieldsOnlyReportLength() {
        val schema = Schema("test", mapOf(1 to FieldDef("blob", Kind.BYTES), 2 to FieldDef("text", Kind.STR)))
        assertEquals("{blob=<8 bytes> text=<8 bytes>}",
            ProtocolLogPolicy.protobuf(PbWriter().bytes(1, marker).bytes(2, marker).toByteArray(), schema))
        assertEquals("{text=\"Hello\"}", ProtocolLogPolicy.protobuf(PbWriter().string(2, "Hello").toByteArray(), schema))
    }

    @Test fun aaAudioAndUnknownOrMalformedControlDescriptionsHaveNoPayload() {
        for (channel in AapMessages.AUDIO_CHANNELS.toList() + AapMessages.CH_MIC) {
            for (type in listOf(AapMessages.MEDIA_DATA, AapMessages.MEDIA_CODEC_CONFIG, AapMessages.MEDIA_START)) {
                assertEquals("", AapMessages.describe(channel, type, marker))
            }
        }
        assertEquals("", AapMessages.describe(AapMessages.CH_CONTROL, 0x7fff, marker))
        assertEquals("", AapMessages.describe(AapMessages.CH_CONTROL, AapMessages.PING_REQUEST, marker))
        assertEquals("", AapMessages.describe(99, AapMessages.PING_REQUEST, PbWriter().int32(1, 123).toByteArray()))
    }

    @Test fun malformedFramingErrorsDoNotReencodeUnknownSampleBytes() {
        fun outer(value: Byte) = assertThrows(ProtocolDesync::class.java) {
            FrameParser().feed(ByteArray(16) { value }, 0, 16) { _, _ -> fail("Invalid frame accepted") }
        }.message
        assertEquals(outer(0x51), outer(0x52))
        fun inner(value: Byte) = assertThrows(BadInnerHeader::class.java) {
            InnerCodec.read(Ch.MEDIA, DataInputStream(ByteArrayInputStream(ByteArray(12) { value })))
        }.message
        assertEquals(inner(0x51), inner(0x52))
    }
}
