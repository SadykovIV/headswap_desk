package com.headswap.desk.desktop

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The rail stays in the parent Activity. Android owns app tasks in this second, viewport-sized display. */
internal class DesktopAppPane(
    private val activity: Activity,
    private val onOpened: () -> Unit,
    private val onHome: () -> Unit,
    private val onError: (String) -> Unit,
) : TextureView.SurfaceTextureListener {
    val view = TextureView(activity).apply {
        surfaceTextureListener = this@DesktopAppPane
        contentDescription = "Окно приложения"
        setOnTouchListener { _, event -> forwardTouch(event) }
    }
    private val uid = Process.myUid()
    private var current: Output? = null // Main thread.
    private var wanted: DesktopAppCatalog.Entry? = null
    private var request = 0L

    private class Output(val texture: SurfaceTexture) {
        val token = Binder()
        val surface = Surface(texture)
        @Volatile var retired = false
        var handle = 0 // Only accessed on the serial worker.
    }

    fun open(entry: DesktopAppCatalog.Entry) {
        wanted = entry
        request++
        current?.let { launch(it, entry) }
    }

    fun home() {
        wanted = null
        request++
        onHome()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(width, height)
        val output = Output(texture)
        current = output
        wanted?.let { launch(output, it) }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        val output = current ?: return
        texture.setDefaultBufferSize(width, height)
        val density = activity.resources.displayMetrics.densityDpi
        work.execute {
            if (!output.retired && output.handle > 0) runCatching {
                EmbeddedSessionRegistry.resize(uid, output.handle, width, height, density)
            }.onFailure { Log.w(TAG, "Viewport resize failed", it) }
        }
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val output = current ?: return true
        current = null
        output.retired = true
        work.execute { retire(output) }
        return false // The consumer stays alive until the remote producer has released the Surface.
    }

    private fun retire(output: Output) {
        try {
            EmbeddedSessionRegistry.releaseOwnSurface(uid, output.token)
            output.surface.release()
            output.texture.release()
        } catch (error: Exception) {
            Log.w(TAG, "Waiting for desktop viewport release", error)
            work.schedule({ retire(output) }, 1, TimeUnit.SECONDS)
        }
    }

    private fun launch(output: Output, entry: DesktopAppCatalog.Entry) {
        val serial = request
        val parent = activity.display?.displayId ?: -1
        val density = activity.resources.displayMetrics.densityDpi
        val width = view.width
        val height = view.height
        work.execute {
            if (output.retired) return@execute
            try {
                // The Shizuku producer runs as shell; it must not use a protected entry unavailable to this app.
                val pm = activity.packageManager
                val component = ComponentName(entry.packageName, entry.className)
                val info = pm.getActivityInfo(component, 0)
                val permission = info.permission
                check(info.enabled && info.exported && info.applicationInfo.enabled &&
                    (permission == null || pm.checkPermission(permission, activity.packageName) == PackageManager.PERMISSION_GRANTED))
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(entry.packageName)
                check(pm.queryIntentActivities(intent, 0).any { it.activityInfo?.name == entry.className })
                if (output.handle == 0) {
                    EmbeddedSessionRegistry.releaseOwnSurface(uid, output.token)
                    output.handle = EmbeddedSessionRegistry.create(uid, output.surface, output.token,
                        parent, width, height, density, ownDesktop = true)
                }
                EmbeddedSessionRegistry.launch(uid, output.handle, entry.packageName, entry.className)
                main.post { if (!output.retired && request == serial) onOpened() }
            } catch (error: Exception) {
                Log.w(TAG, "Cannot open ${entry.key} on the owned desktop", error)
                main.post {
                    if (!output.retired && request == serial) {
                        home()
                        onError("«${entry.label}» не удалось открыть на рабочем столе.")
                    }
                }
            }
        }
    }

    fun back() {
        val entry = wanted ?: return home()
        val output = current ?: return home()
        val serial = request
        work.execute {
            if (output.retired || output.handle <= 0) return@execute
            runCatching {
                EmbeddedSessionRegistry.back(uid, output.handle)
                // Back at an app's root can remove its last task, leaving the child display empty.
                work.schedule({
                    if (!output.retired && runCatching {
                        EmbeddedSessionRegistry.taskState(uid, output.handle, entry.packageName)
                    }.getOrNull() == 0) main.post { if (!output.retired && request == serial) home() }
                }, 500, TimeUnit.MILLISECONDS)
            }.onFailure { Log.w(TAG, "Desktop Back failed", it) }
        }
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        val output = current ?: return false
        if (wanted == null || event.pointerCount !in 1..2 || event.actionMasked !in TOUCH_ACTIONS) return false
        val action = event.actionMasked
        val index = event.actionIndex
        val ids = IntArray(event.pointerCount) { event.getPointerId(it) }
        val xs = FloatArray(ids.size) { event.getX(it) }
        val ys = FloatArray(ids.size) { event.getY(it) }
        work.execute {
            if (!output.retired && output.handle > 0) runCatching {
                EmbeddedSessionRegistry.touchPoints(uid, output.handle, action, index, ids, xs, ys)
            }.onFailure { Log.w(TAG, "Desktop touch rejected", it) }
        }
        return true
    }

    private companion object {
        const val TAG = "HeadSwapDesktopPane"
        val main = Handler(Looper.getMainLooper())
        val work = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "headswap-desktop-pane").apply { isDaemon = true }
        }
        val TOUCH_ACTIONS = setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)
    }
}
