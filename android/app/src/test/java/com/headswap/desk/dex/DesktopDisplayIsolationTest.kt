package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopDisplayIsolationTest {
    @Test fun independentLockRequestsBothFlagsOnlyWhenPermissionWasConfirmed() {
        val extra = DesktopModePolicy.VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP or DesktopModePolicy.VIRTUAL_DISPLAY_ALWAYS_UNLOCKED
        for (sdk in 33..37) for (mode in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
            assertEquals(0, DesktopModePolicy.displayFlags(mode, sdk) and extra)
            assertEquals(extra, DesktopModePolicy.displayFlags(mode, sdk, true) and extra)
            assertEquals(DesktopModePolicy.displayFlags(mode, sdk), DesktopModePolicy.displayFlags(mode, sdk, true) and extra.inv())
        }
        assertEquals(DesktopModePolicy.displayFlags(DesktopModePolicy.SAMSUNG_DEX, 37),
            DesktopModePolicy.displayFlags(DesktopModePolicy.SAMSUNG_DEX, 37, true))
    }

    @Test fun returnedDefaultGroupOverridesMisleadingRequestedFlags() {
        val flags = DesktopDisplayIsolation.OWN_GROUP or DesktopDisplayIsolation.ALWAYS_UNLOCKED
        assertTrue(DesktopDisplayIsolation.independent(flags, 2))
        assertFalse(DesktopDisplayIsolation.independent(flags, 0))
        assertFalse(DesktopDisplayIsolation.independent(flags, -1))
        assertFalse(DesktopDisplayIsolation.independent(DesktopDisplayIsolation.OWN_GROUP, 2))
    }
}
