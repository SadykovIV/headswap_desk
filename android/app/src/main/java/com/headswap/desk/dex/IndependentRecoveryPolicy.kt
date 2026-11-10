package com.headswap.desk.dex

/** No global ADB mutation is authorized by a saved preference alone. */
internal object IndependentRecoveryPolicy {
    const val PORT = 38555
    data class Daemon(val pid: Int, val startTicks: Long)
    enum class Phase { PENDING, READY, RESTORE_REQUIRED }
    data class Record(val boot: Int, val port: Int, val owned: Boolean, val daemon: Daemon?, val phase: Phase)
    data class Device(val runtime: String, val persistent: String, val listenAddresses: String, val daemon: Daemon)
    data class Selection(val port: Int, val startListener: Boolean)

    fun requested(sdk: Int, delegated: Boolean, desktopMode: Int, enabled: Boolean): Boolean =
        sdk >= 37 && delegated && desktopMode != DesktopModePolicy.SAMSUNG_DEX && enabled

    private fun port(value: String): Int {
        if (value.isEmpty() || value == "0" || value == "-1") return 0
        return value.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("Неизвестная конфигурация TCP-отладки. Настройка не изменена.")
    }

    fun effectivePort(device: Device): Int = port(device.runtime.ifEmpty { device.persistent })

    /** A readable runtime override can invalidate a saved READY record without probing the network.
     * Missing/filtered/malformed properties are unknown, not evidence that the listener was disabled.
     * A matching number still does not authenticate the listener; connection paths verify it separately.
     */
    fun runtimePortContradicts(recordPort: Int, runtime: String?): Boolean {
        val current = runtime?.toIntOrNull() ?: return false
        return (current == -1 || current in 0..65535) && current != recordPort
    }

    fun select(device: Device): Selection {
        check(device.listenAddresses.isEmpty()) { "Найдена другая конфигурация адресов отладки. HeadSwap её не изменяет." }
        val existing = effectivePort(device)
        if (existing > 0) return Selection(existing, false)
        // A disabled runtime override must not overwrite a persistent owner's configuration.
        check(port(device.persistent) == 0 && device.listenAddresses.isEmpty()) {
            "Найдена другая конфигурация сетевой отладки. HeadSwap её не изменяет."
        }
        return Selection(PORT, true)
    }

    /** Explicit activation can re-arm only this app's own, unchanged port after a proven refusal.
     * A new daemon alone never transfers ownership or authorizes automatic restoration.
     */
    fun mayRestartOwnedListener(record: Record?, boot: Int, device: Device, refused: Boolean): Boolean =
        refused && record != null && current(record, boot) && record.owned && record.phase == Phase.READY &&
            record.port == PORT && effectivePort(device) == record.port &&
            port(device.persistent) == 0 && device.listenAddresses.isEmpty()

    fun current(record: Record?, boot: Int): Boolean = record != null && boot >= 0 && record.boot == boot &&
        record.port in 1..65535 && (record.daemon != null || record.phase != Phase.READY) &&
        (record.daemon == null || (record.daemon.pid > 1 && record.daemon.startTicks > 0))

    fun matchesManagedRoute(record: Record?, boot: Int, host: String, port: Int): Boolean =
        record != null && current(record, boot) && record.phase == Phase.READY && host == "127.0.0.1" && record.port == port

    fun automaticRestore(record: Record, boot: Int, device: Device): Boolean = current(record, boot) &&
        record.owned && record.phase == Phase.READY && record.daemon != null &&
        record.daemon == device.daemon && effectivePort(device) == record.port &&
        port(device.persistent) == 0 && device.listenAddresses.isEmpty()

    fun daemon(pid: String, stat: String): Daemon {
        val id = pid.toIntOrNull()?.takeIf { it > 1 } ?: error("Не удалось определить службу отладки")
        check(stat.startsWith("$id (") && stat.contains(") ")) { "Не удалось проверить службу отладки" }
        val ticks = stat.substring(stat.lastIndexOf(") ") + 2).trim().split(Regex("\\s+"))
            .getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 } ?: error("Не удалось проверить время запуска службы отладки")
        return Daemon(id, ticks)
    }
}

/** In-memory evidence cannot outlive its process or a newer host/daemon transition. */
internal class RecoveryReadinessProof {
    class Attempt internal constructor(val record: IndependentRecoveryPolicy.Record, internal val generation: Long) {
        internal var cancelled = false
    }
    private var generation = 0L
    private var verified: IndependentRecoveryPolicy.Record? = null
    private var rejected: IndependentRecoveryPolicy.Record? = null
    @Synchronized fun begin(): Long = generation
    @Synchronized fun capture(record: IndependentRecoveryPolicy.Record): Attempt = Attempt(record, generation)
    @Synchronized fun cancel(attempt: Attempt) { attempt.cancelled = true }
    @Synchronized fun confirm(attempt: Attempt, record: IndependentRecoveryPolicy.Record, active: Boolean): Boolean {
        if (!active || attempt.cancelled || record != attempt.record) return false
        return confirm(record, attempt.generation)
    }
    @Synchronized fun isVerified(record: IndependentRecoveryPolicy.Record): Boolean = verified == record
    @Synchronized fun isRejected(record: IndependentRecoveryPolicy.Record): Boolean = rejected == record
    @Synchronized fun confirm(record: IndependentRecoveryPolicy.Record, ticket: Long): Boolean {
        if (ticket != generation) return false
        verified = record; rejected = null
        return true
    }
    @Synchronized fun invalidate() { generation++; verified = null }
    @Synchronized fun reject(record: IndependentRecoveryPolicy.Record) {
        generation++; verified = null; rejected = record
    }
    @Synchronized fun clear() { generation++; verified = null; rejected = null }
}
