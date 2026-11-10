package com.headswap.desk

import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.aa.NaviField6
import com.headswap.desk.aa.NaviTurnInfo
import org.junit.Assert.*
import org.junit.Test

class HondaClusterRoadTest {
    private fun fit(value: String) = NavigationStreetNames.road(value, true, true)

    @Test fun knownHondaChannelsSelectCompatibilityWithoutAffectingOtherHeadUnits() {
        for (channel in listOf("20772115", "20772124", "20832109", "20832125"))
            assertTrue(channel, AaBridgeCore.isHondaChannel(channel))
        for (channel in listOf("20622116", "2077", "207721240", "", null))
            assertFalse("$channel", AaBridgeCore.isHondaChannel(channel))
        val road = "Нахимовский проспект"
        assertEquals("Нахимовский пр", NavigationStreetNames.road(road, true, false))
    }

    @Test fun longRussianNameRetainsRecognizableNameWithinTheClusterWidth() {
        assertEquals("Минская", fit("Минская"))
        assertEquals("Нахимовский", fit("Нахимовский проспект"))
        assertEquals("ул. Октябрьск", fit("ул. Октябрьская"))
        assertEquals("Спасо-Тушинс", fit("Спасо-Тушинский бул."))
    }

    @Test fun trailingSpaceAtTheCutDoesNotConsumeTheVisiblePrefix() {
        assertEquals("abcdefghijklm", fit("abcdefghijklm nop"))
    }

    @Test fun invisibleCharactersCannotExhaustTheDisplayBudget() {
        assertEquals(fit("Нахимовский"), fit("\u200eНахи\u00adмовский\u200f"))
        assertEquals(fit("ул. Ленина"), fit("ул.\u00a0Ленина"))
        assertEquals("", fit("\u200f\u0000\uD800"))
    }

    @Test fun wireTextSatisfiesAllRendererLimitsAcrossUnicodeAndEveryBoundary() {
        val samples = buildList {
            addAll(listOf("Мира", "Нахимовский проспект", "ул. Октябрьская", "人民路", "Ленина 🚙 улица"))
            for (count in 0..80) for (unit in listOf("A", "Я", "中", "🚙", "Я🚙Main路 ")) add(unit.repeat(count))
        }
        for (source in samples) {
            val wire = fit(source)
            val bytes = wire.toByteArray(Charsets.UTF_8)
            val visible = wire.trimEnd(' ')
            assertTrue(source, bytes.size <= 31)
            assertEquals(source, wire, bytes.toString(Charsets.UTF_8))
            assertEquals(source, wire, fit(wire))
            assertFalse(source, wire.contains('\u0000'))
            if (wire.isEmpty()) continue
            val points = visible.codePointCount(0, visible.length)
            assertTrue(source, points <= 14)
            assertTrue(source, visible.codePoints().map { if (it < 0x80) 1 else 2 }.sum() <= 24)
            assertEquals(source, visible, wire)
        }
    }

    @Test fun shortenedOrEmptyRoadNeverChangesManeuverOrDistanceInSerializedPackets() {
        for (road in listOf("Нахимовский проспект", "Я".repeat(100), "A".repeat(100), "\u0000", "")) {
            for (action in listOf(1, 2)) for (turn in listOf(3, 7, 21)) {
                val info = NaviTurnInfo(action, turn, road, 4391, 3811, 90)
                val formatted = NavigationStreetNames.format(info, true, true)
                val fields = PbReader.parse(Msgs.naviNextTurn(formatted, NaviField6.BOTH, ByteArray(0)))!!
                assertEquals(action, PbReader.int(fields, 1))
                assertEquals(turn, PbReader.int(fields, 2))
                assertEquals(4391, PbReader.int(fields, 4))
                assertEquals(3811, PbReader.int(fields, 5))
                assertTrue(PbReader.all(fields, 3).single().data!!.size <= 31)
                assertTrue(PbReader.all(fields, 6).any { it.wire == 2 })
                assertTrue(PbReader.all(fields, 6).any { it.wire == 0 && it.num == 90L })
            }
        }
    }
}
