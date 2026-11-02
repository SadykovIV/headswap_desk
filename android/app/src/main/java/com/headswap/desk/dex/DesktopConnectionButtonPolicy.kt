package com.headswap.desk.dex

/** A ready access grant or a running helper alone is not a connected CarLife desktop. */
internal object DesktopConnectionButtonPolicy {
    enum class State(val label: String, val stopsSession: Boolean) {
        IDLE("Подключить", false),
        CONNECTING("Подключение…", true),
        CONNECTED("Остановить", true),
    }

    fun state(stopped: Boolean, detached: Boolean, serviceAlive: Boolean,
        desktopRunning: Boolean, carLifeRunning: Boolean, videoFlowing: Boolean, failed: Boolean,
        working: Boolean, connectionPending: Boolean): State = when {
        // Old frame/session flags can remain true briefly while producer cleanup completes.
        stopped || detached -> State.IDLE
        serviceAlive && desktopRunning && carLifeRunning && videoFlowing && !failed -> State.CONNECTED
        // An explicit retry may still be retiring the preceding failed generation.
        working -> State.CONNECTING
        failed -> State.IDLE
        connectionPending -> State.CONNECTING
        else -> State.IDLE
    }
}
