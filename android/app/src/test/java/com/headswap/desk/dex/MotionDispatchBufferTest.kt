package com.headswap.desk.dex

import com.headswap.desk.shared.MotionDispatchBuffer
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException

class MotionDispatchBufferTest {
    private class Worker {
        val pending = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        val buffer = MotionDispatchBuffer { pending.addLast(it) }
        fun send(key: Any?, value: String) = buffer.submit(key) { calls += value }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    @Test fun slowConsumerKeepsOnlyLatestPositionWithoutDelayingRelease() {
        val w = Worker()
        w.send(null, "down")
        repeat(10_000) { w.send("lease1/pointer0", "move$it") }
        w.send(null, "up")
        assertEquals(3, w.pending.size)
        w.drain()
        assertEquals(listOf("down", "move9999", "up"), w.calls)
        assertEquals(9999L, w.buffer.coalescedCount())
    }

    @Test fun pointerTransitionsAndNewGesturesStayOrdered() {
        val w = Worker()
        for ((key, label) in listOf(null to "down", "one" to "move1", null to "pointerDown",
            "two" to "move2", null to "pointerUp", "one" to "move3", null to "cancel",
            null to "downAgain", "one" to "move4", null to "up")) w.send(key, label)
        w.drain()
        assertEquals(listOf("down", "move1", "pointerDown", "move2", "pointerUp", "move3",
            "cancel", "downAgain", "move4", "up"), w.calls)
    }

    @Test fun differentLeaseOrGeometryCannotReplaceEarlierMove() {
        val w = Worker()
        w.send("lease1:720p", "old")
        w.send("lease1:900p", "resized")
        w.send("lease2:900p", "replacement")
        w.drain()
        assertEquals(listOf("old", "resized", "replacement"), w.calls)
    }

    @Test fun startedCallCannotBeMutatedAndNewMovesStillCoalesce() {
        val w = Worker()
        w.buffer.submit("gesture") {
            w.calls += "in-flight"
            w.send("gesture", "next1")
            w.send("gesture", "next2")
        }
        w.drain()
        assertEquals(listOf("in-flight", "next2"), w.calls)
    }

    @Test fun rejectedScheduleNeverRetainsAReplaceableGhost() {
        val pending = ArrayDeque<Runnable>()
        var reject = true
        val buffer = MotionDispatchBuffer { if (reject) throw RejectedExecutionException() else pending.add(it) }
        try { buffer.submit("gesture") { fail("Rejected task ran") }; fail("Expected rejection") }
        catch (_: RejectedExecutionException) { }
        reject = false
        var ran = false
        buffer.submit("gesture") { ran = true }
        pending.removeFirst().run()
        assertTrue(ran)
        assertEquals(0L, buffer.coalescedCount())
    }
}
