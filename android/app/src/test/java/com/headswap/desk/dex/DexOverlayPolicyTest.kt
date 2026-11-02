package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DexOverlayPolicyTest {
    private val owner = DexOverlayPolicy.Owner("0123456789abcdef0123456789abcdef", "1280x720/160,hdmi")
    private val previous = DexOverlayPolicy.Owner("fedcba9876543210fedcba9876543210", "1280x720/160,hdmi,should_show_system_decorations")
    private fun display(id: Int = 560, type: String = "OVERLAY", uniqueId: String = "overlay:1",
        width: Int = 1280, height: Int = 720, physicalWidth: Int = width, physicalHeight: Int = height, density: Int = 160) =
        DexOverlayPolicy.DisplaySnapshot(id, uniqueId, type, width, height, density, physicalWidth, physicalHeight)

    @Test fun selectsOnlyNewSystemOverlayWithNative720PhysicalAndLogicalGeometry() {
        val candidates = listOf(display(), display(id = 0), display(id = 559),
            display(id = 561, type = "EXTERNAL", uniqueId = "local:123"),
            display(id = 562, type = "VIRTUAL", uniqueId = "virtual:scrcpy"),
            display(id = 563, width = 1920, height = 1080),
            display(id = 564, physicalWidth = 1920, physicalHeight = 1080),
            display(id = 565, density = 240))
        assertEquals(listOf(display()), DexOverlayPolicy.selectNew(setOf(0, 559), candidates))
    }

    @Test fun ambiguityIsPreservedForCallerToRejectInsteadOfPickingLastId() {
        assertEquals(2, DexOverlayPolicy.selectNew(setOf(0), listOf(display(), display(id = 561, uniqueId = "overlay:2"))).size)
    }

    @Test fun extractsIdentityAndTypeFromPublicDisplayDescription() {
        val text = "Display id 560: DisplayInfo{\"Overlay #1\", displayId 560, uniqueId=\"overlay:1\", real 1280 x 720, type OVERLAY, FLAG_TRUSTED}"
        assertEquals("overlay:1", DexOverlayPolicy.uniqueId(text))
        assertEquals("OVERLAY", DexOverlayPolicy.type(text))
        assertEquals("", DexOverlayPolicy.uniqueId("Display 560"))
        assertEquals("", DexOverlayPolicy.type("unknown type"))
    }

    @Test fun handoffJournalRetainsBothOwnersAcrossCrash() {
        val owners = listOf(owner, previous)
        assertEquals(owners, DexOverlayPolicy.parseJournal(DexOverlayPolicy.journal(owners)))
        assertEquals(owner, DexOverlayPolicy.parseOwner("${owner.token}\n${owner.spec}\n"))
    }

    @Test fun corruptOrArbitraryJournalNeverAuthorizesCleanup() {
        assertTrue(DexOverlayPolicy.parseJournal("2\n${owner.token}\n${owner.spec}").isEmpty())
        assertNull(DexOverlayPolicy.parseOwner("../../other\n${owner.spec}"))
        assertNull(DexOverlayPolicy.parseOwner("${owner.token}\n1920x1080/240"))
        assertNull(DexOverlayPolicy.parseOwner("${owner.token}\n${owner.spec}\nextra"))
        assertTrue(DexOverlayPolicy.parseJournal(DexOverlayPolicy.journal(listOf(owner, previous, owner))).isEmpty())
    }

    @Test fun onlyAbsentSettingCountsAsAvailable() {
        for (value in listOf("", "\n", "null\n", "none")) assertTrue(DexOverlayPolicy.isMissing(value))
        for (value in listOf(owner.spec, "1920x1080/240", "null;1280x720/160")) assertFalse(DexOverlayPolicy.isMissing(value))
    }

    @Test fun watcherAcquiresMutexBeforeMutationAndArmsCleanupBeforeApply() {
        val script = DexOverlayPolicy.watcher(owner, previous)
        val mutex = script.indexOf("flock -n -x 9")
        assertTrue(mutex >= 0 && mutex < script.indexOf("settings delete"))
        assertTrue(script.indexOf("trap cleanup EXIT") < script.indexOf("settings put"))
        assertTrue(script.contains("read -r -t 15 line"))
        assertTrue(script.contains("[ \"\$current_owner\" = \"\$expected\" ] || return"))
        assertTrue(script.contains("if [ \"\$current\" = \"\$spec\" ]; then"))
        assertFalse(script.contains("flock -u")) // Ownership lock lasts through cleanup.
    }

    @Test(expected = IllegalArgumentException::class)
    fun watcherRejectsInjectedSpec() {
        DexOverlayPolicy.watcher(owner.copy(spec = "1280x720/160; reboot"), null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun watcherRejectsInjectedRecoveryToken() {
        DexOverlayPolicy.watcher(owner, previous.copy(token = "x' ; reboot"))
    }
}
