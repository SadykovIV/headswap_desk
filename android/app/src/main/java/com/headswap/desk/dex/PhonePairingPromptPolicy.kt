package com.headswap.desk.dex

/** Entering Android's existing pairing code never depends on discovering its transient port. */
internal object PhonePairingPromptPolicy {
    fun offer(ready: Boolean, forcePair: Boolean, reconfigurationRequired: Boolean): Boolean =
        forcePair || !ready && !reconfigurationRequired

    fun canRetryWithCode(reason: DexAccessReason): Boolean = reason in setOf(
        DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.PAIR_REJECTED,
        DexAccessReason.REFUSED, DexAccessReason.TIMEOUT, DexAccessReason.NETWORK_UNREACHABLE,
        DexAccessReason.CONNECTION_CLOSED, DexAccessReason.TLS_HANDSHAKE, DexAccessReason.TLS_PROTOCOL)
}

/** Retry a saved key only for a new endpoint or an observed radio/debugging state change. */
internal class SavedAdbRetryGate {
    private var environment: Pair<Boolean?, Int>? = null
    private val tried = mutableSetOf<DexEndpoint>()
    fun markTried(endpoints: List<DexEndpoint>, state: Pair<Boolean?, Int>) {
        observe(state)
        tried.addAll(endpoints.filter { it.port in 1..65535 })
    }
    fun fresh(endpoint: DexEndpoint?, state: Pair<Boolean?, Int>): Boolean {
        observe(state)
        return endpoint != null && endpoint.port in 1..65535 && tried.add(endpoint)
    }
    private fun observe(state: Pair<Boolean?, Int>) {
        if (state != environment) { environment = state; tried.clear() }
    }
}

/** One service attempt, one pending code, and a shared boundary for READY/bootstrap dispatch. */
internal class PhonePairingAttemptGate(private val attempt: String, restoredPrompt: String? = null) {
    private var round = if (restoredPrompt == null) 0L else requireNotNull(PairingContinuationPolicy.round(attempt, restoredPrompt))
    private var prompt: String? = restoredPrompt
    private var pending: String? = null
    private var pairing = false
    private var closed = false

    @Synchronized fun open(): String? {
        if (closed || pending != null || pairing) return null
        if (prompt == null) prompt = "$attempt:${++round}"
        return prompt
    }
    @Synchronized fun token(): String? = prompt.takeUnless { closed }
    @Synchronized fun offer(token: String, code: String): Boolean {
        if (closed || pairing || token != prompt || pending != null || PairingReplyPolicy.code(code) == null) return false
        prompt = null
        pending = code
        return true
    }
    @Synchronized fun hasCode(): Boolean = !closed && pending != null
    @Synchronized fun takeCode(): String? = pending.takeUnless { closed || pairing }.also {
        if (it != null) { pending = null; pairing = true }
    }
    @Synchronized fun pairingFinished() { pairing = false }

    /** A code accepted first must finish pairing before an old probe can claim READY or dispatch. */
    @Synchronized fun complete(): Boolean {
        if (closed || pending != null || pairing) return false
        closed = true
        prompt = null
        return true
    }
    @Synchronized fun cancel() { closed = true; prompt = null; pending = null; pairing = false }
}
