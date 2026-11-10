package com.headswap.desk.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopActivationUiPolicyTest {
    @Test fun recoveredLivingProjectionIsNotReportedBrokenWhenItsNextReconnectProofIsUnknown() {
        val result = status(projection = true, verification = true)
        assertEquals(DesktopActivationUiPolicy.State.CONNECTED, result.state)
        assertEquals(DesktopActivationUiPolicy.Recovery.UNVERIFIED, result.recovery)
    }

    @Test fun knownRecoveryProblemRemainsVisibleAlongsideTheWorkingProjection() {
        val result = status(projection = true, needsApply = true, verification = true)
        assertEquals(DesktopActivationUiPolicy.State.CONNECTED, result.state)
        assertEquals(DesktopActivationUiPolicy.Recovery.NEEDS_SETUP, result.recovery)
    }

    @Test fun oldFailureCannotOverrideANewerHealthyProjection() {
        assertEquals(DesktopActivationUiPolicy.State.CONNECTED, status(projection = true, failed = true).state)
        assertEquals(DesktopActivationUiPolicy.State.READY, status(ready = true, failed = true).state)
    }

    @Test fun evenFlowingFramesCannotBypassTheInitialPhoneTransaction() {
        assertEquals(DesktopActivationUiPolicy.State.INCOMPLETE, status(blocked = true, projection = true).state)
        assertEquals(DesktopActivationUiPolicy.State.FAILED, status(blocked = true, projection = true, failed = true).state)
        assertEquals(DesktopActivationUiPolicy.State.ACTIVATING, status(progress = true, projection = true).state)
    }

    @Test fun staleFramesCannotReplaceTheLostAccessOrItsRequiredHost() {
        assertEquals(DesktopActivationUiPolicy.State.NEEDS_ACCESS, status(access = false, projection = true).state)
        assertEquals(DesktopActivationUiPolicy.State.DESKTOP_UNAVAILABLE, status(hostMissing = true, projection = true).state)
        assertEquals(DesktopActivationUiPolicy.State.NEEDS_UPDATED_ACCESS, status(newGrants = true, projection = true).state)
    }

    @Test fun idleAccessAndFutureRecoveryAreNotReportedAsAnActiveProjection() {
        val result = status(verification = true)
        assertEquals(DesktopActivationUiPolicy.State.ACCESS_ACTIVE, result.state)
        assertEquals(DesktopActivationUiPolicy.Recovery.UNVERIFIED, result.recovery)
    }

    @Test fun successfulReadinessAndUnrequestedRecoveryKeepTheirOwnMeaning() {
        assertEquals(DesktopActivationUiPolicy.State.READY, status(ready = true).state)
        assertEquals(DesktopActivationUiPolicy.Recovery.READY, status(ready = true).recovery)
        assertEquals(DesktopActivationUiPolicy.Recovery.NOT_REQUIRED,
            status(requested = false, needsApply = true, verification = true).recovery)
    }

    @Test fun ordinaryOwnedAndReusedRoutesNeverShowTheRepairAction() {
        assertFalse(DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.Kind.OWNED))
        assertFalse(DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.Kind.REUSED))
        assertFalse(DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.Kind.DISABLED))
        assertTrue(DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.Kind.PENDING))
        assertTrue(DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.Kind.RESTORE_REQUIRED))
    }

    private fun status(progress: Boolean = false, blocked: Boolean = false, access: Boolean = true,
        newGrants: Boolean = false, hostMissing: Boolean = false, projection: Boolean = false,
        ready: Boolean = false, failed: Boolean = false, requested: Boolean = true,
        needsApply: Boolean = false, verification: Boolean = false) = DesktopActivationUiPolicy.status(
        progress, blocked, access, newGrants, hostMissing, projection, ready, failed, requested, needsApply, verification)
}
