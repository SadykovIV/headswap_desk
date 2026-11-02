package com.headswap.desk.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPacingTest {
    @Test
    fun idrThrottleLimitsRequestsToOncePerInterval() {
        val t = IdrThrottle()
        assertTrue(t.tryRequest(0))                  // первый запрос проходит и при нулевом времени
        assertFalse(t.tryRequest(10))
        assertFalse(t.tryRequest(1499))
        assertTrue(t.tryRequest(1500))
        assertFalse(t.tryRequest(2999))              // отказ время не сдвигает: отсчёт от последнего выданного запроса
        assertTrue(t.tryRequest(3000))
        t.reset()
        assertTrue(t.tryRequest(3001))
    }

    @Test
    fun idrRequestsOverTenSecondsOfConstantOverflow() {
        val t = IdrThrottle()
        var n = 0
        for (ms in 0 until 10_000 step 33) if (t.tryRequest(ms.toLong())) n++
        assertTrue("запросов $n", n in 6..7)         // 10 с / 1.5 с
    }

    @Test
    fun drainMeterMeasuresVideoBytesOverBusyTime() {
        val m = DrainMeter()
        assertEquals(0.0, m.bytesPerSec, 0.0)
        // 210 КиБ/с: кадры по 7 КБ пишутся по 32.5 мс подряд
        var now = 0L
        repeat(40) {
            now += 33
            m.onWrite(now, true, 7 * 1024, 32_500_000L)
        }
        assertEquals(215_000.0, m.bytesPerSec, 8_000.0)
    }

    @Test
    fun drainMeterCountsAudioTimeButNotAudioBytes() {
        val m = DrainMeter()
        var now = 0L
        repeat(60) {
            now += 20
            m.onWrite(now, true, 2000, 10_000_000L)   // видео: 200 КБ/с, пока пишется
            now += 20
            m.onWrite(now, false, 2000, 10_000_000L)  // звук делит канал
        }
        assertEquals(100_000.0, m.bytesPerSec, 5_000.0)
    }

    @Test
    fun drainMeterIgnoresWindowsWithoutVideoOrBusyTime() {
        val m = DrainMeter()
        var now = 0L
        repeat(30) {
            now += 100
            m.onWrite(now, false, 100, 1_000_000L)
        }
        assertEquals(0.0, m.bytesPerSec, 0.0)
        repeat(5) {
            now += 400
            m.onWrite(now, true, 100, 1_000_000L)      // 5 мс записи за окно: слишком шумно
        }
        assertEquals(0.0, m.bytesPerSec, 0.0)
    }

    @Test
    fun overflowByFramesBytesAndUnmeasuredDrain() {
        val drain = 210.0 * 1024                         // ~105 КБ = 500 мс
        assertFalse(VideoQueueLimit.overflow(0, 0, drain))
        assertFalse(VideoQueueLimit.overflow(30, 100_000, drain))
        assertTrue(VideoQueueLimit.overflow(31, 1_000, drain))
        assertTrue(VideoQueueLimit.overflow(5, 110_000, drain))
        // пока слив не измерен - 512 КБ
        assertFalse(VideoQueueLimit.overflow(5, 500_000, 0.0))
        assertTrue(VideoQueueLimit.overflow(5, 540_000, 0.0))
        // очень медленный слив: лимит не меньше 64 КБ
        assertFalse(VideoQueueLimit.overflow(5, 60_000, 10_000.0))
        // пустая очередь принимает любой кадр
        assertFalse(VideoQueueLimit.overflow(0, 0, 10_000.0))
    }

    @Test
    fun backlogMsAndPolicyBacklog() {
        assertEquals(500L, VideoQueueLimit.backlogMs(105_000, 210_000.0))
        assertEquals(0L, VideoQueueLimit.backlogMs(105_000, 0.0))
        assertEquals(100.0, VideoQueueLimit.policyBacklogMs(100, -1), 0.0)
        assertEquals(100.0, VideoQueueLimit.policyBacklogMs(100, VideoQueueLimit.OVERFLOW_HOLD_MS), 0.0)
        assertEquals(VideoQueueLimit.OVERFLOW_BACKLOG_MS, VideoQueueLimit.policyBacklogMs(100, 200), 0.0)
        assertEquals(1500.0, VideoQueueLimit.policyBacklogMs(1500, 200), 0.0)
    }

    @Test
    fun overflowPressureMakesPolicyCutOnSawtoothBacklog() {
        // очередь с лимитом режет бэклог пилой (до 500 мс и обратно в ноль раз в 1.5 с, кадры при переполнении выбрасываются): сглаженный бэклог ~100 мс, без учёта переполнения отката нет
        val drain = 210.0 * 1024
        fun run(withPressure: Boolean): Int {
            val p = BitratePolicy(6_000_000)
            var lastOverflow = Long.MIN_VALUE
            p.update(0, 0.0, drain)
            var t = 0L
            while (t < 10_000) {
                t += 100
                val phase = t % 1500
                val raw = if (phase < 500) 500.0 - phase else 0.0
                if (phase == 0L) lastOverflow = t           // очередь упёрлась в лимит, кадр выброшен
                val since = if (lastOverflow == Long.MIN_VALUE) -1L else t - lastOverflow
                p.update(t, if (withPressure) VideoQueueLimit.policyBacklogMs(raw.toLong(), since) else raw, drain)
            }
            return p.current
        }
        val without = run(false)
        assertEquals("без давления: $without", 6_000_000, without)
        assertTrue(run(true) < 2_000_000)
    }
}
