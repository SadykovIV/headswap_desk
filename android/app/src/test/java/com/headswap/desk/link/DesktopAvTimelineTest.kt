package com.headswap.desk.link

import org.junit.Assert.*
import org.junit.Test

class DesktopAvTimelineTest {
    @Test fun slowerEncoderCanStartAndReestablishItsBaselineWithoutReplayingStaleFrames() {
        var now = 1_000_000L
        val t = DesktopAvTimeline({ now }) { _, _, _ -> }
        t.reset(true)
        fun written(age: Long, key: Boolean) {
            t.frame(now - age, key, 30)!!.apply { assertTrue(begin()); written() }
        }
        written(210_000, true)
        now += 50_000; written(225_000, false)
        now += 400_000
        assertNull(t.frame(now - 390_000, false, 30))
        assertNull(t.frame(now - 501_000, true, 30))
        written(400_000, true)
        now += 40_000; written(405_000, false)
    }
    @Test fun stalledVideoCannotReplayAnOldGopOrLetPcmRunAhead() {
        var now = 1_000_000L
        val heard = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }) { _, bytes, ttl -> assertTrue(ttl > 0); heard += bytes[0].toInt() }
        t.reset(true)
        t.frame(now, true, 30)!!.apply { assertTrue(begin()); written() }
        val blocked = t.frame(now, false, 30)!!
        assertTrue(blocked.begin())
        val waiting = t.frame(now, false, 30)!!
        repeat(60) { now += 20_000; t.pcm(3, byteArrayOf(it.toByte()), now) }
        assertTrue(heard.all { it < 2 })
        blocked.written()
        assertFalse(waiting.begin())
        assertNull(t.frame(now, false, 30))
        val idr = t.frame(now, true, 30)!!
        assertTrue(idr.begin())
        now += 10_000
        assertTrue(idr.begin()) // even a fragmented access unit makes one admission decision
        idr.written()
        assertTrue(heard.last() >= 58 && heard.none { it in 2..49 })
        assertTrue(t.frame(now, false, 30)!!.begin())
    }
    @Test fun steadyStreamUsesMeasuredVideoLatencyAndStaticPictureDoesNotMuteMusic() {
        var now = 1_000_000L
        val sent = mutableListOf<Long>()
        val t = DesktopAvTimeline({ now }) { _, _, _ -> sent += now }
        t.reset(true); val f = t.frame(now, true, 30)!!
        assertTrue(f.begin()); t.pcm(3, byteArrayOf(1), now)
        now += 90_000; assertTrue(sent.isEmpty()); f.written()
        assertEquals(listOf(now), sent)
        repeat(40) { now += 20_000; t.pcm(3, byteArrayOf(1), now) }
        assertTrue(sent.size in 34..38)
    }
    @Test fun startupDoesNotPlayASecondOfUnmatchedSoundBeforeFirstPicture() {
        var now = 2_000_000L; val heard = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }) { _, b, _ -> heard += b[0].toInt() }
        t.reset(true)
        repeat(70) { now += 20_000; t.pcm(3, byteArrayOf(it.toByte()), now) }
        assertTrue(heard.isEmpty())
        t.frame(now, true, 30)!!.apply { assertTrue(begin()); written() }
        assertTrue(heard.isNotEmpty() && heard.all { it >= 62 })
    }
    @Test fun receiverTrimIsOptionalBoundedAndDoesNotReplayOldAudioOnChange() {
        var now = 2_000_000L; var trim = 1500; val heard = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }, { trim }) { _, b, _ -> heard += b[0].toInt() }
        t.reset(true); t.frame(now, true, 30)!!.apply { assertTrue(begin()); written() }
        repeat(75) { t.pcm(3, byteArrayOf(it.toByte()), now); now += 20_000 }
        assertTrue(heard.isEmpty()); t.pcm(3, byteArrayOf(75), now); assertEquals(listOf(0), heard)
        trim = 0; now += 20_000
        t.pcm(3, byteArrayOf(76), now); assertEquals(listOf(0, 76), heard)
    }
    @Test fun pauseAndReplacementCancelOldFragmentsAndPcm() {
        var now = 1_000_000L; val sent = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }) { _, b, _ -> sent += b[0].toInt() }
        t.reset(true)
        val old = t.frame(now, true, 30)!!
        t.pcm(3, byteArrayOf(1), now)
        t.reset(false)
        assertFalse(old.begin()); old.written()
        t.pcm(3, byteArrayOf(2), now); assertEquals(listOf(2), sent)
        t.close(); now += 10_000; assertFalse(t.pcm(3, byteArrayOf(3), now))
    }
}
