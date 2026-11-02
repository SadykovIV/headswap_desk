package com.headswap.desk.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DexDiscoveryTest {
    @Test fun ignoresOtherDevicesEvenOnTheSameSubnet() {
        val ownAddresses = setOf("192.168.1.24", "fe80::24%wlan0")
        assertNull(selectOwnDexEndpoint(listOf("192.168.1.25", "fe80::25%wlan0"), 37123, ownAddresses::contains))
        assertEquals(DexEndpoint("192.168.1.24", 37123), selectOwnDexEndpoint(
            listOf("192.168.1.25", "192.168.1.24"), 37123, ownAddresses::contains,
        ))
    }

    @Test fun ignoresInvalidAndMissingServicePorts() {
        listOf(-1, 0, 65536).forEach {
            assertNull(selectOwnDexEndpoint(listOf("192.168.1.24"), it) { true })
        }
        assertNull(selectOwnDexEndpoint(emptyList(), 37123) { true })
    }

    @Test fun prefersOwnIpv4ButAllowsOwnScopedIpv6() {
        val addresses = listOf("fe80::24%wlan0", "192.168.1.24")
        assertEquals(DexEndpoint("192.168.1.24", 65535), selectOwnDexEndpoint(addresses, 65535) { true })
        assertEquals(DexEndpoint("fe80::24%wlan0", 1), selectOwnDexEndpoint(addresses.take(1), 1) { true })
    }
}
