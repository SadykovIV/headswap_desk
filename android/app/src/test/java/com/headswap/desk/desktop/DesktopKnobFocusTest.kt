package com.headswap.desk.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopKnobFocusTest {
    private val cells = (0 until 30).map { DesktopKnobFocus.Item("app:$it", it % 3 * 100, it / 3 * 100) } +
        listOf(DesktopKnobFocus.Item("back", -100, 200), DesktopKnobFocus.Item("home", -100, 300))

    @Test fun rotationVisitsEveryApplicationAndControlIncludingOffscreenRows() {
        val focus = DesktopKnobFocus()
        assertEquals("app:0", focus.move(cells, DesktopKnobFocus.Move.NEXT))
        cells.drop(1).forEach { assertEquals(it.id, focus.move(cells, DesktopKnobFocus.Move.NEXT)) }
        assertEquals("app:0", focus.move(cells, DesktopKnobFocus.Move.NEXT))
        assertEquals("home", focus.move(cells, DesktopKnobFocus.Move.PREVIOUS))
    }

    @Test fun touchingHidesSelectionAndFirstMovementOnlyRevealsIt() {
        val focus = DesktopKnobFocus()
        focus.move(cells, DesktopKnobFocus.Move.NEXT)
        focus.move(cells, DesktopKnobFocus.Move.NEXT)
        focus.touch()
        assertFalse(focus.visible)
        assertEquals("app:1", focus.move(cells, DesktopKnobFocus.Move.NEXT))
        assertEquals("app:2", focus.move(cells, DesktopKnobFocus.Move.NEXT))
    }

    @Test fun okCannotActivateAnInvisibleOrRemovedApplication() {
        val focus = DesktopKnobFocus()
        assertNull(focus.activate(cells))
        assertEquals("app:0", focus.activate(cells))
        assertNull(focus.activate(cells.drop(1)))
        assertEquals("app:1", focus.activate(cells.drop(1)))
        assertNull(focus.activate(emptyList()))
    }

    @Test fun tiltUsesGridGeometryWhileRotationUsesSemanticOrder() {
        val focus = DesktopKnobFocus()
        focus.move(cells, DesktopKnobFocus.Move.NEXT)
        assertEquals("app:3", focus.move(cells, DesktopKnobFocus.Move.DOWN))
        assertEquals("app:4", focus.move(cells, DesktopKnobFocus.Move.RIGHT))
        assertEquals("app:1", focus.move(cells, DesktopKnobFocus.Move.UP))
        assertEquals("app:0", focus.move(cells, DesktopKnobFocus.Move.LEFT))
    }

    @Test fun stableIdsSurviveCatalogReordering() {
        val focus = DesktopKnobFocus()
        focus.move(cells, DesktopKnobFocus.Move.NEXT)
        focus.move(cells, DesktopKnobFocus.Move.NEXT)
        assertEquals("app:1", focus.activate(cells.reversed()))
        assertEquals("app:0", focus.move(cells.reversed(), DesktopKnobFocus.Move.NEXT))
    }
}
