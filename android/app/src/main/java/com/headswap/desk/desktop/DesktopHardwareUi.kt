package com.headswap.desk.desktop

import android.os.Looper

/** Main-thread registration of our own catalog only, never a tree inspection of another app. */
internal object DesktopHardwareUi {
    interface BackTarget {
        val modal: Boolean
        fun dispatch(): Boolean
    }
    interface Sink {
        fun rotate(delta: Int): Boolean
        fun key(code: Int): Boolean
        fun hasModal(): Boolean = false
        fun captureBack(): BackTarget? = null
    }
    private class Registration(val sink: Sink)
    private val sinks = mutableMapOf<Int, Registration>()
    fun register(displayId: Int, sink: Sink) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(displayId > 0)
        sinks[displayId] = Registration(sink)
    }
    fun unregister(displayId: Int, sink: Sink) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (sinks[displayId]?.sink === sink) sinks.remove(displayId)
    }
    /** A later non-Back command cancels its pending host callback, just as it resets the service sequence. */
    fun interruptBack(displayId: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        sinks[displayId]?.let { sinks[displayId] = Registration(it.sink) }
    }
    fun rotate(displayId: Int, delta: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return sinks[displayId]?.sink?.rotate(delta) == true
    }
    fun captureBack(displayId: Int): BackTarget? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = sinks[displayId] ?: return null
        val target = owner.sink.captureBack() ?: return null
        return object : BackTarget {
            override val modal = target.modal
            override fun dispatch(): Boolean {
                check(Looper.myLooper() == Looper.getMainLooper())
                return sinks[displayId] === owner && target.dispatch()
            }
        }
    }
    fun hasModal(displayId: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return sinks[displayId]?.sink?.hasModal() == true
    }
    fun key(displayId: Int, code: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return sinks[displayId]?.sink?.key(code) == true
    }
}
