package com.headswap.desk.dex

/** Process-local evidence of a completed ADB probe, never a grant or a saved credential.
 * The connector must authenticate again before creating a display. A new probe or pairing
 * invalidates the evidence first; late results cannot restore an older attempt.
 */
internal class LegacyAdbReadiness {
    private var generation = 0L
    private var verified: DexEndpoint? = null

    @Synchronized fun begin(): Long { verified = null; return ++generation }

    @Synchronized fun verified(attempt: Long, endpoint: DexEndpoint): Boolean {
        if (attempt != generation) return false
        verified = endpoint
        return true
    }

    @Synchronized fun failed(attempt: Long) {
        if (attempt == generation) { verified = null; generation++ }
    }

    @Synchronized fun token(): Long = generation

    @Synchronized fun invalidate(attempt: Long, endpoint: DexEndpoint) {
        if (attempt == generation && verified == endpoint) { verified = null; generation++ }
    }

    @Synchronized fun matches(endpoint: DexEndpoint, paired: Boolean): Boolean =
        paired && verified == endpoint
}
