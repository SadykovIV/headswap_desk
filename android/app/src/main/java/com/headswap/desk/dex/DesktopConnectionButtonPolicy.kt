package com.headswap.desk.dex

/** A ready access grant or a running helper alone is not a connected CarLife desktop. */
internal object DesktopConnectionButtonPolicy {
    enum class State(val label: String, val stopsSession: Boolean) {
        IDLE("Подключить", false),
        CONNECTING("Остановить ожидание", true),
        CONNECTED("Отключить", true),
    }

    fun state(stopped: Boolean, detached: Boolean, serviceAlive: Boolean,
        desktopRunning: Boolean, carLifeRunning: Boolean, videoFlowing: Boolean, failed: Boolean,
        working: Boolean, connectionPending: Boolean): State = when {
        // Old frame/session flags can remain true briefly while producer cleanup completes.
        stopped || detached -> State.IDLE
        serviceAlive && desktopRunning && carLifeRunning && videoFlowing && !failed -> State.CONNECTED
        // An explicit retry may still be retiring the preceding failed generation.
        working -> State.CONNECTING
        // A failed desktop can still own a live transport or an armed wait. Keep Stop available
        // until teardown finishes, so recovery/settings never require another doomed retry.
        serviceAlive || connectionPending -> State.CONNECTING
        else -> State.IDLE
    }
}
