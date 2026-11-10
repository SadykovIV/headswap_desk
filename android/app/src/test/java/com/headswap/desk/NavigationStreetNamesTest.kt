package com.headswap.desk

import com.headswap.desk.aa.NaviField6
import com.headswap.desk.aa.NaviTurnInfo
import org.junit.Assert.*
import org.junit.Test

class NavigationStreetNamesTest {
    private fun turn(road: String = "TEST HUD 123", action: Int = 1) = NaviTurnInfo(action, 7, road, 500, 180, 12)

    @Test fun offHasRequiredEmptyRoadWithoutChangingArrowOrDistances() {
        val original = turn()
        val sent = NavigationStreetNames.format(original, false, true)
        assertEquals("", sent.roadName)
        assertEquals(original.action, sent.action)
        assertEquals(original.nextTurn, sent.nextTurn)
        assertEquals(original.totalDistance, sent.totalDistance)
        assertEquals(original.remainDistance, sent.remainDistance)
        assertEquals(original.timeSeconds, sent.timeSeconds)
        val wire = PbReader.parse(Msgs.naviNextTurn(sent, NaviField6.ICON, ByteArray(0)))!!
        assertEquals("", PbReader.str(wire, 3))
        assertEquals(2, PbReader.all(wire, 6).single().wire)
    }

    @Test fun hondaFitsTheClusterRendererNotOnlyTheLargerNativeBuffer() {
        assertEquals("TEST HUD 123", NavigationStreetNames.road("TEST HUD 123", true, true))
        assertEquals("ул. Ленина", NavigationStreetNames.road("ул. Ленина", true, true))
        assertEquals("A".repeat(14), NavigationStreetNames.road("A".repeat(40), true, true))
        assertEquals("Я".repeat(12), NavigationStreetNames.road("Я".repeat(25), true, true))
    }

