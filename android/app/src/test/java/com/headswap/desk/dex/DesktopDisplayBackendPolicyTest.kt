package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopDisplayBackendPolicyTest {
    @Test fun localBackendRemainsForPreviouslyVerifiedPlatforms() {
        for (sdk in 33..36) for (mode in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
            assertFalse(DesktopDisplayBackendPolicy.requiresVdm(sdk, mode))
        }
        for (mode in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
            assertTrue(DesktopDisplayBackendPolicy.requiresVdm(37, mode))
        }
        assertFalse(DesktopDisplayBackendPolicy.requiresVdm(37, DesktopModePolicy.SAMSUNG_DEX))
    }

    @Test fun childrenCannotEscapeTheirParentGroup() {
        DesktopDisplayBackendPolicy.requireGroup(null, 3)
        DesktopDisplayBackendPolicy.requireGroup(3, 3)
        for ((parent, child) in listOf(null to 0, 3 to 0, 3 to 4)) {
            assertThrows(IllegalStateException::class.java) { DesktopDisplayBackendPolicy.requireGroup(parent, child) }
        }
    }

    @Test fun vdmBrokerCannotOpenForStaleOrAlreadyRegisteredGeneration() {
        val registry = DelegatedInputRegistry()
        val first = Any()
        val second = Any()
        registry.begin(1, first)
        registry.requirePendingParent(first)
        assertThrows(IllegalStateException::class.java) { registry.requirePendingParent(second) }
        registry.registerParent(7, first, 1280, 720)
        assertThrows(IllegalStateException::class.java) { registry.requirePendingParent(first) }
        registry.deactivate()
        assertThrows(IllegalStateException::class.java) { registry.requirePendingParent(first) }
        registry.begin(2, second)
        registry.requirePendingParent(second)
        assertThrows(IllegalStateException::class.java) { registry.requirePendingParent(first) }
    }
}
