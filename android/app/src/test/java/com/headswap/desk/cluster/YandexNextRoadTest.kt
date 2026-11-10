package com.headswap.desk.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic scoped-tree fixtures. They test field boundaries, not a particular installed Yandex version. */
class YandexNextRoadTest {
    private val pkg = "ru.yandex.yandexnavi"
    private data class Node(val id: String?, val text: String? = null, val visible: Boolean = true,
        val pkg: String? = "ru.yandex.yandexnavi", val children: List<Node> = emptyList())
    private var textReads = 0
    private val tree = object : YandexNextRoad.Tree<Node> {
        override fun visible(node: Node) = node.visible
        override fun id(node: Node) = node.id
        override fun pkg(node: Node) = node.pkg
        override fun text(node: Node): String? { textReads++; return node.text }
        override fun childCount(node: Node) = node.children.size
        override fun child(node: Node, index: Int) = node.children[index]
    }
    private fun primary(vararg children: Node) = Node("main_balloon", children = children.toList())
    private fun read(node: Node, enabled: Boolean = true) = YandexNextRoad.read(node, pkg, enabled, tree)

    @Test fun readsOnlyExactNextStreetInsideTheVerifiedPrimaryCard() {
        val card = primary(Node("current_street", "Current Avenue"),
            Node("instruction", "Поверните налево"),
            Node(null, children = listOf(Node("text_nextstreet", "  улица Карла Маркса  "))),
            Node("next_upcoming_maneuver", children = listOf(Node("text_nextstreet", "Second turn"))))
        assertEquals("улица Карла Маркса", read(card))
        assertEquals(1, textReads)
        val sample = ShellManeuverSample.Card(pkg, "Turn left", "100", "m", "en", read(card))
        val received = ShellManeuverSample.decode(sample.encode()) as ShellManeuverSample.Card
        val hint = ForegroundYandexManeuver.parse(received.arrowLabel, received.distance, received.unit,
            received.road, false, mapOf("Turn left" to com.headswap.desk.aa.AaMeta.T_LEFT))
        assertEquals("улица Карла Маркса", hint?.road)
    }

    @Test fun optionOffDoesNotReadAnyTextEvenWhenTheStreetIsAvailable() {
        assertEquals("", read(primary(Node("text_nextstreet", "Secret Road")), false))
        assertEquals(0, textReads)
    }

    @Test fun missingAndHiddenStreetNeverFallBackToCurrentRoadOrInstruction() {
        val card = primary(Node("current_street", "Current Avenue"), Node("instruction", "Turn left"),
            Node("text_nextstreet", "Hidden Road", visible = false))
        assertEquals("", read(card))
        assertEquals("", read(primary(Node(null, visible = false, children = listOf(Node("text_nextstreet", "Hidden parent"))))))
        assertEquals(0, textReads)
    }

    @Test fun foreignOrUnverifiedRootAndDuplicateFieldFailClosed() {
        assertEquals("", read(Node("screen_root", children = listOf(Node("text_nextstreet", "Wrong region")))))
        assertEquals("", read(primary(Node("text_nextstreet", "Foreign", pkg = "com.other"))))
        assertEquals("", read(primary(Node("text_nextstreet", "First"), Node("text_nextstreet", "Second"))))
        assertEquals("", read(primary(Node("text_nextstreet", "Foreign subtree", pkg = "com.other",
            children = listOf(Node("text_nextstreet", "Spoofed child"))))))
    }

    @Test fun controlsOversizedFieldAndTraversalBudgetFailClosed() {
        for (value in listOf("Street\u0000Injected", "Street\nInjected", "x".repeat(101)))
            assertEquals("", read(primary(Node("text_nextstreet", value))))
        assertEquals("", read(primary(*Array(101) { Node("decoration") })))
    }
}
