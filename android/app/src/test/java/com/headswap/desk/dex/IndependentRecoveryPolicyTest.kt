package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class IndependentRecoveryPolicyTest {
    private val daemon = IndependentRecoveryPolicy.Daemon(123, 900L)
    private fun device(runtime: String = "", persistent: String = "", listen: String = "", process: IndependentRecoveryPolicy.Daemon = daemon) =
        IndependentRecoveryPolicy.Device(runtime, persistent, listen, process)
    private val owned = IndependentRecoveryPolicy.Record(9, 38555, true, daemon, IndependentRecoveryPolicy.Phase.READY)

    @Test fun originalAndUnsupportedModesCannotEnableNetworkDebugging() {
        for (sdk in listOf(29, 33, 36, 37)) for (delegated in listOf(false, true)) for (mode in 0..2) for (enabled in listOf(false, true)) {
            assertEquals(sdk == 37 && delegated && mode != 0 && enabled,
                IndependentRecoveryPolicy.requested(sdk, delegated, mode, enabled))
        }
    }
    @Test fun ordinaryConfigurationChoosesDedicatedPort() {
        for (value in listOf("", "0", "-1")) assertEquals(IndependentRecoveryPolicy.Selection(38555, true),
            IndependentRecoveryPolicy.select(device(value)))
    }
    @Test fun existingRuntimePortIsReusedWithoutRestartOrOverwritingItsOwner() {
        assertEquals(IndependentRecoveryPolicy.Selection(5555, false),
            IndependentRecoveryPolicy.select(device("5555", "4321")))
    }
    @Test fun persistentPortWithoutRuntimeOverrideIsReused() {
        assertEquals(IndependentRecoveryPolicy.Selection(4321, false), IndependentRecoveryPolicy.select(device("", "4321")))
    }
    @Test fun disabledForeignPersistentConfigAndCustomBindCannotBeOverwritten() {
        listOf(device("0", "4321"), device("5555", "", "tcp:5555"), device("", "", "tcp:127.0.0.1:5555"), device("garbage"), device("65536")).forEach {
            assertThrows(IllegalStateException::class.java) { IndependentRecoveryPolicy.select(it) }
        }
    }
    @Test fun automaticRollbackRequiresExactBootProcessPortAndOwnConfirmedState() {
        assertTrue(IndependentRecoveryPolicy.automaticRestore(owned, 9, device("38555")))
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned, 10, device("38555")))
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned, 9, device("5555")))
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned, 9, device("38555", process = daemon.copy(pid = 124))))
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned, 9, device("38555", process = daemon.copy(startTicks = 901))))
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned.copy(owned = false), 9, device("38555")))
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned.copy(daemon = null), 9, device("38555")))
        listOf(IndependentRecoveryPolicy.Phase.PENDING, IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED).forEach {
            assertFalse(IndependentRecoveryPolicy.automaticRestore(owned.copy(phase = it), 9, device("38555")))
        }
    }
    @Test fun sameDaemonForeignPropertyChangeRevokesAutomaticRollbackAuthority() {
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned, 9, device("38555", "4321")))
        assertFalse(IndependentRecoveryPolicy.automaticRestore(owned, 9, device("38555", listen = "tcp:5555")))
    }
    @Test fun ownershipDoesNotSurviveRebootOrMalformedRecord() {
        assertTrue(IndependentRecoveryPolicy.current(owned, 9))
        assertFalse(IndependentRecoveryPolicy.current(owned, -1))
        assertFalse(IndependentRecoveryPolicy.current(owned, 10))
        assertFalse(IndependentRecoveryPolicy.current(owned.copy(port = 0), 9))
        assertFalse(IndependentRecoveryPolicy.current(owned.copy(daemon = daemon.copy(startTicks = 0)), 9))
    }
    @Test fun processStartTimeParserRejectsDifferentProcessAndMultiplePidOutput() {
        val tail = MutableList(22) { "0" }.apply { this[0] = "S"; this[19] = "890" }.joinToString(" ")
        assertEquals(IndependentRecoveryPolicy.Daemon(123, 890), IndependentRecoveryPolicy.daemon("123", "123 (adbd helper) $tail"))
        assertThrows(IllegalStateException::class.java) { IndependentRecoveryPolicy.daemon("123 456", "123 (adbd) $tail") }
        assertThrows(IllegalStateException::class.java) { IndependentRecoveryPolicy.daemon("124", "123 (adbd) $tail") }
        assertThrows(IllegalStateException::class.java) { IndependentRecoveryPolicy.daemon("123", "123 (adbd) 0 0 0") }
    }
    @Test fun externalUsbModeInvalidatesSavedReadyPort() {
        assertTrue(IndependentRecoveryPolicy.runtimePortContradicts(owned.port, "0"))
        assertTrue(IndependentRecoveryPolicy.runtimePortContradicts(owned.port, "-1"))
    }
    @Test fun changedRuntimePortNeedsPreparationButMatchingPortDoesNot() {
        assertTrue(IndependentRecoveryPolicy.runtimePortContradicts(owned.port, "5555"))
        assertTrue(IndependentRecoveryPolicy.runtimePortContradicts(owned.port, "65535"))
        assertFalse(IndependentRecoveryPolicy.runtimePortContradicts(owned.port, "38555"))
        assertFalse(IndependentRecoveryPolicy.runtimePortContradicts(5555, "5555"))
    }
    @Test fun unavailableOrMalformedRuntimePropertyCannotPretendListenerIsDisabled() {
        listOf(null, "", "denied", " ", "-2", "65536", "999999999999999").forEach { value ->
            assertFalse("Unknown property: $value", IndependentRecoveryPolicy.runtimePortContradicts(owned.port, value))
        }
    }
    @Test fun refusedOwnedPortCanBeRearmedOnlyByExplicitPreparationWithUnchangedConfiguration() {
        assertTrue(IndependentRecoveryPolicy.mayRestartOwnedListener(owned, 9, device("38555"), true))
        // USB may replace adbd while retaining the global TCP property.
        assertTrue(IndependentRecoveryPolicy.mayRestartOwnedListener(owned, 9,
            device("38555", process = daemon.copy(pid = 124, startTicks = 901)), true))
        assertFalse(IndependentRecoveryPolicy.mayRestartOwnedListener(owned, 9, device("38555"), false))
        assertFalse(IndependentRecoveryPolicy.mayRestartOwnedListener(owned, 10, device("38555"), true))
        assertFalse(IndependentRecoveryPolicy.mayRestartOwnedListener(owned.copy(owned = false), 9, device("38555"), true))
        assertFalse(IndependentRecoveryPolicy.mayRestartOwnedListener(owned, 9, device("5555"), true))
        assertFalse(IndependentRecoveryPolicy.mayRestartOwnedListener(owned, 9, device("38555", "4321"), true))
        assertFalse(IndependentRecoveryPolicy.mayRestartOwnedListener(owned, 9, device("38555", listen = "tcp:38555"), true))
        assertFalse(IndependentRecoveryPolicy.mayRestartOwnedListener(owned.copy(phase = IndependentRecoveryPolicy.Phase.PENDING),
            9, device("38555"), true))
    }
    @Test fun savedOwnershipAloneDoesNotProveLiveRecoveryInNewProcess() {
        val proof = RecoveryReadinessProof()
        assertFalse(proof.isVerified(owned))
        assertTrue(proof.confirm(owned, proof.begin()))
        assertTrue(proof.isVerified(owned))
        assertFalse(proof.isVerified(owned.copy(boot = 10)))
        assertFalse(proof.isVerified(owned.copy(daemon = daemon.copy(pid = 124))))
        // Recreating this in-memory state models application/instrumentation restart.
        assertFalse(RecoveryReadinessProof().isVerified(owned))
    }
    @Test fun lateVerificationCannotReviveProofAfterHostDeath() {
        val proof = RecoveryReadinessProof()
        val ticket = proof.begin()
        proof.invalidate()
        assertFalse(proof.confirm(owned, ticket))
        assertFalse(proof.isVerified(owned))
        assertTrue(proof.confirm(owned, proof.begin()))
    }
    @Test fun refusedRouteInvalidatesOnlyItsReadinessAndSuccessfulRecheckClearsRejection() {
        val proof = RecoveryReadinessProof()
        val ticket = proof.begin()
        proof.reject(owned)
        assertTrue(proof.isRejected(owned))
        assertFalse(proof.confirm(owned, ticket))
        assertFalse(proof.isRejected(owned.copy(boot = 10)))
        assertTrue(proof.confirm(owned, proof.begin()))
        assertFalse(proof.isRejected(owned))
        proof.clear()
        assertFalse(proof.isVerified(owned))
    }
    @Test fun recoveryConfirmationCannotUseTlsPortOrAnotherHostOrBoot() {
        assertTrue(IndependentRecoveryPolicy.matchesManagedRoute(owned, 9, "127.0.0.1", 38555))
        assertFalse(IndependentRecoveryPolicy.matchesManagedRoute(owned, 9, "127.0.0.1", 36681))
        assertFalse(IndependentRecoveryPolicy.matchesManagedRoute(owned, 9, "192.168.0.229", 38555))
        assertFalse(IndependentRecoveryPolicy.matchesManagedRoute(owned, 10, "127.0.0.1", 38555))
        assertFalse(IndependentRecoveryPolicy.matchesManagedRoute(owned.copy(phase = IndependentRecoveryPolicy.Phase.PENDING),
            9, "127.0.0.1", 38555))
    }
    @Test fun managedHostRecoveryRefreshesLostProofWithoutAdoptingNewDaemonOwnership() {
        val proof = RecoveryReadinessProof()
        assertTrue(proof.confirm(owned, proof.begin()))
        proof.invalidate() // Host death invalidates the old route proof.
        val recovery = proof.capture(owned)
        assertTrue(proof.confirm(recovery, owned, active = true))
        assertTrue(proof.isVerified(owned))
        assertEquals(daemon, recovery.record.daemon)
        // Readiness does not grant permission to roll back a different adbd generation.
        assertFalse(IndependentRecoveryPolicy.automaticRestore(recovery.record, 9,
            device("38555", process = daemon.copy(pid = 124, startTicks = 901))))
        proof.cancel(recovery) // Ordinary closing of a completed request does not revoke its verified result.
        assertTrue(proof.isVerified(owned))
    }
    @Test fun cancelledOrRetiredRecoveryCannotRefreshCurrentProof() {
        val proof = RecoveryReadinessProof()
        val cancelled = proof.capture(owned)
        proof.cancel(cancelled)
        assertFalse(proof.confirm(cancelled, owned, active = true))
        val retired = proof.capture(owned)
        assertFalse(proof.confirm(retired, owned, active = false))
        proof.invalidate()
        assertFalse(proof.confirm(retired, owned, active = true))
        assertFalse(proof.isVerified(owned))
    }
    @Test fun recoveredConnectionCannotConfirmAReplacementOwnershipRecord() {
        val proof = RecoveryReadinessProof()
        val recovery = proof.capture(owned)
        val replacement = owned.copy(daemon = daemon.copy(pid = 124))
        assertFalse(proof.confirm(recovery, replacement, active = true))
        assertFalse(proof.isVerified(replacement))
    }
}
