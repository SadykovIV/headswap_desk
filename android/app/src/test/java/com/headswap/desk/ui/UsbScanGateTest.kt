package com.headswap.desk.ui

import org.junit.Assert.*
import org.junit.Test

class UsbScanGateTest {
    @Test fun repeatedRefreshClaimsOnePermissionDialogThenOneServiceLaunch() {
        val gate = UsbScanGate { 0 }
        assertEquals(UsbScanGate.Step.REQUEST_PERMISSION, gate.decide("car", false, false, false))
        val token = gate.pendingToken!!
        repeat(5) { assertEquals(UsbScanGate.Step.PERMISSION_PENDING, gate.decide("car", false, false, true)) }
        assertEquals(token, gate.pendingToken)
        assertTrue(gate.permissionResult("car", token, true))
        assertEquals(UsbScanGate.Step.START, gate.decide("car", true, false, false))
        repeat(5) { assertEquals(UsbScanGate.Step.STARTING, gate.decide("car", true, false, true)) }
        assertEquals(UsbScanGate.Step.ACTIVE, gate.decide("car", true, true, false))
        assertFalse(gate.isStarting)
    }

    @Test fun denialRequiresExplicitRetryAndOldCallbacksCannotAffectNewRequest() {
        val gate = UsbScanGate { 0 }
        gate.decide("car", false, false, false)
        val oldToken = gate.pendingToken!!
        assertTrue(gate.permissionResult("car", oldToken, false))
        repeat(5) { assertEquals(UsbScanGate.Step.DENIED, gate.decide("car", false, false, false)) }
        assertEquals(UsbScanGate.Step.REQUEST_PERMISSION, gate.decide("car", false, false, true))
        assertNotEquals(oldToken, gate.pendingToken)
        assertFalse(gate.permissionResult("car", oldToken, true))
        assertFalse(gate.permissionResult("other-car", gate.pendingToken!!, true))
    }

    @Test fun stopOrDetachInvalidatesAnOutstandingGrant() {
        val gate = UsbScanGate { 0 }
        gate.decide("car", false, false, false)
        val token = gate.pendingToken!!
        gate.reset()
        assertFalse(gate.permissionResult("car", token, true))
        assertEquals(UsbScanGate.Step.WAITING, gate.decide(null, false, false, false))
        assertEquals(UsbScanGate.Step.REQUEST_PERMISSION, gate.decide("car", false, false, false))
        assertNotEquals(token, gate.pendingToken)
        assertEquals(UsbScanGate.Step.WAITING, gate.decide(null, false, false, false))
        assertFalse(gate.permissionResult("car", token, true))
    }

    @Test fun lostPermissionCallbackExpiresWithoutAutomaticallyOpeningAnotherDialog() {
        var time = 0L
        val gate = UsbScanGate { time }
        gate.decide("car", false, false, false)
        time = 60_001
        assertEquals(UsbScanGate.Step.PERMISSION_TIMEOUT, gate.decide("car", false, false, false))
        assertEquals(UsbScanGate.Step.PERMISSION_TIMEOUT, gate.decide("car", false, false, false))
        assertEquals(UsbScanGate.Step.REQUEST_PERMISSION, gate.decide("car", false, false, true))
    }

    @Test fun serviceFailureOrMissingStartupCallbackRequiresRetry() {
        var time = 0L
        val gate = UsbScanGate { time }
        assertEquals(UsbScanGate.Step.START, gate.decide("car", true, false, false))
        gate.failed()
        assertEquals(UsbScanGate.Step.START_FAILED, gate.decide("car", true, false, false))
        assertEquals(UsbScanGate.Step.START, gate.decide("car", true, false, true))
        time = 15_001
        assertEquals(UsbScanGate.Step.START_FAILED, gate.decide("car", true, false, false))
        assertEquals(UsbScanGate.Step.START, gate.decide("car", true, false, true))
    }

    @Test fun permissionRequestFailureDoesNotAutomaticallyRequestAgain() {
        val gate = UsbScanGate { 0 }
        gate.decide("car", false, false, false)
        gate.failed()
        assertEquals(UsbScanGate.Step.DENIED, gate.decide("car", false, false, false))
        assertEquals(UsbScanGate.Step.REQUEST_PERMISSION, gate.decide("car", false, false, true))
    }

    @Test fun supportedAccessoryListIncludesTheUsersMacHeadUnitButNotArbitraryDevices() {
        assertTrue(UsbAccessoryPolicy.accepts("Baidu", "CarLife"))
        assertTrue(UsbAccessoryPolicy.accepts("Mac HU Lab", "CarLife HU"))
        assertFalse(UsbAccessoryPolicy.accepts("Baidu", "CarLife HU"))
        assertFalse(UsbAccessoryPolicy.accepts("Mac HU Lab", "CarLife"))
        assertFalse(UsbAccessoryPolicy.accepts("Other", "CarLife"))
        assertFalse(UsbAccessoryPolicy.accepts(null, null))
    }

    @Test fun accessoryKeyUsesOnlyStablePublicFieldsAndUnambiguousBoundaries() {
        val key = UsbAccessoryPolicy.key("Mac HU Lab", "CarLife HU", "pilot", "1.0", null)
        assertEquals(key, UsbAccessoryPolicy.key("Mac HU Lab", "CarLife HU", "pilot", "1.0", null))
        assertNotEquals(key, UsbAccessoryPolicy.key("Mac HU Lab", "CarLife HU", "pilot", "1.0", ""))
        assertNotEquals(UsbAccessoryPolicy.key("ab", "c", null, null, null), UsbAccessoryPolicy.key("a", "bc", null, null, null))
    }
}
