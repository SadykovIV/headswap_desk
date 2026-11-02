package com.headswap.desk.ime

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.graphics.Region
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** On demand only. The caller has already matched the display to a live HeadSwap viewport. */
class TapOutsideImeAccessibility : AccessibilityService() {
    enum class Hit { UNKNOWN, EDITOR, IME, OUTSIDE }

    // A busy or unresponsive accessibility provider must not build up a queue of stale taps.
    private val queries = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "headswap-ime-geometry").apply { isDaemon = true } })

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        queries.shutdownNow()
        super.onDestroy()
    }

    private fun inspect(id: Int, pkg: String, x: Int, y: Int, deadline: Long): Hit {
        if (connected !== this) return Hit.UNKNOWN
        val windows = windowsOnAllDisplays[id] ?: return Hit.UNKNOWN
        // getRegionInScreen is the *touchable* region; getBoundsInScreen may include
        // transparent space above a full-height IME window or between floating keys.
        val region = Region()
        var hasIme = false
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            window.getRegionInScreen(region)
            if (region.contains(x, y)) return Hit.IME
            if (!region.isEmpty) hasIme = true
        }
        if (!hasIme) return Hit.UNKNOWN
        // Android may expose an editor's floating cursor handle as another application window.
        val app = windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused } ?: return Hit.UNKNOWN
        if (app.parent != null || app.displayId != id) return Hit.UNKNOWN
        app.getRegionInScreen(region)
        if (!region.contains(x, y)) return Hit.UNKNOWN
        for (other in windows) {
            if (other.id == app.id || other.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            // A focused dialog over another application window is not the verified main editor.
            if (other.type == AccessibilityWindowInfo.TYPE_APPLICATION && other.layer < app.layer) return Hit.UNKNOWN
            if (other.layer < app.layer) continue
            other.getRegionInScreen(region)
            if (region.contains(x, y)) return Hit.UNKNOWN
        }
        val root = app.getRoot(0) ?: return Hit.UNKNOWN
        if (root.packageName != pkg || root.windowId != app.id) return Hit.UNKNOWN
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return Hit.UNKNOWN
        if (focused.windowId != app.id || focused.packageName != pkg || !focused.isVisibleToUser ||
            !focused.isFocused || !isEditor(focused)) return Hit.UNKNOWN
        val rect = Rect()
        focused.getBoundsInScreen(rect)
        if (rect.isEmpty) return Hit.UNKNOWN
        if (rect.contains(x, y)) return Hit.EDITOR

        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty()) {
            if (++visited > MAX_NODES || SystemClock.uptimeMillis() >= deadline) return Hit.UNKNOWN
            val node = pending.removeLast()
            if (node.windowId != app.id || node.packageName != pkg) return Hit.UNKNOWN
            if (node.isVisibleToUser && isEditor(node)) {
                node.getBoundsInScreen(rect)
                if (rect.isEmpty) return Hit.UNKNOWN
                if (rect.contains(x, y)) return Hit.EDITOR
            }
            if (visited + pending.size + node.childCount > MAX_NODES) return Hit.UNKNOWN
            for (i in 0 until node.childCount) {
                if (SystemClock.uptimeMillis() >= deadline) return Hit.UNKNOWN
                pending.add(node.getChild(i, 0) ?: return Hit.UNKNOWN)
            }
        }
        return if (connected === this) Hit.OUTSIDE else Hit.UNKNOWN
    }

    private fun isEditor(node: AccessibilityNodeInfo): Boolean = node.isEditable || node.isPassword ||
        node.className == "android.widget.EditText" ||
        node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id }

    companion object {
        private const val MAX_NODES = 180
        private const val TIMEOUT_MS = 120L
        @Volatile private var connected: TapOutsideImeAccessibility? = null

        internal fun classify(displayId: Int, packageName: String, x: Float, y: Float): Hit {
            if (displayId <= 0 || packageName.isBlank() || !x.isFinite() || !y.isFinite() || x < 0f || y < 0f ||
                Looper.myLooper() == Looper.getMainLooper()) return Hit.UNKNOWN
            val service = connected ?: return Hit.UNKNOWN
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            return try {
                val task = service.queries.submit<Hit> { service.inspect(displayId, packageName, x.toInt(), y.toInt(), deadline) }
                try { task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) } finally { task.cancel(false) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Hit.UNKNOWN
            } catch (_: Exception) { Hit.UNKNOWN }
        }
    }
}
