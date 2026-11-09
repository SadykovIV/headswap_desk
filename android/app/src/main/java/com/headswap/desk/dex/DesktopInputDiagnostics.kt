package com.headswap.desk.dex

import java.util.Locale

/** Called on the existing input worker. Only bounded numeric aggregates; no event payloads. */
internal class DesktopInputDiagnostics(private val backend: String) {
    private var generation = Long.MIN_VALUE
    private var beganNs = 0L
    private var count = 0L
    private var failures = 0L
    private var queueNs = 0L
    private var injectNs = 0L
    private var queueMaxNs = 0L
    private var injectMaxNs = 0L
    private var depthMax = 0

    /** The existing task already captures these primitive timestamps; no per-event sample object. */
    fun completed(token: Long, arrivedNs: Long, startedNs: Long, finishedNs: Long,
        observedDepth: Int, succeeded: Boolean): String? {
        if (generation != token) {
            reset()
            generation = token
        }
        if (count == 0L) beganNs = arrivedNs
        val wait = (startedNs - arrivedNs).coerceAtLeast(0)
        val work = (finishedNs - startedNs).coerceAtLeast(0)
        count++
        if (!succeeded) failures++
        queueNs += wait; injectNs += work
        queueMaxNs = maxOf(queueMaxNs, wait); injectMaxNs = maxOf(injectMaxNs, work)
        depthMax = maxOf(depthMax, observedDepth.coerceAtLeast(0))
        if (finishedNs - beganNs < WINDOW_NS) return null
        val report = String.format(Locale.ROOT,
            "DESKTOP INPUT %s: generation=%d count=%d errors=%d queueMs=%.2f/max%.2f injectMs=%.2f/max%.2f observedDepthMax=%d",
            backend, generation, count, failures, queueNs / count / 1_000_000.0, queueMaxNs / 1_000_000.0,
            injectNs / count / 1_000_000.0, injectMaxNs / 1_000_000.0, depthMax)
        reset()
        return report
    }

    private fun reset() {
        count = 0; failures = 0; queueNs = 0; injectNs = 0
        queueMaxNs = 0; injectMaxNs = 0; depthMax = 0
    }
    private companion object { const val WINDOW_NS = 5_000_000_000L }
}
