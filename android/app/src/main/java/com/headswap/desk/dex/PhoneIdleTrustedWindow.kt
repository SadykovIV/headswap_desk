package com.headswap.desk.dex

import android.app.Activity
import android.os.IBinder
import android.view.Display
import android.view.View
import android.window.TrustedPresentationThresholds
import androidx.annotation.RequiresApi
import java.util.function.Consumer

/** A remote focus is accepted only for this fully presented phone window and an owned live display. */
@RequiresApi(35)
internal class PhoneIdleTrustedWindow(private val activity: Activity, private val changed: () -> Unit) {
    private var token: IBinder? = null
    private var listener: Consumer<Boolean>? = null
    private var fullyPresented = false
    var lastTopDisplay = -1
        private set
    val hasPresentationProof: Boolean get() = fullyPresented
    private var generation = 0L

    fun observe() {
        val current = activity.window.decorView.windowToken ?: return
        if (token == current) return
        close()
        token = current
        val registered = generation
        val callback = Consumer<Boolean> { visible ->
            if (registered == generation && token == current) {
                fullyPresented = visible
                changed()
            }
        }
        listener = callback
        val registeredOk = runCatching {
            activity.windowManager.registerTrustedPresentationListener(current,
                TrustedPresentationThresholds(0.999f, 0.999f, 350), activity.mainExecutor, callback)
        }.isSuccess
        if (!registeredOk) listener = null // Unsupported/OEM-disabled: retain the ordinary local-focus rule.
    }

    fun ownsRemoteFocus(): Boolean {
        lastTopDisplay = -1
        val view = activity.window.decorView
        if (!fullyPresented || !view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
            activity.display?.displayId != Display.DEFAULT_DISPLAY || activity.isInMultiWindowMode || activity.isInPictureInPictureMode) return false
        val reader = topDisplayReader ?: return false
        val id = runCatching { reader() }.getOrDefault(-1)
        lastTopDisplay = id
        return DelegatedInputGateway.ownsFocusedDisplay(id)
    }

    fun close() {
        generation++
        fullyPresented = false
        val old = listener
        listener = null
        token = null
        if (old != null) runCatching { activity.windowManager.unregisterTrustedPresentationListener(old) }
    }

    companion object {
        internal fun ownedDisplayHasFocus(): Boolean {
            val reader = topDisplayReader ?: return false
            return DelegatedInputGateway.ownsFocusedDisplay(runCatching { reader() }.getOrDefault(-1))
        }
        // Samsung exposes only this display ID without extra permission. Never query window titles,
        // application history or other displays' contents. A missing API disables the exception.
        private val topDisplayReader: (() -> Int)? by lazy {
            runCatching {
                val manager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
                val getId = Class.forName("android.view.IWindowManager").getMethod("getTopFocusedDisplayId")
                val query: () -> Int = { getId.invoke(manager) as Int }
                query
            }.getOrNull()
        }
    }
}
