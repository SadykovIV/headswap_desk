package com.headswap.desk.embed

import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.IDexUserService
import android.os.IBinder
import java.lang.reflect.Proxy
import com.headswap.desk.shared.DesktopRenderPolicy
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopRenderConfigTest {
    private fun liveService(): IDexUserService = object : IDexUserService.Default() {
        private val lifetime = Proxy.newProxyInstance(IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)) { _, method, _ ->
            when (method.name) {
                "isBinderAlive" -> true
                else -> error("Unexpected Binder method: ${method.name}")
            }
        } as IBinder
        override fun asBinder(): IBinder = lifetime
    }

    @Test fun snapshotBelongsOnlyToTheCurrentDisplayAndProfile() {
        val owner = Any()
        val worker = Executors.newSingleThreadExecutor()
        val remote = liveService()
        try {
            EmbeddedSessionRegistry.activate(owner, remote, 4, worker, DesktopModePolicy.DRIVEDECK, 200, false)
            EmbeddedSessionRegistry.renderConfig(4, false)[0] = 320
            assertArrayEquals(intArrayOf(200, 0), EmbeddedSessionRegistry.renderConfig(4, false))
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(0, false) }
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(4, true) }
            EmbeddedSessionRegistry.deactivate(owner)
            assertThrows(IllegalStateException::class.java) { EmbeddedSessionRegistry.renderConfig(4, false) }

            EmbeddedSessionRegistry.activate(owner, remote, 7, worker, DesktopModePolicy.ANDROID_DESKTOP, 96, true)
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(4, true) }
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(7, false) }
            assertArrayEquals(intArrayOf(96, 1), EmbeddedSessionRegistry.renderConfig(7, true))
        } finally {
            EmbeddedSessionRegistry.deactivate(owner)
            worker.shutdownNow()
        }
    }

    @Test fun bothEmbeddedDesktopsRenderYandexAtHigherResolutionOnlyWhenSelected() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            for (mode in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
                for (compact in listOf(true, false)) {
                    val owner = Any()
                    EmbeddedSessionRegistry.activate(owner, liveService(), 42, worker, mode, 160, compact)
                    try {
                        val config = EmbeddedSessionRegistry.renderConfig(42, ownDesktop = mode == DesktopModePolicy.ANDROID_DESKTOP)
                        for (pkg in listOf("ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "example.music")) {
                            val buffer = DesktopRenderPolicy.geometry(pkg, 576, 684, config[0], config[1] == 1)
                            val expected = if (compact && pkg != "example.music") intArrayOf(1152, 1368, 320)
                                else intArrayOf(576, 684, 160)
                            assertArrayEquals(expected, buffer)
                        }
                    } finally { EmbeddedSessionRegistry.deactivate(owner) }
                }
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun exactSelectionsApplyToMapsOrAnyOtherAppAcrossSplitAndFullscreen() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            for (mode in listOf(DesktopModePolicy.DRIVEDECK, DesktopModePolicy.ANDROID_DESKTOP)) {
                val owner = Any()
                val own = mode == DesktopModePolicy.ANDROID_DESKTOP
                val selected = mutableSetOf("ru.yandex.yandexnavi", "example.music")
                EmbeddedSessionRegistry.activate(owner, liveService(), 42, worker, mode, 160, true, selected)
                try {
                    selected.clear() // Edits belong to the next connection.
                    fun render(pkg: String): IntArray {
                        val cfg = EmbeddedSessionRegistry.renderConfig(42, own, pkg)
                        return DesktopRenderPolicy.geometry(600, 480, cfg[0], cfg[1] == 1)
                    }
                    for (pkg in listOf("ru.yandex.yandexnavi", "example.music"))
                        assertArrayEquals(intArrayOf(1200, 960, 320), render(pkg))
                    for (pkg in listOf("ru.yandex.yandexmaps", "example.music.player", "example.other"))
                        assertArrayEquals(intArrayOf(600, 480, 160), render(pkg))
                    // Tx11 has just one boolean for both legacy packages.
                    assertArrayEquals(intArrayOf(160, 0), EmbeddedSessionRegistry.renderConfig(42, own))
                    assertThrows(IllegalArgumentException::class.java) { render("invalid/name") }
                    assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(41, own, "example.music") }
                    assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(42, !own, "example.music") }
                } finally { EmbeddedSessionRegistry.deactivate(owner) }
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun emptyOrDisabledSelectionDoesNotApplyCorrection() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            for ((enabled, selected) in listOf(true to emptySet<String>(), false to DesktopRenderPolicy.DEFAULT_PACKAGES)) {
                val owner = Any()
                EmbeddedSessionRegistry.activate(owner, liveService(), 42, worker,
                    DesktopModePolicy.DRIVEDECK, 128, enabled, selected)
                try {
                    for (pkg in listOf(null, "ru.yandex.yandexmaps", "example.music"))
                        assertArrayEquals(intArrayOf(128, 0), EmbeddedSessionRegistry.renderConfig(42, false, pkg))
                } finally { EmbeddedSessionRegistry.deactivate(owner) }
            }
        } finally { worker.shutdownNow() }
    }
}
