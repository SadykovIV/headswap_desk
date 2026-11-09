package com.headswap.desk.link.car

/** Decisions shared by USB handoff, explicit wireless start and the Bluetooth trigger. */
object WirelessConnectionPolicy {
    fun retainAutoAfterUsbDetach(enabled: Boolean, carSelected: Boolean, autoMode: Boolean): Boolean =
        enabled && carSelected && autoMode

    fun acceptAttempt(attempt: Long, current: Long, stopping: Boolean, sessionActive: Boolean): Boolean =
        attempt == current && !stopping && !sessionActive
}
