package com.headswap.desk.dex

/** Bootstrap grants alone must not let USB race the remainder of the same phone transaction. */
object PhoneCommittedActivationPolicy {
    enum class Binding { NO_ACTIVATION, CURRENT, OTHER }

    fun binding(requestIdentity: String?, activationIdentity: String?): Binding = when {
        activationIdentity == null -> Binding.NO_ACTIVATION
        PhoneActivationPolicy.validToken(requestIdentity) && requestIdentity == activationIdentity -> Binding.CURRENT
        else -> Binding.OTHER
    }

    fun blocks(delegated: Boolean, pending: Boolean, ready: Boolean, binding: Binding,
        started: Long, completedStarted: Long): Boolean {
        if (!delegated || started < 0 || binding == Binding.OTHER) return false
        if (pending) return true
        return ready && binding == Binding.CURRENT && completedStarted != started
    }

    fun canComplete(ready: Boolean, binding: Binding, started: Long, expectedStarted: Long,
        currentlyReady: Boolean): Boolean = ready && currentlyReady && binding == Binding.CURRENT &&
        expectedStarted >= 0 && started == expectedStarted
}
