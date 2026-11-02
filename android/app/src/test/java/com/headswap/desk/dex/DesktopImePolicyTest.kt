package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopImePolicyTest {
    @Test fun optionalOutsideTapConsumesOneCancelAndNeverNavigatesTwice() {
        val gate = ChildImeDismissGate()
        assertFalse(gate.takeCancelledTap())
        gate.onTouch(0); gate.onTouch(3)
        assertTrue(gate.takeCancelledTap())
        assertFalse(gate.takeCancelledTap())
        assertFalse(gate.shouldDismiss(null, 100))
        assertFalse(gate.shouldDismiss(false, 100))
        assertTrue(gate.shouldDismiss(true, 100)); gate.markDismissed(100)
        gate.onTouch(0); gate.onTouch(3)
        assertTrue(gate.takeCancelledTap())
        assertFalse(gate.shouldDismiss(true, 200))
        assertTrue(gate.shouldDismiss(true, 601))
        gate.onTouch(0); gate.onTouch(1)
        assertFalse(gate.takeCancelledTap())
        assertTrue(gate.shouldDismiss(true, 250))
    }

    @Test fun hardwareBackOnlyTargetsAnUnambiguousVisibleOwnedChild() {
        val parent = 3
        assertEquals(5, DesktopImePolicy.backTarget(parent, mapOf(4 to false, 5 to true, 6 to false)))
        assertEquals(parent, DesktopImePolicy.backTarget(parent, emptyMap()))
        assertEquals(parent, DesktopImePolicy.backTarget(parent, mapOf(4 to false)))
        assertEquals(parent, DesktopImePolicy.backTarget(parent, mapOf(4 to true, 5 to true)))
        assertEquals(parent, DesktopImePolicy.backTarget(parent, mapOf(4 to null, 5 to true)))
        assertThrows(IllegalArgumentException::class.java) { DesktopImePolicy.backTarget(parent, mapOf(0 to true)) }
    }

    @Test fun acceptsOnlyTheRegisteredOwnersTrustedVirtualCanvases() {
        val uid = 12345
        val trusted = DexUserServicePolicy.DISPLAY_TRUSTED
        val parent = "HeadSwap Android Desktop"
        val tile = DesktopImePolicy.childName(false, emptyList())
        val fullscreen = DesktopImePolicy.childName(true, listOf(tile))
        for (name in listOf(parent, "HeadSwap DriveDeck", "HeadSwap Samsung DeX", tile, fullscreen)) {
            DesktopImePolicy.requireOwned(17, 5, trusted, uid, uid, name)
        }
        assertThrows(IllegalArgumentException::class.java) { DesktopImePolicy.requireOwned(0, 5, trusted, uid, uid, parent) }
        assertThrows(IllegalArgumentException::class.java) { DesktopImePolicy.requireOwned(3, 1, trusted, uid, uid, parent) }
        assertThrows(IllegalArgumentException::class.java) { DesktopImePolicy.requireOwned(3, 5, 0, uid, uid, parent) }
        assertThrows(IllegalArgumentException::class.java) { DesktopImePolicy.requireOwned(3, 5, trusted, uid - 1, uid, tile) }
        assertThrows(IllegalArgumentException::class.java) { DesktopImePolicy.requireOwned(3, 5, trusted, uid, uid, "other") }
    }

    @Test fun replacingEitherTileReusesItsStableNameIndependentOfItsSibling() {
        val a = DesktopImePolicy.childName(false, emptyList())
        val b = DesktopImePolicy.childName(false, listOf(a))
        val full = DesktopImePolicy.childName(true, listOf(a, b))
        assertEquals(3, setOf(a, b, full).size)
        assertEquals(a, DesktopImePolicy.childName(false, listOf(full, b)))
        assertEquals(b, DesktopImePolicy.childName(false, listOf(full, a)))
        assertEquals(full, DesktopImePolicy.childName(true, emptyList()))
    }
}
