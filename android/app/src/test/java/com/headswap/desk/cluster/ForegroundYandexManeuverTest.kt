package com.headswap.desk.cluster

import com.headswap.desk.aa.AaMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundYandexManeuverTest {
    private val labels = ForegroundYandexManeuver.labelTable {
        when (it) {
            "navi_turn_left" -> listOf("Поверните налево", "Turn left")
            "navi_turn_right" -> listOf("Поверните направо", "Turn right")
            else -> emptyList()
        }
    }

    @Test fun readsPrimaryArrowAndMetricLabelsInTheOpenApp() {
        assertEquals(ClusterNavigationHint(AaMeta.T_LEFT, "", 20),
            ForegroundYandexManeuver.parse("Поверните налево", "20", "м", null, false, labels))
        assertEquals(ClusterNavigationHint(AaMeta.T_RIGHT, "Street", 1200),
            ForegroundYandexManeuver.parse("Turn right", "1,2", "km", " Street ", false, labels))
        assertEquals(1150, ForegroundYandexManeuver.parse("Turn left", "1\u202f150", "m", null, false, labels)?.distanceMeters)
    }

    @Test fun rejectsWaypointsStaleDescriptionsAndUnrelatedText() {
        assertNull(ForegroundYandexManeuver.parse("Turn left", "20", "m", "Street", true, labels))
        assertNull(ForegroundYandexManeuver.parse("Манёвр", "20", "м", null, false, labels))
        assertNull(ForegroundYandexManeuver.parse("Then turn right", "20", "m", null, false, labels))
        assertNull(ForegroundYandexManeuver.parse("Turn left", "ETA 20", "min", null, false, labels))
        assertNull(ForegroundYandexManeuver.parse("Turn left", "20", "km/h", null, false, labels))
        assertNull(ForegroundYandexManeuver.parse("Turn left", null, "m", null, false, labels))
    }

    @Test fun conflictingTranslationsCannotSelectTheWrongTurn() {
        val conflict = ForegroundYandexManeuver.labelTable {
            if (it in setOf("navi_turn_left", "navi_turn_right")) listOf("Turn") else emptyList()
        }
        assertFalse(conflict.containsKey("Turn"))
    }

    @Test fun instructionFallbackAndRouteSummaryAreNotStreetNames() {
        for (road in listOf("Поверните налево", "Turn right", "Осталось 8 км", "120 м", "Street\u0000Injected")) {
            assertEquals(ClusterNavigationHint(AaMeta.T_LEFT, "", 120),
                ForegroundYandexManeuver.parse("Turn left", "120", "m", road, false, labels))
        }
        assertEquals("улица 1905 года", ForegroundYandexManeuver.parse("Turn left", "120", "m",
            "улица 1905 года", false, labels)?.road)
    }
}
