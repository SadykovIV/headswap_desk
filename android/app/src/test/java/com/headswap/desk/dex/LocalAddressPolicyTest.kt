package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class LocalAddressPolicyTest {
    private val own = listOf("192.168.1.42", "2001:db8::42", "fe80::42%2")

    @Test fun acceptsOnlyExactLoopbackOrAnInterfaceAddress() {
        for (host in listOf("127.0.0.1", "::1", "[::1]", " 192.168.1.42 ", "2001:0db8:0:0:0:0:0:42", "fe80::42%2")) {
            assertTrue(host, LocalAddressPolicy.accepts(host, own))
        }
        for (host in listOf("192.168.1.43", "8.8.8.8", "127.0.0.2", "2001:db8::43", "fe80::42%3", "fe80::42")) {
            assertFalse(host, LocalAddressPolicy.accepts(host, own))
        }
    }

    @Test fun rejectsDnsNamesAmbiguousLiteralsUrlsWildcardsAndMulticast() {
        for (host in listOf("localhost", "example.org", "https://127.0.0.1", "127.1", "2130706433", "0x7f000001", "127.000.0.1", "127.0.0.1:5555", "192.168.1.256", "0.0.0.0", "::", "224.0.0.1", "ff02::1", "", "123456")) {
            assertNull(host, LocalAddressPolicy.normalizeOrNull(host))
            assertFalse(host, LocalAddressPolicy.accepts(host, own))
        }
    }

    @Test fun localInterfaceEnumerationFailureStillAllowsExplicitLoopback() {
        assertTrue(LocalAddressPolicy.accepts("127.0.0.1", emptyList()))
        assertTrue(LocalAddressPolicy.accepts("::1", emptyList()))
        assertFalse(LocalAddressPolicy.accepts("192.168.1.42", emptyList()))
    }
}
