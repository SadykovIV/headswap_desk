package com.headswap.desk.dex

/** Current projection and the ability to recover a future connection are separate UI facts. */
internal object DesktopActivationUiPolicy {
    enum class State { ACTIVATING, INCOMPLETE, NEEDS_ACCESS, NEEDS_UPDATED_ACCESS, DESKTOP_UNAVAILABLE, CONNECTED, READY, ACCESS_ACTIVE, FAILED }
    enum class Recovery { NOT_REQUIRED, READY, NEEDS_SETUP, UNVERIFIED }
    data class Status(val state: State, val recovery: Recovery)

    fun status(inProgress: Boolean, committedBlocked: Boolean, accessReady: Boolean,
        needsNewGrants: Boolean, hostMissing: Boolean, projectionReady: Boolean,
        setupReady: Boolean, attemptFailed: Boolean, recoveryRequested: Boolean,
        recoveryNeedsApply: Boolean, recoveryNeedsVerification: Boolean): Status {
        val recovery = when {
            !recoveryRequested -> Recovery.NOT_REQUIRED
            recoveryNeedsApply -> Recovery.NEEDS_SETUP
            recoveryNeedsVerification -> Recovery.UNVERIFIED
            else -> Recovery.READY
        }
        val state = when {
            inProgress -> State.ACTIVATING
            committedBlocked -> if (attemptFailed) State.FAILED else State.INCOMPLETE
            !accessReady -> if (attemptFailed) State.FAILED else State.NEEDS_ACCESS
            needsNewGrants -> State.NEEDS_UPDATED_ACCESS
            // A stale stream flag must not stand in for its required display host.
            hostMissing -> State.DESKTOP_UNAVAILABLE
            projectionReady -> State.CONNECTED
            setupReady -> State.READY
            attemptFailed -> State.FAILED
            else -> State.ACCESS_ACTIVE
        }
        return Status(state, recovery)
    }

    /** Ambiguous ownership remains actionable; a normal owned/reused route is not an error. */
    fun showRepair(kind: IndependentRecoveryAccess.Kind): Boolean =
        kind == IndependentRecoveryAccess.Kind.PENDING || kind == IndependentRecoveryAccess.Kind.RESTORE_REQUIRED
}
