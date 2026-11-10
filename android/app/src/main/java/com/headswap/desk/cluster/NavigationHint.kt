package com.headswap.desk.cluster

import com.headswap.desk.aa.AaMeta
import kotlin.math.roundToInt

/** road is the next manoeuvre's street from a known navigation field, or empty; never an instruction fallback. */
internal data class ClusterNavigationHint(val nextTurn: Int, val road: String, val distanceMeters: Int?, val timeSeconds: Int? = null)

/** CarLife accepts a manoeuvre enum, not free text. Unknown directions stay unknown. */
internal object NavigationHintParser {
    private val distance = Regex("""(?<![\p{L}\d.,])(\d+(?:[.,]\d+)?)\s*(километр(?:а|ов)?|метр(?:а|ов)?|км|м|kilomet(?:er|re)s?|met(?:er|re)s?|km|m|mi|miles?|ft|feet|yd|yards?)(?![\p{L}/])""", RegexOption.IGNORE_CASE)
    private val durationOrTotal = Regex("""(?:осталось|до (?:конца|финиша|прибытия|места назначения)|remaining|to destination)""", RegexOption.IGNORE_CASE)
    private val nextInstruction = Regex("""(?:,?\s*затем\b|,?\s*(?:and\s+)?then\b)""", RegexOption.IGNORE_CASE)
    private val commands = listOf(
        """(?:развернитесь|разворот|(?:make a |take a )?u[- ]turn)""" to AaMeta.T_BACK,
        """(?:въезжайте на (?:круг|кольцо)|enter (?:the )?roundabout)""" to AaMeta.T_RING,
        """(?:съезжайте с (?:круга|кольца)|exit (?:the )?roundabout)""" to AaMeta.T_RING_OUT,
        """(?:резко налево|sharp left)""" to AaMeta.T_LEFT_BACK,
        """(?:резко направо|sharp right)""" to AaMeta.T_RIGHT_BACK,
        """(?:плавно налево|slight left|bear left)""" to AaMeta.T_LEFT_FRONT,
        """(?:плавно направо|slight right|bear right)""" to AaMeta.T_RIGHT_FRONT,
        """(?:поверните (?:налево|влево)|поворот налево|turn left)""" to AaMeta.T_LEFT,
        """(?:поверните (?:направо|вправо)|поворот направо|turn right)""" to AaMeta.T_RIGHT,
        """(?:двигайтесь прямо|продолжайте прямо|go straight|continue straight)""" to AaMeta.T_FRONT,
    ).map { (pattern, turn) -> Regex("""(?<!\p{L})(?:$pattern)(?!\p{L})""", RegexOption.IGNORE_CASE) to turn }

    fun metres(value: String): Int? {
        if (durationOrTotal.containsMatchIn(value)) return null
        val m = distance.find(value) ?: return null
        val number = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val factor = when {
            unit == "km" || unit.startsWith("к") || unit.startsWith("kilo") -> 1000.0
            unit.startsWith("mi") -> 1609.344
            unit == "ft" || unit == "feet" -> 0.3048
            unit == "yd" || unit.startsWith("yard") -> 0.9144
            else -> 1.0
        }
        val meters = number * factor
        return if (meters in 0.0..1_000_000.0) meters.roundToInt() else null
    }

    private fun turn(text: String): Int? =
        commands.mapNotNull { (pattern, action) -> pattern.find(text)?.let { it.range.first to action } }
            .minByOrNull { it.first }?.second

    internal fun roadLabel(value: String): String = value.trim().takeIf {
        it.length <= YandexNextRoad.MAX_CHARS && it.none(Char::isISOControl) && turn(it) == null &&
            distance.find(it) == null && !durationOrTotal.containsMatchIn(it)
    }.orEmpty()

    fun parse(title: String, detail: String, iconTurn: Int?, guidanceLayout: Boolean): ClusterNavigationHint? {
        val lines = listOf(title, detail).map { nextInstruction.split(it.take(260), limit = 2).first().trim() }
        val action = iconTurn ?: lines.firstNotNullOfOrNull(::turn) ?: return null
        val meters = lines.filter { turn(it) != null }.firstNotNullOfOrNull(::metres)
            ?: lines.firstNotNullOfOrNull(::metres) ?: return null
        // The known Yandex manoeuvre card puts its street below the primary title.
        // Do not send a whole notification, an ETA, or the distance left on the route as a street.
        val road = if (guidanceLayout) roadLabel(lines[1]) else ""
        return ClusterNavigationHint(action, road, meters)
    }
}
