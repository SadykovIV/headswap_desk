package com.headswap.desk.update

/** DriveDeck's notification listener is separate from HeadSwap's HUD notification access. */
internal object DriveDeckPermissionPolicy {
    const val LISTENER_CLASS = "ru.drivedeck.launcher.NotificationBridgeService"
    const val PACKAGE = "ru.drivedeck.headswap"
    const val LISTENER = "$PACKAGE/$LISTENER_CLASS"

    fun listenerEnabled(serialized: String?): Boolean = serialized?.split(':')?.any { it == LISTENER } == true
}
