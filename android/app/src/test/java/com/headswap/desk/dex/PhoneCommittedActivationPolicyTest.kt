package com.headswap.desk.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommittedActivationPolicyTest {
    private val phoneRequest = "a".repeat(32)
    private val computerLease = "b".repeat(32)
    private val started = 10_000L
    private val incomplete = Long.MIN_VALUE

    @Test fun pendingPhoneBootstrapBlocksBeforeTheNewInstrumentationExists() {
        assertTrue(block(pending = true, identity = null))
    }

    @Test fun acquiredGrantsAndReceiptReadyDoNotReplaceCoordinatorCompletion() {
        assertTrue(block(ready = true))
        assertTrue(block(pending = true, completed = started))
    }

    @Test fun oldPhoneReceiptNeverBlocksAnIndependentComputerActivation() {
        assertEquals(PhoneCommittedActivationPolicy.Binding.OTHER,
            PhoneCommittedActivationPolicy.binding(phoneRequest, computerLease))
        assertFalse(block(pending = true, identity = computerLease))
        assertFalse(block(ready = true, identity = computerLease))
    }

    @Test fun aDeadActivationDoesNotReviveAnOldReadyReceipt() {
        assertFalse(block(ready = true, identity = null))
    }

    @Test fun onlyTheExactReceiptCompletionUnblocksProjection() {
        assertTrue(block(ready = true, completed = started - 1))
        assertFalse(block(ready = true, completed = started))
    }

    @Test fun aSuccessorTransactionDoesNotInheritThePreviousCompletion() {
        assertTrue(PhoneCommittedActivationPolicy.blocks(true, false, true,
            PhoneCommittedActivationPolicy.Binding.CURRENT, started + 1, started))
        assertFalse(PhoneCommittedActivationPolicy.canComplete(true,
            PhoneCommittedActivationPolicy.Binding.CURRENT, started + 1, started, true))
    }

    @Test fun completionRequiresTheLiveCapabilitiesOfThisExactPhoneTransaction() {
        assertTrue(PhoneCommittedActivationPolicy.canComplete(true,
            PhoneCommittedActivationPolicy.Binding.CURRENT, started, started, true))
        assertFalse(PhoneCommittedActivationPolicy.canComplete(true,
            PhoneCommittedActivationPolicy.Binding.CURRENT, started, started, false))
        assertFalse(PhoneCommittedActivationPolicy.canComplete(true,
            PhoneCommittedActivationPolicy.Binding.OTHER, started, started, true))
        assertFalse(PhoneCommittedActivationPolicy.canComplete(false,
            PhoneCommittedActivationPolicy.Binding.CURRENT, started, started, true))
    }

    @Test fun hostDeathAfterCompletionDoesNotRequireAnotherPhoneActivation() {
        // Host connectivity is deliberately not an input: normal USB recovery owns it afterwards.
        repeat(3) { assertFalse(block(ready = true, completed = started)) }
    }

    @Test fun failedOrCancelledWorkerCannotManufactureCompletion() {
        // Worker failure/cancel leaves the committed receipt READY and the completion unset.
        assertTrue(block(ready = true, completed = incomplete))
        assertFalse(block(ready = false, pending = false))
    }

    @Test fun otherBackendsAndInvalidReceiptsNeverEnterThisGate() {
        assertFalse(PhoneCommittedActivationPolicy.blocks(false, true, false,
            PhoneCommittedActivationPolicy.Binding.CURRENT, started, incomplete))
        assertFalse(PhoneCommittedActivationPolicy.blocks(true, true, false,
            PhoneCommittedActivationPolicy.Binding.CURRENT, -1, incomplete))
        assertEquals(PhoneCommittedActivationPolicy.Binding.OTHER,
            PhoneCommittedActivationPolicy.binding("invalid", computerLease))
    }

    private fun block(pending: Boolean = false, ready: Boolean = false,
        identity: String? = phoneRequest, completed: Long = incomplete): Boolean =
        PhoneCommittedActivationPolicy.blocks(true, pending, ready,
            PhoneCommittedActivationPolicy.binding(phoneRequest, identity), started, completed)
}
