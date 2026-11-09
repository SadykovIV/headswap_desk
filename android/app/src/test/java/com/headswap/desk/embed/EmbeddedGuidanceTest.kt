package com.headswap.desk.embed

import android.os.IBinder
import android.graphics.SurfaceTexture
import android.view.Surface
import com.headswap.desk.CarLifeState
import com.headswap.desk.cluster.ShellManeuverSample
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.IDexUserService
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedGuidanceTest {
    @Test fun onlyTheLaunchedMapOrItsNavigatorHandoffCanSupplyManeuvers() {
        assertEquals(true, YandexGuidanceScope.allows("ru.yandex.yandexmaps", "ru.yandex.yandexnavi"))
        assertEquals(false, YandexGuidanceScope.allows("other.app", "ru.yandex.yandexnavi"))
        assertEquals(false, YandexGuidanceScope.allows("ru.yandex.yandexnavi", "ru.yandex.yandexmaps"))
        assertEquals(false, YandexGuidanceScope.allows(null, "other.app"))
    }

    @Test fun aCoveredDashboardTargetCannotSupplyANewOrInFlightManeuver() {
        val owner = Any()
        val calls = Executors.newSingleThreadExecutor()
        val visibility = GuidancePaneVisibility()
        val prior = CarLifeState.running
        var releases = 0
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, m, args ->
            when (m.name) {
                "isBinderAlive", "unlinkToDeath" -> true
                "linkToDeath" -> null
                "equals" -> proxy === args.single()
                else -> error(m.name)
            }
        } as IBinder
        val remote = Proxy.newProxyInstance(IDexUserService::class.java.classLoader, arrayOf(IDexUserService::class.java)) { _, m, _ ->
            when (m.name) {
                "asBinder" -> binder
                "createChildDisplay" -> 42
                "launchChildApp" -> null
                "closeChildDisplay" -> { releases++; null }
                else -> error(m.name)
            }
        } as IDexUserService
        val surface = object : Surface(SurfaceTexture(false)) { override fun isValid() = true }
        val maneuver = ShellManeuverSample.Card("ru.yandex.yandexnavi", "Right", "100", "m", "en")
        try {
            CarLifeState.running = true
            EmbeddedSessionRegistry.activate(owner, remote, 32, calls, DesktopModePolicy.ANDROID_DESKTOP, 160, false)
            val handle = EmbeddedSessionRegistry.create(111, surface, binder, 32, 800, 480, 160,
                ownDesktop = true, guidanceVisibility = visibility)
            EmbeddedSessionRegistry.launch(111, handle, maneuver.packageName, "ru.yandex.yandexnavi.MapActivity")
            assertEquals(listOf(42), EmbeddedSessionRegistry.guidanceDisplayIds())
            visibility.show(false)
            assertEquals(emptyList<Int>(), EmbeddedSessionRegistry.guidanceDisplayIds())
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readDelegatedGuidance(42) { error("covered pane") })
            visibility.show(true)
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readDelegatedGuidance(42) {
                visibility.show(false)
                visibility.show(true)
                maneuver
            })
            assertEquals(maneuver, EmbeddedSessionRegistry.readDelegatedGuidance(42) { maneuver })
            var captured: (() -> Unit)? = null
            assertEquals(maneuver, EmbeddedSessionRegistry.readDelegatedGuidanceScoped(42) { requireCurrent ->
                captured = requireCurrent
                requireCurrent()
                maneuver
            })
            visibility.show(false)
            visibility.show(true)
            assertEquals(true, runCatching { captured!!() }.isFailure)
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readDelegatedGuidanceScoped(42) { requireCurrent ->
                visibility.show(false)
                requireCurrent() // The old pane cannot make a request, even before the outer result check.
                error("retired capability was accepted")
            })
            visibility.show(true)

            visibility.close()
            visibility.show(true) // late callback after the Activity was destroyed
            assertEquals(emptyList<Int>(), EmbeddedSessionRegistry.guidanceDisplayIds())
            EmbeddedSessionRegistry.release(111, handle)
            EmbeddedSessionRegistry.release(111, handle)
            assertEquals(1, releases)
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readDelegatedGuidance(42) { error("retired pane") })
        } finally {
            EmbeddedSessionRegistry.deactivate(owner)
            CarLifeState.running = prior
            calls.shutdownNow()
        }
    }

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
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readDelegatedGuidance(0) { error("display 0") })
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readDelegatedGuidance(31) { error("wrong display") })
            assertEquals(ShellManeuverSample.VisibleWithoutHint, EmbeddedSessionRegistry.readDelegatedGuidance(32) {
                ShellManeuverSample.VisibleWithoutHint
            })
            assertEquals(ShellManeuverSample.Unavailable, EmbeddedSessionRegistry.readDelegatedGuidance(32) {
                EmbeddedSessionRegistry.deactivate(owner)
                ShellManeuverSample.VisibleWithoutHint
            })
        } finally {
            EmbeddedSessionRegistry.deactivate(owner)
            CarLifeState.running = prior
            calls.shutdownNow()
        }
    }
}
