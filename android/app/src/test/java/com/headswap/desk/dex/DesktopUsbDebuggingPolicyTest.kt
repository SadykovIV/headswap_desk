package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopUsbDebuggingPolicyTest {
    @Test fun scopeFollowsHostAndTransportRatherThanManufacturer() {
        assertTrue(DesktopUsbDebuggingPolicy.required(37, true, false, false))
        assertTrue(DesktopUsbDebuggingPolicy.required(38, true, false, false))
        assertFalse(DesktopUsbDebuggingPolicy.required(36, true, false, false))
        assertFalse(DesktopUsbDebuggingPolicy.required(37, false, false, false))
        assertFalse(DesktopUsbDebuggingPolicy.required(37, true, true, false))
        assertFalse(DesktopUsbDebuggingPolicy.required(37, true, false, true))
    }
    @Test fun filteredSettingIsNotEvidenceThatDebuggingWasDisabled() {
        for (unknown in listOf(null, "", "null", "-1", "unexpected"))
            assertEquals(DesktopUsbDebuggingPolicy.State.UNKNOWN, DesktopUsbDebuggingPolicy.state(unknown, false))
        assertEquals(DesktopUsbDebuggingPolicy.State.ENABLED, DesktopUsbDebuggingPolicy.state("0", true))
        assertEquals(DesktopUsbDebuggingPolicy.State.DISABLED, DesktopUsbDebuggingPolicy.state("0", null))
    }
    @Test fun onlyExactUsbFunctionCounts() {
        assertTrue(DesktopUsbDebuggingPolicy.hasAdbFunction("accessory,adb"))
        assertTrue(DesktopUsbDebuggingPolicy.hasAdbFunction("sec_charging,adb"))
        assertFalse(DesktopUsbDebuggingPolicy.hasAdbFunction("accessory"))
        assertFalse(DesktopUsbDebuggingPolicy.hasAdbFunction("not_adb"))
    }
    @Test fun verifiedSystemSettingWinsOverATransitioningUsbConfiguration() {
        assertEquals(DesktopUsbDebuggingPolicy.State.DISABLED, DesktopUsbDebuggingPolicy.verifiedState("0", "accessory,adb"))
        assertEquals(DesktopUsbDebuggingPolicy.State.ENABLED, DesktopUsbDebuggingPolicy.verifiedState("1", "none"))
        assertEquals(DesktopUsbDebuggingPolicy.State.ENABLED, DesktopUsbDebuggingPolicy.verifiedState(null, "accessory,adb"))
        assertEquals(DesktopUsbDebuggingPolicy.State.UNKNOWN, DesktopUsbDebuggingPolicy.verifiedState(null, "none"))
    }
    @Test fun realSettingChangeInvalidatesEarlierRejection() {
        val proof = DesktopUsbDebuggingPolicy.Confirmation("0", DesktopUsbDebuggingPolicy.State.DISABLED)
        assertTrue(proof.stillMissing("0", false))
        assertFalse(proof.stillMissing("1", false))
        assertFalse(proof.stillMissing("0", true))
        assertFalse(proof.stillMissing(null, false))
        assertFalse(proof.copy(shellState = DesktopUsbDebuggingPolicy.State.ENABLED).stillMissing("0", false))
    }
    @Test fun confirmedMissingUsbDebuggingNeverRegrantsOrReportsReady() {
        val facts = DesktopReadinessPolicy.Facts(true, true, false, false, true, true, false, usbDebuggingMissing = true)
        val needs = DesktopReadinessPolicy.Requirements(true, false)
        assertEquals(DesktopReadinessPolicy.Step.USB_DEBUGGING_REQUIRED, DesktopReadinessPolicy.next(facts, needs, true))
        assertEquals(DesktopReadinessPolicy.Step.USB_DEBUGGING_REQUIRED, DesktopReadinessPolicy.next(facts.copy(grants = false), needs, true))
        assertEquals(DesktopReadinessPolicy.Step.READY, DesktopReadinessPolicy.next(facts.copy(usbDebuggingMissing = false), needs, true))
    }
}
