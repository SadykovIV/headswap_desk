package com.headswap.desk.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhysicalPanelTokenTest {
    class SurfaceOnly
    class DisplayWithToken {
        companion object {
            @JvmStatic fun getPhysicalDisplayToken(physicalId: Long) = physicalId
        }
    }

    @Test fun android16LooksUpExactPrimaryPhysicalAddressInDisplayControl() {
        val method = physicalPanelTokenMethod(SurfaceOnly::class.java) { DisplayWithToken::class.java }
        val internalPanel = 0x400000007fff0003L
        assertEquals(internalPanel, method.invoke(null, internalPanel))
    }

    @Test fun existingSurfaceMethodDoesNotLoadSystemServer() {
        val method = physicalPanelTokenMethod(DisplayWithToken::class.java) { error("unexpected fallback") }
        assertEquals(DisplayWithToken::class.java, method.declaringClass)
    }

    open class Address
    class Physical(val physicalDisplayId: Long) : Address()
    class StablePhysical(val physicalDisplayId: Long) : Address()
    class NonPhysical(val port: Int) : Address()

    @Test fun frameworkMayChangeConcretePhysicalAddressClassWithoutChangingTheId() {
        val id = 0x400000007fff0003L
        assertEquals(id, physicalPanelIdFromAddress(Physical(id), Address::class.java))
        assertEquals(id, physicalPanelIdFromAddress(StablePhysical(id), Address::class.java))
        assertThrows(NoSuchMethodException::class.java) { physicalPanelIdFromAddress(NonPhysical(3), Address::class.java) }
        assertThrows(IllegalStateException::class.java) { physicalPanelIdFromAddress(StablePhysical(-1L), Address::class.java) }
    }
}
