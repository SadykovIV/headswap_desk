package com.headswap.desk.dex

/** Main-looper retry budget for one CarLife connection, independent of Activity recreation. */
internal class DesktopRecoveryPolicy {
    data class Attempt internal constructor(val token: Long, val number: Int, val delayMs: Long)
    private var serial = 0L
    private var attempts = 0
    private var pending: Attempt? = null
    private var isolatedConnection = false

    /** Only a new transport session resets the budget; a recovered frame does not. */
    fun newConnection() {
        cancel()
        attempts = 0
        isolatedConnection = false
    }

    /** A failed replacement has no display yet; keep the original connection's proven isolation. */
    fun observeIsolation(confirmed: Boolean): Boolean {
        isolatedConnection = isolatedConnection || confirmed
        return isolatedConnection
    }

    fun begin(eligible: Boolean): Attempt? {
        if (!eligible || pending != null || attempts >= 2) return null
        attempts++
        return Attempt(++serial, attempts, if (attempts == 1) 1_000 else 3_000).also { pending = it }
    }

    fun isCurrent(attempt: Attempt): Boolean = pending === attempt

    fun consume(attempt: Attempt): Boolean {
        if (!isCurrent(attempt)) return false
        pending = null
        return true
    }

    /** Stop/detach cancels a queued callback without manufacturing another retry budget. */
    fun cancel() { pending = null; serial++ }

    companion object {
        /** Never unlock or keep the phone awake to recover an already isolated car desktop. */
        fun phoneStateEligible(interactive: Boolean, locked: Boolean,
                               confirmedIsolatedSession: Boolean): Boolean =
            confirmedIsolatedSession || interactive && !locked

        /** Source recovery belongs to the active output, not to the USB attachment mechanism. */
        fun transportEligible(kind: String, connected: Boolean, usbAutoStartStopped: Boolean,
                              permittedAccessoryPresent: Boolean): Boolean = connected && when (kind) {
            "usb-aoa" -> !usbAutoStartStopped && permittedAccessoryPresent
            "tcp6" -> true
            else -> false
        }
    }
}
