package com.headswap.desk.link.car

/** Decisions shared by USB handoff, explicit wireless start and the Bluetooth trigger. */
object WirelessConnectionPolicy {
    fun savedMode(mode: String?, legacyWireless: Boolean): CarLifeConnectionMode =
        CarLifeConnectionMode.entries.firstOrNull { it.name == mode }
            ?: if (legacyWireless) CarLifeConnectionMode.WIFI else CarLifeConnectionMode.USB

    fun selectedTransport(mode: CarLifeConnectionMode, compatibleUsb: Boolean, configuredCar: Boolean = true): CarLifeConnectionMode = when (mode) {
        CarLifeConnectionMode.USB -> CarLifeConnectionMode.USB
        CarLifeConnectionMode.WIFI -> CarLifeConnectionMode.WIFI
        CarLifeConnectionMode.AUTO -> if (compatibleUsb || !configuredCar) CarLifeConnectionMode.USB else CarLifeConnectionMode.WIFI
    }

    fun mayHandoffToUsb(mode: CarLifeConnectionMode, wirelessActive: Boolean, compatibleUsb: Boolean,
        usbPermission: Boolean): Boolean = mode == CarLifeConnectionMode.AUTO && wirelessActive && compatibleUsb && usbPermission

    fun fallbackAfterUsb(mode: CarLifeConnectionMode, configuredCar: Boolean, bluetoothAuto: Boolean): Boolean =
        mode == CarLifeConnectionMode.AUTO && configuredCar || mode == CarLifeConnectionMode.WIFI && bluetoothAuto

    fun retainAutoAfterUsbDetach(enabled: Boolean, carSelected: Boolean, autoMode: Boolean): Boolean =
        enabled && carSelected && autoMode

    fun acceptAttempt(attempt: Long, current: Long, stopping: Boolean, sessionActive: Boolean): Boolean =
        attempt == current && !stopping && !sessionActive
}
