package com.headswap.desk.dex

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopAccessModeTest {
    @Test fun newInstallationOffersHeadSwapActivationWithoutShizuku() {
        assertEquals(DesktopAccessMode.COMPUTER, DesktopAccessMode.restore(null, null))
    }
    @Test fun explicitChoicesWithoutALegacyFlagArePreserved() {
        DesktopAccessMode.entries.forEach { mode -> assertEquals(mode, DesktopAccessMode.restore(mode.name, null)) }
        // A damaged existing choice is not treated as a new installation.
        assertEquals(DesktopAccessMode.SHIZUKU, DesktopAccessMode.restore("UNSUPPORTED", null))
    }
    @Test fun upgradingShizukuInstallationKeepsItsBackend() {
        assertEquals(DesktopAccessMode.SHIZUKU, DesktopAccessMode.restore(null, true))
    }
    @Test fun upgradingPairedLegacyInstallationDoesNotActivateComputerAccess() {
        assertEquals(DesktopAccessMode.LEGACY_ADB, DesktopAccessMode.restore(null, false))
    }
    @Test fun explicitComputerSelectionSurvivesOldPreferenceValue() {
        assertEquals(DesktopAccessMode.COMPUTER, DesktopAccessMode.restore("COMPUTER", false))
    }
    @Test fun unknownSelectionFallsBackToPreviouslySelectedBackend() {
        assertEquals(DesktopAccessMode.LEGACY_ADB, DesktopAccessMode.restore("UNSUPPORTED", false))
        assertEquals(DesktopAccessMode.SHIZUKU, DesktopAccessMode.restore("", true))
    }
}
