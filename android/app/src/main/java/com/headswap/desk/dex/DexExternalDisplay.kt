package com.headswap.desk.dex

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.AtomicFile
import android.util.DisplayMetrics
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import com.headswap.desk.AppLog
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A system overlay display, with a shell-side lease that survives an APK crash. */
class DexExternalDisplay private constructor(
    context: Context,
    private val adb: Kadb,
    private val owner: DexOverlayPolicy.Owner,
    private val isCancelled: () -> Boolean,
) : AutoCloseable {
    private val context = context.applicationContext
    private val journal = AtomicFile(this.context.noBackupFilesDir.resolve("dex-overlay-lease-v1"))
    private val closed = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val terminated = CountDownLatch(1)
    private val error = AtomicReference<String?>(null)
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dex-display-lease").apply { isDaemon = true }
    }
    private val writeLock = Any()
    @Volatile private var stream: AdbStream? = null
    @Volatile private var acknowledged = false
    @Volatile private var cleaned = false
    var displayId: Int = -1
        private set

    private fun acquire(): DexExternalDisplay {
        val priorJournal = readJournal()
        val remoteOwnerResult = adb.shell("cat $OWNER_FILE 2>/dev/null")
        val remoteOwner = DexOverlayPolicy.parseOwner(remoteOwnerResult.output)
        // Only a token already recorded by this APK authorizes recovery of a stale lease.
        val previous = remoteOwner?.takeIf { it in priorJournal }
        if (remoteOwner != null && previous == null) {
            throw IOException("Остался экран другой сессии HeadSwap. Дождитесь его завершения и подключитесь снова.")
        }
        val setting = adb.shell("settings get global overlay_display_devices")
        checkResult(setting.exitCode == 0, "Не удалось проверить системные экраны")
        if (!DexOverlayPolicy.isMissing(setting.output) && previous == null) {
            throw IOException("Уже включён другой имитируемый экран. Отключите его в настройках разработчика и подключитесь снова.")
        }
        val before = displays().mapTo(HashSet()) { it.id }
        ensureActive()
        // Persist before the first possible global mutation. Both tokens cover a crash
        // during the hand-off from a stale session to the new shell watcher.
        writeJournal(listOfNotNull(owner, previous))
        val shell = adb.open("shell:sh -c ${DexOverlayPolicy.quote(DexOverlayPolicy.watcher(owner, previous))}")
        stream = shell
        Thread({ readWatcher(shell) }, "dex-display-lease-log").apply { isDaemon = true; start() }
        heartbeat.scheduleWithFixedDelay({
            if (!closed.get()) runCatching { send("PING") }.onFailure {
                error.compareAndSet(null, "Потерян контроль системного экрана DeX")
            }
        }, 3, 3, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!ready.await(100, TimeUnit.MILLISECONDS)) {
            ensureActive()
            checkResult(System.nanoTime() < deadline, "Системный экран DeX не ответил вовремя")
        }
        checkAlive()
        checkResult(acknowledged, "Не удалось создать системный экран DeX")
        while (System.nanoTime() < deadline) {
            ensureActive()
            checkAlive()
            val candidates = DexOverlayPolicy.selectNew(before, displays())
            if (candidates.size > 1) throw IOException("Появилось несколько новых экранов. Подключение DeX остановлено.")
            if (candidates.size == 1) {
                val display = candidates.single()
                displayId = display.id
                AppLog.i("DEX system-display id=$displayId type=${display.type} uniqueId=${display.uniqueId} physical=${display.physicalWidth}x${display.physicalHeight} logical=${display.width}x${display.height} density=${display.density}", ui = false)
                return this
            }
            Thread.sleep(100)
        }
        throw IOException("Samsung не создал системный экран DeX 1280×720. Подключение остановлено.")
    }

    /** Call with the video watchdog; a lost cleanup guardian must stop the session. */
    fun checkAlive() {
        error.get()?.let { throw IOException(it) }
        if (!closed.get() && terminated.count == 0L) throw IOException("Системный экран DeX завершился")
    }

    private fun readWatcher(shell: AdbStream) {
        try {
            while (true) {
                when (val line = shell.source.readUtf8LineStrict(4096).trim()) {
                    "READY ${owner.token}" -> { acknowledged = true; ready.countDown() }
                    "CLEANED ${owner.token}", "PRESERVED ${owner.token}" -> cleaned = true
                    "ERROR BUSY" -> error.compareAndSet(null, "Предыдущий экран DeX ещё завершается. Подождите 15 секунд и подключитесь снова.")
                    "ERROR FOREIGN" -> error.compareAndSet(null, "Уже включён другой имитируемый экран. HeadSwap не изменил его настройки.")
                    "ERROR RECOVERY" -> error.compareAndSet(null, "Не удалось завершить предыдущий системный экран DeX. Подключитесь снова.")
                    "ERROR LOCK" -> error.compareAndSet(null, "Прошивка не поддерживает безопасное завершение системного экрана")
                    "ERROR APPLY", "ERROR CLEANUP" -> error.compareAndSet(null, "Не удалось изменить настройку системного экрана DeX")
                    else -> if (line.startsWith("ERROR")) error.compareAndSet(null, "Не удалось создать системный экран DeX")
                }
            }
        } catch (_: Exception) {
            if (!closed.get() && !cleaned) error.compareAndSet(null, "Потерян контроль системного экрана DeX")
        } finally {
            ready.countDown()
            terminated.countDown()
        }
    }

    private fun send(verb: String) = synchronized(writeLock) {
        val shell = stream ?: return@synchronized
        shell.sink.writeUtf8("$verb ${owner.token}\n")
        shell.sink.flush()
    }

    /** Worker-thread operation. The guardian also cleans up on EOF or 15s without a ping. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeat.shutdownNow()
        try {
            if (stream != null) {
                // Never block Stop indefinitely behind a failed socket write.
                Thread({ runCatching { send("STOP") } }, "dex-display-stop").apply { isDaemon = true; start() }
                try { terminated.await(2500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                runCatching { stream?.close() }
            }
            if (cleaned && readJournal().firstOrNull() == owner) journal.delete()
        } finally {
            ACTIVE.set(false)
        }
    }

    private fun ensureActive() {
        if (closed.get() || isCancelled() || Thread.currentThread().isInterrupted) throw IOException("DeX остановлен")
    }

    @Suppress("DEPRECATION")
    private fun displays(): List<DexOverlayPolicy.DisplaySnapshot> {
        val manager = context.getSystemService(DisplayManager::class.java)
        return manager.displays.mapNotNull { display ->
            runCatching {
                val metrics = DisplayMetrics().also(display::getRealMetrics)
                val mode = display.mode
                val description = display.toString()
                DexOverlayPolicy.DisplaySnapshot(
                    display.displayId, DexOverlayPolicy.uniqueId(description), DexOverlayPolicy.type(description),
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    mode.physicalWidth, mode.physicalHeight,
                )
            }.getOrNull()
        }
    }

    private fun readJournal(): List<DexOverlayPolicy.Owner> = runCatching {
        val bytes = journal.openRead().use { it.readBytes() }
        if (bytes.size > 1024) emptyList() else DexOverlayPolicy.parseJournal(bytes.toString(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    private fun writeJournal(owners: List<DexOverlayPolicy.Owner>) {
        val out = journal.startWrite()
        try {
            out.write(DexOverlayPolicy.journal(owners).toByteArray(Charsets.UTF_8))
            journal.finishWrite(out)
        } catch (e: Exception) {
            journal.failWrite(out)
            throw IOException("Не удалось сохранить данные восстановления экрана DeX", e)
        }
    }

    companion object {
        const val SPEC = "1280x720/160,hdmi"
        private const val OWNER_FILE = DexOverlayPolicy.OWNER_FILE
        private val ACTIVE = AtomicBoolean(false)

        /** Blocking. Caller owns [adb] and must close this lease before closing that socket. */
        fun acquire(context: Context, adb: Kadb, isCancelled: () -> Boolean, spec: String = SPEC): DexExternalDisplay {
            require(spec in DexOverlayPolicy.ALLOWED_SPECS)
            checkResult(ACTIVE.compareAndSet(false, true), "Системный экран DeX уже запускается")
            val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            val lease = DexExternalDisplay(context, adb, DexOverlayPolicy.Owner(token, spec), isCancelled)
            try { return lease.acquire() } catch (e: Exception) { lease.close(); throw e }
        }

        private fun checkResult(condition: Boolean, message: String) { if (!condition) throw IOException(message) }
    }
}

