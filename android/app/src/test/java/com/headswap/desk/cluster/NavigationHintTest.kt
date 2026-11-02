package com.headswap.desk.cluster

import com.headswap.desk.aa.AaMeta
import org.junit.Assert.*
import org.junit.Test

class NavigationHintTest {
    @Test fun supportsManeuverTextAndDecimalUnits() {
        assertEquals(ClusterNavigationHint(AaMeta.T_LEFT, "", 450),
            NavigationHintParser.parse("Через 450 м поверните налево", "Тестовая улица", null, false))
        assertEquals(ClusterNavigationHint(AaMeta.T_RIGHT, "Тестовая улица", 1200),
            NavigationHintParser.parse("1,2 км", "Тестовая улица", AaMeta.T_RIGHT, true))
        assertEquals(805, NavigationHintParser.metres("0.5 miles"))
        assertEquals(90, NavigationHintParser.metres("90 метров"))
    }
    @Test fun ignoresMissingManeuverDistanceSpeedAndEta() {
        assertNull(NavigationHintParser.parse("До конца маршрута 8 км", "Поверните налево", null, false))
        assertNull(NavigationHintParser.parse("Скорость 60 км/ч", "Поворот налево", null, false))
        assertNull(NavigationHintParser.parse("Перестроение маршрута", "220 м", null, false))
        assertNull(NavigationHintParser.parse("Поверните направо", "", null, false))
    }
    @Test fun usesTheUpcomingManeuverWhenTheInstructionPreviewsASecondTurn() {
        assertEquals(ClusterNavigationHint(AaMeta.T_RIGHT, "", 120),
            NavigationHintParser.parse("Через 120 м поверните направо, затем через 400 м поверните налево", "", null, false))
        assertNull(NavigationHintParser.parse("Поверните направо, затем через 400 м поверните налево", "", null, false))
    }
}
