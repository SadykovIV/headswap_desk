package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class VdmRecoveryScheduleTest {
    private val saved = DexEndpoint("127.0.0.1", 34857)
    private val managed = DexEndpoint("127.0.0.1", 38555)

    @Test fun wifiListenerReturningOnTheSamePortIsRetriedAfterUsbRestart() {
        val schedule = VdmRecoverySchedule()
        schedule.offer(saved)
        assertEquals(saved, schedule.take(1000))
        schedule.failed(saved, DexAccessReason.REFUSED, 1000)
        assertNull(schedule.take(1199))
        assertEquals(saved, schedule.take(1200))
    }

    @Test fun newTlsPortDoesNotWaitForAFailingManagedPort() {
        val schedule = VdmRecoverySchedule()
        schedule.offer(managed)
        assertEquals(managed, schedule.take(1000))
        schedule.failed(managed, DexAccessReason.REFUSED, 1000)
        val fresh = DexEndpoint("127.0.0.1", 40001)
        schedule.offer(fresh)
        assertEquals(fresh, schedule.take(1250))
        assertEquals(managed, schedule.take(1250))
    }

    @Test fun repeatedAdvertisementsCannotCreateBusyLoopOrDuplicateAttempt() {
        val schedule = VdmRecoverySchedule()
        schedule.offer(saved)
        assertEquals(saved, schedule.take(0))
        repeat(100) { schedule.offer(saved) }
        assertNull(schedule.take(0))
        schedule.failed(saved, DexAccessReason.CONNECTION_CLOSED, 0)
        repeat(100) { schedule.offer(saved) }
        assertNull(schedule.take(199))
        assertEquals(saved, schedule.take(200))
        schedule.failed(saved, DexAccessReason.TIMEOUT, 200)
        assertNull(schedule.take(599))
        assertEquals(saved, schedule.take(600))
    }

    @Test fun identityFailureIsNeverRevivedByAnAdvertisement() {
        for (reason in DexAccessReason.entries.filterNot(VdmRecoveryPolicy::retryableTransport)) {
            val schedule = VdmRecoverySchedule()
            schedule.offer(saved)
            assertEquals(saved, schedule.take(1))
            schedule.failed(saved, reason, 1)
            schedule.offer(saved)
            assertNull(reason.name, schedule.take(60_000))
        }
    }

    @Test fun lateListenerIsStillRetriedButWithBackoff() {
        val schedule = VdmRecoverySchedule()
        schedule.offer(managed)
        var now = 1000L
        for (delay in listOf(200L, 400L, 800L, 1500L, 3000L, 3000L)) {
            assertEquals(managed, schedule.take(now))
            schedule.failed(managed, DexAccessReason.REFUSED, now)
            assertNull(schedule.take(now + delay - 1))
            now += delay
        }
        assertEquals(managed, schedule.take(now))
    }

    @Test fun candidateCountIsBoundedAndPortsValidated() {
        val schedule = VdmRecoverySchedule(2)
        schedule.offer(saved.copy(port = 0))
        schedule.offer(saved.copy(port = 65536))
        schedule.offer(saved)
        schedule.offer(managed)
        repeat(100) { schedule.offer(saved.copy(port = 45000 + it)) }
        assertEquals(saved, schedule.take(1))
        assertEquals(managed, schedule.take(1))
        assertNull(schedule.take(1))
    }
}
