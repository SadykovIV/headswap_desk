package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DesktopReadDeadlineTest {
    @Test fun slowPlacementLeavesDisplayInputAvailableAndLateResultIsIgnored() {
        val display = Executors.newSingleThreadExecutor()
        val placements = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            val started = CountDownLatch(1)
            val caller = Executors.newSingleThreadExecutor()
            try {
                val result = caller.submit<Int> {
                    DesktopReadDeadline.call({ placements.execute(it); true }, 150, { -1 }) {
                        started.countDown(); release.await(); 2
                    }
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertEquals("input", display.submit<String> { "input" }.get(1, TimeUnit.SECONDS))
                assertEquals(-1, result.get(2, TimeUnit.SECONDS).toInt())
                release.countDown()
                assertEquals(2, DesktopReadDeadline.call({ placements.execute(it); true }, 2000, { -1 }) { 2 })
            } finally { caller.shutdownNow() }
        } finally { release.countDown(); display.shutdownNow(); placements.shutdownNow() }
    }

    @Test fun busyDisplayNeverRunsAnExpiredSnapshotLater() {
        var saved: Runnable? = null
        var read = false
        assertEquals(-1, DesktopReadDeadline.call({ saved = it; true }, 1, { -1 }) { read = true; 2 })
        saved!!.run()
        assertFalse(read)
        // Unavailability does not hide an access violation from the caller.
        assertThrows(SecurityException::class.java) {
            DesktopReadDeadline.call({ it.run(); true }, 100, { -1 }) { throw SecurityException("unowned display") }
        }
    }
}
