package com.headswap.desk.link.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessConnectionPolicyTest {
    @Test fun lateConnectionCannotReviveStoppedSessionOrReplaceUsb() {
        assertTrue(WirelessConnectionPolicy.acceptAttempt(7, 7, false, false))
        assertFalse(WirelessConnectionPolicy.acceptAttempt(7, 8, false, false))
        assertFalse(WirelessConnectionPolicy.acceptAttempt(7, 7, true, false))
        assertFalse(WirelessConnectionPolicy.acceptAttempt(7, 7, false, true))
    }

    @Test fun usbDetachRetainsOnlyPreviouslyChosenNearbyCar() {
        assertTrue(WirelessConnectionPolicy.retainAutoAfterUsbDetach(true, true, true))
        assertFalse(WirelessConnectionPolicy.retainAutoAfterUsbDetach(false, true, true))
        assertFalse(WirelessConnectionPolicy.retainAutoAfterUsbDetach(true, false, true))
        assertFalse(WirelessConnectionPolicy.retainAutoAfterUsbDetach(true, true, false))
    }
}
