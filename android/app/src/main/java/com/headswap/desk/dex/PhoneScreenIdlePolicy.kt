package com.headswap.desk.dex

/** Phone-window activity only; remote-display input never reaches this policy. */
internal class PhoneScreenIdlePolicy {
    data class State(val keepAwake: Boolean, val curtainVisible: Boolean, val delayMs: Long?,
        val requestPhysicalOff: Boolean = false)
    private enum class Dispatch { NONE, REQUESTED, WAITING, DONE }

    private var active = false
    private var curtain = false
    private var lastInteractionMs = 0L
    private var lastObservedMs = 0L
    private var timeoutMs = 0L
    private var physical = false
    private var panelBusy = false
    private var panelOff = false
    private var dispatch = Dispatch.NONE
    private var requestedAt = 0L
    private var sawBusy = false

    fun refresh(eligible: Boolean, nowMs: Long, systemTimeoutMs: Long, physicalMode: Boolean = false,
        panelBusy: Boolean = false, panelOff: Boolean = false): State {
        // Invalid settings must not turn into an immediate black screen or an unlimited wake lock.
        if (!eligible || nowMs < 0 || systemTimeoutMs !in 1..Int.MAX_VALUE.toLong()) {
            active = false
            curtain = false
            timeoutMs = 0
            dispatch = Dispatch.NONE
            return state(nowMs)
        }
        if (!active || nowMs < lastObservedMs || physical != physicalMode) {
            active = true
            curtain = false
            lastInteractionMs = nowMs
            dispatch = Dispatch.NONE
            sawBusy = false
        }
        physical = physicalMode
        this.panelBusy = panelBusy
        this.panelOff = panelOff
        timeoutMs = systemTimeoutMs
        lastObservedMs = nowMs
        if (dispatch == Dispatch.WAITING) {
            sawBusy = sawBusy || panelBusy
            if (panelOff || (sawBusy && !panelBusy) || nowMs - requestedAt >= HANDOFF_MS) dispatch = Dispatch.DONE
        }
        if (panelOff || panelBusy) {
            curtain = false
            lastInteractionMs = nowMs
            // A panel already controlled manually must not be blanked again when it is restored.
            if (physical && dispatch == Dispatch.NONE) dispatch = Dispatch.DONE
            return state(nowMs)
        }
        if (!curtain && dispatch == Dispatch.NONE && nowMs - lastInteractionMs >= timeoutMs) return show(nowMs)
        return state(nowMs)
    }

    fun onUserInteraction(nowMs: Long, panelUnavailable: Boolean = panelBusy || panelOff): State {
        if (active && !curtain && !panelUnavailable && dispatch != Dispatch.REQUESTED && dispatch != Dispatch.WAITING && nowMs >= 0) {
            dispatch = Dispatch.NONE
            lastInteractionMs = nowMs
            lastObservedMs = nowMs
        }
        return state(nowMs)
    }

    fun showNow(nowMs: Long): State {
        return if (active && !panelBusy && !panelOff && dispatch == Dispatch.NONE && nowMs >= lastObservedMs) show(nowMs) else state(nowMs)
    }

    private fun show(nowMs: Long): State {
        if (!physical) { curtain = true; return state(nowMs) }
        dispatch = Dispatch.REQUESTED // Reserve before the external dispatch can trigger a callback.
        requestedAt = nowMs
        sawBusy = false
        return state(nowMs).copy(requestPhysicalOff = true)
    }

    fun physicalRequestResult(accepted: Boolean, nowMs: Long): State {
        if (dispatch == Dispatch.REQUESTED) {
            dispatch = if (accepted && !panelOff && nowMs >= requestedAt && nowMs - requestedAt < HANDOFF_MS)
                Dispatch.WAITING else Dispatch.DONE
        }
        return state(nowMs)
    }

    fun dismiss(nowMs: Long): State {
        curtain = false
        if (active && !physical && nowMs >= 0) {
            lastInteractionMs = nowMs
            lastObservedMs = nowMs
        }
        return state(nowMs)
    }

    private fun state(nowMs: Long): State {
        if (!active || panelOff || dispatch == Dispatch.DONE) return State(false, false, null)
        if (dispatch == Dispatch.WAITING) {
            return State(true, false, (HANDOFF_MS - (nowMs - requestedAt).coerceAtLeast(0)).coerceAtLeast(0))
        }
        if (panelBusy) return State(false, false, null)
        if (dispatch == Dispatch.REQUESTED) return State(true, false, null)
        val elapsed = if (nowMs >= lastInteractionMs) nowMs - lastInteractionMs else 0L
        return State(active, curtain, if (active && !curtain) (timeoutMs - elapsed).coerceAtLeast(0) else null)
    }

    companion object { const val HANDOFF_MS = 3_000L }
}
