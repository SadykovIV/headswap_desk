package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DesktopReadinessPolicyTest {
    private val ready = DesktopReadinessPolicy.Facts(true, true, true, false, true, true, false)
    private val requested = DesktopReadinessPolicy.Requirements(true, true)
    private fun next(facts: DesktopReadinessPolicy.Facts, explicit: Boolean = true) =
        DesktopReadinessPolicy.next(facts, requested, explicit)

    @Test fun livingGrantsAndHostNeedNeitherAdbNorInstrumentation() {
        assertEquals(DesktopReadinessPolicy.Step.READY, next(ready))
        assertEquals(DesktopReadinessPolicy.Step.READY, next(ready, false))
    }
    @Test fun missingHostRepairsHostWithoutReacquiringLivingGrants() {
        assertEquals(DesktopReadinessPolicy.Step.RECOVER_HOST, next(ready.copy(host = false)))
        assertEquals(DesktopReadinessPolicy.Step.RECOVER_HOST, next(ready.copy(host = false), false))
    }
    @Test fun absentLeaseRequiresExplicitActivationAndCannotBeReportedReadyByHostAlone() {
        assertEquals(DesktopReadinessPolicy.Step.ACQUIRE_GRANTS, next(ready.copy(grants = false)))
        assertEquals(DesktopReadinessPolicy.Step.USER_ACTIVATION_REQUIRED, next(ready.copy(grants = false), false))
    }
    @Test fun liveLeaseCannotGainNewCapabilitiesThroughIdempotentRepeat() {
        assertEquals(DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED, next(ready.copy(audio = false)))
        assertEquals(DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED, next(ready.copy(panel = false)))
        assertEquals(DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED, next(ready.copy(audio = false), false))
    }
    @Test fun unsupportedPanelHasExplicitFallbackInsteadOfReactivationLoop() {
        assertEquals(DesktopReadinessPolicy.Step.READY, next(ready.copy(panel = false, panelUnsupported = true)))
        assertEquals(DesktopReadinessPolicy.Step.RECOVER_HOST,
            next(ready.copy(panel = false, panelUnsupported = true, host = false)))
    }
    @Test fun selectionAloneCannotMutateGlobalAdbDuringBackgroundRecovery() {
        val selected = ready.copy(modePending = true, host = false)
        assertEquals(DesktopReadinessPolicy.Step.APPLY_MODE, next(selected))
        assertEquals(DesktopReadinessPolicy.Step.RECOVER_HOST, next(selected, false))
        assertEquals(DesktopReadinessPolicy.Step.READY, next(selected.copy(host = true), false))
    }
    @Test fun olderAndroidAndDexDoNotRequireTheIndependentHost() {
        assertEquals(DesktopReadinessPolicy.Step.READY, next(ready.copy(hostRequired = false, host = false)))
    }
    @Test fun savedModeAndLivingHostAfterRestartAreNotReadyUntilOwnKeyRouteIsVerified() {
        assertEquals(DesktopReadinessPolicy.Step.VERIFY_RECOVERY, next(ready.copy(recoveryNeedsVerification = true)))
        assertEquals(DesktopReadinessPolicy.Step.VERIFY_RECOVERY,
            next(ready.copy(host = false, recoveryNeedsVerification = true)))
        assertEquals(DesktopReadinessPolicy.Step.READY, next(ready.copy(recoveryNeedsVerification = false)))
    }
    @Test fun rejectedRouteIsPreparedWithoutRegrantingLiveAccess() {
        assertEquals(DesktopReadinessPolicy.Step.APPLY_MODE,
            next(ready.copy(modePending = true, recoveryNeedsVerification = true)))
    }
    @Test fun postDispatchContinuationMayVerifyAndRepairButNeverAcquireReplacementGrants() {
        assertEquals(DesktopReadinessPolicy.Step.VERIFY_RECOVERY, DesktopReadinessPolicy.next(
            ready.copy(recoveryNeedsVerification = true), requested, explicit = true, allowAcquireGrants = false))
        assertEquals(DesktopReadinessPolicy.Step.RECOVER_HOST, DesktopReadinessPolicy.next(
            ready.copy(host = false), requested, explicit = true, allowAcquireGrants = false))
        // The same continuation can lose its lease between the receipt and endpoint resolution.
        assertEquals(DesktopReadinessPolicy.Step.USER_ACTIVATION_REQUIRED, DesktopReadinessPolicy.next(
            ready.copy(grants = false), requested, explicit = true, allowAcquireGrants = false))
    }
    @Test fun turningOptionalFeaturesOffDoesNotRequireNewGrants() {
        assertEquals(DesktopReadinessPolicy.Step.READY, DesktopReadinessPolicy.next(
            ready.copy(audio = false, panel = false), DesktopReadinessPolicy.Requirements(false, false), true))
    }
}

class PairingCodeGateTest {
    @Test fun simultaneousNotificationAndInAppRepliesOnlyConsumeOnce() {
        val gate = PairingCodeGate("operation")
        val token = gate.open()!!
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit<Boolean> { start.await(); gate.consume(token, "123456") } }
            start.countDown()
            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
            assertNull(gate.token())
        } finally { executor.shutdownNow() }
    }
    @Test fun failedCodeRotatesPromptCapabilityButPreservesOperation() {
        val gate = PairingCodeGate("operation")
        val first = gate.open()!!
        assertTrue(gate.consume(first, "012345"))
        val second = gate.open()!!
        assertNotEquals(first, second)
        assertFalse(gate.consume(first, "012345"))
        assertTrue(gate.consume(second, "654321"))
    }
    @Test fun invalidCodeDoesNotConsumeReplyAndCancellationCannotBeReopened() {
        val gate = PairingCodeGate("operation")
        val token = gate.open()!!
        assertFalse(gate.consume(token, "12345"))
        assertEquals(token, gate.token())
        gate.cancel()
        assertFalse(gate.consume(token, "123456"))
        assertNull(gate.open())
        assertNull(gate.token())
    }
    @Test fun oldOperationCannotSubmitToSuccessor() {
        val old = PairingCodeGate("old").open()!!
        val successor = PairingCodeGate("new")
        val current = successor.open()!!
        assertFalse(successor.consume(old, "123456"))
        assertTrue(successor.consume(current, "123456"))
    }
}
