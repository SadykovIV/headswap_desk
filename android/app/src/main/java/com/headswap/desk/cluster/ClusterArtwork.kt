package com.headswap.desk.cluster

/** Value equality avoids resending identical covers on each playback-position callback. */
class ClusterArtwork(bytes: ByteArray) {
    private val value = bytes.copyOf()
    fun copyBytes() = value.copyOf()
    override fun equals(other: Any?) = other is ClusterArtwork && value.contentEquals(other.value)
    override fun hashCode() = value.contentHashCode()
    override fun toString() = "ClusterArtwork(${value.size} bytes)"
}
