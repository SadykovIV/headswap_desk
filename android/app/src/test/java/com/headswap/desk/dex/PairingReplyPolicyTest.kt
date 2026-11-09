package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PairingReplyPolicyTest {
    @Test fun acceptsSixAsciiDigitsAndHumanReadableSpacesOnly() {
        assertEquals("012345", PairingReplyPolicy.code("012345"))
        assertEquals("012345", PairingReplyPolicy.code(" 012 345\n"))
        for (invalid in listOf(null, "", "12345", "1234567", "１２３４５６", "123-456", "123a56")) {
            assertNull(PairingReplyPolicy.code(invalid))
        }
    }
    @Test fun aReplyRequiresTheCurrentAttemptAndAnOpenReplyWindow() {
        assertTrue(PairingReplyPolicy.accepts("fresh", "fresh", true))
        assertFalse(PairingReplyPolicy.accepts("fresh", "old", true))
        assertFalse(PairingReplyPolicy.accepts("fresh", "fresh", false))
        assertFalse(PairingReplyPolicy.accepts(null, null, true))
        assertFalse(PairingReplyPolicy.accepts("", "", true))
    }
    @Test fun pairingUsesOurOwnPublicIdentity() {
        assertEquals("headswap-desk", AdbClientIdentity.NAME)
    }
}