/** Pure policy and fixed shell program; no arbitrary settings, paths, or commands are accepted. */
internal object DexOverlayPolicy {
    const val OWNER_FILE = "/data/local/tmp/headswap-dex-overlay.owner"
    private const val LOCK_FILE = "/data/local/tmp/headswap-dex-overlay.lock"
    val ALLOWED_SPECS = setOf("1280x720/160,hdmi", "1280x720/160,hdmi,should_show_system_decorations")
    data class Owner(val token: String, val spec: String)
    data class DisplaySnapshot(val id: Int, val uniqueId: String, val type: String,
        val width: Int, val height: Int, val density: Int, val physicalWidth: Int, val physicalHeight: Int)

    fun isMissing(value: String): Boolean = value.trim().let { it.isEmpty() || it == "null" || it == "none" }
    fun parseOwner(value: String): Owner? {
        val lines = value.trim().lines()
        if (lines.size != 2 || !lines[0].matches(Regex("[0-9a-f]{32}")) || lines[1] !in ALLOWED_SPECS) return null
        return Owner(lines[0], lines[1])
    }
    fun journal(owners: List<Owner>): String = "1\n" + owners.joinToString("\n\n") { "${it.token}\n${it.spec}" }
    fun parseJournal(value: String): List<Owner> {
        if (!value.startsWith("1\n")) return emptyList()
        val entries = value.removePrefix("1\n").split("\n\n")
        if (entries.size !in 1..2) return emptyList()
        return entries.map { parseOwner(it) ?: return emptyList() }
    }
    fun uniqueId(description: String): String = Regex("uniqueId=\"([^\"]+)\"").find(description)?.groupValues?.get(1).orEmpty()
    fun type(description: String): String = Regex("\\btype\\s+(OVERLAY|EXTERNAL|VIRTUAL|INTERNAL)\\b").find(description)?.groupValues?.get(1).orEmpty()
    fun selectNew(before: Set<Int>, after: List<DisplaySnapshot>): List<DisplaySnapshot> = after.filter {
        it.id > 0 && it.id !in before && it.type == "OVERLAY" && it.uniqueId.startsWith("overlay:") &&
            it.width == 1280 && it.height == 720 && it.physicalWidth == 1280 && it.physicalHeight == 720 && it.density == 160
    }
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun watcher(owner: Owner, previous: Owner?): String {
        require(parseOwner("${owner.token}\n${owner.spec}") == owner)
        require(previous == null || parseOwner("${previous.token}\n${previous.spec}") == previous)
        // flock is held for the whole lease. A previous watcher cannot delete a new
        // lease with the same display spec, including during process-death recovery.
        return """
            umask 077
            exec 9>${quote(LOCK_FILE)} || { echo 'ERROR LOCK'; exit 1; }
            command -v flock >/dev/null 2>&1 || { echo 'ERROR LOCK'; exit 1; }
            flock -n -x 9 || { echo 'ERROR BUSY'; exit 1; }
            token=${quote(owner.token)}
            spec=${quote(owner.spec)}
            ownerfile=${quote(OWNER_FILE)}
            expected=${quote("${owner.token}\n${owner.spec}")}
            previous=${quote(previous?.let { "${it.token}\n${it.spec}" }.orEmpty())}
            previous_spec=${quote(previous?.spec.orEmpty())}
            missing() { [ -z "${'$'}1" ] || [ "${'$'}1" = null ] || [ "${'$'}1" = none ]; }
            current_owner=${'$'}(cat "${'$'}ownerfile" 2>/dev/null)
            if [ -n "${'$'}current_owner" ]; then
              if [ -z "${'$'}previous" ] || [ "${'$'}current_owner" != "${'$'}previous" ]; then echo 'ERROR FOREIGN'; exit 1; fi
              current=${'$'}(settings get global overlay_display_devices) || { echo 'ERROR RECOVERY'; exit 1; }
              if [ "${'$'}current" = "${'$'}previous_spec" ]; then
                settings delete global overlay_display_devices >/dev/null || { echo 'ERROR RECOVERY'; exit 1; }
                current=${'$'}(settings get global overlay_display_devices) || { echo 'ERROR RECOVERY'; exit 1; }
                missing "${'$'}current" || { echo 'ERROR RECOVERY'; exit 1; }
              fi
              rm -f "${'$'}ownerfile"
            fi
            current=${'$'}(settings get global overlay_display_devices) || { echo 'ERROR APPLY'; exit 1; }
            missing "${'$'}current" || { echo 'ERROR FOREIGN'; exit 1; }
            cleanup() {
              trap - EXIT
              current_owner=${'$'}(cat "${'$'}ownerfile" 2>/dev/null)
              [ "${'$'}current_owner" = "${'$'}expected" ] || return
              current=${'$'}(settings get global overlay_display_devices) || { echo 'ERROR CLEANUP'; return; }
              if [ "${'$'}current" = "${'$'}spec" ]; then
                settings delete global overlay_display_devices >/dev/null || { echo 'ERROR CLEANUP'; return; }
                current=${'$'}(settings get global overlay_display_devices) || { echo 'ERROR CLEANUP'; return; }
                missing "${'$'}current" || { echo 'ERROR CLEANUP'; return; }
                rm -f "${'$'}ownerfile"
                echo "CLEANED ${'$'}token"
              else
                rm -f "${'$'}ownerfile"
                echo "PRESERVED ${'$'}token"
              fi
            }
            trap cleanup EXIT
            trap 'exit 0' HUP INT TERM
            printf '%s\n' "${'$'}expected" > "${'$'}ownerfile" || { echo 'ERROR APPLY'; exit 1; }
            settings put global overlay_display_devices "${'$'}spec" || { echo 'ERROR APPLY'; exit 1; }
            current=${'$'}(settings get global overlay_display_devices) || { echo 'ERROR APPLY'; exit 1; }
            [ "${'$'}current" = "${'$'}spec" ] || { echo 'ERROR APPLY'; exit 1; }
            echo "READY ${'$'}token"
            while IFS= read -r -t 15 line; do
              [ "${'$'}line" = "PING ${'$'}token" ] || break
            done
        """.trimIndent()
    }
}
