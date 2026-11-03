package com.headswap.desk.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRequestGateTest {
    private val gate = PanelRequestGate(10111, "com.headswap.desk", "com.example.panel")

    @Test fun rejectsForwardedBinderAndRecursiveTargetsBeforeWorkIsQueued() {
        assertFalse(gate.reserve(10112, "com.example.app", "Screen", 1000))
        assertFalse(gate.reserve(10111, "com.headswap.desk", "Screen", 1000))
        assertFalse(gate.reserve(10111, "com.example.panel", "Screen", 1000))
        assertFalse(gate.reserve(10111, "com.example.app", null, 1000))
        assertTrue(gate.reserve(10111, "com.example.app", "Screen", 1000))
    }

    @Test fun catalogUsesTheSameIssuerAndQueueAsLaunchingApps() {
        assertFalse(gate.reserveCatalog(10112, 1000))
        assertTrue(gate.reserveCatalog(10111, 1000))
        assertFalse(gate.reserve(10111, "com.example.app", "Screen", 1400))
        gate.finish()
        gate.revoke()
        assertFalse(gate.reserveCatalog(10111, 2000))
    }

    @Test fun boundedRequestsCannotReviveSessionAfterHomeOrDisconnect() {
        assertTrue(gate.reserve(10111, "com.example.app", "Screen", 1000))
        assertFalse(gate.reserve(10111, "com.example.app", "Screen", 1900))
        gate.finish()
        assertFalse(gate.reserve(10111, "com.example.app", "Screen", 1199))
        assertTrue(gate.reserve(10111, "com.example.app", "Screen", 2000))
        gate.revoke()
        gate.finish()
        assertFalse(gate.reserve(10111, "com.example.app", "Screen", 3000))
    }
}
