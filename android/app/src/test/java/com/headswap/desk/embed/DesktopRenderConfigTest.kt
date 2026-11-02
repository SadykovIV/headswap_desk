package com.headswap.desk.embed

import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.IDexUserService
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopRenderConfigTest {
    @Test fun snapshotBelongsOnlyToTheCurrentDisplayAndProfile() {
        val owner = Any()
        val worker = Executors.newSingleThreadExecutor()
        val remote = IDexUserService.Default()
        try {
            EmbeddedSessionRegistry.activate(owner, remote, 4, worker, DesktopModePolicy.DRIVEDECK, 200, true)
            EmbeddedSessionRegistry.renderConfig(4, false)[0] = 320
            assertArrayEquals(intArrayOf(200, 1), EmbeddedSessionRegistry.renderConfig(4, false))
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(0, false) }
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(4, true) }
            EmbeddedSessionRegistry.deactivate(owner)
            assertThrows(IllegalStateException::class.java) { EmbeddedSessionRegistry.renderConfig(4, false) }

            EmbeddedSessionRegistry.activate(owner, remote, 7, worker, DesktopModePolicy.ANDROID_DESKTOP, 96, false)
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(4, true) }
            assertThrows(IllegalArgumentException::class.java) { EmbeddedSessionRegistry.renderConfig(7, false) }
            assertArrayEquals(intArrayOf(96, 0), EmbeddedSessionRegistry.renderConfig(7, true))
        } finally {
            EmbeddedSessionRegistry.deactivate(owner)
            worker.shutdownNow()
        }
    }
}
