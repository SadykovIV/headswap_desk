package com.headswap.desk.component

import android.content.pm.ActivityInfo

internal object DesktopComponentPolicy {
    const val ACTION = "com.headswap.desk.action.DESKTOP_COMPONENT"
    const val API = "com.headswap.desk.component.API_MAJOR"
    const val PROFILE = "com.headswap.desk.component.PROFILE"
    const val MODE_ID = "com.headswap.desk.component.MODE_ID"
    const val ANDROID_DESKTOP = "android-desktop"

    private val modePattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun acceptsDeclaration(api: Any?, profile: Any?, exported: Boolean, enabled: Boolean, accessible: Boolean,
                           modeId: Any? = null): Boolean =
        api == 1 && profile == ANDROID_DESKTOP && exported && enabled && accessible &&
            (modeId == null || modeId is String && modePattern.matches(modeId))

    fun acceptsOwner(pkg: String, host: String, hostUid: Int, uid: Int, sameUser: Boolean,
                     sharedUserId: String?, packagesForUid: Set<String>): Boolean =
        pkg.isNotEmpty() && pkg != host && uid != hostUid && sameUser && sharedUserId == null && packagesForUid == setOf(pkg)

    /** A dedicated panel must not demand an existing phone task, or reparent its activity later. */
    fun supportsIsolatedTask(launchMode: Int, documentMode: Int, allowsReparenting: Boolean): Boolean =
        (launchMode == ActivityInfo.LAUNCH_MULTIPLE || launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) &&
            documentMode == ActivityInfo.DOCUMENT_LAUNCH_NONE && !allowsReparenting

    data class Pin(val component: String, val userSerial: Long, val signers: Set<String>, val modeId: String? = null) {
        fun accepts(actual: Pin?): Boolean = component.isNotBlank() && userSerial >= 0 && signers.isNotEmpty() && this == actual
    }
}
