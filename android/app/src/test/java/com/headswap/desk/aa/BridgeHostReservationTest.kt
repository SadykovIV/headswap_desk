package com.headswap.desk.aa

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Runs even cancelled/dequeued deadlines explicitly, without timing-dependent sleeps. */
class BridgeHostReservationTest {
    private class Deadline(private val callback: Runnable, val millis: Long) : ScheduledFuture<Unit> {
        private var cancelled = false
        private var done = false
        fun runDequeued() { callback.run(); done = true }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled = true; return true }
        override fun isCancelled() = cancelled
        override fun isDone() = done || cancelled
        override fun get() { check(isDone) }
        override fun get(timeout: Long, unit: TimeUnit) = get()
        override fun getDelay(unit: TimeUnit) = unit.convert(millis, TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }
    private class Scheduler : ScheduledThreadPoolExecutor(1) {
        val deadlines = mutableListOf<Deadline>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            Deadline(command, unit.toMillis(delay)).also(deadlines::add)
    }
    private class Source : AaLifecycle {
        var starts = 0
        var stops = 0
        var acknowledgeStop: (() -> Unit)? = null
        val started = CountDownLatch(1)
        override fun start() { starts++; started.countDown() }
        override fun stop(onDone: () -> Unit) { stops++; acknowledgeStop = onDone }
    }
    private fun host(source: Source, scheduler: Scheduler, previous: BridgeHost? = null) =
        BridgeHost(AaBridgeCore({ _, _ -> }), source, scheduler, { _, _ -> }, previous = previous)

    @Test fun claimedWarmSourceCannotExpireWhileTheNewSessionIsBeingConstructed() {
        val scheduler = Scheduler()
        val source = Source()
        val host = host(source, scheduler)
        try {
            assertTrue(host.onUsbSessionStarted())
            host.onUsbSessionClosed(false)
            val old = scheduler.deadlines.single()
            assertEquals(8_000L, old.millis)
            assertTrue(host.reserveSession()) // Before CarLife construction, not after s.start().
            old.runDequeued() // cancel(false) cannot recall a callback already running.
            assertFalse(host.isDead)
            assertFalse(host.isInGrace)
            assertEquals(0, source.stops)
            assertTrue(host.onUsbSessionStarted())
            assertEquals(1, source.starts)
        } finally { host.shutdown("test cleanup"); scheduler.shutdownNow() }
    }

    @Test fun cancelledOldDeadlineCannotExpireTheNextDisconnectGracePeriod() {
        val scheduler = Scheduler()
        val source = Source()
        val host = host(source, scheduler)
        try {
            host.onUsbSessionStarted()
            host.onUsbSessionClosed(false)
            val old = scheduler.deadlines.single()
            assertTrue(host.reserveSession())
            host.onUsbSessionStarted()
            host.onUsbSessionClosed(false)
            val current = scheduler.deadlines.last()
            old.runDequeued()
            assertTrue(host.isInGrace)
            assertFalse(host.isDead)
            assertEquals(0, source.stops)
            current.runDequeued()
            assertTrue(host.isDead)
            assertEquals(1, source.stops)
            old.runDequeued()
            current.runDequeued()
            assertEquals(1, source.stops)
        } finally { host.shutdown("test cleanup"); scheduler.shutdownNow() }
    }

    @Test fun expiredSourceIsNeverReusedAndReplacementWaitsForItsRealStopAcknowledgement() {
        val scheduler = Scheduler()
        val first = Source()
        val old = host(first, scheduler)
        val second = Source()
        var replacement: BridgeHost? = null
        try {
            old.onUsbSessionStarted()
            old.onUsbSessionClosed(false)
            scheduler.deadlines.single().runDequeued()
            assertFalse(old.reserveSession())
            assertFalse(old.isTornDown)
            replacement = host(second, scheduler, old)
            assertTrue(replacement.reserveSession())
            assertTrue(replacement.onUsbSessionStarted())
            assertEquals(0, second.starts)
            checkNotNull(first.acknowledgeStop).invoke()
            assertTrue(second.started.await(3, TimeUnit.SECONDS))
            assertEquals(1, second.starts)
        } finally {
            replacement?.shutdown("test cleanup")
            old.shutdown("test cleanup")
            scheduler.shutdownNow()
        }
    }
}
