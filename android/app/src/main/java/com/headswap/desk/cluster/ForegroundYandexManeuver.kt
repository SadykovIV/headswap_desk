package com.headswap.desk.cluster

import android.content.Context
import android.content.res.Configuration
import com.headswap.desk.aa.AaMeta
import java.util.Locale

/** Only the primary ContextManeuverView balloon, never its secondary preview or map texture. */
internal object ForegroundYandexManeuver {
    const val PRIMARY = "main_balloon"
    const val ARROW = "image_maneuverballoon_maneuver"
    const val DISTANCE = "text_maneuverballoon_distance"
    const val UNIT = "text_maneuverballoon_metrics"
    const val ROAD = "text_nextstreet"
    const val VIA = "text_via_point_number"

    // ContextManeuverView resolves ManeuverAccessibilityTextId to these resources on the arrow.
    private val arrowResources = mapOf(
        "navi_turn_right" to AaMeta.T_RIGHT, "navi_turn_left" to AaMeta.T_LEFT,
        "navi_drive_straight" to AaMeta.T_FRONT,
        "navi_make_a_slight_left" to AaMeta.T_LEFT_FRONT, "navi_make_a_slight_right" to AaMeta.T_RIGHT_FRONT,
        "accessibility_maneuver_hard_turn_left" to AaMeta.T_LEFT_BACK, "accessibility_maneuver_hard_turn_right" to AaMeta.T_RIGHT_BACK,
        "navi_make_a_left_u_turn" to AaMeta.T_BACK, "navi_make_a_right_u_turn" to AaMeta.T_BACK,
        "navi_enter_the_roundabout" to AaMeta.T_RING, "navi_exit_the_roundabout" to AaMeta.T_RING_OUT,
        "navi_exit_to_the_left" to AaMeta.T_LEFT_SIDE, "navi_exit_to_the_right" to AaMeta.T_RIGHT_SIDE,
        "transfer_to_ferry" to AaMeta.T_IN_FERRY, "accessibility_routes_route_finish" to AaMeta.T_DEST
    )
    private val number = Regex("""(?:[0-9]{1,7}|[0-9]{1,3}(?:[ \u00a0\u202f][0-9]{3}){1,2})(?:[.,][0-9]{1,3})?""")
    private val unitName = Regex("""\p{L}{1,12}""")

    private data class Cached(val updated: Long, val locales: List<String>, val labels: Map<String, Int>)
    private val cache = mutableMapOf<String, Cached>()

    /** No foreign code loaded. Keys come only from installed stock maneuver strings. */
    @Synchronized
    fun labels(context: Context, pkg: String, languageTags: String? = null): Map<String, Int> {
        if (pkg !in YandexGuidanceCard.PACKAGES) return emptyMap()
        val app = context.createPackageContext(pkg, Context.CONTEXT_RESTRICTED)
        val locales = (languageTags.orEmpty().split(',') +
            app.resources.configuration.locales.toLanguageTags().split(',') + listOf("ru", "en"))
            .filter(String::isNotBlank).distinct()
        val updated = app.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
        cache[pkg]?.takeIf { it.updated == updated && it.locales == locales }?.let { return it.labels }
        val resources = locales.map { tag ->
            app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }).resources
        }
        val labels = labelTable { name ->
            val id = app.resources.getIdentifier(name, "string", pkg)
            if (id == 0) emptyList() else resources.map { it.getString(id) }
        }
        cache[pkg] = Cached(updated, locales, labels)
        return labels
    }

    internal fun labelTable(resolve: (String) -> List<String>): Map<String, Int> {
        val turns = mutableMapOf<String, MutableSet<Int>>()
        for ((name, turn) in arrowResources) for (label in resolve(name)) {
            if (label.isNotBlank()) turns.getOrPut(label.trim()) { mutableSetOf() }.add(turn)
        }
        return turns.mapNotNull { (label, values) -> values.singleOrNull()?.let { label to it } }.toMap()
    }

    fun parse(arrowLabel: String?, distance: String?, unit: String?, road: String?, viaPointVisible: Boolean,
        turnLabels: Map<String, Int>): ClusterNavigationHint? {
        // The publisher reuses the same ImageView for a waypoint and can leave the last arrow description on it.
        if (viaPointVisible) return null
        val turn = turnLabels[arrowLabel?.trim()] ?: return null
        val value = distance?.trim()?.takeIf(number::matches) ?: return null
        val symbol = unit?.trim()?.takeIf(unitName::matches) ?: return null
        val metres = NavigationHintParser.metres(value.filterNot(Char::isWhitespace) + " " + symbol) ?: return null
        return ClusterNavigationHint(turn, road.orEmpty().trim().filterNot(Char::isISOControl).take(100), metres)
    }
}
