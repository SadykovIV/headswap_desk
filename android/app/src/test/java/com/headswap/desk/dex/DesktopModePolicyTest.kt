package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopModePolicyTest {
    private val app = "com.headswap.desk"
    private val genericModes = listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)

    @Test fun genericDisplayDoesNotRequestSamsungDesktopOrAutoMirroring() {
        for (mode in genericModes) {
            val flags = DesktopModePolicy.displayFlags(mode, 33)
            assertEquals(1355, flags)
            assertEquals(0, flags and (131072 or 16 or 512))
            assertTrue(flags and 8 != 0) // Blank when there is no own content, never mirror the phone.
            assertTrue(flags and 1024 != 0) // Trusted display still requires the shell helper.
            assertTrue(flags and 256 != 0) // Destroy display-owned tasks on close.
        }
    }

    @Test fun samsungDisplayKeepsItsVerifiedFlagSet() {
        for (sdk in listOf(33, 34, 37)) {
            assertEquals(132419, DesktopModePolicy.displayFlags(DesktopModePolicy.SAMSUNG_DEX, sdk))
        }
    }

    @Test fun modernGenericDisplaysKeepFocusWithoutDisablingImeOrChangingTrustScope() {
        for (mode in genericModes) for (sdk in listOf(34, 35, 36, 37)) {
            val flags = DesktopModePolicy.displayFlags(mode, sdk)
            val legacy = DesktopModePolicy.displayFlags(mode, 33)
            assertEquals(1 shl 14, flags xor legacy) // Only OWN_FOCUS changes.
            assertEquals(0, flags and (1 shl 16)) // STEAL_TOP_FOCUS_DISABLED would disable IME.
            assertTrue(flags and 1024 != 0)
            assertEquals(0, flags and (16 or 512 or 4096 or 131072))
        }
    }

    @Test fun genericHomeExplicitlyTargetsOwnedDisplayAndOwnFullscreenActivity() {
        val command = DesktopModePolicy.homeCommand(DesktopModePolicy.ANDROID_DESKTOP, 586, app).toList()
        assertEquals(listOf("/system/bin/am", "start", "--display", "586"), command.take(4))
        assertEquals("1", command[command.indexOf("--windowingMode") + 1])
        assertEquals("$app/${com.headswap.desk.desktop.AndroidDesktopActivity::class.java.name}", command.last())
        assertFalse(command.any { it.contains("samsung", true) || it.contains("com.sec.") || it.contains("SECONDARY_HOME") })
        assertFalse(command.any { it == "android.intent.category.HOME" })
        assertEquals("0x34000000", command[command.indexOf("-f") + 1])
        assertFalse(command.contains("--activity-new-task"))
    }

    @Test fun samsungHomeLaunchIsUnchangedAndStillTargetsOwnedDisplay() {
        assertArrayEquals(arrayOf("/system/bin/am", "start", "--display", "586",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.SECONDARY_HOME",
            "-n", "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher"),
            DesktopModePolicy.homeCommand(DesktopModePolicy.SAMSUNG_DEX, 586, app))
    }

    @Test fun driveDeckLaunchUsesAdaptedPackageAndOriginalClassWithoutPhoneHomeResolution() {
        val command = DesktopModePolicy.homeCommand(DesktopModePolicy.DRIVEDECK, 586, app).toList()
        assertEquals(listOf("/system/bin/am", "start", "--display", "586",
            "--windowingMode", "1", "-f", "0x34000000",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-n", "ru.drivedeck.headswap/ru.drivedeck.launcher.MainActivity"), command)
        assertFalse(command.contains("android.intent.category.HOME"))
        assertFalse(command.contains("android.intent.category.SECONDARY_HOME"))
        assertFalse(command.contains("set-home-activity"))
    }

    @Test fun rejectsPrimaryOrInvalidDisplayBeforeConstructingEitherLaunch() {
        for (mode in genericModes + DesktopModePolicy.SAMSUNG_DEX) {
            for (id in listOf(0, -1)) {
                try { DesktopModePolicy.homeCommand(mode, id, app); fail("Accepted display $id") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun invalidModeCannotFallBackToSamsungOrPhoneHome() {
        for (mode in listOf(-1, 3, Int.MAX_VALUE)) {
            for (sdk in listOf(33, 34, 37)) {
                try { DesktopModePolicy.displayFlags(mode, sdk); fail("Accepted mode $mode") }
                catch (_: IllegalArgumentException) { }
            }
            try { DesktopModePolicy.homeCommand(mode, 586, app); fail("Accepted mode $mode") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun onlyGenericDesktopConsumesHomeAndRecentsBeforeInputInjection() {
        for (mode in genericModes) {
            assertTrue(DesktopModePolicy.consumesNavigationKey(mode, 3))
            assertTrue(DesktopModePolicy.consumesNavigationKey(mode, 187))
            assertFalse(DesktopModePolicy.consumesNavigationKey(mode, 4)) // Back stays on the current app.
        }
        assertFalse(DesktopModePolicy.consumesNavigationKey(DesktopModePolicy.SAMSUNG_DEX, 3))
        assertFalse(DesktopModePolicy.consumesNavigationKey(DesktopModePolicy.SAMSUNG_DEX, 187))
    }

    @Test fun genericNativeGeometryDoesNotRequireSamsungCapability() {
        for (mode in genericModes) {
            DexUserServicePolicy.validateGeometry(586, 1600, 900, 1600, 900, 160, 128,
                desktopMode = mode)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun genericDisplayStillRejectsUntrustedSurface() {
        DexUserServicePolicy.validateGeometry(586, 1600, 900, 1600, 900, 160, 8,
            desktopMode = DesktopModePolicy.ANDROID_DESKTOP)
    }

    @Test(expected = IllegalStateException::class)
    fun genericDisplayStillRejectsPrimaryDisplay() {
        DexUserServicePolicy.validateGeometry(0, 1600, 900, 1600, 900, 160, 128,
            desktopMode = DesktopModePolicy.ANDROID_DESKTOP)
    }

    @Test(expected = IllegalStateException::class)
    fun genericDisplayStillRejectsHiddenScaling() {
        DexUserServicePolicy.validateGeometry(586, 1600, 900, 1920, 1080, 160, 128,
            desktopMode = DesktopModePolicy.ANDROID_DESKTOP)
    }
}
