package com.headswap.desk.dex

/**
 * A wireless-debugging listener may be replaced while pairing or changing networks.
 * Retry only a newly observed endpoint, with the same identity and a fixed attempt bound.
 * Never turn an authentication rejection into repeated connections or a key reset.
 */
internal class DexConnectRetryPolicy {
    private val failedEndpoints = mutableSetOf<DexEndpoint>()

    fun accepts(endpoint: DexEndpoint): Boolean =
        failedEndpoints.size < MAX_ENDPOINTS && endpoint !in failedEndpoints

    fun failed(endpoint: DexEndpoint, reason: DexAccessReason): Boolean {
        failedEndpoints.add(endpoint)
        return failedEndpoints.size < MAX_ENDPOINTS && reason in REDISCOVERABLE
    }

    companion object {
        private const val MAX_ENDPOINTS = 3
        private val REDISCOVERABLE = setOf(
            DexAccessReason.REFUSED,
            DexAccessReason.TIMEOUT,
            DexAccessReason.NETWORK_UNREACHABLE,
            DexAccessReason.CONNECTION_CLOSED,
            DexAccessReason.TLS_HANDSHAKE,
            DexAccessReason.TLS_PROTOCOL,
        )
    }
}
