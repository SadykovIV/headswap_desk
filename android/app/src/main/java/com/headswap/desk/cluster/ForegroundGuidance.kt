package com.headswap.desk.cluster

internal sealed interface ForegroundGuidance {
    data class Maneuver(val hint: ClusterNavigationHint) : ForegroundGuidance
    /** A recognised Yandex route screen explicitly shows that guidance has ended. */
    data object VisibleWithoutManeuver : ForegroundGuidance
    /** No target, an unreadable card, a failed request or a timeout. */
    data object Unavailable : ForegroundGuidance
}

internal typealias RequestForegroundGuidance = ((ForegroundGuidance) -> Unit) -> Unit

/** Owned by the source worker. During a source switch, allow one retry within a single
 * three-second window. Only a fresh manoeuvre can start another window. */
internal class RouteGuidanceCoordinator(
    private val requestForeground: RequestForegroundGuidance?,
    private val onWorker: (() -> Unit) -> Unit,
    private val after: (Long, () -> Unit) -> Unit,
    private val emit: (ClusterNavigationHint?) -> Unit,
) {
    private var enabled = false
    private var notification: ClusterNavigationHint? = null
    private var retiredNotification: ClusterNavigationHint? = null
    private var foreground: ForegroundGuidance = ForegroundGuidance.Unavailable
    private var nextId = 0L
    private var pending: Long? = null
    private var gap: Long? = null
    private var retriedGap: Long? = null
    private var sent = false
    private var last: ClusterNavigationHint? = null
    private var notificationRevision = 0L

    fun notifications(hint: ClusterNavigationHint?, canReadForeground: Boolean) {
        val withdrawn = notification != null && hint == null
        val changed = hint != notification
        if (changed) {
            notificationRevision++
            retiredNotification = null
            // A live notification arriving as Yandex moves to the background supersedes the old window.
            if (hint != null) foreground = ForegroundGuidance.Unavailable
        }
        notification = hint
        val previouslyEnabled = enabled
        enabled = canReadForeground && requestForeground != null
        if (!enabled) {
            pending = null
            foreground = ForegroundGuidance.Unavailable
        } else if (withdrawn) {
            pending = null // Ignore replies sampled before the notification disappeared.
            foreground = ForegroundGuidance.Unavailable
        }
        publish()
        if (enabled && (!previouslyEnabled || withdrawn)) requestSample()
    }

    fun poll() {
        if (gap == null) requestSample() // the gap owns its one retry and its original deadline
    }

    private fun requestSample() {
        val request = requestForeground ?: return
        if (!enabled || pending != null) return
        val id = ++nextId
        val revision = notificationRevision
        pending = id
        after(1_500) { finish(id, revision, ForegroundGuidance.Unavailable) }
        try {
            request { result -> onWorker { finish(id, revision, result) } }
        } catch (_: Exception) { // includes a disconnected Android binder
            finish(id, revision, ForegroundGuidance.Unavailable)
        }
    }

    private fun finish(id: Long, revision: Long, result: ForegroundGuidance) {
        if (pending != id) return // timed out, disconnected, or superseded
        pending = null
        if (notificationRevision != revision) return
        foreground = result
        // An unremoved old card must not resurrect a finished route when Yandex leaves the screen.
        if (result == ForegroundGuidance.VisibleWithoutManeuver) retiredNotification = notification
        publish()
        val current = gap ?: return
        if (retriedGap != current) {
            retriedGap = current
            after(500) { if (gap == current) requestSample() }
        }
    }

    private fun publish() {
        val value = when (val current = foreground) {
            is ForegroundGuidance.Maneuver -> current.hint
            ForegroundGuidance.VisibleWithoutManeuver -> null
            ForegroundGuidance.Unavailable -> notification?.takeUnless { it == retiredNotification }
        }
        if (enabled && value == null && last != null && foreground == ForegroundGuidance.Unavailable) {
            if (gap == null) {
                val current = ++nextId
                gap = current
                after(3_000) {
                    if (gap == current) {
                        gap = null
                        pending = null
                        send(null)
                    }
                }
            }
            return
        }
        gap = null
        send(value)
    }

    private fun send(value: ClusterNavigationHint?) {
        if (sent && value == last) return
        sent = true
        last = value
        emit(value)
    }
}
