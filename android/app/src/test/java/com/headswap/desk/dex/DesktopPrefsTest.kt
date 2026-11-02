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
    @Test fun upgradeRetainsOldGeometryAndDpiButDesktopFpsIsIndependentFromAaDefault() {
        val context = MemoryPrefs()
        context.values["shizuku_density"] = 128
        val prefs = DexPrefs(context)
        assertEquals(DesktopResolution.R1600_900, prefs.desktopResolution)
        assertEquals(128, prefs.desktopDensity)
        assertEquals(FpsSetting.FPS30, prefs.desktopFps)
        assertEquals(FpsSetting.FPS15, FpsSetting.DEFAULT)
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
        assertEquals(160, restored.shizukuDensity)
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
            assertEquals(1600, prefs.customDesktopWidth)
            assertEquals(900, prefs.customDesktopHeight)
        }
    }
    @Test fun freshInstallDefaultsToHeadSwapButBothLegacyChoicesSurvive() {
        val context = MemoryPrefs()
        assertEquals(DesktopAccessMode.COMPUTER, DexPrefs(context).accessMode)
        context.values["use_shizuku"] = true
        assertEquals(DesktopAccessMode.SHIZUKU, DexPrefs(context).accessMode)
        context.values["use_shizuku"] = false
        assertEquals(DesktopAccessMode.LEGACY_ADB, DexPrefs(context).accessMode)
        context.values["access_backend"] = "SHIZUKU"
        assertEquals(DesktopAccessMode.SHIZUKU, DexPrefs(context).accessMode)
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
