package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PairingRequestTest {
    @Test fun cancellationClosesTheOwnedConnectionExactlyOnce() {
        val closes = AtomicInteger()
        val request = PairingRequest()
        request.attach(AutoCloseable { closes.incrementAndGet() })
        request.close()
        request.close()
        assertEquals(1, closes.get())
        assertThrows(InterruptedException::class.java) { request.commit { fail("Cancelled result was saved") } }
    }

    @Test fun lateConnectionCannotEscapeCancellation() {
        val closes = AtomicInteger()
        val request = PairingRequest()
        request.close()
        assertThrows(InterruptedException::class.java) {
            request.attach(AutoCloseable { closes.incrementAndGet() })
        }
        assertEquals(1, closes.get())
    }

    @Test fun cancellationCannotReturnWhileAnAcceptedResultIsStillBeingSaved() {
        val request = PairingRequest()
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellationStarted = CountDownLatch(1)
        val cancellationFinished = CountDownLatch(1)
        val writes = AtomicInteger()
        val commit = thread {
            request.commit {
                saving.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                writes.incrementAndGet()
            }
        }
        assertTrue(saving.await(3, TimeUnit.SECONDS))
        val cancel = thread {
            cancellationStarted.countDown()
            request.close()
            cancellationFinished.countDown()
        }
        try {
            assertTrue(cancellationStarted.await(3, TimeUnit.SECONDS))
            assertFalse(cancellationFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            commit.join(3000)
            cancel.join(3000)
        }
        assertFalse(commit.isAlive)
        assertFalse(cancel.isAlive)
        assertEquals(1, writes.get())
        assertEquals(0L, cancellationFinished.count)
        assertThrows(InterruptedException::class.java) { request.commit { writes.incrementAndGet() } }
        assertEquals(1, writes.get())
    }
}
