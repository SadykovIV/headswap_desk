package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class VdmRecoveryPolicyTest {
    @Test fun disabledDebuggingWithoutListenerDoesNotSpendDesktopDeadlineOnClosedPorts() {
        listOf(null, "", "-1", "0").forEach { assertTrue(VdmRecoveryPolicy.debuggingUnavailable(0, it)) }
    }
    @Test fun liveLoopbackRemainsUsableWithoutExternalWifi() {
        assertFalse(VdmRecoveryPolicy.debuggingUnavailable(0, "43129"))
    }
    @Test fun unknownOrEnabledDebuggingStillAllowsDiscovery() {
        assertFalse(VdmRecoveryPolicy.debuggingUnavailable(-1, ""))
        assertFalse(VdmRecoveryPolicy.debuggingUnavailable(1, "-1"))
    }
    @Test fun rejectedIdentityIsNotRetriedAsAnotherNetworkAddress() {
        assertTrue(VdmRecoveryPolicy.terminal(DexAccessReason.AUTHORIZATION_REJECTED))
        assertTrue(VdmRecoveryPolicy.terminal(DexAccessReason.PERMISSION_DENIED))
        assertFalse(VdmRecoveryPolicy.terminal(DexAccessReason.REFUSED))
    }
    @Test fun radioOffDoesNotWaitForDiscoveryEvenIfDebuggingSettingRemainsEnabled() {
        assertFalse(VdmRecoveryPolicy.discoveryAllowed(false, 1, null))
        assertFalse(VdmRecoveryPolicy.discoveryAllowed(false, 1, ""))
        assertFalse(VdmRecoveryPolicy.discoveryAllowed(false, -1, "-1"))
        // An existing published listener is dialled locally, without starting mDNS.
        assertFalse(VdmRecoveryPolicy.discoveryAllowed(false, 1, "43129"))
    }
    @Test fun unknownWifiStateCannotSuppressDiscovery() {
        assertTrue(VdmRecoveryPolicy.discoveryAllowed(null, 1, ""))
        assertTrue(VdmRecoveryPolicy.discoveryAllowed(null, -1, null))
        assertTrue(VdmRecoveryPolicy.discoveryAllowed(true, 1, null))
        // A known live listener is allowed despite a delayed debugging setting update.
        assertTrue(VdmRecoveryPolicy.discoveryAllowed(null, 0, "43129"))
    }
    @Test fun localOnlyStillTriesTheSavedPortButCannotDialOldNetworkAddresses() {
        val candidates = VdmRecoveryEndpoints.candidates(DexEndpoint("192.168.1.2", 43129),
            savedPairedPort = true, isOwnHost = { true })
        assertEquals(listOf(DexEndpoint("127.0.0.1", 43129)),
            candidates.filter { VdmRecoveryPolicy.endpointAllowed(it, localOnly = true) })
        assertTrue(VdmRecoveryPolicy.endpointAllowed(DexEndpoint("::1", 43129), true))
        assertFalse(VdmRecoveryPolicy.endpointAllowed(DexEndpoint("localhost", 43129), true))
        assertFalse(VdmRecoveryPolicy.endpointAllowed(DexEndpoint("127.0.0.2", 43129), true))
        assertTrue(VdmRecoveryPolicy.endpointAllowed(DexEndpoint("192.168.1.2", 43129), false))
    }
    @Test fun disabledDebuggingStillLeavesCachedLoopbackEligible() {
        val localOnly = !VdmRecoveryPolicy.discoveryAllowed(false, 0, null)
        assertTrue(localOnly)
        assertTrue(VdmRecoveryPolicy.endpointAllowed(DexEndpoint("127.0.0.1", 43129), localOnly))
    }
    @Test fun managedListenerGetsBoundedRestartWindowOnlyForRefused() {
        val delays = (0..8).mapNotNull { VdmRecoveryPolicy.managedRetryDelay(DexAccessReason.REFUSED, true, it) }
        assertEquals(listOf(200L, 400L, 800L) + List(6) { 1500L }, delays)
        // The request's hard deadline bounds retries. A daemon returning after 5 seconds
        // must still be retried, unlike stale TLS ports which are never retried.
        assertEquals(1500L, VdmRecoveryPolicy.managedRetryDelay(DexAccessReason.REFUSED, true, 100))
        assertNull(VdmRecoveryPolicy.managedRetryDelay(DexAccessReason.REFUSED, true, -1))
        assertNull(VdmRecoveryPolicy.managedRetryDelay(DexAccessReason.REFUSED, false, 0))
        for (reason in DexAccessReason.entries.filter { it != DexAccessReason.REFUSED })
            assertNull(VdmRecoveryPolicy.managedRetryDelay(reason, true, 0))
    }
    @Test fun unsafeAuthenticationOrForeignPrivateProofNeverFallsBackToOtherAddresses() {
        assertTrue(VdmRecoveryPolicy.terminal(DexAccessReason.LOCAL_ADB_CONFIGURATION))
        assertTrue(VdmRecoveryPolicy.terminal(DexAccessReason.IDENTITY_FAILURE))
    }
}
