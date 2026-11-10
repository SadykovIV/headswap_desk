package com.headswap.desk.dex

/** UI readiness follows how this lease was obtained, without changing transport selection or ownership. */
internal object DesktopActivationSetupPolicy {
    fun isReady(method: ActivationMethod, binding: PhoneCommittedActivationPolicy.Binding,
        facts: DesktopReadinessPolicy.Facts, needs: DesktopReadinessPolicy.Requirements,
        committedPhoneActivationBlocked: Boolean): Boolean {
        if (committedPhoneActivationBlocked) return false
        // A new computer activation has proved its capabilities and host through the PC's own key.
        // An earlier phone-only recovery preference must not require a different key/transport here.
        // Changing the method selector cannot bypass the current phone activation's obligations.
        val independentComputerLease = method == ActivationMethod.COMPUTER &&
            binding != PhoneCommittedActivationPolicy.Binding.CURRENT
        val requiredFacts = if (independentComputerLease)
            facts.copy(modePending = false, recoveryNeedsVerification = false) else facts
        return DesktopReadinessPolicy.next(requiredFacts, needs, explicit = true) == DesktopReadinessPolicy.Step.READY
    }
}
