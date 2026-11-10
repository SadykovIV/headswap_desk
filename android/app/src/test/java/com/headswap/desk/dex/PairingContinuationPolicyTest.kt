package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PairingContinuationPolicyTest {
    private val attempt = "d8318bf3-613a-4be6-9b72-99668f1dc487"
    private val request = PairingContinuationPolicy.Request(attempt, "$attempt:2", 1000, 3, 53, "original-selection")
    private fun valid(record: PairingContinuationPolicy.Request = request, now: Long = 2000) =
        PairingContinuationPolicy.valid(record, request.prompt, now, 3, 53, "original-selection")

    @Test fun freshReplyRestoresOnlyCurrentBuildBootAndSelection() {
        assertTrue(valid())
        assertFalse(valid(request.copy(boot = -1)))
        assertFalse(valid(request.copy(boot = 2)))
        assertFalse(valid(request.copy(build = 52)))
        assertFalse(valid(request.copy(selection = "added-capabilities")))
        assertFalse(valid(request.copy(prompt = "$attempt:1")))
    }
    @Test fun expiredOrRebootedElapsedClockCannotResume() {
        assertFalse(valid(now = 999))
        assertFalse(valid(now = 1000 + PairingContinuationPolicy.MAX_AGE_MS))
        assertTrue(valid(now = 999 + PairingContinuationPolicy.MAX_AGE_MS))
    }
    @Test fun restoredPromptIsConsumedOnceAndNextPromptGetsANewIdentity() {
        val gate = PhonePairingAttemptGate(attempt, request.prompt)
        assertFalse(gate.offer("$attempt:1", "123456"))
        assertTrue(gate.offer(request.prompt, "123456"))
        assertFalse(gate.offer(request.prompt, "123456"))
        assertEquals("123456", gate.takeCode())
        assertNull(gate.open())
        gate.pairingFinished()
        assertEquals("$attempt:3", gate.open())
        gate.cancel()
        assertNull(gate.open())
    }
    @Test fun malformedReceiptCannotAuthorizeAnything() {
        for (prompt in listOf("wrong:2", "$attempt:0", "$attempt:1000001", "$attempt:2:extra", "$attempt:extra:2"))
            assertFalse(valid(request.copy(prompt = prompt)))
        assertFalse(valid(request.copy(attempt = "wrong")))
    }
}
