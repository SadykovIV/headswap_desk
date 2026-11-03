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

    @Test fun mediaHasIndependentPollAndClickLimitsAndCannotOutliveRevocation() {
        assertFalse(gate.reserveMedia(44, true, 1000))
        assertTrue(gate.reserveMedia(10111, true, 1000))
        assertFalse(gate.reserveMedia(10111, false, 1002)) // Only one media call concurrently.
        assertTrue(gate.reserveCatalog(10111, 1002))
        gate.finishMedia()
        assertTrue(gate.reserveMedia(10111, false, 1005))
        gate.finishMedia()
        assertFalse(gate.reserveMedia(10111, true, 1500))
        assertTrue(gate.reserveMedia(10111, true, 1750))
        gate.revoke()
        gate.finishMedia()
        assertFalse(gate.reserveMedia(10111, false, 2000))
    }

    @Test fun forwardedBinderCannotChangeChromeAndLayoutCannotBlockNavigationAction() {
        assertFalse(gate.reserveChrome(55, true, 1000))
        assertTrue(gate.reserveChrome(10111, true, 1000))
        assertFalse(gate.reserveChrome(10111, true, 2000))
        assertTrue(gate.reserveChrome(10111, false, 1000))
        gate.finishChrome(false)
        assertFalse(gate.reserveChrome(10111, false, 1100))
        gate.finishChrome(true)
        assertTrue(gate.reserveChrome(10111, true, 1100))
        gate.revoke()
        gate.finishChrome(true)
        assertFalse(gate.reserveChrome(10111, true, 2000))
    }
}
