package com.headswap.desk.dex

/** Single-attempt setup flow. Discovery updates never initiate a second pair or probe. */
internal class DexSetupFlow {
    enum class Phase { IDLE, FIND_PAIRING, PAIRING, FIND_CONNECT, PROBING, READY, ACTIVATING, FAILED, CANCELLED }
    sealed interface Command {
        data object None : Command
        data class Discover(val token: Long, val kind: DexService) : Command
        data class Pair(val token: Long, val endpoint: DexEndpoint, val code: String) : Command
        data class Probe(val token: Long, val endpoint: DexEndpoint) : Command
        data class Ready(val endpoint: DexEndpoint) : Command
        data class NotFound(val kind: DexService) : Command
    }

    var token = 0L
        private set
    var phase = Phase.IDLE
        private set
    private var code: String? = null
    private var connect: DexEndpoint? = null
    private var fallback: DexEndpoint? = null
    private var probing: DexEndpoint? = null

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
            phase == Phase.FIND_CONNECT && connection != null -> probe(connection)
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
    }
}
