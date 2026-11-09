package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.headswap.desk.dex.DelegatedDesktopAccess
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.IVdmDesktopHost
import com.headswap.desk.dex.ShellDisplayIme
import com.headswap.desk.dex.VdmHostLink
import com.headswap.desk.dex.VdmHostRecovery
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
                ShellDisplayIme.PERMISSION, "android.permission.DUMP")
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
            // This closes only the host accepted by our current activation, through its own API.
            first.shutdown()
            await { !firstBinder.isBinderAlive && !VdmHostLink.isReady }
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

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Owned host did not stop" }
            SystemClock.sleep(80)
        }
    }
}
