package com.headswap.desk.desktop

import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.IDexUserService
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.shared.DesktopRenderPolicy
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DesktopRenderPolicyTest {
    @Test fun mapsAndOtherAppsUseTheirViewportAndExactSessionDensity() {
        val owner = Any()
        val worker = Executors.newSingleThreadExecutor()
        val remote = IDexUserService.Default()
        try {
            for (mode in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
                for (dpi in listOf(96, 128, 160, 200, 320, 640)) {
                    EmbeddedSessionRegistry.activate(owner, remote, 4, worker, mode, dpi)
                    val policy = EmbeddedSessionRegistry.renderConfig(4, mode == DesktopModePolicy.ANDROID_DESKTOP)
                    assertArrayEquals(intArrayOf(dpi, 0), policy)
                    // Android Desktop and DriveDeck code303 use this same geometry contract.
                    for (pkg in listOf("ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.yandex.music", "example.other")) {
                        assertArrayEquals(intArrayOf(705, 541, dpi),
                            DesktopRenderPolicy.geometry(pkg, 705, 541, policy[0], policy[1] == 1))
                    }
                }
            }
        } finally {
            EmbeddedSessionRegistry.deactivate(owner)
            worker.shutdownNow()
        }
    }
}
