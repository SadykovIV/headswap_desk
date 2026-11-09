package com.headswap.desk.dex

/** Process-local one-use handoff. Never persisted or logged with the nonce. */
class VdmHostRendezvous internal constructor(
    val packageName: String, val appPid: Int, val nonce: String,
    val generation: Long, internal val createdMs: Long,
) {
    override fun toString() = "VdmHostRendezvous(package=$packageName,pid=$appPid,generation=$generation)"
}
