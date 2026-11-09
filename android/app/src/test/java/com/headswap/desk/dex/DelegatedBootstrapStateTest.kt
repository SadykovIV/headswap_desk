package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DelegatedBootstrapStateTest {
    @Test fun newProcessHasNoAccessAndCannotStopOldBootstrap() {
        val state = DelegatedBootstrapState()
        assertFalse(state.isActive())
        assertFalse(state.hasOwner())
        assertFalse(state.requestStop())
    }

    @Test fun stopInvalidatesReadinessImmediatelyAndRunsOnlyOnce() {
        val state = DelegatedBootstrapState()
        var requests = 0
        val lease = state.begin { requests++; assertFalse(state.isActive()) }
        assertTrue(state.isActive())
        assertTrue(state.requestStop())
        assertTrue("Native cleanup must still own the activation after Stop", state.hasOwner())
        assertFalse(state.requestStop())
        assertEquals(1, requests)
        state.end(lease)
        assertFalse(state.isActive())
        assertFalse(state.hasOwner())
    }

    @Test fun overlappingActivationCannotReplaceCurrentStopOwner() {
        val state = DelegatedBootstrapState()
        var oldStopped = false
        var newStopped = false
        state.begin { oldStopped = true }
        try { state.begin { newStopped = true }; fail("Concurrent activation replaced the owner") }
        catch (_: IllegalStateException) { }
        state.requestStop()
        assertTrue(oldStopped)
        assertFalse(newStopped)
    }

    @Test fun lateCleanupCannotEndSuccessorActivation() {
        val state = DelegatedBootstrapState()
        val old = state.begin { }
        state.end(old)
        var successorStopped = false
        state.begin { successorStopped = true }
        state.end(old)
        assertTrue(state.isActive())
        assertTrue(state.requestStop())
        assertTrue(successorStopped)
    }
}
