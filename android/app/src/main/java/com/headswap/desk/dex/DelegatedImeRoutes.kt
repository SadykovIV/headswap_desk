package com.headswap.desk.dex

/** Keep retries scoped to exact registrations. Holding this monitor across the scoped shell
 * request lets unregister/deactivate drain it before its display ID can be reused. It is
 * separate from the input monitor: normal pointer and key events do not acquire this lock. */
internal class DelegatedImeRoutes {
    private class Route(val entry: DelegatedInputRegistry.Entry) {
        var applied = false
        var retryAt = 0L
        var delay = FIRST_RETRY_MS
    }
    private val routes = LinkedHashMap<Int, Route>()
    private var channel: Any? = null

    @Synchronized fun register(entry: DelegatedInputRegistry.Entry) {
        require(entry.id > 0)
        check(!routes.containsKey(entry.id))
        routes[entry.id] = Route(entry)
    }

    @Synchronized fun unregister(entry: DelegatedInputRegistry.Entry) {
        if (routes[entry.id]?.entry !== entry) return
        if (entry.parent == null) clear() else routes.remove(entry.id)
    }

    @Synchronized fun clear() {
        routes.clear()
        channel = null
    }

    @Synchronized fun refresh(connection: Any?, now: Long, only: DelegatedInputRegistry.Entry? = null,
        apply: (DelegatedInputRegistry.Entry) -> Boolean) {
        if (channel !== connection) {
            channel = connection
            for (route in routes.values) {
                route.applied = false
                route.retryAt = 0L
                route.delay = FIRST_RETRY_MS
            }
        }
        if (connection == null) return
        val due = routes.values.filter { !it.applied && it.retryAt <= now && (only == null || it.entry === only) }
            .sortedBy { it.retryAt }
        for (route in due) {
            if (apply(route.entry)) route.applied = true
            else {
                route.retryAt = now + route.delay
                route.delay = (route.delay * 2).coerceAtMost(MAX_RETRY_MS)
                break // One unavailable or slow call per heartbeat. Other due displays get their turn.
            }
        }
    }

    private companion object {
        const val FIRST_RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 60_000L
    }
}
