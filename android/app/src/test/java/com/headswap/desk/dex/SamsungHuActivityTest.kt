package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class SamsungHuActivityTest {
    class Motion {
        var tagged = false
        fun semSetNonResetUserActivityTimeout() { tagged = true }
    }
    class Key {
        var tagged = false
        fun semSetNonResetUserActivityTimeout() { tagged = true }
    }
    class Missing
    class Rejected { fun semSetNonResetUserActivityTimeout() { error("unavailable") } }

    @Test fun bothPointerAndKeyInputOnOwnedCarLifeDisplaysCanSuppressActivity() {
        val access = SamsungHuActivity(Motion::class.java, Key::class.java)
        val motion = Motion()
        val key = Key()
        assertTrue(access.mark(motion, 1, true))
        assertTrue(access.mark(key, 2, true))
        assertTrue(motion.tagged)
        assertTrue(key.tagged)
    }

    @Test fun neverMarksPhysicalScreenOrAnInactiveReceiver() {
        val access = SamsungHuActivity(Motion::class.java, Key::class.java)
        val motion = Motion()
        assertFalse(access.mark(motion, 0, true))
        assertFalse(access.mark(motion, -1, true))
        assertFalse(access.mark(motion, 1, false))
        assertFalse(motion.tagged)
    }

    @Test fun unavailableFirmwareExtensionDoesNotBreakEventDelivery() {
        val access = SamsungHuActivity(Missing::class.java, Rejected::class.java)
        assertFalse(access.mark(Missing(), 1, true))
        assertFalse(access.mark(Rejected(), 1, true))
        assertFalse(access.mark(Any(), 1, true))
    }
}
