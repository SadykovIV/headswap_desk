package com.headswap.desk.ui

import com.headswap.desk.dex.DexAutoConnectPolicy
import org.junit.Assert.*
import org.junit.Test

class UsbDetachPolicyTest {
    @Test fun onlyConfirmedPhysicalAbsenceOfOwnedUsbMarksDetached() {
        assertTrue(UsbDetachPolicy.confirmedDetached(true, false))
        assertFalse("IO/protocol error with accessory present keeps reconnect grace", UsbDetachPolicy.confirmedDetached(true, true))
        assertFalse("failed UsbManager query is not proof of detach", UsbDetachPolicy.confirmedDetached(true, null))
        assertFalse("unrelated USB broadcast cannot stop TCP", UsbDetachPolicy.confirmedDetached(false, false))
        assertFalse(UsbDetachPolicy.confirmedDetached(false, null))
    }

    @Test fun staleQueuedDetachDoesNotOverrideAlreadyReconnectedAccessory() {
        assertFalse(UsbDetachPolicy.confirmedDetached(ownedUsbSession = true, compatibleAccessoryPresent = true))
    }

    @Test fun detachedIdleNeverResumesForOrdinaryPcAndExplicitConnectOnlyArmsWaiting() {
        val idle = UsbDetachedIdle()
        val gate = UsbScanGate { 0 }
        idle.detached()
        repeat(5) { idle.observeCompatibleAccessory(false); assertTrue(idle.idle) }
        idle.explicitConnect()
        assertFalse(idle.idle)
        assertEquals(UsbScanGate.Step.WAITING, gate.decide(null, false, false, false))
    }

    @Test fun newCarLifeAccessoryResumesWithoutRevokingAccessOrTreatingItAsUserStop() {
        val idle = UsbDetachedIdle()
        val auto = DexAutoConnectPolicy()
        auto.observeAccessory("car")
        idle.detached(); auto.observeAccessory(null)
        assertTrue(idle.idle)
        assertFalse("passive detach is not explicit Stop", auto.stopped)
        idle.observeCompatibleAccessory(true); auto.observeAccessory("car")
        assertFalse(idle.idle)
        assertEquals(DexAutoConnectPolicy.Decision.CONNECT, auto.decide(false, false, false))
    }

    @Test fun fastReplugDuringGraceMonitorsTheExistingHost() {
        val idle = UsbDetachedIdle()
        val auto = DexAutoConnectPolicy()
        auto.observeAccessory("car"); idle.detached(); auto.observeAccessory(null)
        idle.observeCompatibleAccessory(true); auto.observeAccessory("car")
        assertFalse(idle.idle)
        assertEquals(DexAutoConnectPolicy.Decision.MONITOR, auto.decide(false, true, false))
    }

    @Test fun absentAccessoryWinsOverStaleActiveStateAndOldPermissionCallback() {
        val gate = UsbScanGate { 0 }
        gate.decide("car", false, false, false)
        val token = gate.pendingToken!!
        assertEquals(UsbScanGate.Step.WAITING, gate.decide(null, false, true, false))
        assertFalse(gate.permissionResult("car", token, true))
        assertEquals(UsbScanGate.Step.START, gate.decide("car", true, false, false))
    }
}
