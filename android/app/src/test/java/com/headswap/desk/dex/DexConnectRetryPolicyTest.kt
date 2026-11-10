package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DexConnectRetryPolicyTest {
    private val endpoint = DexEndpoint("127.0.0.1", 33000)

    @Test fun rejectsReplayAndStopsAfterThreeDistinctListeners() {
        val policy = DexConnectRetryPolicy()
        assertTrue(policy.accepts(endpoint))
        assertTrue(policy.failed(endpoint, DexAccessReason.REFUSED))
        assertFalse(policy.accepts(endpoint))
        val next = endpoint.copy(port = 33001)
        assertTrue(policy.accepts(next))
        assertTrue(policy.failed(next, DexAccessReason.TLS_PROTOCOL))
        val last = endpoint.copy(port = 33002)
        assertTrue(policy.accepts(last))
        assertFalse(policy.failed(last, DexAccessReason.CONNECTION_CLOSED))
        assertFalse(policy.accepts(endpoint.copy(port = 33003)))
    }

    @Test fun credentialsPermissionsAndProtocolCompatibilityAreNeverRetried() {
        for (reason in listOf(DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.IDENTITY_FAILURE,
            DexAccessReason.PERMISSION_DENIED, DexAccessReason.TLS_VERSION_UNSUPPORTED,
            DexAccessReason.SHELL_REJECTED, DexAccessReason.INVALID_HOST, DexAccessReason.TLS_PROVIDER)) {
            assertFalse(reason.name, DexConnectRetryPolicy().failed(endpoint, reason))
        }
    }
}
