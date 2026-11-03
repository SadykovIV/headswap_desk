package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.headswap.desk.dex.*

/** Real WM policy on newly registered DriveDeck windows with no authenticated shell connection. */
internal object OfflineImeProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish"))
            val ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.INJECT_EVENTS, DelegatedDesktopAccess.REAL_GET_TASKS,
                DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL, ShellDisplayIme.PERMISSION)
            try {
                val context = test.targetContext
                check(!ShellPanelClient.isConnected && ShellDisplayIme.availableInActivation(context))
                val manager = context.getSystemService(DisplayManager::class.java)
                val lease = DelegatedDesktopAccess.begin {}
                try {
                    val gateway = DelegatedInputGateway(context)
                    val parentToken = Binder()
                    gateway.beginGeneration(1, parentToken, true)
                    try {
                        withDisplay(manager, "HeadSwap DriveDeck", 1280, 720) { parent ->
                            gateway.registerParent(parent, parentToken, 1280, 720)
                            check(policy(parent) == 0) { "Parent did not receive LOCAL" }
                            repeat(2) {
                                withDisplay(manager, "HeadSwap navigator widget A", 640, 480) { child ->
                                    val token = Binder()
                                    gateway.registerChild(parent, child, token, 640, 480)
                                    check(policy(child) == 0) { "New child did not receive LOCAL" }
                                    check(gateway.childImeVisibility(child, token) == 0) { "Expected hidden IME" }
                                    gateway.unregisterDisplay(child, token)
                                    result.putInt("child${it + 1}", child)
                                }
                            }
                            gateway.unregisterDisplay(parent, parentToken)
                        }
                    } finally { gateway.deactivate() }
                } finally { DelegatedDesktopAccess.end(lease) }
            } finally { ui.dropShellPermissionIdentity() }
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private val wmApi = Class.forName("android.view.IWindowManager")
    private val wm by lazy { Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null) }
    private fun policy(id: Int) = wmApi.getMethod("getDisplayImePolicy", Integer.TYPE).invoke(wm, id) as Int

    private fun withDisplay(manager: DisplayManager, name: String, w: Int, h: Int, run: (Int) -> Unit) {
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).use { images ->
            val display = checkNotNull(manager.createVirtualDisplay(name, w, h, 160, images.surface,
                DesktopModePolicy.displayFlags(DesktopModePolicy.DRIVEDECK, Build.VERSION.SDK_INT)))
            try {
                val id = display.display.displayId
                ShellDisplayIme.requireOwned(id, Process.myUid())
                // Some Android versions remember a previous display with the same name.
                // Recreate the physical regression deterministically on this test-owned display.
                wmApi.getMethod("setDisplayImePolicy", Integer.TYPE, Integer.TYPE).invoke(wm, id, 1)
                check(policy(id) == 1) { "Could not set up FALLBACK on the test window" }
                run(id)
            } finally { display.release() }
        }
    }
}
