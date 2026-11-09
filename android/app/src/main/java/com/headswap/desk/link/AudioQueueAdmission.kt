package com.headswap.desk.link

import java.util.concurrent.LinkedBlockingQueue

internal class QueuedTransportPacket(val chan: Int, val inner: ByteArray, val deadlineMs: Long)

/** Bound waiting timed PCM even when its writer is blocked and other TCP6 channels remain healthy.
 * A partially written packet is never interrupted: at most one further in-flight packet per writer
 * can exist outside these limits. Control/video messages are neither removed nor reordered.
 */
internal object AudioQueueAdmission {
    const val MAX_PENDING_PCM_BYTES = 64 * 1024
    const val MAX_PENDING_PCM_PACKETS = 32

    fun offer(queue: LinkedBlockingQueue<QueuedTransportPacket>, packet: QueuedTransportPacket,
        nowMs: Long, expired: (Long) -> Unit, overflow: (Long) -> Unit) = synchronized(queue) {
        if (packet.deadlineMs == Long.MAX_VALUE) {
            queue.offer(packet)
            return@synchronized
        }
        var old = 0L
        // Expire at admission as well as at dequeue. Relying only on the writer permits an
        // unbounded PCM queue when e.g. MEDIA stalls while the independent VIDEO socket drains.
        for (queued in queue) {
            if (queued.deadlineMs != Long.MAX_VALUE && nowMs >= queued.deadlineMs && queue.remove(queued)) old++
        }
        if (old > 0) expired(old)
        if (packet.inner.size > MAX_PENDING_PCM_BYTES) {
            overflow(1)
            return@synchronized
        }
        val same = queue.filter { it.chan == packet.chan && it.deadlineMs != Long.MAX_VALUE }
        var bytes = same.sumOf { it.inner.size.toLong() }
        var count = same.size
        var evicted = 0L
        for (queued in same) {
            if (bytes + packet.inner.size <= MAX_PENDING_PCM_BYTES && count < MAX_PENDING_PCM_PACKETS) break
            // A concurrent take may already have removed this packet. Either way it no longer
            // contributes to the waiting budget; count a discard only when we actually remove it.
            if (queue.remove(queued)) evicted++
            bytes -= queued.inner.size
            count--
        }
        if (evicted > 0) overflow(evicted)
        queue.offer(packet)
    }
}
