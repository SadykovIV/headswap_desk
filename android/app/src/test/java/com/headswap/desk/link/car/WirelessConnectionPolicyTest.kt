package com.headswap.desk.link.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessConnectionPolicyTest {
    @Test fun automaticSelectsUsbFirstAndOnlyFallsBackToAConfiguredCar() {
        assertEquals(CarLifeConnectionMode.USB, WirelessConnectionPolicy.selectedTransport(CarLifeConnectionMode.AUTO, true, true))
        assertEquals(CarLifeConnectionMode.USB, WirelessConnectionPolicy.selectedTransport(CarLifeConnectionMode.AUTO, false, false))
        assertEquals(CarLifeConnectionMode.WIFI, WirelessConnectionPolicy.selectedTransport(CarLifeConnectionMode.AUTO, false, true))
        assertEquals(CarLifeConnectionMode.WIFI, WirelessConnectionPolicy.selectedTransport(CarLifeConnectionMode.WIFI, true, true))
        assertEquals(CarLifeConnectionMode.USB, WirelessConnectionPolicy.selectedTransport(CarLifeConnectionMode.USB, false, true))
    }

    @Test fun anAttachedCableMayReplaceWirelessOnlyAfterExplicitAutomaticSelectionAndUsbGrant() {
        assertTrue(WirelessConnectionPolicy.mayHandoffToUsb(CarLifeConnectionMode.AUTO, true, true, true))
        assertFalse(WirelessConnectionPolicy.mayHandoffToUsb(CarLifeConnectionMode.WIFI, true, true, true))
        assertFalse(WirelessConnectionPolicy.mayHandoffToUsb(CarLifeConnectionMode.AUTO, true, true, false))
        assertFalse(WirelessConnectionPolicy.mayHandoffToUsb(CarLifeConnectionMode.AUTO, false, true, true))
        assertFalse(WirelessConnectionPolicy.mayHandoffToUsb(CarLifeConnectionMode.AUTO, true, false, true))
    }

    @Test fun priorWirelessPreferenceMigratesWithoutEnablingNewTransport() {
        assertEquals(CarLifeConnectionMode.USB, WirelessConnectionPolicy.savedMode(null, false))
        assertEquals(CarLifeConnectionMode.WIFI, WirelessConnectionPolicy.savedMode(null, true))
        assertEquals(CarLifeConnectionMode.AUTO, WirelessConnectionPolicy.savedMode("AUTO", false))
        assertEquals(CarLifeConnectionMode.USB, WirelessConnectionPolicy.savedMode("unknown", false))
        assertFalse(WirelessConnectionPolicy.fallbackAfterUsb(CarLifeConnectionMode.AUTO, false, false))
        assertTrue(WirelessConnectionPolicy.fallbackAfterUsb(CarLifeConnectionMode.AUTO, true, false))
        assertFalse(WirelessConnectionPolicy.fallbackAfterUsb(CarLifeConnectionMode.USB, true, true))
    }

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
