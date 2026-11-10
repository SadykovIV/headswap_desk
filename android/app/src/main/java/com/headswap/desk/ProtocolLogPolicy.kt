package com.headswap.desk

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Packet logging has no raw-byte fallback, including malformed or unknown control messages. */
internal object ProtocolLogPolicy {
    data class Entry(val header: String, val detail: String?)

    fun mayExposePayload(channel: Int, type: Int): Boolean =
        (channel == Ch.CMD || channel == Ch.TOUCH) && type ushr 16 == channel && Schemas.byType.containsKey(type)

    @Suppress("UNUSED_PARAMETER") // Keep the logging call contract; never trust pre-rendered raw payload/note strings.
    fun message(dir: String, channel: Int, outerLength: Int, type: Int, length: Int,
        decoded: String?, inner: ByteArray, note: String = ""): Entry {
        val header = "$dir ${Ch.name(channel)} ${Msg.hex(type)} ${Msg.name(type)} len=$length"
        if (!mayExposePayload(channel, type)) return Entry(header, null)
        val packet = Wire.parseCmd(inner)?.takeIf { it.type == type && !it.truncated }
            ?: return Entry(header, null)
        val metadata = protobuf(packet.body, Schemas.byType[type]) ?: return Entry(header, null)
        return Entry("$header $metadata", null)
    }

    /** Known, correctly typed protobuf fields only. Unknown/nested/binary failures never quote bytes. */
    fun protobuf(body: ByteArray, schema: Schema?, depth: Int = 0): String? {
        if (schema == null || depth > 4) return null
        val fields = PbReader.parse(body) ?: return null
        val cluster = schema === Schemas.mediaInfo || schema === Schemas.naviNextTurnInfo
        return fields.mapNotNull { field ->
            val definition = schema.fields[field.number] ?: return@mapNotNull null
            // Numeric layout diagnostics only; never log private street text. In UTF-8, 0x20
            // cannot be part of another character, so counting it needs no text decoding.
            if (schema === Schemas.naviNextTurnInfo && field.number == 3 && field.wire == 2) {
                val bytes = field.data!!
                return@mapNotNull "roadNameBytes=${bytes.size} roadNameSpaces=${bytes.count { it == 0x20.toByte() }}"
            }
            // HUD packets carry personal track, artist and road names. Log only their numeric state.
            if (cluster && definition.kind != Kind.INT) return@mapNotNull null
            val value = when {
                field.wire == 0 && definition.kind == Kind.INT -> field.num.toInt().toString()
                field.wire == 0 && definition.kind == Kind.BOOL -> (field.num != 0L).toString()
                field.wire == 2 && definition.kind == Kind.MSG ->
                    protobuf(field.data!!, definition.sub, depth + 1) ?: "<omitted>"
                field.wire == 2 && definition.kind == Kind.BYTES -> "<${field.data!!.size} bytes>"
                field.wire == 2 && definition.kind == Kind.STR -> safeString(field.data!!)
                else -> "<omitted>"
            }
            "${definition.name}=$value"
        }.joinToString(" ", "{", "}")
    }

    private fun safeString(bytes: ByteArray): String {
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (text == null || text.any { it.isISOControl() }) return "<${bytes.size} bytes>"
        return "\"" + text.take(256).replace("\\", "\\\\").replace("\"", "\\\"") +
            (if (text.length > 256) "…" else "") + "\""
    }
}
