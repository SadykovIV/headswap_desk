package com.headswap.desk.notify

/** Temporary build-wide suspension of the HU shade. Phone pairing and HUD are independent. */
internal object HuNotificationFeature {
    const val AVAILABLE = false
    private const val CAP_NOTIFICATIONS = 1 shl 6

    fun enabled(requested: Boolean): Boolean = AVAILABLE && requested
    fun capabilities(value: Int): Int = if (AVAILABLE) value else value and CAP_NOTIFICATIONS.inv()
}
