package com.headswap.desk.dex

/** Worker-owned candidates for one bounded recovery request, including listeners still restarting. */
internal class VdmRecoverySchedule(private val capacity: Int = 12) {
    private data class Entry(var failures: Int = 0, var dueMs: Long = 0)
    private val entries = linkedMapOf<DexEndpoint, Entry>()

    /** Repeated mDNS/property observations must not reset backoff or revive a rejected endpoint. */
    fun offer(endpoint: DexEndpoint) {
        if (endpoint.port in 1..65535 && endpoint !in entries && entries.size < capacity)
            entries[endpoint] = Entry()
    }

    fun take(nowMs: Long): DexEndpoint? {
        // Freshly observed ports are attempted before known, failing ports.
        val next = entries.entries.filter { it.value.dueMs <= nowMs }
            .minByOrNull { it.value.dueMs } ?: return null
        next.value.dueMs = Long.MAX_VALUE
        return next.key
    }

    fun failed(endpoint: DexEndpoint, reason: DexAccessReason, nowMs: Long) {
        val entry = entries[endpoint] ?: return
        if (!VdmRecoveryPolicy.retryableTransport(reason)) return
        val delay = longArrayOf(200, 400, 800, 1500, 3000)[entry.failures.coerceAtMost(4)]
        entry.failures++
        entry.dueMs = nowMs + delay
    }
}

/** Preserve transport failure identity instead of converting it to an unrecoverable assertion. */
internal class VdmRecoveryException(message: String, val reason: DexAccessReason?) : java.io.IOException(message) {
    val retryable: Boolean get() = reason?.let(VdmRecoveryPolicy::retryableTransport) == true
}