    @Test fun byteLimitNeverSplitsEmojiOrOtherUnicodeCodePoint() {
        assertEquals("🚙".repeat(7), NavigationStreetNames.road("🚙".repeat(10), true, true))
        assertEquals("A".repeat(14), NavigationStreetNames.road("A".repeat(35) + "🚙", true, true))
        assertEquals("中".repeat(10), NavigationStreetNames.road("中".repeat(13), true, true))
        for (source in listOf("Я🚙Main路".repeat(8), "Test 👨‍👩‍👧‍👦 Street".repeat(3))) {
            val result = NavigationStreetNames.road(source, true, true)
            val bytes = result.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= 31)
            assertEquals(result, bytes.toString(Charsets.UTF_8))
            assertFalse(result.lastOrNull()?.isHighSurrogate() == true)
        }
    }

    @Test fun controlCharactersEmptyAndMalformedUtf16CannotReachTheReceiver() {
        assertEquals("ул. Ленина", NavigationStreetNames.road("  ул.\n Ленина\u0000\u0007 ", true, true))
        assertEquals("AB", NavigationStreetNames.road("A\uD800B", true, true))
        for (value in listOf("", " \r\n\t", "\u0000\u0007\uD800"))
            assertEquals("", NavigationStreetNames.road(value, true, true))
        assertEquals("", NavigationStreetNames.format(turn(action = 3), true, true).roadName)
    }

    @Test fun researchedHondaByteLimitDoesNotTruncateOtherHeadUnits() {
        assertEquals("Long Road " + "A".repeat(60), NavigationStreetNames.road("Long Road " + "A".repeat(60), true, false))
        val limited = NavigationStreetNames.road("🚙".repeat(300), true, false)
        assertEquals(256, limited.codePointCount(0, limited.length))
    }

    @Test fun roadTypesAreAbbreviatedAsWholeWordsBeforeApplyingReceiverLimits() {
        for ((source, expected) in listOf(
            "улица Мира" to "ул Мира", "Мира УЛИЦА" to "Мира ул",
            "Проспект Мира" to "пр Мира", "Мира ПРОСПЕКТ" to "Мира пр",
            "  улица\u00a0  Мира  " to "ул Мира", "улица, проспект;" to "ул, пр;",
            "Минская улица" to "Минская ул", "проспект Мира, улица Ленина" to "пр Мира, ул Ленина",
        )) {
            assertEquals(source, expected, NavigationStreetNames.road(source, true, false))
        }
        // Without abbreviation first, the full road type would use most of Honda's display cells.
        assertEquals("пр Мира", NavigationStreetNames.road("ПРОСПЕКТ Мира", true, true))
        assertEquals("ул Ленина", NavigationStreetNames.road("Улица Ленина", true, true))
    }

    @Test fun abbreviationsDoNotRewritePartsOfStreetNamesOrAlreadyAbbreviatedTypes() {
        for (source in listOf("Проспектный проезд", "Улицанская", "улицами", "Ново-проспект",
            "улицаX", "_улица", "улица\u0301", "ул. Ленина", "пр Мира", "пр-т Мира")) {
            assertEquals(source, source, NavigationStreetNames.road(source, true, false))
        }
    }

    @Test fun formattingAddsNeitherInterletterSpacesNorTrailingPadding() {
        for (honda in listOf(false, true)) {
            assertEquals("Нахимовский", NavigationStreetNames.road("Нахимовский", true, honda))
            assertEquals("ул Мира", NavigationStreetNames.road("  улица   Мира ", true, honda))
        }
    }

    @Test fun hondaAlwaysIncludesRequiredBytesFieldSixWithLegacyIconOffSettings() {
        for (selected in NaviField6.values()) {
            val wire = PbReader.parse(Msgs.naviNextTurn(turn(), NavigationStreetNames.field6(selected, true), ByteArray(0)))!!
            val six = PbReader.all(wire, 6)
            assertEquals(1, six.count { it.wire == 2 && it.data?.isEmpty() == true })
            assertEquals(if (selected.time) 1 else 0, six.count { it.wire == 0 })
            assertEquals(selected, NavigationStreetNames.field6(selected, false))
        }
    }

    @Test fun toggleUpdatesCurrentManeuverAndRetiresQueuedOldText() {
        val state = NavigationStreetPresentation(true)
        val shown = state.accept(turn())
        state.submitted(shown)
        val queued = state.accept(turn("ул. Ленина", 2))
        val hidden = state.change(false)!!
        assertFalse(state.current(queued))
        assertTrue(state.current(hidden))
        assertEquals(2, hidden.info.action)
        assertEquals(7, hidden.info.nextTurn)
        assertEquals(180, hidden.info.remainDistance)
        assertEquals("", hidden.info.roadName)
        assertEquals("", NavigationStreetNames.format(hidden.info, hidden.enabled, true).roadName)
        val visible = state.change(true)!!
        assertFalse(state.current(hidden))
        assertEquals("", visible.info.roadName) // OFF erased the cache; ON needs a fresh, allowed sample.
        val fresh = state.accept(turn("ул. Ленина", 2))
        assertEquals("ул. Ленина", NavigationStreetNames.format(fresh.info, fresh.enabled, true).roadName)
        assertNull(state.change(true))
    }

    @Test fun aDisabledDeliveryDoesNotCacheStreetTextForLaterReplay() {
        val state = NavigationStreetPresentation(false)
        assertEquals("", state.accept(turn("Hidden Street")).info.roadName)
        assertEquals("", state.change(true)!!.info.roadName)
    }

    @Test fun toggleCannotReplaceAnUnsentInitialShowWithAnUpdate() {
        val state = NavigationStreetPresentation(false)
        val queuedShow = state.accept(turn())
        val queuedUpdate = state.accept(turn(action = 2))
        val replacement = state.change(true)!!
        assertEquals(1, replacement.info.action)
        assertFalse(state.current(queuedShow))
        assertFalse(state.current(queuedUpdate))
        state.submitted(replacement)
        assertEquals(2, state.change(false)!!.info.action)
    }

    @Test fun endAndStoppedSubscriptionCannotBeResurrectedBySettings() {
        val state = NavigationStreetPresentation(true)
        val queuedShow = state.accept(turn())
        val end = state.accept(turn(action = 3))
        assertNull(state.change(false))
        assertFalse(state.current(queuedShow))
        assertTrue(state.current(end)) // A settings change must never drop the end-of-route message.
        state.accept(turn())
        state.clear() // Subscription STOP clears the replay cache.
        assertNull(state.change(true))
    }
}
