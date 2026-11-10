package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PairingNotificationPolicyTest {
    @Test fun appDenialOverridesIndividualChannelSettings() {
        assertEquals(PairingNotificationBlock.APP, PairingNotificationPolicy.block(false, 2, 4, true))
        assertEquals(PairingNotificationBlock.APP, PairingNotificationPolicy.block(false, 0, 0, false))
    }
    @Test fun blockedPairingChannelCannotStartAnInvisibleCodePrompt() {
        assertEquals(PairingNotificationBlock.CODE_CHANNEL, PairingNotificationPolicy.block(true, 2, 0, true))
        // Existing-key startup can proceed, but a later authorization rejection must
        // reevaluate with needsCode=true and use in-app input for this blocked channel.
        assertNull(PairingNotificationPolicy.block(true, 2, 0, false))
    }
    @Test fun progressChannelIsRequiredForStatusAndCancellation() {
        assertEquals(PairingNotificationBlock.PROGRESS_CHANNEL, PairingNotificationPolicy.block(true, 0, 4, true))
        assertEquals(PairingNotificationBlock.PROGRESS_CHANNEL, PairingNotificationPolicy.block(true, 0, 4, false))
    }
    @Test fun freshInstallAndEnabledChannelsMayProceed() {
        assertNull(PairingNotificationPolicy.block(true, null, null, true))
        assertNull(PairingNotificationPolicy.block(true, 2, 4, true))
    }
}
