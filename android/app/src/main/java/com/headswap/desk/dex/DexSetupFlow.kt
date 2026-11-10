package com.headswap.desk.dex

/** One user attempt. Pairing is never repeated; replacement connect endpoints are bounded. */
internal class DexSetupFlow {
    enum class Phase { IDLE, FIND_PAIRING, PAIRING, FIND_CONNECT, PROBING, READY, ACTIVATING, FAILED, CANCELLED }
    sealed interface Command {
        data object None : Command
        data class Discover(val token: Long, val kind: DexService) : Command
        data class Pair(val token: Long, val endpoint: DexEndpoint, val code: String) : Command
        data class Probe(val token: Long, val endpoint: DexEndpoint) : Command
        data class Ready(val endpoint: DexEndpoint) : Command
        data class NotFound(val kind: DexService) : Command
        data class ProbeFailed(val error: DexAccessException) : Command
    }

    var token = 0L
        private set
    var phase = Phase.IDLE
        private set
    private var code: String? = null
    private var connect: DexEndpoint? = null
    private var fallback: DexEndpoint? = null
    private var probing: DexEndpoint? = null
    private var retry = DexConnectRetryPolicy()
    private var probeFailure: DexAccessException? = null

    fun beginConnect(saved: DexEndpoint? = null, manual: DexEndpoint? = null): Command {
        reset()
        fallback = saved
        phase = Phase.FIND_CONNECT
        return if (manual != null) probe(manual) else Command.Discover(token, DexService.CONNECT)
    }

    fun beginPair(pairingCode: String, manual: DexEndpoint? = null): Command {
        reset()
        code = pairingCode
        phase = Phase.FIND_PAIRING
        return if (manual != null) pair(manual) else Command.Discover(token, DexService.PAIRING)
    }

    fun discovered(attempt: Long, pairing: DexEndpoint?, connection: DexEndpoint?): Command {
        if (attempt != token) return Command.None
        connect = connection // A lost service clears the candidate before pairing completes.
        return when {
            phase == Phase.FIND_PAIRING && pairing != null -> pair(pairing)
            phase == Phase.FIND_CONNECT && connection != null && retry.accepts(connection) -> probe(connection)
            else -> Command.None
        }
    }

    fun paired(attempt: Long): Command {
        if (!isCurrent(attempt, Phase.PAIRING)) return Command.None
        phase = Phase.FIND_CONNECT
        return connect?.let(::probe) ?: Command.Discover(token, DexService.CONNECT)
    }

    fun verified(attempt: Long): Command {
        if (!isCurrent(attempt, Phase.PROBING)) return Command.None
        phase = Phase.READY
        return Command.Ready(checkNotNull(probing))
    }

    /** Wait for new discovery evidence after a transport failure, never replay the failed port. */
    fun probeFailed(attempt: Long, error: DexAccessException): Command {
        if (!isCurrent(attempt, Phase.PROBING)) return Command.None
        probeFailure = error
        fallback = null
        if (!retry.failed(checkNotNull(probing), error.reason)) {
            phase = Phase.FAILED
            return Command.ProbeFailed(error)
        }
        phase = Phase.FIND_CONNECT
        return connect?.takeIf(retry::accepts)?.let(::probe)
            ?: Command.Discover(token, DexService.CONNECT)
    }

    /** Only an explicitly requested activation may consume the just-verified endpoint once. */
    fun activating(attempt: Long): Boolean {
        if (!isCurrent(attempt, Phase.READY)) return false
        phase = Phase.ACTIVATING
        return true
    }

    fun expired(attempt: Long, kind: DexService): Command {
        val expected = if (kind == DexService.PAIRING) Phase.FIND_PAIRING else Phase.FIND_CONNECT
        if (!isCurrent(attempt, expected)) return Command.None
        if (kind == DexService.CONNECT) fallback?.let {
            fallback = null
            return probe(it) // One fallback to the last verified endpoint if mDNS is unavailable.
        }
        phase = Phase.FAILED
        code = null
        if (kind == DexService.CONNECT) probeFailure?.let { return Command.ProbeFailed(it) }
        return Command.NotFound(kind)
    }

    fun failed(attempt: Long): Boolean {
        if (attempt != token || phase == Phase.CANCELLED || phase == Phase.READY) return false
        phase = Phase.FAILED
        code = null
        return true
    }

    fun cancel() { reset(); phase = Phase.CANCELLED }
    fun isCurrent(attempt: Long, expected: Phase) = attempt == token && phase == expected

    private fun pair(endpoint: DexEndpoint): Command {
        phase = Phase.PAIRING
        val value = checkNotNull(code)
        code = null
        return Command.Pair(token, endpoint, value)
    }
    private fun probe(endpoint: DexEndpoint): Command {
        phase = Phase.PROBING
        probing = endpoint
        return Command.Probe(token, endpoint)
    }
    private fun reset() {
        token++
        code = null
        connect = null
        fallback = null
        probing = null
        retry = DexConnectRetryPolicy()
        probeFailure = null
    }
}
