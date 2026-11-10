package com.headswap.desk

/**
 * Presentation limits of the known Honda cluster, distinct from the HU's 36-byte native buffer.
 *
 * Keep the limits used by the working 0.6.11: <= 31 UTF-8 bytes, <= 14 code points and
 * <= 24 display cells (ASCII = 1, other code points = 2). These are receiver compatibility
 * rules, not CarLife protocol limits; apply them only to an identified Honda's roadName.
 *
 * 0.6.12 deliberately removes trailing padding for the requested vehicle comparison.
 * The native UTF-16 capacity defect may still truncate text; these bounds prevent oversized
 * packets, but do not promise that the receiver renders every character.
 */
internal object HondaClusterRoad {
    const val MAX_CELLS = 24
    const val MAX_CHARS = 14
    const val MAX_BYTES = 31

    /** Input is normalized and abbreviated by [NavigationStreetNames]; no padding is added. */
    fun fit(normalized: String): String {
        var end = 0
        var cells = 0
        var points = 0
        var bytes = 0
        while (end < normalized.length) {
            val cp = normalized.codePointAt(end)
            val nextCells = cells + if (cp < 0x80) 1 else 2
            val nextPoints = points + 1
            val nextBytes = bytes + when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (nextCells > MAX_CELLS || nextPoints > MAX_CHARS || nextBytes > MAX_BYTES) break
            end += Character.charCount(cp)
            cells = nextCells
            points = nextPoints
            bytes = nextBytes
        }
        return normalized.substring(0, end).trimEnd(' ')
    }
}
