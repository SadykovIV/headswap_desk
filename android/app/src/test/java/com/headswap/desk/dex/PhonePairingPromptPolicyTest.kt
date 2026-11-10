package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class PhonePairingPromptPolicyTest {
    @Test fun codeEntryIsAvailableBeforeAnyPortDiscoveryOrWithAStaleSavedKey() {
        // Neither a discovered endpoint nor hasPaired is a prerequisite for showing this input.
        assertTrue(PhonePairingPromptPolicy.offer(ready = false, forcePair = false, reconfigurationRequired = false))
        assertTrue(PhonePairingPromptPolicy.canRetryWithCode(DexAccessReason.REFUSED))
        assertTrue(PhonePairingPromptPolicy.canRetryWithCode(DexAccessReason.TIMEOUT))
        val gate = PhonePairingAttemptGate("fixture")
        val prompt = checkNotNull(gate.open())
        assertTrue(gate.offer(prompt, "123456"))
        assertTrue(gate.hasCode())
        assertEquals("123456", gate.takeCode())
        assertNull(gate.takeCode())
    }

    @Test fun readyFastPathAndPermissionReconfigurationDoNotShowAnUnnecessaryCodeForm() {
        assertFalse(PhonePairingPromptPolicy.offer(true, false, false))
        assertFalse(PhonePairingPromptPolicy.offer(false, false, true))
        assertTrue(PhonePairingPromptPolicy.offer(true, true, false))
    }

    @Test fun transportFailureCanKeepInputButIdentityAndOwnershipFailuresStayTerminal() {
        for (reason in listOf(DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.CONNECTION_CLOSED,
            DexAccessReason.NETWORK_UNREACHABLE, DexAccessReason.TLS_HANDSHAKE)) {
            assertTrue(PhonePairingPromptPolicy.canRetryWithCode(reason))
        }
        for (reason in listOf(DexAccessReason.IDENTITY_FAILURE, DexAccessReason.SHELL_REJECTED,
            DexAccessReason.PERMISSION_DENIED, DexAccessReason.LOCAL_ADB_CONFIGURATION,
            DexAccessReason.LOCAL_ADB_RESTORE_REQUIRED)) {
            assertFalse(PhonePairingPromptPolicy.canRetryWithCode(reason))
        }
    }

    @Test fun changingProgressDoesNotReplaceTheVisibleReplyToken() {
        val gate = PhonePairingAttemptGate("fixture")
        val prompt = gate.open()
        repeat(10) { assertEquals(prompt, gate.open()) }
        assertFalse(gate.offer(checkNotNull(prompt), "invalid"))
        assertFalse(gate.offer("stale-round", "123456"))
        assertEquals(prompt, gate.token())
    }

    @Test fun acceptedCodePreventsOldProbeReadyOrDispatchUntilPairingFinishes() {
        val gate = PhonePairingAttemptGate("fixture")
        val prompt = checkNotNull(gate.open())
        assertTrue(gate.offer(prompt, "123456"))
        assertFalse(gate.complete())
        assertFalse(gate.offer(prompt, "123456"))
        assertNull(gate.open())
        assertEquals("123456", gate.takeCode())
        assertFalse(gate.complete())
        assertNull(gate.open()) // A queued old progress callback cannot open a second prompt during pairing.
        gate.pairingFinished()
        val next = checkNotNull(gate.open())
        assertNotEquals(prompt, next)
        assertFalse(gate.offer(prompt, "123456"))
        assertTrue(gate.complete())
        assertFalse(gate.offer(next, "123456"))
    }

    @Test fun readyOrDispatchedBoundaryRejectsLateReplies() {
        val gate = PhonePairingAttemptGate("fixture")
        val prompt = checkNotNull(gate.open())
        assertTrue(gate.complete())
        assertFalse(gate.offer(prompt, "123456"))
        assertNull(gate.token())
        assertNull(gate.open())
        assertNull(gate.takeCode())
    }

    @Test fun replyRacingReadyOrDispatchHasExactlyOneWinner() {
        repeat(50) {
            val gate = PhonePairingAttemptGate("fixture")
            val prompt = checkNotNull(gate.open())
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val accepted = AtomicBoolean()
            val completed = AtomicBoolean()
            val reply = Thread { ready.countDown(); start.await(); accepted.set(gate.offer(prompt, "123456")) }
            val finish = Thread { ready.countDown(); start.await(); completed.set(gate.complete()) }
            reply.start(); finish.start(); ready.await(); start.countDown()
            reply.join(1_000); finish.join(1_000)
            assertFalse(reply.isAlive || finish.isAlive)
            assertTrue(accepted.get() xor completed.get())
            assertEquals(accepted.get(), gate.hasCode())
        }
    }

    @Test fun cancellationClearsTransientCodeAndCannotAffectAnotherAttempt() {
        val first = PhonePairingAttemptGate("first")
        val old = checkNotNull(first.open())
        assertTrue(first.offer(old, "123456"))
        first.cancel()
        assertNull(first.takeCode())
        assertFalse(first.complete())
        val second = PhonePairingAttemptGate("second")
        val current = second.open()
        assertFalse(second.offer(old, "123456"))
        assertEquals(current, second.token())
    }

    @Test fun closedSavedPortDoesNotRetryUntilFreshEndpointOrWirelessStateChange() {
        val gate = SavedAdbRetryGate()
        val closed = DexEndpoint("127.0.0.1", 30101)
        val enabled = true to 1
        gate.markTried(listOf(closed), enabled)
        repeat(20) { assertFalse(gate.fresh(closed, enabled)) }
        assertTrue(gate.fresh(DexEndpoint("127.0.0.1", 30102), enabled))
        assertFalse(gate.fresh(DexEndpoint("127.0.0.1", 30102), enabled))
        gate.markTried(emptyList(), true to 0)
        assertTrue(gate.fresh(closed, enabled)) // A newly enabled listener may reuse the same port.
        assertFalse(gate.fresh(closed, enabled))
    }

    @Test fun MissingDiscoveryNeverCreatesAnEndpointOrConsumesTheOptionalCode() {
        val retry = SavedAdbRetryGate()
        val gate = PhonePairingAttemptGate("fixture")
        val prompt = gate.open()
        repeat(10) { assertFalse(retry.fresh(null, true to 1)) }
        assertEquals(prompt, gate.token())
        assertFalse(retry.fresh(DexEndpoint("127.0.0.1", 0), true to 1))
    }
}
