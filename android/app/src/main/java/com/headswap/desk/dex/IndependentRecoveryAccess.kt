package com.headswap.desk.dex

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.flyfishxu.kadb.Kadb
import com.headswap.desk.AppLog
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in only. Uses authenticated public ADB services, never edits keys or system properties. */
internal object IndependentRecoveryAccess {
    enum class Kind { DISABLED, OWNED, REUSED, PENDING, RESTORE_REQUIRED }
    data class Snapshot(val kind: Kind, val port: Int = 0)
    private const val STATE = "independent-recovery.json"
    private const val PROOF = "no_backup/independent-recovery-proof"
    private val operations = Any()
    // Failed live verification invalidates readiness, never the private ownership/rollback record.
    private val readinessProof = RecoveryReadinessProof()
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "headswap-local-adb-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun requested(context: Context): Boolean = DexPrefs(context).let {
        IndependentRecoveryPolicy.requested(Build.VERSION.SDK_INT, it.useDelegated, it.desktopMode, it.independentRecoveryWithoutWifi)
    }

    fun snapshot(context: Context): Snapshot {
        val state = read(context)
        if (!IndependentRecoveryPolicy.current(state, boot(context))) return Snapshot(Kind.DISABLED)
        state!!
        return Snapshot(when (state.phase) {
            IndependentRecoveryPolicy.Phase.PENDING -> Kind.PENDING
            IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED -> Kind.RESTORE_REQUIRED
            IndependentRecoveryPolicy.Phase.READY -> if (state.owned) Kind.OWNED else Kind.REUSED
        }, state.port)
    }

    fun hasManagedState(context: Context) = snapshot(context).kind != Kind.DISABLED
    /** Safe state only: no key, owner token, network address or raw property value. */
    fun diagnosticState(context: Context): String {
        val state = snapshot(context)
        val runtime = runtimePort()?.trim()?.toIntOrNull()?.takeIf { it in -1..65535 }
        return "mode=${state.kind} port=${state.port} runtimePort=${runtime ?: "unknown"}"
    }
    fun needsApply(context: Context): Boolean {
        if (!requested(context)) return false
        val state = snapshot(context)
        return state.kind !in setOf(Kind.OWNED, Kind.REUSED) ||
            IndependentRecoveryPolicy.runtimePortContradicts(state.port, runtimePort()) ||
            read(context)?.let(readinessProof::isRejected) == true
    }

    /** A host/adbd generation transition makes the old live-route proof obsolete. */
    fun invalidateLiveVerification() = readinessProof.invalidate()

    /** Process-owned proof deliberately does not survive instrumentation/application restart. */
    fun requiresLiveVerification(context: Context): Boolean {
        if (!requested(context)) return false
        val record = read(context)?.takeIf { IndependentRecoveryPolicy.current(it, boot(context)) &&
            it.phase == IndependentRecoveryPolicy.Phase.READY } ?: return false
        return !readinessProof.isVerified(record)
    }

    /** This is only a candidate, never proof that the listener or activation is alive. */
    fun recoveryEndpoint(context: Context): DexEndpoint? = snapshot(context).takeIf {
        requested(context) && it.kind in setOf(Kind.OWNED, Kind.REUSED)
    }?.let { DexEndpoint("127.0.0.1", it.port) }

    /** Read-only, bounded validation for an explicit activation; UI polling never opens a socket. */
    fun verifyReady(context: Context, ensureActive: () -> Unit, onConnection: (Kadb?) -> Unit): Boolean {
        if (!requested(context)) return true
        check(Looper.myLooper() != Looper.getMainLooper())
        synchronized(operations) {
            ensureActive()
            val record = read(context)?.takeIf {
                IndependentRecoveryPolicy.current(it, boot(context)) && it.phase == IndependentRecoveryPolicy.Phase.READY
            } ?: return false
            val verificationTicket = readinessProof.begin()
            try {
                withProof(context) { proof ->
                    Scope(null, 5000).use { scope ->
                        try { LegacyAdbChallenge.requireAuthenticated(record.port, scope.remaining(1500)) }
                        catch (_: IllegalStateException) {
                            throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.LOCAL_ADB_CONFIGURATION)
                        }
                        ensureActive()
                        scope.connect(context, record.port).use { adb ->
                            try {
                                onConnection(adb)
                                ensureActive()
                                verify(adb, DexEndpoint("127.0.0.1", record.port), proof)
                                DesktopUsbDebugging.requireEnabled(context, adb)
                                val live = device(adb)
                                ensureActive()
                                check(scope.alive()) { "Истекло время проверки отладки" }
                                if (IndependentRecoveryPolicy.effectivePort(live) != record.port || live.listenAddresses.isNotEmpty())
                                    throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.LOCAL_ADB_CONFIGURATION)
                                if (read(context) != record) return false
                                // Keep original pid/ticks: a daemon restart does not grant rollback authority.
                                if (!readinessProof.confirm(record, verificationTicket)) return false
                                AppLog.i("Local ADB recovery verified: port=${record.port}", false)
                                return true
                            } finally { onConnection(null) }
                        }
                    }
                }
            } catch (error: Exception) {
                ensureActive()
                if (read(context) == record) readinessProof.reject(record)
                val reason = DexAccessDiagnostics.classify(error)
                AppLog.i("Local ADB recovery verification failed: port=${record.port} reason=$reason", false)
                if (reason == DexAccessReason.REFUSED || reason == DexAccessReason.TIMEOUT || reason == DexAccessReason.CONNECTION_CLOSED)
                    return false
                throw error as? DexAccessException ?: DexAccessException(DexAccessStage.CONNECT, reason)
            }
        }
    }

    /** Capture before authenticating a recovery connection, never after a newer host has taken over. */
    fun captureManagedRecovery(context: Context, endpoint: DexEndpoint): RecoveryReadinessProof.Attempt? {
        if (!requested(context)) return null
        val record = read(context)?.takeIf {
            IndependentRecoveryPolicy.matchesManagedRoute(it, boot(context), endpoint.host, endpoint.port)
        } ?: return null
        return readinessProof.capture(record)
    }

    fun cancelManagedRecovery(attempt: RecoveryReadinessProof.Attempt?) {
        attempt?.let(readinessProof::cancel)
    }

    /** Reuse the exact authenticated managed socket that restored this host. Never bless a TLS fallback. */
    fun confirmManagedRecovery(context: Context, endpoint: DexEndpoint, attempt: RecoveryReadinessProof.Attempt,
        adb: Kadb, isCurrent: () -> Boolean): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        synchronized(operations) {
            if (!isCurrent() || !requested(context) || read(context) != attempt.record ||
                !IndependentRecoveryPolicy.matchesManagedRoute(attempt.record, boot(context), endpoint.host, endpoint.port)) return false
            withProof(context) { proof ->
                // The request's existing deadline owns this socket; no second connection or timeout is started.
                verify(adb, endpoint, proof)
                val live = device(adb)
                if (IndependentRecoveryPolicy.effectivePort(live) != attempt.record.port || live.listenAddresses.isNotEmpty()) return false
                val record = read(context) ?: return false
                if (!IndependentRecoveryPolicy.matchesManagedRoute(record, boot(context), endpoint.host, endpoint.port)) return false
                val confirmed = readinessProof.confirm(attempt, record, requested(context) && isCurrent())
                if (confirmed) AppLog.i("Local ADB recovery proof refreshed after host recovery: port=${record.port}", false)
                // Original ownership, pid and startTicks are deliberately left unchanged.
                return confirmed
            }
        }
    }

    fun noteUnavailable(context: Context, endpoint: DexEndpoint, reason: DexAccessReason) {
        if (reason != DexAccessReason.REFUSED || endpoint.host != "127.0.0.1") return
        val record = read(context)?.takeIf { IndependentRecoveryPolicy.current(it, boot(context)) &&
            it.phase == IndependentRecoveryPolicy.Phase.READY && it.port == endpoint.port } ?: return
        readinessProof.reject(record)
    }

    /** Called only inside explicit phone activation, after its private request was verified. */
    fun prepare(context: Context, authenticated: Kadb, endpoint: DexEndpoint, ensureActive: () -> Unit): DexEndpoint = try {
        prepareInternal(context, authenticated, endpoint, ensureActive)
    } catch (error: Exception) {
        AppLog.i("Local ADB preparation: ${DexAccessDiagnostics.safeTrace(error)}", false)
        val pending = snapshot(context).kind in setOf(Kind.PENDING, Kind.RESTORE_REQUIRED)
        if (pending) runCatching { read(context)?.let { write(context, it.copy(phase = IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED)) } }
        if (error is DexAccessException && !pending) throw error
        throw DexAccessException(DexAccessStage.CONNECT, if (pending) DexAccessReason.LOCAL_ADB_RESTORE_REQUIRED else DexAccessReason.LOCAL_ADB_CONFIGURATION)
    }

    private fun prepareInternal(context: Context, authenticated: Kadb, endpoint: DexEndpoint, ensureActive: () -> Unit): DexEndpoint {
        if (!requested(context)) return endpoint
        check(DexPrefs(context).hasPaired) { "Сначала выполните сопряжение HeadSwap по Wi-Fi. Ключ компьютера его не заменяет." }
        check(Looper.myLooper() != Looper.getMainLooper())
        synchronized(operations) {
            ensureActive()
            check(boot(context) >= 0) { "Не удалось проверить текущую загрузку Android" }
            withProof(context) { proof ->
                Scope(authenticated, 25_000).use { scope ->
                    verify(authenticated, endpoint, proof)
                    DesktopUsbDebugging.requireEnabled(context, authenticated)
                    val before = device(authenticated)
                    val selected = IndependentRecoveryPolicy.select(before)
                    val prior = read(context)?.takeIf { IndependentRecoveryPolicy.current(it, boot(context)) }
                    val rearmOwned = !selected.startListener && IndependentRecoveryPolicy.mayRestartOwnedListener(
                        prior, boot(context), before, refused(selected.port))
                    val selection = if (rearmOwned) selected.copy(startListener = true) else selected
                    if (selection.startListener) {
                        // AOSP may use 5555 when USB is unavailable even with empty properties.
                        // Never replace that listener, or an unrelated service occupying our port.
                        check(refused(selection.port) && refused(5555)) { "Порт отладки уже занят; настройка не изменена" }
                        val pending = IndependentRecoveryPolicy.Record(boot(context), selection.port, true, null, IndependentRecoveryPolicy.Phase.PENDING)
                        check(pending.boot >= 0)
                        ensureActive()
                        write(context, pending) // A crash cannot erase the fact that a global change was attempted.
                        ensureActive()
                        check(scope.alive()) { "Истекло время настройки отладки" }
                        try {
                            service(authenticated, "tcpip:${selection.port}")
                        } catch (error: Exception) {
                            // adbd restart can drop the reply; only a new authenticated connection proves success.
                            AppLog.i("Local ADB restart response: ${error.javaClass.simpleName}", false)
                        }
                    }
                    val verificationTicket = readinessProof.begin()
                    var confirmed: IndependentRecoveryPolicy.Device? = null
                    var failure: Exception? = null
                    while (scope.alive()) {
                        ensureActive()
                        try {
                            LegacyAdbChallenge.requireAuthenticated(selection.port, scope.remaining(1500))
                            scope.connect(context, selection.port).use { adb ->
                                verify(adb, DexEndpoint("127.0.0.1", selection.port), proof)
                                val current = device(adb)
                                check(IndependentRecoveryPolicy.effectivePort(current) == selection.port) { "Конфигурация отладки изменилась" }
                                if (selection.startListener) check(current.daemon != before.daemon) { "Служба отладки ещё перезапускается" }
                                confirmed = current
                            }
                            break
                        } catch (error: Exception) {
                            failure = error
                            if (!selection.startListener || (error is DexAccessException && VdmRecoveryPolicy.terminal(error.reason))) break
                            Thread.sleep(150)
                        }
                    }
                    val current = confirmed
                    if (current == null) {
                        if (selection.startListener) write(context, checkNotNull(read(context)).copy(phase = IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED))
                        AppLog.i("Local ADB preparation failed: ${failure?.javaClass?.simpleName ?: "deadline"}", false)
                        error(if (selection.startListener)
                            "Сетевой режим отладки запрошен, но доступ не подтверждён. Откройте «Активация → Исправить настройку активации» с включённым Wi-Fi; при недоступности отладки используйте компьютер: adb usb."
                            else "Не удалось использовать уже настроенную сетевую отладку. Проверьте сопряжение HeadSwap; чужая настройка не изменена.")
                    }
                    ensureActive()
                    check(scope.alive()) { "Истекло время настройки отладки" }
                    val stillOwned = prior != null && IndependentRecoveryPolicy.automaticRestore(prior, boot(context), current)
                    write(context, IndependentRecoveryPolicy.Record(boot(context), selection.port,
                        selection.startListener || stillOwned, current.daemon, IndependentRecoveryPolicy.Phase.READY))
                    read(context)?.let { readinessProof.confirm(it, verificationTicket) }
                    AppLog.i("Local ADB recovery prepared: ownership=${if (selection.startListener || stillOwned) "owned" else "reused"}", false)
                    return DexEndpoint("127.0.0.1", selection.port)
                }
            }
        }
    }

    /** Explicit confirmation authorizes restoring GLOBAL ADB mode, including a reused listener. */
    fun restoreExplicitly(context: Context) {
        try { restore(context, explicit = true) }
        catch (error: Exception) {
            AppLog.i("Local ADB explicit restore: ${DexAccessDiagnostics.safeTrace(error)}", false)
            if (error is DexAccessException) throw error
            throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.LOCAL_ADB_RESTORE_REQUIRED)
        }
    }

    /** Activation end/failure only. Never called on USB detach or an ordinary session Stop. */
    fun restoreOwnedAfterActivation(context: Context) {
        try { restore(context, explicit = false) }
        catch (error: Exception) {
            runCatching { read(context)?.takeIf { it.owned && IndependentRecoveryPolicy.current(it, boot(context)) }
                ?.let { write(context, it.copy(phase = IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED)) } }
            AppLog.i("Local ADB restore requires user action: ${error.javaClass.simpleName}", false)
        }
    }

    private fun restore(context: Context, explicit: Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper())
        synchronized(operations) {
            val record = read(context)?.takeIf { IndependentRecoveryPolicy.current(it, boot(context)) } ?: return
            if (!explicit && (!record.owned || record.phase != IndependentRecoveryPolicy.Phase.READY)) return
            withProof(context) { proof ->
                Scope(null, 12_000).use { scope ->
                    val prefs = DexPrefs(context)
                    val candidates = listOf(DexEndpoint("127.0.0.1", record.port), DexEndpoint(prefs.host, prefs.connectPort))
                        .filter { it.port in 1..65535 && DexAccess.acceptsHost(it.host) }.distinct()
                    var verified: Kadb? = null
                    var current: IndependentRecoveryPolicy.Device? = null
                    for (candidate in candidates) {
                        if (!scope.alive()) break
                        try {
                            val adb = scope.connect(context, candidate)
                            verify(adb, candidate, proof)
                            current = device(adb)
                            verified = adb
                            break
                        } catch (_: Exception) { scope.closeCurrent() }
                    }
                    val adb = verified ?: error("Нет доступа к отладке для возврата обычного режима. Включите Wi-Fi и беспроводную отладку либо выполните на компьютере adb usb.")
                    val state = checkNotNull(current)
                    if (state.listenAddresses.isNotEmpty()) {
                        write(context, record.copy(phase = IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED))
                        throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.LOCAL_ADB_CONFIGURATION)
                    }
                    val currentPort = IndependentRecoveryPolicy.effectivePort(state)
                    if (currentPort == 0) {
                        check(refused(record.port)) { "Закрытие сохранённого порта пока не подтверждено" }
                        clear(context)
                        if (explicit) prefs.independentRecoveryWithoutWifi = false
                        return
                    }
                    if (explicit && currentPort != record.port) {
                        write(context, record.copy(phase = IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED))
                        throw DexAccessException(DexAccessStage.CONNECT, DexAccessReason.LOCAL_ADB_CONFIGURATION)
                    }
                    if (!explicit && !IndependentRecoveryPolicy.automaticRestore(record, boot(context), state)) {
                        // Even a new daemon on the same port might have another owner. Do not adopt it.
                        write(context, record.copy(phase = IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED))
                        return
                    }
                    write(context, record.copy(phase = IndependentRecoveryPolicy.Phase.RESTORE_REQUIRED))
                    var acknowledged = false
                    check(scope.alive()) { "Истекло время возврата режима отладки" }
                    try { acknowledged = service(adb, "usb:").contains("restarting in USB mode") }
                    catch (_: Exception) { /* Restart can close the accepted command stream. */ }
                    val until = SystemClock.elapsedRealtime() + 5_000
                    while (scope.alive() && SystemClock.elapsedRealtime() < until) {
                        if ((acknowledged || runtimePort() == "0") && refused(currentPort)) {
                            clear(context)
                            if (explicit) prefs.independentRecoveryWithoutWifi = false
                            AppLog.i("Local ADB recovery returned to ordinary mode", false)
                            return
                        }
                        Thread.sleep(120)
                    }
                    error("Возврат обычного режима пока не подтверждён. Повторите с включённой беспроводной отладкой либо выполните на компьютере adb usb.")
                }
            }
        }
    }

    private fun device(adb: Kadb): IndependentRecoveryPolicy.Device {
        check(shell(adb, "getprop ro.adb.secure") == "1") { "Сетевая отладка требует проверку ключа" }
        val pid = shell(adb, "pidof adbd")
        check(pid.matches(Regex("[0-9]+"))) { "Не удалось проверить службу отладки" }
        return IndependentRecoveryPolicy.Device(shell(adb, "getprop service.adb.tcp.port"),
            shell(adb, "getprop persist.adb.tcp.port"), shell(adb, "getprop service.adb.listen_addrs"),
            IndependentRecoveryPolicy.daemon(pid, shell(adb, "cat /proc/$pid/stat")))
    }

    private fun verify(adb: Kadb, endpoint: DexEndpoint, proof: String) {
        DexAccess.verifyShell(adb, endpoint.host, endpoint.port)
        check(shell(adb, "run-as ${ManualAccessPolicy.PACKAGE} --user ${Process.myUid() / 100000} cat $PROOF") == proof) {
            "Конечная точка отладки не принадлежит этой установке HeadSwap"
        }
    }

    private inline fun <T> withProof(context: Context, work: (String) -> T): T {
        val file = File(context.dataDir, PROOF)
        val proof = UUID.randomUUID().toString()
        file.writeText(proof, Charsets.US_ASCII)
        try { return work(proof) } finally { if (file.readText(Charsets.US_ASCII) == proof) file.delete() }
    }

    private fun shell(adb: Kadb, command: String): String {
        val response = adb.shell(command)
        check(response.exitCode == 0 && response.output.length <= 4096) { "Системная проверка отладки не выполнена" }
        return response.output.trim()
    }

    /** Public Kadb generic ADB service API; the fixed commands match AOSP restart_service.cpp. */
    private fun service(adb: Kadb, name: String): String {
        require(name == "usb:" || name == "tcpip:${IndependentRecoveryPolicy.PORT}")
        return adb.open(name).use { stream ->
            val buffer = okio.Buffer()
            while (buffer.size <= 1024) {
                if (stream.source.read(buffer, 1025 - buffer.size) < 0) break
            }
            check(buffer.size <= 1024) { "Unexpected ADB service response" }
            buffer.readUtf8()
        }
    }

    private fun refused(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; false
    } catch (_: ConnectException) { true } catch (_: Exception) { false }

    private fun runtimePort(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tcp.port") as? String
    }.getOrNull()

    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, STATE))
    private fun clear(context: Context) { file(context).delete(); readinessProof.clear() }
    private fun read(context: Context): IndependentRecoveryPolicy.Record? = runCatching {
        val source = file(context)
        val bytes = source.openRead().use { it.readNBytes(2049) }
        check(bytes.size <= 2048)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        check(json.getInt("version") == 1)
        val pid = json.getInt("pid")
        IndependentRecoveryPolicy.Record(json.getInt("boot"), json.getInt("port"), json.getBoolean("owned"),
            if (pid > 0) IndependentRecoveryPolicy.Daemon(pid, json.getLong("ticks")) else null,
            IndependentRecoveryPolicy.Phase.valueOf(json.getString("phase")))
    }.getOrNull()

    private fun write(context: Context, record: IndependentRecoveryPolicy.Record) {
        val bytes = JSONObject().put("version", 1).put("boot", record.boot).put("port", record.port)
            .put("owned", record.owned).put("pid", record.daemon?.pid ?: 0).put("ticks", record.daemon?.startTicks ?: 0)
            .put("phase", record.phase.name).toString().toByteArray(Charsets.UTF_8)
        val target = file(context)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Throwable) { target.failWrite(output); throw error }
    }

    private class Scope(initial: Kadb?, duration: Long) : AutoCloseable {
        private val deadline = SystemClock.elapsedRealtime() + duration
        private val current = AtomicReference(initial)
        private val expiry = deadlines.schedule({ closeCurrent() }, duration, TimeUnit.MILLISECONDS)
        fun alive() = !Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline
        fun remaining(max: Int) = (deadline - SystemClock.elapsedRealtime()).coerceIn(1, max.toLong()).toInt()
        fun connect(context: Context, port: Int) = connect(context, DexEndpoint("127.0.0.1", port))
        fun connect(context: Context, endpoint: DexEndpoint): Kadb {
            check(alive()) { "Истекло время настройки отладки" }
            closeCurrent()
            return DexAccess.connectEndpoint(context, endpoint, remaining(3000)).also {
                current.set(it)
                if (!alive()) { closeCurrent(); error("Истекло время настройки отладки") }
            }
        }
        fun closeCurrent() { runCatching { current.getAndSet(null)?.close() } }
        override fun close() { expiry.cancel(false); closeCurrent() }
    }
}
