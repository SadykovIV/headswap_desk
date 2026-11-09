package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopRecoveryPolicyTest {
    @Test fun anIsolatedDesktopRecoversWithPhoneOffAndPinLocked() {
        for (interactive in listOf(false, true)) for (locked in listOf(false, true)) {
            assertTrue(DesktopRecoveryPolicy.phoneStateEligible(interactive, locked, true))
            assertEquals(interactive && !locked,
                DesktopRecoveryPolicy.phoneStateEligible(interactive, locked, false))
        }
    }

    @Test fun wirelessRecoveryDoesNotRequireUsbOrInheritAnOldUsbStop() {
        for (usbStopped in listOf(false, true)) for (accessory in listOf(false, true)) {
            assertTrue(DesktopRecoveryPolicy.transportEligible("tcp6", true, usbStopped, accessory))
            assertFalse(DesktopRecoveryPolicy.transportEligible("tcp6", false, usbStopped, accessory))
        }
    }

    @Test fun usbRecoveryStillRequiresItsOwnLivePermittedAccessory() {
        assertTrue(DesktopRecoveryPolicy.transportEligible("usb-aoa", true, false, true))
        assertFalse(DesktopRecoveryPolicy.transportEligible("usb-aoa", false, false, true))
        assertFalse(DesktopRecoveryPolicy.transportEligible("usb-aoa", true, true, true))
        assertFalse(DesktopRecoveryPolicy.transportEligible("usb-aoa", true, false, false))
        for (kind in listOf("tcp-debug", "unknown", ""))
            assertFalse(DesktopRecoveryPolicy.transportEligible(kind, true, false, true))
    }

    @Test fun twoHelperDeathsCanRecoverButRepeatedSuccessDoesNotRenewTheBudget() {
        val policy = DesktopRecoveryPolicy()
        policy.newConnection()
        val first = checkNotNull(policy.begin(eligible = true))
        assertEquals(1, first.number)
        assertEquals(1_000L, first.delayMs)
        assertNull("duplicate failure while cleanup is pending", policy.begin(eligible = true))
        assertTrue(policy.consume(first))
        // First replacement starts and renders frames; the same USB connection has only one retry left.
        val second = checkNotNull(policy.begin(eligible = true))
        assertEquals(2, second.number)
        assertEquals(3_000L, second.delayMs)
        assertTrue(second.token > first.token)
        assertTrue(policy.consume(second))
        repeat(10) { assertNull(policy.begin(eligible = true)) }
    }

    @Test fun stopOrDetachInvalidatesDelayedCallbackWithoutResettingItsBudget() {
        val policy = DesktopRecoveryPolicy()
        val old = checkNotNull(policy.begin(eligible = true))
        policy.cancel()
        assertFalse(policy.isCurrent(old))
        assertFalse(policy.consume(old))
        assertNull(policy.begin(eligible = false)) // User Stop / locked / missing authorization.
        val remaining = checkNotNull(policy.begin(eligible = true))
        assertEquals(2, remaining.number)
        assertFalse("old callback cannot consume its successor", policy.consume(old))
        assertTrue(policy.isCurrent(remaining))
    }

    @Test fun onlyANewConnectionRearmsRetryAndOldCallbackCannotStealItsTicket() {
        val policy = DesktopRecoveryPolicy()
        val old = checkNotNull(policy.begin(eligible = true))
        policy.newConnection()
        val current = checkNotNull(policy.begin(eligible = true))
        assertEquals(1, current.number)
        assertFalse(policy.consume(old))
        assertTrue(policy.consume(current))
        assertFalse("same scheduled callback may run at most once", policy.consume(current))
    }

    @Test fun UnavailableServerOrNonRecoverableErrorNeverSchedulesOrSpendsRetry() {
        val policy = DesktopRecoveryPolicy()
        repeat(20) { assertNull(policy.begin(eligible = false)) }
        assertEquals(1, checkNotNull(policy.begin(eligible = true)).number)
    }
}
