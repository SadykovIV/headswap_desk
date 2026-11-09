package com.headswap.desk.desktop

import android.app.Activity
import com.headswap.desk.embed.EmbeddedSessionRegistry

/** Back for our managed fullscreen hosts must not depend on the parent's input-window focus. */
internal class FullscreenHardwareBack private constructor(
    private val activity: Activity,
    private val pane: DesktopAppPane,
    private val parentId: Int,
    private val parentProof: Any,
    private val acceptsInput: () -> Boolean,
) {
    private var resumed = false
    private var closed = false
    private val sink = object : DesktopHardwareUi.Sink {
        override fun rotate(delta: Int) = false
        override fun key(code: Int) = false

        override fun captureBack(): DesktopHardwareUi.BackTarget? {
            if (!current()) return null
            val ownsPane = pane.captureBackOwner()
            return object : DesktopHardwareUi.BackTarget {
                override val modal = false

                override fun dispatch(): Boolean {
                    // The service already decided between IME, double-Back Home and ordinary
                    // Back. Only that last case reaches this exact surviving pane request.
                    if (!current() || !ownsPane()) return false
                    pane.back()
                    return true
                }
            }
        }
    }

    private fun current(): Boolean = resumed && !closed &&
        !activity.isFinishing && !activity.isDestroyed &&
        activity.display?.displayId == parentId &&
        EmbeddedSessionRegistry.isCurrentParent(parentId, parentProof) && acceptsInput()

    fun resume() {
        if (closed) return
        resumed = true
        if (current()) DesktopHardwareUi.register(parentId, sink)
    }

    fun pause() {
        resumed = false
        DesktopHardwareUi.unregister(parentId, sink)
    }

    fun close() {
        closed = true
        pause()
    }

    fun touchDown() {
        if (current()) DesktopHardwareUi.interruptBack(parentId)
    }

    companion object {
        fun attach(activity: Activity, pane: DesktopAppPane,
            acceptsInput: () -> Boolean = { true }): FullscreenHardwareBack? {
            val parentId = activity.display?.displayId ?: return null
            val proof = EmbeddedSessionRegistry.captureParent(parentId) ?: return null
            return FullscreenHardwareBack(activity, pane, parentId, proof, acceptsInput)
        }
    }
}
