package com.headswap.desk.cluster

import org.junit.Assert.*
import org.junit.Test

class ClusterArtworkTest {
    @Test fun snapshotsHaveValueEqualityAndDoNotLeakMutableBuffer() {
        val source = byteArrayOf(1, 2, 3)
        val cover = ClusterArtwork(source)
        source[0] = 5
        val copy = cover.copyBytes()
        copy[1] = 9
        assertEquals(ClusterArtwork(byteArrayOf(1, 2, 3)), cover)
        assertEquals(ClusterArtwork(byteArrayOf(1, 2, 3)).hashCode(), cover.hashCode())
        assertFalse(cover == ClusterArtwork(byteArrayOf(9)))
        assertEquals("ClusterArtwork(3 bytes)", cover.toString())
    }
}
