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
import android.os.SystemClock
import com.headswap.desk.component.DesktopComponentEntry
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.embed.FullscreenSlotBusyException
import com.headswap.desk.shared.DesktopRenderPolicy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The rail stays in the parent Activity. Android owns app tasks in this second, viewport-sized display. */
internal class DesktopAppPane(
    private val activity: Activity,
    private val onOpened: () -> Unit,
    private val onHome: () -> Unit,
    private val onError: (String) -> Unit,
    private val fullscreenDriveDeck: Boolean = false,
) : TextureView.SurfaceTextureListener {
    val view = TextureView(activity).apply {
        surfaceTextureListener = this@DesktopAppPane
        contentDescription = "Окно приложения"
        setOnTouchListener { _, event -> forwardTouch(event) }
    }
    private val uid = Process.myUid()
    private var current: Output? = null // Main thread.
    private var wanted: DesktopAppCatalog.Entry? = null
    private var fromPanel = false
    private var component: DesktopComponentEntry? = null
    private var attachSession: ((Intent) -> Unit)? = null
    @Volatile private var request = 0L

    private class Output(val texture: SurfaceTexture) {
        val token = Binder()
        val surface = Surface(texture)
        @Volatile var retired = false
        var handle = 0 // Only accessed on the serial worker.
        var buffer: Buffer? = null
        var appPackage: String? = null
        var needsFreshChild = false
    }

    private data class Buffer(val width: Int, val height: Int, val density: Int)

    fun open(entry: DesktopAppCatalog.Entry, requestedByPanel: Boolean = false) {
        fromPanel = requestedByPanel
        if (component != null) resetChild()
        component = null
        attachSession = null
        wanted = entry
        request++
        current?.let { launch(it, entry) }
    }

    fun openComponent(entry: DesktopComponentEntry, attach: (Intent) -> Unit) {
        resetChild()
        fromPanel = false
        component = entry
        attachSession = attach
        val target = DesktopAppCatalog.Entry(entry.packageName, entry.componentName.className, entry.label)
        wanted = target
        request++
        current?.let { launch(it, target) }
    }

    fun ifComponentForeground(entry: DesktopComponentEntry, action: () -> Unit) {
        val output = current ?: return
        val serial = request
        if (component != entry) return
        work.execute {
            if (output.retired || request != serial || output.handle == 0) return@execute
            if (runCatching { EmbeddedSessionRegistry.taskState(uid, output.handle, entry.packageName) }.getOrNull() == 2)
                main.post { if (!output.retired && request == serial && component == entry) action() }
        }
    }

    fun home() {
        if (component != null) resetChild()
        component = null
        attachSession = null
        wanted = null
        request++
        onHome()
    }

    // Do not retain a provider's old tasks under the stock catalog or another app.
    private fun resetChild() {
        val output = current ?: return
        work.execute {
            if (output.retired) return@execute
            output.needsFreshChild = true
            runCatching { releaseChild(output) }.onFailure { Log.w(TAG, "Child release pending", it) }
        }
    }

    private fun releaseChild(output: Output) {
        EmbeddedSessionRegistry.releaseOwnSurface(uid, output.token)
        output.handle = 0
        output.buffer = null
        output.appPackage = null
        output.needsFreshChild = false
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(width, height)
        val output = Output(texture)
        current = output
        wanted?.let { launch(output, it) }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (current?.texture === texture) refreshBuffer(width, height)
    }

    fun refreshBuffer(width: Int = view.width, height: Int = view.height) {
        val output = current ?: return
        val parent = activity.display?.displayId ?: return
        if (width <= 0 || height <= 0) return
        work.execute {
            val pkg = output.appPackage
            if (!output.retired && pkg != null) runCatching {
                configure(output, parent, pkg, width, height)
            }.onFailure { Log.w(TAG, "Viewport resize failed", it) }
        }
    }

    // Runs on the serial worker. Both the texture size and Android's child display have
    // to switch before input is mapped to the new coordinate space.
    private fun configure(output: Output, parent: Int, pkg: String, width: Int, height: Int) {
        if (output.needsFreshChild) releaseChild(output)
        val policy = EmbeddedSessionRegistry.renderConfig(parent, ownDesktop = !fullscreenDriveDeck, packageName = pkg)
        val g = DesktopRenderPolicy.geometry(width, height, policy[0], policy[1] == 1)
        val next = Buffer(g[0], g[1], g[2])
        val previous = output.buffer
        output.texture.setDefaultBufferSize(next.width, next.height)
        try {
            if (output.handle == 0) {
                EmbeddedSessionRegistry.releaseOwnSurface(uid, output.token)
                output.handle = EmbeddedSessionRegistry.create(uid, output.surface, output.token,
                    parent, next.width, next.height, next.density, ownDesktop = !fullscreenDriveDeck, fullscreen = fullscreenDriveDeck)
            } else if (previous != next) {
                EmbeddedSessionRegistry.resize(uid, output.handle, next.width, next.height, next.density)
            }
            output.buffer = next
        } catch (error: Exception) {
            if (previous != null) output.texture.setDefaultBufferSize(previous.width, previous.height)
            throw error
        }
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val output = current ?: return true
        current = null
        output.retired = true
        if (component != null) home()
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

    private fun launch(output: Output, entry: DesktopAppCatalog.Entry, retry: Int = 0) {
        val serial = request
        val provider = component
        val publicLaunch = fromPanel
        val attach = attachSession
        val parent = activity.display?.displayId ?: -1
        val width = view.width
        val height = view.height
        work.execute {
            if (output.retired || request != serial) return@execute
            try {
                if (provider != null) {
                    configure(output, parent, entry.packageName, width, height)
                    EmbeddedSessionRegistry.launchDesktopComponent(activity, output.handle, provider) { intent, _ ->
                        check(request == serial && !output.retired)
                        checkNotNull(attach)(intent)
                    }
                    output.appPackage = entry.packageName
                    watchComponent(output, entry, serial, false, SystemClock.elapsedRealtime() + 7_000)
                    return@execute
                }
                if (publicLaunch) {
                    configure(output, parent, entry.packageName, width, height)
                    if (request != serial || output.retired) return@execute
                    EmbeddedSessionRegistry.launchPanelApp(activity, output.handle, ComponentName(entry.packageName, entry.className))
                    output.appPackage = entry.packageName
                    main.post { if (!output.retired && request == serial) onOpened() }
                    return@execute
                }
                // The Shizuku producer runs as shell; it must not use a protected entry unavailable to this app.
                val pm = activity.packageManager
                val target = ComponentName(entry.packageName, entry.className)
                val info = pm.getActivityInfo(target, 0)
                val permission = info.permission
                check(info.enabled && info.exported && info.applicationInfo.enabled &&
                    (permission == null || pm.checkPermission(permission, activity.packageName) == PackageManager.PERMISSION_GRANTED))
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(entry.packageName)
                check(pm.queryIntentActivities(intent, 0).any { it.activityInfo?.name == entry.className })
                configure(output, parent, entry.packageName, width, height)
                if (request != serial || output.retired) return@execute
                EmbeddedSessionRegistry.launch(uid, output.handle, entry.packageName, entry.className)
                output.appPackage = entry.packageName
                main.post { if (!output.retired && request == serial) onOpened() }
            } catch (error: Exception) {
                if (fullscreenDriveDeck && error is FullscreenSlotBusyException && retry < 10) {
                    main.postDelayed({
                        if (!output.retired && request == serial && current === output) launch(output, entry, retry + 1)
                    }, 150)
                    return@execute
                }
                Log.w(TAG, "Cannot open ${entry.key} on the owned desktop", error)
                main.post {
                    if (!output.retired && request == serial) {
                        if (fullscreenDriveDeck) { wanted = null; request++ } else home()
                        onError("«${entry.label}» не удалось открыть на рабочем столе.")
                    }
                }
            }
        }
    }

    // Launch success alone does not prove placement. ActivityManager observations are
    // bounded by the existing task query; a crash/missing task returns to stock without relaunch.
    private fun watchComponent(output: Output, entry: DesktopAppCatalog.Entry, serial: Long,
        confirmed: Boolean, deadline: Long) {
        if (output.retired || request != serial || output.handle <= 0) return
        val state = runCatching { EmbeddedSessionRegistry.taskState(uid, output.handle, entry.packageName) }.getOrNull()
        val now = SystemClock.elapsedRealtime()
        val placed = state == 2 || confirmed && state == 1
        if (placed && !confirmed) main.post { if (!output.retired && request == serial) onOpened() }
        if (!placed && (now >= deadline || confirmed && state == 0)) {
            Log.w(TAG, "Panel placement lost: package=${entry.packageName} state=$state confirmed=$confirmed")
            main.post {
                if (!output.retired && request == serial) {
                    home()
                    onError("Компонент «${entry.label}» завершился или не появился в своём окне.")
                }
            }
            return
        }
        work.schedule({ watchComponent(output, entry, serial, confirmed || placed,
            if (placed) now + 5_000 else deadline) }, if (placed) 1_000 else 300, TimeUnit.MILLISECONDS)
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
                    if (!output.retired) {
                        val state = runCatching { EmbeddedSessionRegistry.taskState(uid, output.handle, entry.packageName) }.getOrNull()
                        if (state == 0 || (fullscreenDriveDeck && state == 1)) {
                            main.post { if (!output.retired && request == serial) home() }
                        }
                    }
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
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false
        work.execute {
            if (!output.retired && output.handle > 0) runCatching {
                val buffer = output.buffer ?: return@runCatching
                for (i in xs.indices) {
                    xs[i] = (xs[i] * buffer.width / width).coerceIn(0f, (buffer.width - 1).toFloat())
                    ys[i] = (ys[i] * buffer.height / height).coerceIn(0f, (buffer.height - 1).toFloat())
                }
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
