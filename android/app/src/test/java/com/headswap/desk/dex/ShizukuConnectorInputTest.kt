package com.headswap.desk.dex

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.view.MotionEvent
import android.view.Surface
import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.aa.DesktopVideoGeometry
import com.headswap.desk.aa.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the actual connector and input executor, not a copy of its coordinate policy. */
class ShizukuConnectorInputTest {
    private data class Pointer(val action: Int, val x: Int, val y: Int, val buttons: Int)
    private data class Contacts(val action: Int, val index: Int, val ids: List<Int>, val xs: List<Float>, val ys: List<Float>)

    private class Fixture(mode: Int = DesktopModePolicy.SAMSUNG_DEX) : AutoCloseable {
        private val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val core = AaBridgeCore({ _, _ -> }).apply {
            videoMode = DesktopVideoGeometry
            desktopInputEnabled = true
        }
        val connector = ShizukuDexConnector(context, core)
        private val received = LinkedBlockingQueue<Pointer>()
        private val contacts = LinkedBlockingQueue<Contacts>()
        private val service = object : IDexUserService.Stub() {
            override fun protocolVersion() = DexUserServicePolicy.PROTOCOL_VERSION
            override fun start(surface: Surface?, lifetimeToken: IBinder?, density: Int) = error("Unexpected display creation")
            override fun startDesktopSized(surface: Surface?, lifetimeToken: IBinder?, width: Int, height: Int, density: Int, mode: Int) = error("Unexpected display creation")
            override fun startDesktop(surface: Surface?, lifetimeToken: IBinder?, density: Int, mode: Int) = error("Unexpected display creation")
            override fun showDesktop() = error("Unexpected desktop navigation")
            override fun createChildDisplay(surface: Surface?, childToken: IBinder?, width: Int, height: Int, density: Int): Int = error("Unexpected child display creation")
            override fun createFullscreenChildDisplay(surface: Surface?, childToken: IBinder?, width: Int, height: Int, density: Int): Int = error("Unexpected fullscreen display creation")
            override fun launchChildApp(childToken: IBinder?, packageName: String?, className: String?) = error("Unexpected child launch")
            override fun resizeChildDisplay(childToken: IBinder?, width: Int, height: Int, density: Int) = error("Unexpected child resize")
            override fun injectChildTouch(childToken: IBinder?, action: Int, x: Float, y: Float) = error("Unexpected child touch")
            override fun injectChildTouchPoints(childToken: IBinder?, action: Int, actionIndex: Int,
                pointerIds: IntArray?, xs: FloatArray?, ys: FloatArray?) = error("Unexpected child touch")
            override fun injectTouchPoints(action: Int, actionIndex: Int, pointerIds: IntArray,
                xs: FloatArray, ys: FloatArray) {
                contacts.put(Contacts(action, actionIndex, pointerIds.toList(), xs.toList(), ys.toList()))
            }
            override fun injectChildBack(childToken: IBinder?) = error("Unexpected child key")
            override fun closeChildDisplay(childToken: IBinder?) = error("Unexpected child close")
            override fun childTaskState(childToken: IBinder?, packageName: String?): Int = error("Unexpected child inspection")
            override fun childProducerLifetime(childToken: IBinder?): IBinder = error("Unexpected lifetime query")
            override fun injectPointer(action: Int, x: Int, y: Int, buttons: Int) {
                received.put(Pointer(action, x, y, buttons))
            }
            override fun injectKey(action: Int, keycode: Int) = error("Unexpected key injection")
            override fun stop() = Unit
            override fun setPhoneScreenOff(off: Boolean) = error("Unexpected panel control")
            override fun destroy() = Unit
        }

        init {
            // Only fake the completed Android binding/frame handshake. Core mapping,
            // connector filtering/clamping and asynchronous dispatch remain production code.
            field("generation").setLong(connector, 1L)
            field("firstFrame").setBoolean(connector, true)
            field("service").set(connector, service)
            field("desktopMode").setInt(connector, mode)
            core.peer = connector
        }

        fun next(): Pointer = received.poll(2, TimeUnit.SECONDS) ?: error("Pointer did not reach the user service")
        fun nextContacts(): Contacts = contacts.poll(2, TimeUnit.SECONDS) ?: error("Contacts did not reach the user service")

        private fun field(name: String) = ShizukuDexConnector::class.java.getDeclaredField(name).apply { isAccessible = true }

        override fun close() {
            core.peer = null
            // No Android binding or display was created; release only fixture executors.
            for (name in listOf("input", "binderCalls", "watchdog")) {
                val executor = field(name).get(connector) as ExecutorService
                executor.shutdownNow()
                check(executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun carlifeBottomTaskbarAndFarRightReachUserServiceAtNative900Coordinates() = Fixture().use { f ->
        // These points were previously silently clamped to y=719 / x=1279 inside
        // ShizukuDexConnector even though the core and service both accepted 900p.
        f.core.onCarlifeTouch(MotionEvent.ACTION_DOWN, 800, 870)
        f.core.onCarlifeTouch(MotionEvent.ACTION_UP, 800, 870)
        f.core.onCarlifeTouch(MotionEvent.ACTION_DOWN, 1599, 899)
        f.core.onCarlifeTouch(MotionEvent.ACTION_UP, 1599, 899)
        assertEquals(Pointer(MotionEvent.ACTION_DOWN, 800, 870, MotionEvent.BUTTON_PRIMARY), f.next())
        assertEquals(Pointer(MotionEvent.ACTION_UP, 800, 870, 0), f.next())
        assertEquals(Pointer(MotionEvent.ACTION_DOWN, 1599, 899, MotionEvent.BUTTON_PRIMARY), f.next())
        assertEquals(Pointer(MotionEvent.ACTION_UP, 1599, 899, 0), f.next())
    }

    @Test fun connectorBoundsMalformedCoordinatesToCurrentDisplayAndReleasesMouseOnCancel() = Fixture().use { f ->
        f.connector.sendTouch(MotionEvent.ACTION_DOWN, 0, listOf(TouchPoint(0, 9000, 9000)))
        f.connector.sendTouch(MotionEvent.ACTION_CANCEL, 0, listOf(TouchPoint(0, -20, -30)))
        assertEquals(Pointer(MotionEvent.ACTION_DOWN, 1599, 899, MotionEvent.BUTTON_PRIMARY), f.next())
        assertEquals(Pointer(MotionEvent.ACTION_CANCEL, 0, 0, 0), f.next())
    }

    @Test fun carlifeTwoFingerGestureReachesDesktopWithStableContactIds() = Fixture(DesktopModePolicy.DRIVEDECK).use { f ->
        f.core.onCarlifeTouch(MotionEvent.ACTION_DOWN, 100, 200)
        f.core.onCarlifeTouch(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 100, 200, 400, 500)
        f.core.onCarlifeTouch(MotionEvent.ACTION_MOVE, 80, 200, 420, 500)
        val down = f.nextContacts()
        val second = f.nextContacts()
        val move = f.nextContacts()
        assertEquals(0, down.action)
        assertEquals(5, second.action); assertEquals(1, second.index)
        assertEquals(listOf(0, 1), second.ids)
        assertEquals(listOf(100f, 400f), second.xs)
        assertEquals(listOf(80f, 420f), move.xs)
        assertEquals(second.ids, move.ids)
    }
}
