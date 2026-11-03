package com.headswap.desk.dex

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DelegatedImeRoutesTest {
    private fun entry(id: Int, parent: Int? = null) =
        DelegatedInputRegistry.Entry(id, Any(), parent, 800, 600)

    @Test fun newHelperReappliesAllLiveRoutesOnceEvenAfterFastReconnect() {
        val routes = DelegatedImeRoutes()
        val parent = entry(7)
        val child = entry(11, 7)
        routes.register(parent)
        routes.register(child)
        val calls = mutableListOf<Int>()
        val apply = { target: DelegatedInputRegistry.Entry -> calls.add(target.id); true }

        routes.refresh(null, 0, apply = apply)
        assertTrue(calls.isEmpty())
        val first = Any()
        routes.refresh(first, 5_000, apply = apply)
        routes.refresh(first, 10_000, apply = apply)
        routes.refresh(Any(), 15_000, apply = apply)
        assertEquals(listOf(7, 11, 7, 11), calls)
    }

    @Test fun retryIsBoundedAndAHealthyChildIsNotStarvedByItsParent() {
        val routes = DelegatedImeRoutes()
        val parent = entry(7)
        val child = entry(11, 7)
        val helper = Any()
        routes.register(parent)
        routes.register(child)
        val calls = mutableListOf<Int>()
        val apply = { target: DelegatedInputRegistry.Entry -> calls += target.id; target === child }

        routes.refresh(helper, 0, apply = apply)       // At most one failed request per heartbeat.
        routes.refresh(helper, 1_000, apply = apply)   // Parent is backing off.
        routes.refresh(helper, 4_999, apply = apply)
        routes.refresh(helper, 5_000, apply = apply)
        routes.refresh(helper, 14_999, apply = apply)
        routes.refresh(helper, 15_000, apply = apply)
        assertEquals(listOf(7, 11, 7, 7), calls)
        listOf(35_000L, 75_000L, 135_000L).forEach { routes.refresh(helper, it, apply = apply) }
        assertEquals(7, calls.size)
        routes.refresh(helper, 194_999, apply = apply)
        assertEquals(7, calls.size)
        routes.refresh(helper, 195_000, apply = apply)
        assertEquals(8, calls.size)

        routes.refresh(Any(), 195_001) { calls += it.id; true }
        assertEquals(listOf(7, 11), calls.takeLast(2))
    }

    @Test fun closedOrReusedIdsCannotReviveRevokedRegistrations() {
        val routes = DelegatedImeRoutes()
        val helper = Any()
        val old = entry(7)
        val oldChild = entry(11, 7)
        routes.register(old)
        routes.register(oldChild)
        routes.unregister(oldChild)
        routes.refresh(helper, 0, only = oldChild) { error("retired child") }
        routes.register(entry(12, 7))
        routes.unregister(old)
        routes.refresh(helper, 1) { error("retired parent or child") }

        val next = entry(7)
        routes.register(next)
        routes.unregister(old)
        routes.refresh(helper, 2, only = old) { error("retired generation") }
        var hits = 0
        routes.refresh(helper, 3) { assertSame(next, it); hits++; true }
        assertEquals(1, hits)
        routes.clear()
        routes.refresh(Any(), 4) { error("deactivated gateway") }
        assertThrows(IllegalArgumentException::class.java) { routes.register(entry(0)) }
    }

    @Test fun unregisterDrainsInFlightPolicyChangeBeforeIdReuse() {
        val routes = DelegatedImeRoutes()
        val old = entry(17)
        routes.register(old)
        val inShell = CountDownLatch(1)
        val finishShell = CountDownLatch(1)
        val removing = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val apply = thread {
            routes.refresh(Any(), 0) { inShell.countDown(); finishShell.await(8, TimeUnit.SECONDS); true }
        }
        try {
            assertTrue(inShell.await(5, TimeUnit.SECONDS))
            val close = thread {
                removing.countDown()
                routes.unregister(old)
                removed.countDown()
            }
            assertTrue(removing.await(5, TimeUnit.SECONDS))
            assertFalse(removed.await(100, TimeUnit.MILLISECONDS))
            finishShell.countDown()
            assertTrue(removed.await(5, TimeUnit.SECONDS))
            close.join()
        } finally {
            finishShell.countDown()
            apply.join()
        }
        routes.refresh(Any(), 10) { error("removed ID used by later heartbeat") }
    }
}
