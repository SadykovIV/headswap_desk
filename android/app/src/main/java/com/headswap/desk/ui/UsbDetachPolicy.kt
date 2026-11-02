package com.headswap.desk.ui

/** Absence must be observed successfully; an IO error or old broadcast alone is not a detach. */
internal object UsbDetachPolicy {
    fun confirmedDetached(ownedUsbSession: Boolean, compatibleAccessoryPresent: Boolean?): Boolean =
        ownedUsbSession && compatibleAccessoryPresent == false
}

/** Physical detach stops projection polling, but does not revoke access or disable the next CarLife attach. */
internal class UsbDetachedIdle {
    var idle = false
        private set

    fun detached() { idle = true }
    fun observeCompatibleAccessory(present: Boolean) { if (present) idle = false }
    fun explicitConnect() { idle = false }
}
