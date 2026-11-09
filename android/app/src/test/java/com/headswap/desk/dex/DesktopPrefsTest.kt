package com.headswap.desk.dex

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.headswap.desk.aa.DesktopResolution
import com.headswap.desk.aa.FpsSetting
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DesktopPrefsTest {
    private class MemoryPrefs : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        var commits = 0
        var commitSucceeds = true
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "apply" -> null
                method.name == "commit" -> { commits++; commitSucceeds }
                else -> error("Unexpected editor ${method.name}")
            }
        } as SharedPreferences.Editor
        private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getInt", "getBoolean" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                else -> error("Unexpected preferences ${method.name}")
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    @Test fun newTransportControlsHaveSafeDefaultsAndKeepExplicitChoices() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        assertEquals(8, prefs.desktopBitrateMbps)
        assertTrue(prefs.autoMusicPlayback)
        assertFalse(prefs.huNotificationsEnabled)
        assertFalse(prefs.initialSetupCompleted)
        prefs.desktopBitrateMbps = 16
        prefs.autoMusicPlayback = false
        prefs.huNotificationsEnabled = true
        prefs.initialSetupCompleted = true
        val restored = DexPrefs(context)
        assertEquals(16, restored.desktopBitrateMbps)
        assertFalse(restored.autoMusicPlayback)
        assertTrue(restored.huNotificationsEnabled)
        assertTrue(restored.initialSetupCompleted)
        assertThrows(IllegalArgumentException::class.java) { prefs.desktopBitrateMbps = 0 }
        assertThrows(IllegalArgumentException::class.java) { prefs.desktopBitrateMbps = 21 }
    }
    @Test fun customBitrateAndGeometryRestoreWithoutOverwritingDpiOrLegacyFps() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.desktopBitrateMbps = 7 // A manually entered value outside the presets.
        prefs.setCustomDesktopSize(1440, 810)
        val restored = DexPrefs(context)
        assertEquals(7, restored.desktopBitrateMbps)
        assertEquals("1440 × 810", restored.desktopResolutionLabel)
        assertEquals(1440, restored.desktopVideoSelection().initialGeometry().outW)
        assertEquals(810, restored.desktopVideoSelection().initialGeometry().outH)
        assertEquals(128, restored.desktopDensity)
        assertEquals(FpsSetting.FPS15, restored.desktopFps)
        assertThrows(IllegalArgumentException::class.java) { restored.desktopBitrateMbps = Int.MAX_VALUE }
        assertThrows(IllegalArgumentException::class.java) { restored.setCustomDesktopSize(1441, 810) }
        assertEquals(7, restored.desktopBitrateMbps)
        assertEquals("1440 × 810", restored.desktopResolutionLabel)
    }
    @Test fun pairingAloneDoesNotCompleteAccessSetupOrClaimPermissions() {
        val context = MemoryPrefs()
        context.values["has_paired"] = true
        assertFalse(DexPrefs(context).initialSetupCompleted)
        assertFalse(DexPrefs(context).huNotificationsEnabled)
        context.values["desktop_bitrate_mbps"] = -50
        assertEquals(1, DexPrefs(context).desktopBitrateMbps)
        context.values["desktop_bitrate_mbps"] = 999
        assertEquals(20, DexPrefs(context).desktopBitrateMbps)
    }
    @Test fun freshDesktopUsesFixed720pAt15FpsAnd128Dpi() {
        val prefs = DexPrefs(MemoryPrefs())
        val video = prefs.desktopVideoSelection()
        assertEquals(DesktopResolution.R1280_720, prefs.desktopResolution)
        assertEquals(1280, video.initialGeometry().outW)
        assertEquals(720, video.initialGeometry().outH)
        assertEquals(128, prefs.desktopDensity)
        assertEquals(FpsSetting.FPS15, prefs.desktopFps)
    }
    @Test fun legacyAdbAndDesktopUseTheSameDpiEvenWhenOldPreferencesDisagree() {
        val context = MemoryPrefs()
        context.values["density"] = 144
        val prefs = DexPrefs(context)
        val legacy720 = com.headswap.desk.aa.VideoMode.R720
        assertEquals(144, prefs.desktopVideoSelection().densityFor(legacy720))
        context.values["shizuku_density"] = 128
        assertEquals(128, prefs.desktopVideoSelection().densityFor(legacy720))
        prefs.desktopDensity = 200
        assertEquals(200, prefs.desktopVideoSelection().densityFor(legacy720))
        prefs.desktopDensity = 0
        assertEquals(128, prefs.desktopVideoSelection().densityFor(legacy720))
    }
    @Test fun desktopChoicesSurviveNewPreferenceInstancesAndDoNotOverwriteLegacySettings() {
        val context = MemoryPrefs()
        context.values["shizuku_density"] = 160
        val prefs = DexPrefs(context)
        prefs.desktopResolution = DesktopResolution.AUTO
        prefs.desktopDensity = 0
        prefs.desktopFps = FpsSetting.FPS15
        val restored = DexPrefs(context)
        assertEquals(DesktopResolution.AUTO, restored.desktopResolution)
        assertEquals(0, restored.desktopDensity)
        assertEquals(FpsSetting.FPS15, restored.desktopFps)
        assertEquals(160, restored.shizukuDensity)
        restored.desktopDensity = 200
        assertEquals(200, DexPrefs(context).desktopDensity)
    }
    @Test fun customGeometryAndDpiAndSixtyFpsSurviveRestartWithoutChangingLegacyOrDefaults() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.setCustomDesktopSize(1920, 1080)
        prefs.desktopDensity = 96
        prefs.desktopFps = FpsSetting.FPS60
        val restored = DexPrefs(context)
        assertEquals(DesktopResolution.CUSTOM, restored.desktopResolution)
        assertEquals(1920, restored.customDesktopWidth)
        assertEquals(1080, restored.customDesktopHeight)
        assertEquals("1920 × 1080", restored.desktopResolutionLabel)
        assertEquals(96, restored.desktopDensity)
        assertEquals(FpsSetting.FPS60, restored.desktopFps)
        assertEquals(128, restored.shizukuDensity)
        val snapshot = restored.desktopVideoSelection()
        restored.setCustomDesktopSize(1024, 768)
        restored.desktopDensity = 640
        assertEquals(1920, snapshot.resolve().outW)
        assertEquals(96, snapshot.densityOverride)
        assertEquals(640, DexPrefs(context).desktopDensity)
    }
    @Test fun rejectedDimensionsDoNotPartiallySaveAndCorruptStoredCustomFallsBackSafely() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.setCustomDesktopSize(1280, 720)
        assertThrows(IllegalArgumentException::class.java) { prefs.setCustomDesktopSize(1921, 1080) }
        assertThrows(IllegalArgumentException::class.java) { prefs.desktopDensity = 641 }
        assertEquals(1280, prefs.customDesktopWidth)
        assertEquals(720, prefs.customDesktopHeight)
        for (bad in listOf("1921x1080", "1920x1080x1", "bad", "999999999999x720", "640x900")) {
            context.values["desktop_custom_size"] = bad
            assertEquals(1280, prefs.customDesktopWidth)
            assertEquals(720, prefs.customDesktopHeight)
        }
    }
    @Test fun migrateOldBackendOnlyForProfilesThatSupportIt() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        assertEquals(DesktopAccessMode.COMPUTER, prefs.accessMode)
        prefs.desktopMode = DesktopModePolicy.SAMSUNG_DEX
        assertEquals(DesktopAccessMode.SHIZUKU, prefs.accessMode)
        context.values["use_shizuku"] = false
        assertEquals(DesktopAccessMode.LEGACY_ADB, prefs.accessMode)
        prefs.desktopMode = DesktopModePolicy.DRIVEDECK
        assertEquals(DesktopAccessMode.COMPUTER, prefs.accessMode)
        context.values["access_backend"] = "SHIZUKU"
        assertEquals(DesktopAccessMode.SHIZUKU, prefs.accessMode)
        context.values["access_backend"] = "COMPUTER"
        prefs.desktopMode = DesktopModePolicy.SAMSUNG_DEX
        assertEquals(DesktopAccessMode.SHIZUKU, prefs.accessMode)
    }

    @Test fun samsungAndAndroidDesktopsKeepSeparateChoicesAcrossRestarts() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.desktopMode = DesktopModePolicy.SAMSUNG_DEX
        prefs.useShizuku = false
        assertEquals(DesktopAccessMode.LEGACY_ADB, prefs.accessMode)
        assertFalse(prefs.persistActivationSelection(audio = true, screen = true))
        assertThrows(IllegalArgumentException::class.java) { prefs.useDelegated = true }
        prefs.desktopMode = DesktopModePolicy.ANDROID_DESKTOP
        assertThrows(IllegalArgumentException::class.java) { prefs.accessMode = DesktopAccessMode.LEGACY_ADB }
        prefs.useShizuku = true
        prefs.desktopMode = DesktopModePolicy.DRIVEDECK
        assertEquals(DesktopAccessMode.SHIZUKU, DexPrefs(context).accessMode)
        prefs.useShizuku = false
        assertTrue(prefs.persistActivationSelection(audio = true, screen = true))
        prefs.desktopMode = DesktopModePolicy.SAMSUNG_DEX
        assertEquals(DesktopAccessMode.LEGACY_ADB, DexPrefs(context).accessMode)
        prefs.desktopMode = DesktopModePolicy.ANDROID_DESKTOP
        assertEquals(DesktopAccessMode.COMPUTER, DexPrefs(context).accessMode)
    }

    @Test fun targetProfileBackendCanBeCheckedBeforeAnyPreferenceMutation() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.desktopMode = DesktopModePolicy.SAMSUNG_DEX
        prefs.accessMode = DesktopAccessMode.LEGACY_ADB
        prefs.desktopMode = DesktopModePolicy.ANDROID_DESKTOP
        val before = context.values.toMap()
        assertEquals(DesktopAccessMode.COMPUTER, prefs.accessModeFor(DesktopModePolicy.DRIVEDECK))
        assertEquals(DesktopAccessMode.LEGACY_ADB, prefs.accessModeFor(DesktopModePolicy.SAMSUNG_DEX))
        assertEquals(before, context.values)
        assertEquals(DesktopModePolicy.ANDROID_DESKTOP, prefs.desktopMode)
    }

    @Test fun firstActivationOffersAudioAndPanelAndPreservesIndependentOptOutsAfterUpdate() {
        val context = MemoryPrefs()
        val fresh = DexPrefs(context)
        assertTrue(fresh.usbPlaybackAudio)
        assertTrue(fresh.desktopPanelControl)
        assertTrue(fresh.persistActivationSelection(audio = true, screen = true))

        fresh.usbPlaybackAudio = false
        val updated = DexPrefs(context)
        assertFalse(updated.usbPlaybackAudio)
        assertTrue(updated.desktopPanelControl)
        updated.desktopPanelControl = false
        assertFalse(DexPrefs(context).desktopPanelControl)
        assertTrue(DexPrefs(context).persistActivationSelection(audio = false, screen = false))
    }
    @Test fun phoneActivationCommitsOnlyTheAlreadySelectedOptionsAndReportsFailure() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.desktopPanelControl = false
        assertFalse(prefs.persistActivationSelection(audio = false, screen = false))
        assertFalse(prefs.persistActivationSelection(audio = true, screen = true))
        assertEquals(0, context.commits)
        assertFalse(prefs.desktopPanelControl)
        assertTrue(prefs.persistActivationSelection(audio = true, screen = false))
        assertEquals(1, context.commits)
        context.commitSucceeds = false
        assertFalse(prefs.persistActivationSelection(audio = true, screen = false))
        prefs.useShizuku = true
        val before = context.commits
        assertFalse(prefs.persistActivationSelection(audio = true, screen = false))
        assertEquals(before, context.commits)
        assertTrue(prefs.useShizuku)
    }
}
