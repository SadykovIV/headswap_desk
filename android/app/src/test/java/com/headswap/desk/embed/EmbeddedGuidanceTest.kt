package com.headswap.desk.embed

import android.os.IBinder
import com.headswap.desk.CarLifeState
import com.headswap.desk.cluster.ShellManeuverSample
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.IDexUserService
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedGuidanceTest {
    @Test fun aShizukuReadRequiresCarLifeAndTheSameLiveDisplayBeforeAndAfterBinder() {
        val owner = Any()
        val calls = Executors.newSingleThreadExecutor()
        val prior = CarLifeState.running
        var reads = 0
        var retireDuringRead = false
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, _ ->
            check(method.name == "isBinderAlive")
            true
        } as IBinder
        val remote = Proxy.newProxyInstance(IDexUserService::class.java.classLoader, arrayOf(IDexUserService::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "readShizukuGuidance" -> {
                    assertEquals(32, args.single())
                    reads++
                    if (retireDuringRead) EmbeddedSessionRegistry.deactivate(owner)
                    arrayOf("ru.yandex.yandexnavi", "Turn left", "20", "m", "en")
                }
                else -> error(method.name)
            }
        } as IDexUserService
        try {
            CarLifeState.running = false
            EmbeddedSessionRegistry.activate(owner, remote, 32, calls, DesktopModePolicy.SAMSUNG_DEX, 160, false, shizuku = true)
            assertEquals(emptyList<Int>(), EmbeddedSessionRegistry.guidanceDisplayIds())
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readShizukuGuidance(32))
            CarLifeState.running = true
            assertEquals(listOf(32), EmbeddedSessionRegistry.guidanceDisplayIds())
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readShizukuGuidance(0))
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readShizukuGuidance(31))
            assertEquals(0, reads)
            assertEquals(ShellManeuverSample.Card("ru.yandex.yandexnavi", "Turn left", "20", "m", "en"),
                EmbeddedSessionRegistry.readShizukuGuidance(32))
            retireDuringRead = true
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readShizukuGuidance(32))
            assertEquals(2, reads)
            EmbeddedSessionRegistry.activate(owner, remote, 32, calls, DesktopModePolicy.DRIVEDECK, 160, false)
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readShizukuGuidance(32))
            assertEquals(2, reads)
        } finally {
            EmbeddedSessionRegistry.deactivate(owner)
            CarLifeState.running = prior
            calls.shutdownNow()
        }
    }
}
