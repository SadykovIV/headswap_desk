package com.headswap.desk.dex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePanelWireTest {
    @Test fun aServerResponseAndAnOldSessionCannotAuthenticateTheCurrentApp() {
        val key = "00112233445566778899aabbccddeeff"
        val client = ByteArray(RemotePanelWire.NONCE_BYTES) { 1 }
        val server = ByteArray(RemotePanelWire.NONCE_BYTES) { 2 }
        val expected = RemotePanelWire.proof(key, 2, 4000, client, server)
        assertTrue(RemotePanelWire.validProof(expected, RemotePanelWire.proof(key, 2, 4000, client, server)))
        assertFalse(RemotePanelWire.validProof(expected, RemotePanelWire.proof(key, 1, 4000, client, server)))
        assertFalse(RemotePanelWire.validProof(expected, RemotePanelWire.proof(key, 2, 4001, client, server)))
        assertFalse(RemotePanelWire.validProof(expected, RemotePanelWire.proof(key, 2, 4000, server, client)))
        assertFalse(RemotePanelWire.validProof(expected, RemotePanelWire.proof(key.reversed(), 2, 4000, client, server)))
    }
}
