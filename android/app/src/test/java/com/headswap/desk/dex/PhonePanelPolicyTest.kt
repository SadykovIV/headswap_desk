package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class PhonePanelPolicyTest {
    @Test fun honorCurtainDefaultDoesNotOverrideEitherExplicitChoice() {
        assertTrue(PhonePanelPolicy.needsPhoneCurtain("Honor", "ABR-LX1"))
        assertFalse(PhonePanelPolicy.needsPhoneCurtain("Honor", "ABR-LX2"))
        assertEquals(PhoneIdleMode.CURTAIN, PhonePanelPolicy.screenMode(null, null, true))
        assertEquals(PhoneIdleMode.CURTAIN, PhonePanelPolicy.screenMode(null, "PANEL", true))
        assertEquals(PhoneIdleMode.PANEL, PhonePanelPolicy.screenMode("PANEL", "CURTAIN", true))
        assertEquals(PhoneIdleMode.CURTAIN, PhonePanelPolicy.screenMode("CURTAIN", "PANEL", false))
    }

    @Test fun otherPhonesRetainClassicModeAndExistingCurtainSelection() {
        assertEquals(PhoneIdleMode.PANEL, PhonePanelPolicy.screenMode(null, null, false))
        assertEquals(PhoneIdleMode.PANEL, PhonePanelPolicy.screenMode(null, "DISABLED", false))
        assertEquals(PhoneIdleMode.CURTAIN, PhonePanelPolicy.screenMode(null, "CURTAIN", false))
    }
}
