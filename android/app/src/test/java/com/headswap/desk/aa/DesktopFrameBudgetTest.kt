package com.headswap.desk.aa

import com.headswap.desk.Msg
import com.headswap.desk.Wire
import org.junit.Assert.*
import org.junit.Test

class DesktopFrameBudgetTest {
    @Test fun oversizeIdrReducesRateBeforeTransportReceivesIt() {
        assertNull(DesktopFrameBudget.reducedBitrate(512 * 1024, 8_000_000))
        assertTrue(DesktopFrameBudget.reducedBitrate(800 * 1024, 8_000_000)!! < 6_000_000)
        assertEquals(BitratePolicy.FLOOR_BPS, DesktopFrameBudget.reducedBitrate(Int.MAX_VALUE, BitratePolicy.FLOOR_BPS))
    }
    @Test fun sourceDelayPreservesTheUpstreamUnitOfEachWireChannel() {
        val clock = DesktopMediaClock(1_700_000_000_000L, 1_000_000)
        // Same capture time is delayed 2 seconds before sending: do not stamp it as now.
        assertEquals(1_700_000_000, clock.wireVideoSeconds(1_040_000, 3_040_000))
        assertEquals((1_700_000_000_040L and 0xffffffffL).toInt(), clock.wireAudioMillis(1_040_000, 3_040_000))
        assertEquals(1_700_000_001, clock.wireVideoSeconds(2_080_000, 3_120_000))
        assertEquals((1_700_000_001_080L and 0xffffffffL).toInt(), clock.wireAudioMillis(2_080_000, 3_120_000))
    }
    @Test fun serializedDataHeaderKeepsVideoSecondsAndAudioMillis() {
        val clock = DesktopMediaClock(1_700_000_000_000L, 1_000_000)
        for (type in listOf(Msg.VIDEO_DATA, Msg.VIDEO_HEARTBEAT)) {
            val packet = Wire.dataInner(type, clock.wireVideoSeconds(1_040_000, 3_040_000), byteArrayOf(7))
            assertEquals(1_700_000_000, Wire.u32(packet, 4))
            assertEquals(type, Wire.u32(packet, 8))
        }
        for (type in listOf(CarlifeAudio.MEDIA_DATA, CarlifeAudio.TTS_DATA)) {
            val packet = Wire.dataInner(type, clock.wireAudioMillis(1_040_000, 3_040_000), byteArrayOf(7))
            assertEquals((1_700_000_000_040L and 0xffffffffL).toInt(), Wire.u32(packet, 4))
            assertEquals(type, Wire.u32(packet, 8))
        }
    }
    @Test fun eachChannelWrapsAfterConversionToItsOwnUnit() {
        val audio = DesktopMediaClock(0xfffffff0L, 1_000_000)
        assertEquals(0x10, audio.wireAudioMillis(1_032_000, 1_032_000))
        val video = DesktopMediaClock(0xffffffffL * 1000, 1_000_000)
        assertEquals(-1, video.wireVideoSeconds(1_999_000, 1_999_000))
        assertEquals(0, video.wireVideoSeconds(2_000_000, 2_000_000))
    }
    @Test fun unavailableCaptureTimeFallsBackWithoutChangingChannelUnits() {
        val clock = DesktopMediaClock(5000, 1_000_000)
        assertEquals(5120, clock.wireAudioMillis(0, 1_120_000))
        assertEquals(5, clock.wireVideoSeconds(0, 1_120_000))
        assertEquals(6200, clock.wireAudioMillis(null, 2_200_000))
        assertEquals(6, clock.wireVideoSeconds(null, 2_200_000))
    }
}
