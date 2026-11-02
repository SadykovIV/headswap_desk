package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexUserServicePolicyTest {
    @Test fun targetOneUiCapabilitySurvivesDmsFlagValidation() {
        val flags = DexUserServicePolicy.VIRTUAL_DISPLAY_FLAGS
        assertEquals(132419, flags)
        assertTrue(flags and 131072 != 0)
        assertTrue(flags and 1024 != 0)
        assertTrue(flags and 1 != 0)
        assertEquals(0, flags and (8 or 16 or 512)) // Each makes the target DMS strip the capability.
    }

    private fun geometry(id: Int = 563, logicalW: Int = 1600, logicalH: Int = 900,
        physicalW: Int = 1600, physicalH: Int = 900, density: Int = 160, flags: Int = 0x8088, expectedDensity: Int = 160) =
        DexUserServicePolicy.validateGeometry(id, logicalW, logicalH, physicalW, physicalH, density, flags, expectedDensity)

    @Test fun acceptsNative1600x900Display() = geometry()
    @Test fun acceptsRequested128DpiWithoutChangingNativePixelGeometry() = geometry(density = 128, expectedDensity = 128)
    @Test(expected = IllegalStateException::class) fun rejectsReported160When128WasRequested() = geometry(expectedDensity = 128)
    @Test fun acceptsSupportedDensityBounds() { geometry(density = 96, expectedDensity = 96); geometry(density = 640, expectedDensity = 640) }
    @Test(expected = IllegalArgumentException::class) fun rejectsDensityBelowSupportedRange() = geometry(density = 95, expectedDensity = 95)
    @Test(expected = IllegalArgumentException::class) fun rejectsDensityAboveSupportedRange() = geometry(density = 641, expectedDensity = 641)
    @Test(expected = IllegalStateException::class) fun rejectsPrimaryDisplay() = geometry(id = 0)
    @Test(expected = IllegalStateException::class) fun rejects1080BackingEvenWith900LogicalSize() = geometry(physicalW = 1920, physicalH = 1080)
    @Test(expected = IllegalStateException::class) fun rejectsLogicalDownscale() = geometry(logicalW = 1280, logicalH = 720)
    @Test(expected = IllegalStateException::class) fun rejects720BackingEvenWith900LogicalSize() = geometry(physicalW = 1280, physicalH = 720)
    @Test(expected = IllegalStateException::class) fun rejectsLostCapability() = geometry(flags = 0x88)
    @Test(expected = IllegalStateException::class) fun rejectsUntrustedDisplay() = geometry(flags = 0x8008)
    @Test(expected = IllegalStateException::class) fun rejectsDifferentDensity() = geometry(density = 240)

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test fun loadsOnlyHashVerifiedDexFromEmbeddedArchive() {
        val dex = byteArrayOf(100, 101, 120, 10)
        val archive = archive("classes.dex" to dex, "metadata.txt" to byteArrayOf(1))
        assertArrayEquals(dex, DexUserServicePolicy.verifiedDex(archive, hash(archive)))
    }
    @Test(expected = IllegalStateException::class) fun rejectsChangedArchiveBeforeParsingCode() {
        DexUserServicePolicy.verifiedDex(archive("classes.dex" to byteArrayOf(1)), "0".repeat(64))
    }
    @Test(expected = IllegalStateException::class) fun rejectsMissingDex() {
        val archive = archive("metadata.txt" to byteArrayOf(1))
        DexUserServicePolicy.verifiedDex(archive, hash(archive))
    }
    @Test(expected = IllegalStateException::class) fun rejectsUnexpectedSecondaryDex() {
        val archive = archive("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2))
        DexUserServicePolicy.verifiedDex(archive, hash(archive))
    }
    @Test(expected = IllegalStateException::class) fun rejectsOversizedInflatedDex() {
        val archive = archive("classes.dex" to ByteArray(DexUserServicePolicy.MAX_CODE_BYTES + 1))
        DexUserServicePolicy.verifiedDex(archive, hash(archive))
    }

    @Test fun dragUsesMouseButtonsForDownMoveAndRelease() {
        val state = DexMouseState()
        val down = state.events(0, 100, 200, 1)
        assertEquals(listOf(7, 0, 11), down.map { it.action })
        assertEquals(listOf(0, 1, 1), down.map { it.buttons })
        assertEquals(1, down.last().actionButton)
        assertEquals(listOf(DexMouseState.Event(2, 500, 300, 1)), state.events(2, 500, 300, 1))
        val up = state.events(1, 500, 300, 0)
        assertEquals(listOf(12, 1, 7), up.map { it.action })
        assertEquals(1, up.first().actionButton)
        assertTrue(up.all { it.buttons == 0 })
        assertEquals(0, state.pressedButtons)
    }

    @Test fun cancelAndDisconnectNeverLeavePrimaryPressed() {
        val state = DexMouseState()
        state.events(0, 1599, 899, 1)
        assertEquals(listOf(DexMouseState.Event(3, 1599, 899, 0)), state.events(3, 1599, 899, 0))
        assertTrue(state.events(1, 1599, 899, 0).isEmpty())
        assertEquals(7, state.events(2, 10, 10, 0).single().action)
        state.events(0, 10, 10, 1)
        state.reset()
        assertEquals(7, state.events(2, 30, 30, 0).single().action)
    }

    @Test fun duplicateDownCancelsOldGestureAndStartsFreshOne() {
        val state = DexMouseState()
        state.events(0, 10, 10, 1)
        assertEquals(listOf(3, 7, 0, 11), state.events(0, 20, 20, 1).map { it.action })
        assertEquals(listOf(12, 1, 7), state.events(1, 20, 20, 0).map { it.action })
    }

    @Test fun rejectedInputCannotMutatePressedState() {
        val state = DexMouseState()
        state.events(0, 10, 10, 1)
        for (invalid in listOf({ state.events(1, 1600, 10, 0) }, { state.events(5, 10, 10, 0) }, { state.events(0, 10, 10, 8) })) {
            try { invalid(); fail("Invalid event accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(1, state.pressedButtons)
        }
    }
    @Test fun verifiesRequestedNative720AndHu1024GeometryWithoutAcceptingDownscale() {
        for ((w, h) in listOf(1280 to 720, 1024 to 768)) {
            DexUserServicePolicy.validateGeometry(9, w, h, w, h, 160, 128,
                desktopMode = DesktopModePolicy.ANDROID_DESKTOP, expectedWidth = w, expectedHeight = h)
            try {
                DexUserServicePolicy.validateGeometry(9, w, h, 1600, 900, 160, 128,
                    desktopMode = DesktopModePolicy.ANDROID_DESKTOP, expectedWidth = w, expectedHeight = h)
                fail("Accepted downscaled backing")
            } catch (_: IllegalStateException) { }
            val mouse = DexMouseState(w, h)
            mouse.events(0, w - 1, h - 1, 1)
            try { mouse.events(2, w, h - 1, 1); fail("Accepted out-of-bounds input") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, mouse.pressedButtons)
        }
    }

}
