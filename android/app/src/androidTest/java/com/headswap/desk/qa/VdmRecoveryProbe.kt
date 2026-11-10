package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import com.headswap.desk.dex.DelegatedDesktopAccess
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.IVdmDesktopHost
import com.headswap.desk.dex.ShellDisplayIme
import com.headswap.desk.dex.VdmHostLink
import com.headswap.desk.dex.VdmHostRecovery
import com.headswap.desk.dex.VdmRecoveryPolicy
import java.io.File
import java.util.UUID

/** Requires genuine prior pairing on this disposable AVD; no fake ADB server or shell bootstrap. */
internal object VdmRecoveryProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        val context = test.targetContext
        var ui: UiAutomation? = null
        var lease: com.headswap.desk.dex.DelegatedBootstrapState.Lease? = null
        var recordFile: File? = null
        var priorRecord: ByteArray? = null
        var ownRecord: String? = null
        var restoreWifiDebugging = false
        var priorWifiDebugging: String? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 37) {
                "Android 17 disposable emulator required"
            }
            check(DexPrefs(context).hasPaired) { "Complete real phone-side pairing before this probe" }
            check(!VdmHostLink.isReady)
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL,
                ShellDisplayIme.PERMISSION, "android.permission.DUMP", "android.permission.WRITE_SECURE_SETTINGS")
            lease = DelegatedDesktopAccess.begin { }
            val token = UUID.randomUUID().toString().replace("-", "")
            VdmHostLink.beginActivation(token)
            // Instrumentation replaced the previous app process. Create this test lease's exact
            // private proof; the production recovery verifies it over genuine authenticated TLS.
            val pid = Process.myPid()
            val stat = File("/proc/$pid/stat").readText()
            val ticks = stat.substring(stat.lastIndexOf(") ") + 2).trim().split(Regex("\\s+"))[19].toLong()
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            check(ticks > 0 && boot >= 0)
            val record = "1 ${Process.myUid()} $pid $ticks $boot ${SystemClock.elapsedRealtime()} ime $token"
            val file = File(context.noBackupFilesDir, "display-helper-session")
            recordFile = file
            priorRecord = file.takeIf(File::isFile)?.readBytes()
            ownRecord = record
            file.writeText(record, Charsets.US_ASCII)

            VdmHostRecovery.create(context) { false }.use { check(!it.ensureReady(500)) }
            VdmHostRecovery.create(context) { true }.use { request ->
                request.close()
                check(!request.ensureReady(500))
            }
            check(!VdmHostLink.isReady) { "Cancelled recovery created a host" }
            result.putBoolean("cancelledBeforeStart", true)

            VdmHostRecovery.create(context) { true }.use { request ->
                check(request.ensureReady(18_000)) { request.failure ?: "TLS recovery did not acknowledge readiness" }
            }
            val firstBinder = checkNotNull(VdmHostLink.nativeHostLifetime)
            val first = IVdmDesktopHost.Stub.asInterface(firstBinder)
            val firstPid = first.hostPid()
            check(firstPid > 1)
            result.putBoolean("savedTlsPairingBootstrap", true)

            // A live Binder needs no saved address or paired-key metadata. Remove only this
            // probe's connection hints, never the real key or Android's pairing record.
            val rawPrefs = context.getSharedPreferences("headswap_dex", android.content.Context.MODE_PRIVATE)
            val hadPaired = rawPrefs.contains("has_paired")
            val paired = rawPrefs.getBoolean("has_paired", false)
            val hadPort = rawPrefs.contains("connect_port")
            val port = rawPrefs.getInt("connect_port", 0)
            try {
                check(rawPrefs.edit().putBoolean("has_paired", false).putInt("connect_port", 0).commit())
                val started = SystemClock.elapsedRealtime()
                VdmHostRecovery.create(context) { true }.use { request ->
                    check(request.ensureReady(1_000)) { "Live host incorrectly required paired-debugging metadata" }
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                check(elapsed < 1_000) { "Live host did not take the direct Binder path" }
                check(VdmHostLink.nativeHostLifetime === firstBinder && first.hostPid() == firstPid)
                result.putLong("liveHostNoPairingMetadataMs", elapsed)
                result.putBoolean("liveHostDoesNotReconnectAdb", true)
            } finally {
                rawPrefs.edit().apply {
                    if (hadPaired) putBoolean("has_paired", paired) else remove("has_paired")
                    if (hadPort) putInt("connect_port", port) else remove("connect_port")
                }.commit()
            }
            // This closes only the host accepted by our current activation, through its own API.
            first.shutdown()
            await { !firstBinder.isBinderAlive && !VdmHostLink.isReady }

            // On this disposable AVD only: check the real Android wireless-debugging setting,
            // retain the adopted permissions, then restore the setting before TLS restart.
            priorWifiDebugging = Settings.Global.getString(context.contentResolver, "adb_wifi_enabled")
            restoreWifiDebugging = true
            check(Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0))
            await {
                Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1) == 0 &&
                    wirelessDebugState(automation)?.let { !it.enabled && it.port == 0 } == true
            }
            val offlineStarted = SystemClock.elapsedRealtime()
            VdmHostRecovery.create(context) { true }.use { request ->
                check(!request.ensureReady(18_000)) { "Missing host unexpectedly started without wireless debugging" }
                check(request.failure == VdmRecoveryPolicy.OFFLINE_MESSAGE) { "Offline failure hid the retained activation" }
            }
            val offlineMs = SystemClock.elapsedRealtime() - offlineStarted
            check(offlineMs < 20_000) { "Offline recovery exceeded its bounded restart window" }
            check(DelegatedDesktopAccess.isReady(context)) { "Offline recovery discarded existing grants" }
            check(!VdmHostLink.isReady)
            result.putLong("offlineHostFailureMs", offlineMs)
            result.putBoolean("offlineFailureRetainsActivation", true)
            check(Settings.Global.putString(context.contentResolver, "adb_wifi_enabled", priorWifiDebugging))
            await { wirelessDebugState(automation)?.let { it.enabled && it.port > 0 } == true }
            result.putBoolean("wirelessDebuggingRestored", true)
            restoreWifiDebugging = false
            VdmHostRecovery.create(context) { true }.use { request ->
                check(request.ensureReady(18_000)) { request.failure ?: "TLS host restart did not acknowledge readiness" }
            }
            val second = IVdmDesktopHost.Stub.asInterface(checkNotNull(VdmHostLink.nativeHostLifetime))
            check(second.hostPid() > 1 && second.hostPid() != firstPid)
            result.putBoolean("savedTlsPairingHostRestart", true)
            result.putBoolean("pairingRetained", DexPrefs(context).hasPaired)
            result.putInt("sdk", Build.VERSION.SDK_INT)
        } catch (error: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(error))
        } finally {
            if (restoreWifiDebugging) runCatching {
                Settings.Global.putString(context.contentResolver, "adb_wifi_enabled", priorWifiDebugging)
            }
            runCatching { VdmHostLink.endActivation() }
            lease?.let { runCatching { DelegatedDesktopAccess.end(it) } }
            recordFile?.let { file -> runCatching {
                if (ownRecord != null && file.readText(Charsets.US_ASCII) == ownRecord) {
                    val previous = priorRecord
                    if (previous == null) file.delete() else file.writeBytes(previous)
                }
            } }
            ui?.dropShellPermissionIdentity()
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private data class WirelessDebugState(val enabled: Boolean, val port: Int)

    /** State only: never retain or report the keys included elsewhere in this system dump. */
    private fun wirelessDebugState(automation: UiAutomation): WirelessDebugState? {
        val text = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys adb")).use { input ->
            val bytes = ByteArray(65_537)
            var length = 0
            while (length < bytes.size) {
                val count = input.read(bytes, length, bytes.size - length)
                if (count < 0) break
                length += count
            }
            check(length <= 65_536) { "ADB state too large" }
            String(bytes, 0, length, Charsets.UTF_8)
        }.substringAfter("adb_wifi={", "").substringBefore('}')
        val enabled = Regex("\\benabled=(true|false)").find(text)?.groupValues?.get(1) ?: return null
        val port = Regex("\\btls_port=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return WirelessDebugState(enabled == "true", port)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Owned host did not stop" }
            SystemClock.sleep(80)
        }
    }
}
