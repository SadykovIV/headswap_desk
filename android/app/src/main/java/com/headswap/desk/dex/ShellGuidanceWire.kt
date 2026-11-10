package com.headswap.desk.dex

import com.headswap.desk.cluster.ShellManeuverSample
import java.io.DataInputStream
import java.io.DataOutputStream

/** The current turn and distance; an opted-in request may also include its next-street label. */
internal object ShellGuidanceWire {
    fun write(output: DataOutputStream, sample: ShellManeuverSample) {
        val fields = sample.encode()
        output.writeByte(fields?.size ?: -1)
        fields?.forEach(output::writeUTF)
    }

    fun read(input: DataInputStream): ShellManeuverSample = when (val count = input.readByte().toInt()) {
        -1 -> ShellManeuverSample.Unavailable
        0, 5, 6 -> ShellManeuverSample.decode(Array(count) { input.readUTF() })
        else -> throw java.io.IOException("Unknown guidance response")
    }
}
