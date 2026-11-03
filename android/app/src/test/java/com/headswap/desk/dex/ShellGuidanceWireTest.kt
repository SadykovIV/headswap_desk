package com.headswap.desk.dex

import com.headswap.desk.cluster.ShellManeuverSample
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellGuidanceWireTest {
    @Test fun threeReplyTypesAndAdjacentPanelAckStayInFrame() {
        val card = ShellManeuverSample.Card("ru.yandex.yandexnavi", "Turn left", "200", "m", "en")
        val samples = listOf(ShellManeuverSample.Unavailable, ShellManeuverSample.VisibleWithoutHint, card)
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        samples.forEach { ShellGuidanceWire.write(output, it) }
        output.writeByte(RemotePanelWire.OK)
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(samples, samples.map { ShellGuidanceWire.read(input) })
        assertEquals(RemotePanelWire.OK, input.readUnsignedByte())
        assertEquals(-1, input.read())
    }

    @Test fun malformedFieldCountIsRejectedWithoutConsumingTheNextFrame() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(42, RemotePanelWire.OK.toByte())))
        assertThrows(IOException::class.java) { ShellGuidanceWire.read(input) }
        assertEquals(RemotePanelWire.OK, input.readUnsignedByte())
    }
}
