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
    @Test fun transientTransportFailureIsRecoverableWithoutNewGrants() {
        for (reason in DexAccessReason.entries) {
            val expected = reason in setOf(DexAccessReason.REFUSED, DexAccessReason.TIMEOUT,
                DexAccessReason.NETWORK_UNREACHABLE, DexAccessReason.CONNECTION_CLOSED,
                DexAccessReason.TLS_HANDSHAKE, DexAccessReason.TLS_PROTOCOL)
            assertEquals(reason.name, expected, VdmRecoveryException("unavailable", reason).retryable)
        }
        assertFalse(VdmRecoveryException("missing pairing", null).retryable)
        assertFalse(VdmRecoveryPolicy.OFFLINE_MESSAGE.contains("Повторная активация"))
    }
    @Test fun unsafeAuthenticationOrForeignPrivateProofNeverFallsBackToOtherAddresses() {
        assertTrue(VdmRecoveryPolicy.terminal(DexAccessReason.LOCAL_ADB_CONFIGURATION))
        assertTrue(VdmRecoveryPolicy.terminal(DexAccessReason.IDENTITY_FAILURE))
    }
}
