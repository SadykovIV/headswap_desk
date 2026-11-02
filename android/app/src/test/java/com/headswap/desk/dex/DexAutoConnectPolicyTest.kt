package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DexAutoConnectPolicyTest {
    @Test fun returningToActivityDuringStartupOnlyMonitorsExistingService() {
        val policy = DexAutoConnectPolicy()
        repeat(5) {
            assertEquals(DexAutoConnectPolicy.Decision.MONITOR,
                policy.decide(explicit = false, serviceAlive = true, sessionReady = false))
        }
        assertEquals(DexAutoConnectPolicy.Decision.RESTART,
            policy.decide(explicit = true, serviceAlive = true, sessionReady = false))
        assertEquals(DexAutoConnectPolicy.Decision.MONITOR,
            policy.decide(explicit = true, serviceAlive = true, sessionReady = true))
    }

    @Test fun sharedStopSurvivesNewActivityAndDuplicateAttachOrBinderCallbacks() {
        val policy = DexAutoConnectPolicy()
        policy.observeAccessory("car")
        policy.stop() // Either Activity or notification; both own this same process policy.
        repeat(5) {
            policy.observeAccessory("car")
            assertEquals(DexAutoConnectPolicy.Decision.STOPPED,
                policy.decide(explicit = false, serviceAlive = false, sessionReady = false))
        }
        assertEquals(DexAutoConnectPolicy.Decision.STOPPED,
            policy.decide(explicit = false, serviceAlive = true, sessionReady = false))
    }

    @Test fun sameAccessoryOnlyRearmsAfterPhysicalDetachAndReconnect() {
        val policy = DexAutoConnectPolicy()
        policy.observeAccessory("car")
        policy.stop()
        policy.observeAccessory(null)
        assertTrue(policy.stopped)
        policy.observeAccessory(null)
        assertTrue(policy.stopped)
        policy.observeAccessory("car")
        assertFalse(policy.stopped)
        assertEquals(DexAutoConnectPolicy.Decision.CONNECT,
            policy.decide(explicit = false, serviceAlive = false, sessionReady = false))
    }

    @Test fun stopWhileWaitingIsRearmedByNextAccessoryOrExplicitConnect() {
        val policy = DexAutoConnectPolicy()
        policy.observeAccessory(null)
        policy.stop()
        policy.observeAccessory(null)
        assertTrue(policy.stopped)
        policy.observeAccessory("car")
        assertFalse(policy.stopped)
        policy.stop()
        assertEquals(DexAutoConnectPolicy.Decision.CONNECT,
            policy.decide(explicit = true, serviceAlive = false, sessionReady = false))
        assertFalse(policy.stopped)
    }

    @Test fun sessionFailureAloneDoesNotRearmAUserStop() {
        val policy = DexAutoConnectPolicy()
        policy.observeAccessory("car")
        policy.stop()
        repeat(3) {
            assertEquals(DexAutoConnectPolicy.Decision.STOPPED,
                policy.decide(explicit = false, serviceAlive = it == 0, sessionReady = false))
        }
        policy.observeAccessory("other-car")
        assertFalse(policy.stopped)
    }
}
