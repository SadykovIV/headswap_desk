package com.headswap.desk.embed

import android.graphics.SurfaceTexture
import android.os.IBinder
import android.view.Surface
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.IDexUserService
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

/** A dead intermediate producer must not authorize disposal while its native owner still lives. */
class EmbeddedNativeLifetimeTest {
    private class Life {
        var alive = true
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "linkToDeath" -> null
                "unlinkToDeath" -> true
                "equals" -> proxy === args?.single()
                "hashCode" -> System.identityHashCode(proxy)
                else -> error(method.name)
            }
        } as IBinder
    }

    @Test fun helperDeathRetainsConsumerUntilNativeHostDeath() {
        val producer = Life()
        val native = Life()
        val consumer = Life()
        val owner = Any()
        val calls = Executors.newSingleThreadExecutor()
        val remote = object : IDexUserService.Default() {
            override fun asBinder() = producer.binder
            override fun createChildDisplay(surface: Surface, token: IBinder, width: Int, height: Int, density: Int) = 42
        }
        val surface = object : Surface(SurfaceTexture(false)) { override fun isValid() = true }
        try {
            EmbeddedSessionRegistry.activate(owner, remote, 9, calls, DesktopModePolicy.DRIVEDECK,
                128, false, nativeLifetime = native.binder)
            val handle = EmbeddedSessionRegistry.create(111, surface, consumer.binder, 9, 640, 480, 128)
            producer.alive = false
            assertThrows(IllegalStateException::class.java) { EmbeddedSessionRegistry.release(111, handle) }
            // The same retained lease is still owned by the original consumer, even after failure.
            assertThrows(SecurityException::class.java) { EmbeddedSessionRegistry.release(222, handle) }
            native.alive = false
            EmbeddedSessionRegistry.release(111, handle)
            EmbeddedSessionRegistry.release(111, handle) // Native-death acknowledgement remains idempotent.
        } finally {
            native.alive = false
            EmbeddedSessionRegistry.producerStopped(owner)
            calls.shutdownNow()
        }
    }

    @Test fun explicitNativeReleaseAckDoesNotRequireKillingReusableHost() {
        val producer = Life()
        val native = Life()
        val consumer = Life()
        val owner = Any()
        val calls = Executors.newSingleThreadExecutor()
        var releases = 0
        val remote = object : IDexUserService.Default() {
            override fun asBinder() = producer.binder
            override fun createChildDisplay(surface: Surface, token: IBinder, width: Int, height: Int, density: Int) = 42
            override fun closeChildDisplay(token: IBinder) { releases++ }
        }
        val surface = object : Surface(SurfaceTexture(false)) { override fun isValid() = true }
        try {
            EmbeddedSessionRegistry.activate(owner, remote, 9, calls, DesktopModePolicy.DRIVEDECK,
                128, false, nativeLifetime = native.binder)
            val handle = EmbeddedSessionRegistry.create(111, surface, consumer.binder, 9, 640, 480, 128)
            EmbeddedSessionRegistry.release(111, handle)
            assertEquals(1, releases)
            assertTrue(native.alive)
        } finally {
            EmbeddedSessionRegistry.producerStopped(owner)
            calls.shutdownNow()
        }
    }
}
