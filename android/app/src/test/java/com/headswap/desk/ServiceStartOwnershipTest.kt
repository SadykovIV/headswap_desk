package com.headswap.desk

import org.junit.Assert.*
import org.junit.Test

class ServiceStartOwnershipTest {
    @Test fun queuedReconnectCannotRemoveForegroundOrWakeLockOfTheNewStart() {
        val ownership = ServiceStartOwnership()
        ownership.delivered(10)
        // Android has accepted start 11, but its main-looper onStartCommand is still queued.
        var frameworkStart = 11
        var foreground = true
        var wakeLockHeld = true
        var resourcesClosed = 0
        val attemptedIds = mutableListOf<Int>()
        val stopLatest = { id: Int -> attemptedIds += id; id == frameworkStart }
        val release: () -> Unit = { foreground = false; wakeLockHeld = false; resourcesClosed++ }

        assertFalse(ownership.finish(stopLatest, release)) // Old grace/producer ACK.
        assertTrue(foreground)
        assertTrue(wakeLockHeld)
        assertEquals(0, resourcesClosed)
        assertEquals(listOf(10), attemptedIds)

        ownership.delivered(11) // The queued reconnect now runs in the same service.
        frameworkStart = 12 // A further start accepted before its callback is delivered.
        assertFalse(ownership.finish(stopLatest, release))
        assertTrue(foreground)
        assertTrue(wakeLockHeld)
        assertEquals(0, resourcesClosed)
        ownership.delivered(12)
        assertTrue(ownership.finish(stopLatest, release))
        assertFalse(foreground)
        assertFalse(wakeLockHeld)
        assertEquals(1, resourcesClosed)
        assertEquals(listOf(10, 11, 12), attemptedIds)
    }

    @Test fun resourcesAreReleasedOnlyAfterTheFrameworkAcceptsTheStop() {
        val ownership = ServiceStartOwnership()
        val calls = mutableListOf<String>()
        assertFalse(ownership.finish({ error("No delivered start is owned") }, { error("No resources to release") }))
        ownership.delivered(3)
        assertTrue(ownership.finish({ id -> calls += "stop:$id"; true }, { calls += "release" }))
        assertEquals(listOf("stop:3", "release"), calls)
    }
}
