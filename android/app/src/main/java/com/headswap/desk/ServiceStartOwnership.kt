package com.headswap.desk

/** Main-looper ownership of a started Service, including starts still queued in Android. */
internal class ServiceStartOwnership {
    private var latestDelivered = 0

    fun delivered(startId: Int) {
        require(startId > 0)
        latestDelivered = startId
    }

    /** Android's stopSelfResult also checks starts that have not reached onStartCommand yet. */
    fun finish(stopLatest: (Int) -> Boolean, release: () -> Unit): Boolean {
        if (latestDelivered == 0 || !stopLatest(latestDelivered)) return false
        // Keep foreground, wake lock and connection resources intact if a newer start won.
        release()
        return true
    }
}
