package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** PC-started, main-process lifetime for a narrowly scoped, temporary UID delegation. */
@Keep
class DelegatedDesktopInstrumentation : Instrumentation() {
    private var requestAudio = false
    private var requestScreen = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestAudio = DesktopAudioPolicy.requestFromArgument(arguments?.getString(DesktopAudioPolicy.ACTIVATION_ARGUMENT))
        requestScreen = DelegatedPanelAccess.requestFromArgument(arguments?.getString(DelegatedPanelAccess.ACTIVATION_ARGUMENT))
        start()
    }

    // The SDK IntDef omits FLAG_DONT_USE_ACCESSIBILITY; this public flag was verified
    // on the target phone and prevents engaging/suppressing accessibility services.
    @SuppressLint("WrongConstant")
    override fun onStart() {
        val context = targetContext
        val stop = CountDownLatch(1)
        var lease: DelegatedBootstrapState.Lease? = null
        var automation: UiAutomation? = null
        var adoptedHere = false
        var ready = false
        var failureReason: String? = null
        val heartbeat = AtomicFile(File(context.filesDir, "computer-access-status.json"))
        fun writeHeartbeat(active: Boolean) {
            val permissions = DelegatedDesktopAccess.hasPermissions(context)
            val bytes = JSONObject().put("protocol", DelegatedDesktopAccess.PROTOCOL)
                .put("ready", active && permissions).put("pid", Process.myPid())
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("permissionsChecked", permissions)
                .put("mediaControl", active && context.checkSelfPermission(DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                .put("playbackAudio", active && requestAudio && DesktopAudioAccess.hasPermission(context))
                .put("panelControl", active && requestScreen && DelegatedPanelAccess.hasPermission(context))
                .toString().toByteArray(Charsets.UTF_8)
            runCatching {
                val output = heartbeat.startWrite()
                try { output.write(bytes); heartbeat.finishWrite(output) }
                catch (error: Throwable) { heartbeat.failWrite(output); throw error }
            }.onFailure { Log.w(DelegatedDesktopAccess.LOG_TAG, "Heartbeat write failed", it) }
        }
        try {
            check(Application.getProcessName() == context.packageName) { "Activation requires the main app process" }
            automation = checkNotNull(getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY or
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
            check(!requestAudio || DexPrefs(context).usbPlaybackAudio) {
                "Сначала включите «Звук по USB» в HeadSwap; разрешение не добавлено"
            }
            check(!requestScreen || DexPrefs(context).desktopPanelControl) {
                "Сначала включите управление панелью в HeadSwap; разрешение не добавлено"
            }
            val permissions = mutableListOf(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.INJECT_EVENTS, DelegatedDesktopAccess.REAL_GET_TASKS,
                DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL)
            if (requestAudio) permissions.add(DesktopAudioPolicy.PERMISSION)
            if (requestScreen) {
                permissions.add(DelegatedPanelAccess.permission())
                permissions.add(PhonePowerActivity.PERMISSION)
            }
            automation.adoptShellPermissionIdentity(*permissions.toTypedArray())
            adoptedHere = true
            check(DelegatedDesktopAccess.hasPermissions(context)) { "Android не предоставил все права рабочего стола и управления музыкой. Повторите явную активацию HeadSwap." }
            check(!requestAudio || DesktopAudioAccess.hasPermission(context)) { "Android не разрешил захват звука приложений" }
            check(!requestScreen || context.checkSelfPermission(PhonePowerActivity.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "Android не предоставил временное право чтения таймера экрана. Повторите активацию с управлением экраном."
            }
            if (requestScreen) try { DelegatedPanelAccess.preflight(context) }
            catch (error: Throwable) { throw IllegalStateException(
                "Управление панелью недоступно (${DelegatedPanelAccess.permission()}): ${error.cause?.message ?: error.message}", error) }
            lease = DelegatedDesktopAccess.begin { stop.countDown() }
            DexPrefs(context).useDelegated = true
            ready = true
            writeHeartbeat(true)
            Log.i(DelegatedDesktopAccess.LOG_TAG, "READY protocol=1 uid=${Process.myUid()} pid=${Process.myPid()} permissions=${permissions.joinToString(",")} playbackAudio=$requestAudio panelControl=$requestScreen")
            runCatching { sendStatus(0, Bundle().apply { putString("headswap_access", "ready") }) }
            // USB detach and producer/service shutdown must never count this latch down.
            while (!stop.await(5, TimeUnit.SECONDS)) {
                check(DelegatedDesktopAccess.hasPermissions(context)) { "Desktop permissions were revoked" }
                writeHeartbeat(true)
            }
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failureReason = ManualAccessPolicy.conciseFailure(error)
            Log.e(DelegatedDesktopAccess.LOG_TAG, "FAILED protocol=1 ${error.javaClass.simpleName}", error)
        } finally {
            lease?.let(DelegatedDesktopAccess::end) // reject new panel leases before cleanup snapshot
            DesktopMusicPlayback.cancel("activation ended")
            DelegatedPanelAccess.restoreAll()
            writeHeartbeat(false)
            // A failed adoption must not drop another package's active delegation.
            if (adoptedHere) runCatching { automation?.dropShellPermissionIdentity() }
            Log.i(DelegatedDesktopAccess.LOG_TAG, "STOPPED protocol=1")
            // Report failure only after cleanup: the CLI may retire its launcher once it reads this result.
            val result = Bundle().apply {
                failureReason?.let { putString("headswap_access", "failed"); putString("headswap_access_error", it) }
            }
            try { finish(if (ready && failureReason == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result) }
            catch (error: Throwable) {
                // UiAutomation's original shell binder may be gone. Target death then
                // makes ActivityManager finish instrumentation and remove its delegate.
                Log.w(DelegatedDesktopAccess.LOG_TAG, "Launcher unavailable; ending only the instrumentation process", error)
                Process.killProcess(Process.myPid())
            }
        }
    }
}
