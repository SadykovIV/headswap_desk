package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.flyfishxu.kadb.Kadb
import com.headswap.desk.dex.DelegatedBootstrapState
import com.headswap.desk.dex.DelegatedDesktopAccess
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.DexAccess
import com.headswap.desk.dex.DexAccessDiagnostics
import com.headswap.desk.dex.DexEndpoint
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.IVdmDesktopHost
import com.headswap.desk.dex.IVdmDesktopSession
import com.headswap.desk.dex.ManualAccessPolicy
import com.headswap.desk.dex.ShellDisplayIme
import com.headswap.desk.dex.VdmHostBootstrapPolicy
import com.headswap.desk.dex.VdmHostLink
import com.headswap.desk.dex.VdmHostRecovery
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Separately authorized physical-device experiment. It never enables TCP ADB, changes Wi-Fi,
 * modifies an ADB key, or starts an unrelated app. The operator owns transport setup/rollback.
 * Launch its instrumentation command detached so a lost Mac Wi-Fi socket cannot end the lease.
 */
internal object LegacyVdmRecoveryProbe {
    private const val PORT = 38555
    private const val REPORT = "qa-legacy-vdm-recovery.json"

    @JvmStatic fun run(test: Instrumentation, args: Bundle) {
        val context = test.targetContext
        val result = Bundle()
        var automation: UiAutomation? = null
        var lease: DelegatedBootstrapState.Lease? = null
        var recordFile: File? = null
        var previousRecord: ByteArray? = null
        var ownRecord: String? = null
        var prefsBefore: Map<String, Any?>? = null
        var session: IVdmDesktopSession? = null
        var image: ImageReader? = null
        var displayId = -1
        var stage = "guard"
        fun checkpoint(next: String) {
            stage = next
            result.putString("phase", next)
            result.putLong("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            writeReport(context, result)
        }
        try {
            check(args.getString("physical_tcp_test") == "yes" && args.getString("tcp_port") == PORT.toString()) {
                "Physical TCP experiment requires its explicit arguments"
            }
            check(Build.VERSION.SDK_INT == 37 && Build.HARDWARE !in listOf("ranchu", "goldfish")) {
                "Only the explicitly authorized physical Android 17 device may run this probe"
            }
            check(DexPrefs(context).hasPaired) { "The application must already have its own paired key" }
            check(!VdmHostLink.isReady)
            result.putInt("sdk", Build.VERSION.SDK_INT)
            result.putInt("tcpPort", PORT)
            checkpoint("legacy-authentication")
            requireLegacyChallenge()
            legacy(context) { adb ->
                DexAccess.verifyShell(adb, "127.0.0.1", PORT)
                check(shell(adb, "getprop ro.adb.secure") == "1") { "ADB must require authentication" }
                check(shell(adb, "getprop service.adb.tcp.port") == PORT.toString()) { "Unexpected temporary TCP port" }
            }
            result.putBoolean("legacyRsaChallenge", true)
            result.putBoolean("applicationKeyShellUid2000", true)

            val activeAutomation = test.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY or
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation = activeAutomation
            activeAutomation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL,
                ShellDisplayIme.PERMISSION)
            lease = DelegatedDesktopAccess.begin { }
            val token = UUID.randomUUID().toString().replace("-", "")
            VdmHostLink.beginActivation(token)
            val pid = Process.myPid()
            val stat = File("/proc/$pid/stat").readText()
            val ticks = stat.substring(stat.lastIndexOf(") ") + 2).trim().split(Regex("\\s+"))[19].toLong()
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            check(ticks > 0 && boot >= 0)
            val record = "1 ${Process.myUid()} $pid $ticks $boot ${SystemClock.elapsedRealtime()} ime $token"
            val file = File(context.noBackupFilesDir, "display-helper-session")
            recordFile = file
            previousRecord = file.takeIf(File::isFile)?.readBytes()
            ownRecord = record
            file.writeText(record, Charsets.US_ASCII)
            val rawPrefs = context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE)
            prefsBefore = listOf("host", "connect_port", "has_paired").associateWith { rawPrefs.all[it] }
            check(rawPrefs.edit().putString("host", "127.0.0.1").putInt("connect_port", PORT)
                .putBoolean("has_paired", true).commit())

            checkpoint("legacy-host-bootstrap")
            val request = checkNotNull(VdmHostLink.requestLaunch())
            legacy(context) { adb ->
                DexAccess.verifyShell(adb, "127.0.0.1", PORT)
                check(shell(adb, "run-as ${ManualAccessPolicy.PACKAGE} --user ${Process.myUid() / 100000} cat no_backup/display-helper-session") == record) {
                    "The authenticated endpoint did not prove this activation owner"
                }
                val command = VdmHostBootstrapPolicy.recoveryCommand(context.applicationInfo.sourceDir,
                    Process.myUid(), pid, token, request.nonce)
                val output = adb.shell(command)
                check(output.exitCode == 0 && output.output.lineSequence().any { it.trim() == "HEADSWAP_VDM_READY" }
                    && VdmHostLink.isReady) { "Legacy TCP host bootstrap did not confirm readiness" }
            }
            val firstBinder = checkNotNull(VdmHostLink.nativeHostLifetime)
            val first = IVdmDesktopHost.Stub.asInterface(firstBinder)
            val firstPid = first.hostPid()
            check(firstPid > 1)
            result.putBoolean("legacyBootstrapReady", true)
            checkpoint("ready-for-wifi-off")

            // The external operator now disables Wi-Fi. This probe has no permission or
            // command that could do so itself; the local result survives loss of Mac ADB.
            val wifi = context.getSystemService(WifiManager::class.java)
            await(60_000, "Operator did not disable Wi-Fi") { wifi.wifiState == WifiManager.WIFI_STATE_DISABLED }
            checkpoint("wifi-off-confirmed")
            requireLegacyChallenge()
            legacy(context) { adb ->
                DexAccess.verifyShell(adb, "127.0.0.1", PORT)
                await(6_000, "TLS listener still active; offline legacy proof is ambiguous") {
                    val adbState = shell(adb, "dumpsys adb").substringAfter("adb_wifi={", "").substringBefore('}')
                    Regex("\\btls_port=0\\b").containsMatchIn(adbState)
                }
            }
            result.putBoolean("wifiDisabled", true)
            result.putBoolean("noTlsListener", true)
            result.putBoolean("legacyAuthenticationWithoutWifi", true)
            result.putBoolean("firstHostSurvivedWifiOff", firstBinder.isBinderAlive && VdmHostLink.isReady)
            if (firstBinder.isBinderAlive) first.shutdown()
            await(6_000, "Owned host did not stop") { !firstBinder.isBinderAlive && !VdmHostLink.isReady }
            check(DelegatedDesktopAccess.isReady(context)) { "Test lease was lost" }

            checkpoint("legacy-offline-host-recovery")
            VdmHostRecovery.create(context) { true }.use { recovery ->
                check(recovery.ensureReady(18_000)) { "Production recovery did not restart the host over local TCP" }
            }
            val second = IVdmDesktopHost.Stub.asInterface(checkNotNull(VdmHostLink.nativeHostLifetime))
            check(second.hostPid() > 1 && second.hostPid() != firstPid)
            check(wifi.wifiState == WifiManager.WIFI_STATE_DISABLED)
            result.putBoolean("legacyOfflineHostRestart", true)

            checkpoint("empty-owned-display")
            val ownedSession = VdmHostLink.openSession(Binder(), Process.myPid()).also { session = it }
            val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 2).also { image = it }
            displayId = ownedSession.createDisplay(reader.surface, "HeadSwap Android Desktop", 320, 180, 128,
                DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, Build.VERSION.SDK_INT, true))
            check(displayId > 0 && ownedSession.ownsDisplay(displayId))
            val group = ownedSession.displayGroup(displayId)
            check(group > 0) { "Display was not in its independent group" }
            result.putBoolean("emptyOwnedDisplayCreated", true)
            result.putInt("independentDisplayGroup", group)
            ownedSession.releaseDisplay(displayId)
            check(!ownedSession.ownsDisplay(displayId))
            displayId = -1
            result.putBoolean("ownedDisplayReleased", true)
            result.putBoolean("passed", true)
        } catch (error: Throwable) {
            result.putBoolean("passed", false)
            result.putString("errorStage", stage)
            result.putString("error", DexAccessDiagnostics.safeTrace(error))
        } finally {
            if (displayId > 0) runCatching { session?.releaseDisplay(displayId) }
            runCatching { session?.close() }
            runCatching { image?.close() }
            runCatching { VdmHostLink.endActivation() }
            lease?.let { runCatching { DelegatedDesktopAccess.end(it) } }
            prefsBefore?.let { before ->
                val restored = runCatching {
                    context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE).edit().apply {
                        before.forEach { (key, value) -> when (value) {
                            null -> remove(key)
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Boolean -> putBoolean(key, value)
                            else -> error("Unexpected preference type")
                        } }
                    }.commit()
                }.getOrDefault(false)
                result.putBoolean("preferencesRestored", restored)
                if (!restored) result.putBoolean("passed", false)
            }
            recordFile?.let { file -> runCatching {
                if (file.isFile && ownRecord != null && file.readText(Charsets.US_ASCII) == ownRecord) {
                    val previous = previousRecord
                    if (previous == null) file.delete() else file.writeBytes(previous)
                }
            } }
            runCatching { automation?.dropShellPermissionIdentity() }
            checkpoint("finished")
        }
        test.finish(if (result.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun legacy(context: Context, action: (Kadb) -> Unit) {
        DexAccess.connectEndpoint(context, DexEndpoint("127.0.0.1", PORT), 20_000).use(action)
    }

    private fun shell(adb: Kadb, command: String): String {
        val result = adb.shell(command)
        check(result.exitCode == 0) { "Read-only local ADB command failed" }
        return result.output.trimEnd()
    }

    /** ADB CNXN preface only: close after AUTH, without sending a key, code or shell command. */
    private fun requireLegacyChallenge() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 3_000)
            socket.soTimeout = 5_000
            val cnxn = 0x4e584e43
            val auth = 0x48545541
            val payload = "host::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cnxn).putInt(0x01000001).putInt(1_048_576).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 255 }).putInt(cnxn xor -1).array()
            socket.getOutputStream().apply { write(header); write(payload); flush() }
            val reply = ByteArray(24)
            DataInputStream(socket.getInputStream()).readFully(reply)
            val decoded = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
            val command = decoded.int
            val type = decoded.int
            decoded.int
            val length = decoded.int
            decoded.int
            val magic = decoded.int
            check(command == auth && type == 1 && length == 20 && magic == (command xor -1)) {
                "Port did not request authenticated legacy ADB; TLS/anonymous transport is not accepted"
            }
        }
    }

    private fun await(timeout: Long, message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < until) { message }
            SystemClock.sleep(100)
        }
    }

    private fun writeReport(context: Context, result: Bundle) {
        val json = JSONObject()
        result.keySet().forEach { key -> json.put(key, result.get(key)) }
        val file = AtomicFile(File(context.filesDir, REPORT))
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
