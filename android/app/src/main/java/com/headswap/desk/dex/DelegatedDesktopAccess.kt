package com.headswap.desk.dex

import android.content.Context
import android.content.pm.PackageManager

/** A selected backend is not proof of access: the live bootstrap and all grants are required. */
object DelegatedDesktopAccess {
    const val PROTOCOL = 1
    const val LOG_TAG = "HeadSwapDelegated"
    const val ADD_ALWAYS_UNLOCKED_DISPLAY = "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY"
    const val ADD_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
    const val INJECT_EVENTS = "android.permission.INJECT_EVENTS"
    const val REAL_GET_TASKS = "android.permission.REAL_GET_TASKS"
    const val MEDIA_CONTENT_CONTROL = "android.permission.MEDIA_CONTENT_CONTROL"
    enum class Status { INACTIVE, PERMISSION_LOST, READY }

    private val bootstrap = DelegatedBootstrapState()

    fun status(context: Context): Status {
        if (!bootstrap.isActive()) return Status.INACTIVE
        return if (hasPermissions(context)) Status.READY else Status.PERMISSION_LOST
    }

    fun isReady(context: Context): Boolean = status(context) == Status.READY

    /** Also usable in the separate app-UID producer process, which has no bootstrap registry. */
    fun hasPermissions(context: Context): Boolean = runCatching {
        context.checkSelfPermission(ADD_TRUSTED_DISPLAY) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(REAL_GET_TASKS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Optional capability; unsupported lock isolation never invalidates ordinary activation. */
    fun canKeepDisplayUnlocked(context: Context): Boolean = runCatching {
        context.checkPermission(ADD_ALWAYS_UNLOCKED_DISPLAY, android.os.Process.myPid(), android.os.Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    internal fun begin(stop: () -> Unit): DelegatedBootstrapState.Lease = bootstrap.begin(stop)
    internal fun end(lease: DelegatedBootstrapState.Lease) = bootstrap.end(lease)

    /** Explicitly ends activation and may close the app. USB/session Stop must never call this. */
    fun requestStop(): Boolean = bootstrap.requestStop()
}
