package com.headswap.desk.cluster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShellManeuverSampleTest {
    @Test fun scopedCardSurvivesTransportWithoutStreetOrExtraWindowFields() {
        val fields = arrayOf("ru.yandex.yandexnavi", "Поверните налево", "20", "м", "ru-RU")
        val result = ShellManeuverSample.decode(fields)
        assertEquals(ShellManeuverSample.Card(fields[0], fields[1], fields[2], fields[3], fields[4]), result)
        assertArrayEquals(fields, result.encode())
    }

    @Test fun unavailableAndVisibleWithoutHintSurviveTransportSeparately() {
        for (state in listOf(ShellManeuverSample.Unavailable, ShellManeuverSample.VisibleWithoutHint))
            assertSame(state, ShellManeuverSample.decode(state.encode()))
    }

    @Test fun optedInStreetSurvivesTransportButDoesNotChangeLegacyFiveFields() {
        val card = ShellManeuverSample.Card("ru.yandex.yandexmaps", "Turn right", "100", "m", "en", "улица Карла Маркса")
        assertEquals(6, card.encode()!!.size)
        assertEquals(card, ShellManeuverSample.decode(card.encode()))
        assertEquals(card.copy(road = ""), ShellManeuverSample.decode(card.encode()!!.take(5).toTypedArray()))
    }

    @Test fun malformedOrForeignCardsFailClosed() {
        val valid = arrayOf("ru.yandex.yandexmaps", "Поверните налево", "20", "м", "ru")
        for (fields in listOf(valid + arrayOf("street", "more"), valid + "x".repeat(101), valid + "Street\u0000Injected", valid.copyOf(2),
            valid.clone().apply { this[0] = "com.other.app" },
            valid.clone().apply { this[1] = "" },
            valid.clone().apply { this[2] = "1234567890".repeat(4) },
            valid.clone().apply { this[3] = "m\n" })) {
            assertSame(ShellManeuverSample.Unavailable, ShellManeuverSample.decode(fields))
        }
    }
}
