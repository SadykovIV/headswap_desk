package com.headswap.desk.dex

/** A notification reply may continue only the exact still-pending user-authorized attempt. */
internal object PairingContinuationPolicy {
    const val MAX_AGE_MS = 5 * 60_000L
    data class Request(val attempt: String, val prompt: String, val started: Long,
        val boot: Int, val build: Int, val selection: String)

    fun valid(request: Request, prompt: String?, now: Long, boot: Int, build: Int, selection: String): Boolean =
        request.prompt == prompt && round(request.attempt, request.prompt) != null &&
            request.boot >= 0 && request.boot == boot && request.build == build &&
            request.selection == selection && request.started >= 0 &&
            now >= request.started && now - request.started < MAX_AGE_MS

    fun round(attempt: String, prompt: String): Long? {
        if (!attempt.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) ||
            !prompt.startsWith("$attempt:")) return null
        return prompt.removePrefix("$attempt:").toLongOrNull()?.takeIf { it in 1..1_000_000 }
    }
}
