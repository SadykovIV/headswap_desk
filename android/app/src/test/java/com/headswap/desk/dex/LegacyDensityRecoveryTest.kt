package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class LegacyDensityRecoveryTest {
    private class Phone(var raw: String?, var density: Int, var forced: Int?) : LegacyDensityRecovery.Access {
        var writes = 0
        var reads = 0
        var restoresAllowed = true
        override fun journal() = raw
        override fun current() = (density to forced).also { reads++ }
        override fun restoreOriginal(forced: Int?) {
            writes++
            if (restoresAllowed) { this.forced = forced; density = forced ?: 450 }
        }
        override fun forget() { raw = null }
    }

    @Test fun everyProjectionModeLeavesPrimaryDensityAloneWithoutPendingJournal() {
        for (mode in listOf(DesktopModePolicy.SAMSUNG_DEX, DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
            DesktopModePolicy.requireMode(mode)
            val phone = Phone(null, 420, 420)
            for (externalDpi in listOf(96, 128, 200, 640)) {
                DexUserServicePolicy.requireProjectionDensity(externalDpi, externalDpi)
                LegacyDensityRecovery.recover(phone)
            }
            assertEquals(0, phone.reads)
            assertEquals(0, phone.writes)
            assertEquals(420, phone.forced)
            assertNull(phone.raw)
        }
    }

    @Test fun existingUserValueIsOnlyRestoredForAMatchingKnownLegacyRecord() {
        val phone = Phone("v1:450:420:420:128", 128, 128)
        LegacyDensityRecovery.recover(phone)
        assertEquals(420, phone.forced)
        assertNull(phone.raw)
        LegacyDensityRecovery.recover(phone)
        assertEquals(1,phone.writes)

        val noOverride = Phone("v1:450:450:0:160",160,160)
        LegacyDensityRecovery.recover(noOverride)
        assertNull(noOverride.forced)
        assertEquals(450,noOverride.density)
    }

    @Test fun userChangesMalformedRecordsAndFailedRecoveryAreNeverGuessed() {
        val changed = Phone("v1:450:420:420:128", 390, 390)
        LegacyDensityRecovery.recover(changed)
        assertEquals(0,changed.writes)
        assertEquals(390,changed.forced)
        val invalid = Phone("v1:450:1:420:128",128,128)
        assertThrows(IllegalStateException::class.java) { LegacyDensityRecovery.recover(invalid) }
        assertEquals(0,invalid.writes)
        assertNotNull(invalid.raw)
        val refused = Phone("v1:450:420:420:128",128,128)
        refused.restoresAllowed=false
        assertThrows(IllegalStateException::class.java) { LegacyDensityRecovery.recover(refused) }
        assertNotNull(refused.raw)
    }

    @Test fun aDifferentScrcpyDensityCannotBeReportedAsMatched() {
        assertThrows(IllegalStateException::class.java) { DexUserServicePolicy.requireProjectionDensity(160,128) }
    }
}
