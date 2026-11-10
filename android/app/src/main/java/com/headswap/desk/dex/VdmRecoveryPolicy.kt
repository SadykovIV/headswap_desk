package com.headswap.desk.dex

/** Missing desktop ownership is distinct from losing the application's adopted permissions. */
internal object VdmRecoveryPolicy {
    const val OFFLINE_MESSAGE = "Права HeadSwap сохранены, но служба отладки недоступна. " +
        "Включите беспроводную отладку и повторите подключение к CarLife. Заново выдавать права не нужно."

    // This only suppresses discovery, never the cached loopback attempt. A missing/filtered
    // property is not proof of a disabled listener, and a local listener may outlast a network change.
    fun debuggingUnavailable(wifiDebugging: Int, tlsPort: String?): Boolean =
        wifiDebugging == 0 && VdmRecoveryEndpoints.tlsLoopback(tlsPort) == null

    /** Missing system properties never rule out an already listening cached loopback port. */
    fun discoveryAllowed(wifiEnabled: Boolean?, wifiDebugging: Int, tlsPort: String?): Boolean =
        wifiEnabled != false && !debuggingUnavailable(wifiDebugging, tlsPort)

    fun endpointAllowed(endpoint: DexEndpoint, localOnly: Boolean): Boolean = !localOnly ||
        LocalAddressPolicy.normalizeOrNull(endpoint.host) in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

    /** Authentication/identity failures never become automatic retries or new grant requests. */
    fun retryableTransport(reason: DexAccessReason): Boolean = reason in setOf(
        DexAccessReason.REFUSED, DexAccessReason.TIMEOUT, DexAccessReason.NETWORK_UNREACHABLE,
        DexAccessReason.CONNECTION_CLOSED, DexAccessReason.TLS_HANDSHAKE, DexAccessReason.TLS_PROTOCOL,
    )

    fun terminal(reason: DexAccessReason): Boolean = reason in setOf(
        DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.PERMISSION_DENIED,
        DexAccessReason.TLS_VERSION_UNSUPPORTED, DexAccessReason.IDENTITY_FAILURE,
        DexAccessReason.SHELL_REJECTED, DexAccessReason.LOCAL_ADB_CONFIGURATION, DexAccessReason.USB_DEBUGGING_DISABLED,
    )
}
