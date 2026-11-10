package com.headswap.desk

import com.headswap.desk.aa.NaviField6
import com.headswap.desk.aa.NaviTurnInfo

/** Normalize source text once; apply receiver presentation limits only at the TBT boundary. */
internal object NavigationStreetNames {
    private const val OTHER_MAX_CODE_POINTS = 256
    private val ROAD_TYPE = Regex(
        """(?<![\p{L}\p{M}\p{N}_-])(улица|проспект)(?![\p{L}\p{M}\p{N}_-])""",
        RegexOption.IGNORE_CASE,
    )

    fun road(value: String, enabled: Boolean, honda: Boolean): String {
        if (!enabled) return ""
        val cleaned = StringBuilder()
        var offset = 0
        while (offset < value.length) {
            val cp = value.codePointAt(offset)
            offset += Character.charCount(cp)
            when {
                cp in 0xD800..0xDFFF -> Unit // An unpaired UTF-16 surrogate is not a Unicode code point.
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) ->
                    if (cleaned.isNotEmpty() && cleaned.last() != ' ') cleaned.append(' ')
                Character.isISOControl(cp) -> Unit // Includes NUL: Honda uses strlen after decoding protobuf.
                // Invisible formatting must not consume the cluster's limited width/byte budget.
                honda && Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                else -> cleaned.appendCodePoint(cp)
            }
        }
        // Abbreviate whole road-type words before cutting the receiver's limited text field.
        val source = ROAD_TYPE.replace(cleaned.toString().trim()) { match ->
            if (match.value.equals("улица", ignoreCase = true)) "ул" else "пр"
        }
        if (honda) return HondaClusterRoad.fit(source)
        val result = StringBuilder()
        var points = 0
        offset = 0
        while (offset < source.length && points < OTHER_MAX_CODE_POINTS) {
            val cp = source.codePointAt(offset)
            result.appendCodePoint(cp)
            points++
            offset += Character.charCount(cp)
        }
        return result.toString().trimEnd()
    }

    fun field6(selected: NaviField6, honda: Boolean): NaviField6 =
        if (honda) NaviField6.of(icon = true, time = selected.time) else selected

    fun format(info: NaviTurnInfo, enabled: Boolean, honda: Boolean): NaviTurnInfo =
        NaviTurnInfo(info.action, info.nextTurn, road(info.roadName, enabled && info.action != 3, honda),
            info.totalDistance, info.remainDistance, info.timeSeconds)
}

/** Guarded by the bridge. A settings change retires queued text without restarting the route/subscription. */
internal class NavigationStreetPresentation(initiallyEnabled: Boolean) {
    class Delivery internal constructor(val info: NaviTurnInfo, val enabled: Boolean,
        internal val presentation: Any, internal val route: Any)
    private var enabled = initiallyEnabled
    private var presentation = Any()
    private var route = Any()
    private var submittedRoute: Any? = null
    private var latest: NaviTurnInfo? = null

    fun accept(info: NaviTurnInfo): Delivery {
        val current = if (enabled) info else withoutRoad(info)
        if (current.action == 3) clear() else latest = current
        return Delivery(current, enabled, presentation, route)
    }

    fun change(enabled: Boolean): Delivery? {
        if (enabled == this.enabled) return null
        this.enabled = enabled
        presentation = Any()
        if (!enabled) latest = latest?.let(::withoutRoad)
        val info = latest ?: return null
        // If SHOW is still in the metadata queue, replace it with SHOW; transport ordering then preserves it.
        val refreshed = NaviTurnInfo(if (submittedRoute === route) 2 else 1, info.nextTurn, info.roadName,
            info.totalDistance, info.remainDistance, info.timeSeconds)
        return Delivery(refreshed, enabled, presentation, route)
    }

    fun current(delivery: Delivery): Boolean = delivery.info.action == 3 ||
        (delivery.presentation === presentation && delivery.route === route)

    fun submitted(delivery: Delivery) {
        if (delivery.info.action != 3 && current(delivery)) submittedRoute = delivery.route
    }

    fun clear() {
        latest = null
        submittedRoute = null
        route = Any()
    }

    private fun withoutRoad(info: NaviTurnInfo) = NaviTurnInfo(info.action, info.nextTurn, "",
        info.totalDistance, info.remainDistance, info.timeSeconds)
}
