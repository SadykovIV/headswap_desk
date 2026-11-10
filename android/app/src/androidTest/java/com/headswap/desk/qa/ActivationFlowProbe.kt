package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.RemoteInput
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.headswap.desk.BridgeService
import com.headswap.desk.dex.ActivationMethod
import com.headswap.desk.dex.DelegatedBootstrapState
import com.headswap.desk.dex.DelegatedDesktopAccess
import com.headswap.desk.dex.DesktopActivationCoordinator
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.DexState
import com.headswap.desk.dex.PhonePairingAttemptGate
import com.headswap.desk.dex.PhonePairingService
import java.io.File

/** Actual foreground-service lifecycle. No valid code is ever submitted to a pairing endpoint. */
internal object ActivationFlowProbe {
    private const val CANCEL = "com.headswap.desk.activation.CANCEL"
    private const val REPLY = "com.headswap.desk.activation.REPLY"
    private const val TOKEN = "attempt"
    private const val CODE = "headswap_desk_pairing_code"

    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var ui: UiAutomation? = null
        var lease: DelegatedBootstrapState.Lease? = null
        var originalPrefs: Map<String, *>? = null
        var originalReceipts: Map<String, ByteArray>? = null
        val context = test.targetContext
        val storage = context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE)
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            check(Build.VERSION.SDK_INT >= 33) { "Supported Android emulator required" }
            check(!BridgeService.alive && !DexState.running && !DelegatedDesktopAccess.ownsActivation &&
                !PhonePairingService.active && serviceAbsent()) { "Stop existing projection and activation before this probe" }
            originalPrefs = storage.all.toMap()
            originalReceipts = receipts(context)
            ui = test.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY or
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.INJECT_EVENTS, DelegatedDesktopAccess.REAL_GET_TASKS,
                DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL,
                "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND",
                "android.permission.ACCESS_LOCAL_NETWORK")
            val prefs = DexPrefs(context)
            prefs.desktopMode = DesktopModePolicy.ANDROID_DESKTOP
            prefs.activationMethod = ActivationMethod.WIFI
            prefs.usbPlaybackAudio = false
            prefs.desktopPanelControl = false
            prefs.independentRecoveryWithoutWifi = false
            prefs.hasPaired = false
            prefs.host = "127.0.0.1"
            prefs.connectPort = 0

            // Exercise the normal first activation, not a test-only forcePair path.
            val first = startWaiting(test, forcePair = false)
            result.putBoolean("firstActivationOffersCodeBeforeEndpoint", true)
            val firstReply = checkNotNull(PhonePairingService.replyAttempt())
            check(!PhonePairingService.cancel(context, "unrelated-test-attempt"))
            deliverCancel(test, "unrelated-test-attempt")
            assertStillWaiting(test, first, firstReply)
            result.putBoolean("staleCancelCannotCloseCurrentRequest", true)

            check(!PhonePairingService.submitCode(context, firstReply, ""))
            check(!PhonePairingService.submitCode(context, firstReply, "invalid"))
            check(!PhonePairingService.submitCode(context, "unrelated-test-round", "invalid"))
            deliverReply(test, firstReply, "invalid")
            assertStillWaiting(test, first, firstReply)
            result.putBoolean("invalidAndStaleCodeDoNotConsumeRound", true)

            test.runOnMainSync {
                check(PhonePairingService.start(context, forcePair = true, allowWithoutNotification = true) != null)
            }
            assertStillWaiting(test, first, firstReply)
            result.putBoolean("duplicateStartKeepsOneServiceAttempt", true)
            cancelAndAwait(test, first)
            // No current service exists, so even a valid-shaped stale value cannot reach a server.
            check(!PhonePairingService.submitCode(context, firstReply, "123456"))
            result.putBoolean("validInAppCancellationStopsService", true)

            val second = startWaiting(test)
            val secondReply = checkNotNull(PhonePairingService.replyAttempt())
            check(second != first && secondReply != firstReply)
            check(!PhonePairingService.cancel(context, first))
            deliverCancel(test, first)
            check(!PhonePairingService.submitCode(context, firstReply, "invalid"))
            deliverReply(test, firstReply, "invalid")
            assertStillWaiting(test, second, secondReply)
            result.putBoolean("oldCancelAndReplyCannotAffectSuccessor", true)
            cancelAndAwait(test, second)

            // Test successful one-use consumption locally, not against a potentially discovered ADB server.
            val gate = PhonePairingAttemptGate("local-fixture")
            val round = checkNotNull(gate.open())
            check(gate.offer(round, "123456"))
            check(!gate.offer(round, "123456") && !gate.complete())
            check(gate.takeCode() != null)
            gate.pairingFinished()
            val successor = checkNotNull(gate.open())
            check(!gate.offer(round, "123456") && gate.token() == successor)
            gate.cancel()
            check(!gate.offer(successor, "123456") && gate.open() == null)
            result.putBoolean("isolatedReplyGateIsOneUse", true)
            result.putBoolean("noValidCodeDeliveredToLiveService", true)

            if (Build.VERSION.SDK_INT <= 36) {
                val before = receipts(context)
                lease = DelegatedDesktopAccess.begin { }
                check(DelegatedDesktopAccess.isReady(context) && DesktopActivationCoordinator.isReady(context)) {
                    "Actual adopted permissions did not satisfy the ready path"
                }
                val started = SystemClock.elapsedRealtime()
                test.runOnMainSync {
                    check(PhonePairingService.start(context, allowWithoutNotification = true) != null)
                }
                await(test, "ready fast path") {
                    PhonePairingService.phase == PhonePairingService.Phase.READY &&
                        PhonePairingService.snapshot.attempt !in setOf(first, second) &&
                        !PhonePairingService.active && serviceAbsent()
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                check(elapsed < 2_000) { "Ready service unexpectedly waited for discovery" }
                check(!prefs.hasPaired && PhonePairingService.replyAttempt() == null)
                check(sameReceipts(before, receipts(context))) { "Ready path dispatched a new grant request" }
                result.putLong("readyWithoutPairingMs", elapsed)
                result.putBoolean("liveAdoptedGrantsUseReadyFastPath", true)
            } else result.putString("readyFastPath", "NOT_RUN: API37 needs an actual VDM host; no TCP setup in this probe")
        } catch (error: Throwable) {
            // Fixture values and tokens are never included in assertions or result output.
            result.putString("error", android.util.Log.getStackTraceString(error))
        } finally {
            val restorePrefs = originalPrefs
            if (restorePrefs != null) {
                runCatching {
                    test.runOnMainSync {
                        PhonePairingService.snapshot.attempt?.let { PhonePairingService.cancel(context, it) }
                        context.stopService(Intent(context, PhonePairingService::class.java))
                    }
                    await(test, "service cleanup") { !PhonePairingService.active && serviceAbsent() }
                }.onFailure { result.putString("cleanupError", it.javaClass.simpleName) }
                lease?.let(DelegatedDesktopAccess::end)
                originalReceipts?.let { saved ->
                    runCatching {
                        receiptFiles(context).forEach { check(it.delete()) }
                        saved.forEach { (name, bytes) -> File(context.filesDir, name).writeBytes(bytes) }
                    }.onFailure { result.putString("receiptRestoreError", it.javaClass.simpleName) }
                }
                runCatching { restore(storage, restorePrefs) }
                    .onFailure { result.putString("prefsRestoreError", it.javaClass.simpleName) }
            }
            runCatching { ui?.dropShellPermissionIdentity() }
        }
        test.finish(if (result.keySet().any { it.endsWith("Error") || it == "error" }) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private fun startWaiting(test: Instrumentation, forcePair: Boolean = true): String {
        test.runOnMainSync {
            check(PhonePairingService.start(test.targetContext, forcePair = forcePair, allowWithoutNotification = true) != null)
        }
        await(test, "pairing prompt") {
            PhonePairingService.active && PhonePairingService.phase == PhonePairingService.Phase.WAITING_CODE &&
                PhonePairingService.replyAttempt() != null
        }
        check(PhonePairingService.snapshot.inAppOnly)
        return checkNotNull(PhonePairingService.snapshot.attempt)
    }

    private fun cancelAndAwait(test: Instrumentation, attempt: String) {
        test.runOnMainSync {
            check(PhonePairingService.cancel(test.targetContext, attempt))
            check(!PhonePairingService.cancel(test.targetContext, attempt))
        }
        await(test, "cancellation") { !PhonePairingService.active && serviceAbsent() }
        check(PhonePairingService.phase == PhonePairingService.Phase.CANCELLED)
    }

    private fun deliverCancel(test: Instrumentation, attempt: String) = test.runOnMainSync {
        test.targetContext.startService(Intent(test.targetContext, PhonePairingService::class.java)
            .setAction(CANCEL).putExtra(TOKEN, attempt))
    }

    private fun deliverReply(test: Instrumentation, attempt: String, value: String) = test.runOnMainSync {
        val intent = Intent(test.targetContext, PhonePairingService::class.java).setAction(REPLY).putExtra(TOKEN, attempt)
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(CODE).build()), intent,
            Bundle().apply { putCharSequence(CODE, value) })
        test.targetContext.startService(intent)
    }

    private fun assertStillWaiting(test: Instrumentation, attempt: String, reply: String) {
        test.waitForIdleSync()
        SystemClock.sleep(150)
        check(PhonePairingService.active && PhonePairingService.snapshot.attempt == attempt &&
            PhonePairingService.phase == PhonePairingService.Phase.WAITING_CODE &&
            PhonePairingService.replyAttempt() == reply) { "Unrelated action changed the active attempt" }
    }

    private fun serviceAbsent(): Boolean = PhonePairingService::class.java.getDeclaredField("currentInstance")
        .apply { isAccessible = true }.get(null) == null

    private fun await(test: Instrumentation, label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $label" }
            test.waitForIdleSync()
            SystemClock.sleep(25)
        }
    }

    private fun receiptFiles(context: Context) = context.filesDir.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("phone-activation-") }
    private fun receipts(context: Context) = receiptFiles(context).associate { file ->
        check(file.length() <= 65_536) { "Unexpected activation fixture size" }
        file.name to file.readBytes()
    }
    private fun sameReceipts(a: Map<String, ByteArray>, b: Map<String, ByteArray>) =
        a.keys == b.keys && a.all { (name, bytes) -> bytes.contentEquals(b.getValue(name)) }

    private fun restore(storage: SharedPreferences, saved: Map<String, *>) {
        val edit = storage.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported preference fixture type")
        } }
        check(edit.commit()) { "Could not restore preferences" }
    }
}
