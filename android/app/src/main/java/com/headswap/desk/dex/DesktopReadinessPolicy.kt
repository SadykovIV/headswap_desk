package com.headswap.desk.dex

/** A grant receipt, a desktop host and a selected transport are separate facts. */
internal object DesktopReadinessPolicy {
    data class Facts(val grants: Boolean, val audio: Boolean, val panel: Boolean,
                     val panelUnsupported: Boolean, val hostRequired: Boolean, val host: Boolean,
                     val modePending: Boolean, val recoveryNeedsVerification: Boolean = false)
    data class Requirements(val audio: Boolean, val panel: Boolean)
    enum class Step { READY, ACQUIRE_GRANTS, APPLY_MODE, VERIFY_RECOVERY, RECOVER_HOST, RECONFIGURATION_REQUIRED, USER_ACTIVATION_REQUIRED }

    fun next(facts: Facts, needs: Requirements, explicit: Boolean, allowAcquireGrants: Boolean = true): Step {
        val sufficient = facts.grants && (!needs.audio || facts.audio) &&
            (!needs.panel || facts.panel || facts.panelUnsupported)
        if (facts.grants && !sufficient) return Step.RECONFIGURATION_REQUIRED
        if (!sufficient) return if (explicit && allowAcquireGrants) Step.ACQUIRE_GRANTS else Step.USER_ACTIVATION_REQUIRED
        if (facts.modePending && explicit) return Step.APPLY_MODE
        if (facts.recoveryNeedsVerification) return Step.VERIFY_RECOVERY
        if (facts.hostRequired && !facts.host) return Step.RECOVER_HOST
        return Step.READY
    }
}

/** One operation may have several pairing prompts, each with a one-use reply token. */
internal class PairingCodeGate(private val attempt: String) {
    private var round = 0L
    private var pending: String? = null
    private var cancelled = false
    @Synchronized fun open(): String? {
        if (cancelled) return null
        return "$attempt:${++round}".also { pending = it }
    }
    @Synchronized fun token(): String? = pending.takeUnless { cancelled }
    @Synchronized fun consume(token: String, code: String): Boolean {
        if (cancelled || token != pending || PairingReplyPolicy.code(code) == null) return false
        pending = null
        return true
    }
    @Synchronized fun cancel() { cancelled = true; pending = null }
}
