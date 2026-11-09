package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class VdmRecoveryEndpointsTest {
    @Test fun systemTlsPortOnlyAddsValidLoopbackWithNoGuessedFallback() {
        assertEquals(DexEndpoint("127.0.0.1", 45001), VdmRecoveryEndpoints.tlsLoopback("45001"))
        listOf(null, "", "0", "-1", "65536", "5555x", "127.0.0.1:45001").forEach {
            assertNull(VdmRecoveryEndpoints.tlsLoopback(it))
        }
    }

    @Test fun wifiChangeRetainsOnlyThePreviouslyPairedLoopbackPort() {
        assertEquals(listOf(DexEndpoint("127.0.0.1", 42000)),
            VdmRecoveryEndpoints.candidates(DexEndpoint("192.168.0.8", 42000), true) { false })
    }
    @Test fun unownedDiscoveryCannotProbeLoopbackOrForeignHosts() {
        assertTrue(VdmRecoveryEndpoints.candidates(DexEndpoint("192.168.0.8", 42000), false) { false }.isEmpty())
    }
    @Test fun currentOwnEndpointHasLoopbackThenItsActualAddressWithoutGuessedPorts() {
        assertEquals(listOf(DexEndpoint("127.0.0.1", 45678), DexEndpoint("192.168.0.9", 45678)),
            VdmRecoveryEndpoints.candidates(DexEndpoint("192.168.0.9", 45678), false) { true })
        assertTrue(VdmRecoveryEndpoints.candidates(DexEndpoint("127.0.0.1", 0), true) { true }.isEmpty())
    }
}
