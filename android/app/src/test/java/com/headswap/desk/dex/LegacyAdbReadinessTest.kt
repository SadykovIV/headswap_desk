package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class LegacyAdbReadinessTest {
    private val a = DexEndpoint("127.0.0.1", 30001)
    private val b = DexEndpoint("127.0.0.1", 30002)

    @Test fun endpointAndPairedIdentityMustMatch() {
        val state = LegacyAdbReadiness()
        assertFalse(state.matches(a, true))
        assertTrue(state.verified(state.begin(), a))
        assertTrue(state.matches(a, true))
        assertFalse(state.matches(b, true))
        assertFalse(state.matches(DexEndpoint("192.168.0.1", a.port), true))
        assertFalse(state.matches(a, false))
    }

    @Test fun newProbeClearsEvidenceAndLateOldSuccessCannotResurrectIt() {
        val state = LegacyAdbReadiness()
        val old = state.begin()
        state.verified(old, a)
        val next = state.begin()
        assertFalse(state.matches(a, true))
        assertFalse(state.verified(old, a))
        state.failed(next)
        assertFalse(state.verified(old, a))
        assertFalse(state.verified(next, b))
        assertFalse(state.matches(a, true))
        assertFalse(state.matches(b, true))
    }

    @Test fun olderFailureCannotClearNewSuccessfulProbe() {
        val state = LegacyAdbReadiness()
        val old = state.begin()
        val next = state.begin()
        state.verified(next, b)
        state.failed(old)
        assertTrue(state.matches(b, true))
        state.invalidate(old, b)
        assertTrue(state.matches(b, true))
        state.invalidate(next, b)
        assertFalse(state.matches(b, true))
    }
}
