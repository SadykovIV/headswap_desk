package com.headswap.desk.dex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopActivationSetupPolicyTest {
    private val readyHostWithOldPhonePreference = DesktopReadinessPolicy.Facts(
        grants = true, audio = true, panel = true, panelUnsupported = false,
        hostRequired = true, host = true, modePending = true, recoveryNeedsVerification = true)
    private val needs = DesktopReadinessPolicy.Requirements(audio = true, panel = true)

    @Test fun independentPcActivationCanFinishWithAnOldPhoneRecoveryPreference() {
        assertTrue(ready())
        // UI assessment never modifies the facts used by projection recovery or its selected route.
        assertTrue(readyHostWithOldPhonePreference.modePending)
        assertTrue(readyHostWithOldPhonePreference.recoveryNeedsVerification)
    }

    @Test fun firstComputerActivationWithoutAPhoneReceiptDoesNotNeedAnAppPairingKey() {
        assertTrue(ready(binding = PhoneCommittedActivationPolicy.Binding.NO_ACTIVATION))
    }

    @Test fun computerActivationStillRequiresALiveHostWhenAndroidNeedsOne() {
        assertFalse(ready(facts = readyHostWithOldPhonePreference.copy(host = false)))
        assertTrue(ready(facts = readyHostWithOldPhonePreference.copy(hostRequired = false, host = false)))
    }

    @Test fun aHostNeverReplacesTheComputerLeasesActualPermissions() {
        assertFalse(ready(facts = readyHostWithOldPhonePreference.copy(grants = false)))
        assertFalse(ready(facts = readyHostWithOldPhonePreference.copy(audio = false)))
        assertFalse(ready(facts = readyHostWithOldPhonePreference.copy(panel = false)))
        assertTrue(ready(facts = readyHostWithOldPhonePreference.copy(panel = false, panelUnsupported = true)))
    }

    @Test fun selectedWifiActivationStillRequiresItsPreparedAndVerifiedRecoveryRoute() {
        assertFalse(ready(method = ActivationMethod.WIFI))
        assertFalse(ready(method = ActivationMethod.WIFI,
            facts = readyHostWithOldPhonePreference.copy(modePending = false)))
        assertTrue(ready(method = ActivationMethod.WIFI,
            facts = readyHostWithOldPhonePreference.copy(modePending = false, recoveryNeedsVerification = false)))
    }

    @Test fun switchingTheSelectorToComputerCannotBypassTheCurrentPhoneTransaction() {
        assertFalse(ready(binding = PhoneCommittedActivationPolicy.Binding.CURRENT))
        assertFalse(ready(binding = PhoneCommittedActivationPolicy.Binding.CURRENT,
            facts = readyHostWithOldPhonePreference.copy(modePending = false)))
        assertTrue(ready(binding = PhoneCommittedActivationPolicy.Binding.CURRENT,
            facts = readyHostWithOldPhonePreference.copy(modePending = false, recoveryNeedsVerification = false)))
    }

    @Test fun pendingOrIncompletePhoneBootstrapBlocksEveryReadinessVariant() {
        for (method in listOf(ActivationMethod.COMPUTER, ActivationMethod.WIFI)) {
            for (binding in PhoneCommittedActivationPolicy.Binding.entries) {
                assertFalse(ready(method, binding,
                    readyHostWithOldPhonePreference.copy(modePending = false, recoveryNeedsVerification = false), blocked = true))
            }
        }
    }

    private fun ready(method: ActivationMethod = ActivationMethod.COMPUTER,
        binding: PhoneCommittedActivationPolicy.Binding = PhoneCommittedActivationPolicy.Binding.OTHER,
        facts: DesktopReadinessPolicy.Facts = readyHostWithOldPhonePreference,
        blocked: Boolean = false): Boolean = DesktopActivationSetupPolicy.isReady(method, binding, facts, needs, blocked)
}
