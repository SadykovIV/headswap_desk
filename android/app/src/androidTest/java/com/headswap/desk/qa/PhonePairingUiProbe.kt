package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.RemoteInput
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.headswap.desk.dex.PhonePairingService
import com.headswap.desk.dex.PairingNotificationBlock

/** Exercises real service/RemoteInput identity. The synthetic six-digit value is not a system pairing code. */
internal object PhonePairingUiProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        val ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = test.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            shell(ui, "pm grant com.headswap.desk android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= 37) shell(ui,"pm grant com.headswap.desk android.permission.ACCESS_LOCAL_NETWORK")
            ui.adoptShellPermissionIdentity()
            test.runOnMainSync { PhonePairingService.start(context, true) }
            await { manager.activeNotifications.any { it.id == 72 && !it.notification.actions.isNullOrEmpty() } }
            val notice = manager.activeNotifications.first { it.id == 72 }.notification
            val firstService = currentService(test)
            val firstAttempt = checkNotNull(PhonePairingService.replyAttempt())
            check(!PhonePairingService.submitCode(context, firstAttempt, ""))
            check(!PhonePairingService.submitCode(context, firstAttempt, "12x"))
            check(PhonePairingService.replyAttempt() == firstAttempt && currentService(test) === firstService)
            result.putBoolean("emptyAppReplyKeepsSameAttempt", true)
            val action = notice.actions.single()
            val input = action.remoteInputs.single()
            result.putBoolean("inlineReply", input.allowFreeFormInput)
            result.putString("action", action.title.toString())
            result.putBoolean("secretOnLockScreen", notice.visibility == android.app.Notification.VISIBILITY_SECRET)
            shell(ui, "am start -W -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
            SystemClock.sleep(400)
            check(PhonePairingService.active) { "Opening Settings cancelled activation" }
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey,"12x") })
            action.actionIntent.send(context,0,fillIn)
            await { manager.activeNotifications.any { it.id == 72 &&
                it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.contains("ровно 6") == true } }
            result.putBoolean("invalidCodeCanRetry", true)
            val cancel = manager.activeNotifications.first { it.id == 71 }.notification.actions.single()
            cancel.actionIntent.send()
            await { !PhonePairingService.active && manager.activeNotifications.none { it.id == 71 || it.id == 72 } }
            result.putBoolean("cancelledAndRemoved", true)
            // Reusing a requestCode with FLAG_UPDATE_CURRENT used to replace A's token with B's token.
            // Verify actual Android PendingIntent identity, rather than only comparing token strings.
            test.runOnMainSync { PhonePairingService.start(context, true) }
            await { manager.activeNotifications.any { it.id == 72 && !it.notification.actions.isNullOrEmpty() } }
            val second = manager.activeNotifications.first { it.id == 72 }.notification
            val secondReply = second.actions.single()
            val secondCancel = manager.activeNotifications.first { it.id == 71 }.notification.actions.single()
            check(action.actionIntent != secondReply.actionIntent && cancel.actionIntent != secondCancel.actionIntent) {
                "An old PendingIntent was reused for the new attempt"
            }
            val secondText = second.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            val secondAttempt = checkNotNull(PhonePairingService.replyAttempt())
            check(secondAttempt != firstAttempt)
            check(!PhonePairingService.submitCode(context, firstAttempt, "123456"))
            check(PhonePairingService.replyAttempt() == secondAttempt)
            result.putBoolean("oldAppReplyCannotAffectNewAttempt", true)
            verifyAppReplyUi(test, secondAttempt, result)
            action.actionIntent.send(context, 0, fillIn)
            cancel.actionIntent.send()
            // Replay the terminal callback belonging to the destroyed A instance after B exists.
            // This is the same callback a slow, cancelled ADB socket can finish later.
            test.runOnMainSync {
                PhonePairingService::class.java.getDeclaredMethod("finish", String::class.java).apply { isAccessible = true }
                    .invoke(firstService, null)
                PhonePairingService::class.java.getDeclaredMethod("update", String::class.java).apply { isAccessible = true }
                    .invoke(firstService, "stale instance A")
            }
            SystemClock.sleep(500)
            check(PhonePairingService.active) { "Old cancel stopped the new attempt" }
            check(manager.activeNotifications.firstOrNull { it.id == 72 }?.notification?.extras
                ?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == secondText) {
                "Old reply changed the new attempt"
            }
            result.putBoolean("previousAttemptCannotAffectNext", true)
            result.putBoolean("oldInstanceCannotFinishNext", true)
            val sameService = currentService(test)
            verifyAppReplyUi(test, secondAttempt, result, submit = true)
            await { PhonePairingService.replyAttempt() == null }
            check(PhonePairingService.active && currentService(test) === sameService) { "App reply restarted activation instead of using its worker" }
            result.putBoolean("appReplyUsesNotificationWorker", true)
            secondCancel.actionIntent.send()
            await { !PhonePairingService.active && manager.activeNotifications.none { it.id == 71 || it.id == 72 } }
            // Old replies and cancellation must not leave an empty foreground service behind.
            action.actionIntent.send(context,0,fillIn)
            cancel.actionIntent.send()
            SystemClock.sleep(500)
            check(!PhonePairingService.active)
            check(manager.activeNotifications.none { it.id == 71 || it.id == 72 })
            result.putBoolean("staleReplyRejected", true)
            result.putBoolean("staleCancelDoesNotResurrectService", true)
            verifyNotificationGates(test, ui, manager, result)
        } catch (error: Throwable) { result.putString("error",android.util.Log.getStackTraceString(error)) }
        finally {
            context.stopService(Intent(context, PhonePairingService::class.java))
            ui.dropShellPermissionIdentity()
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK,result)
    }
    private fun verifyAppReplyUi(test: Instrumentation, attempt: String, result: Bundle, submit: Boolean = false) {
        val intent = Intent(test.targetContext, com.headswap.desk.dex.DexSetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = test.startActivitySync(intent)
        try {
            test.runOnMainSync {
                val type = com.headswap.desk.dex.DexSetupActivity::class.java
                val code = type.getDeclaredField("phonePairCode").apply { isAccessible = true }.get(activity) as android.widget.EditText
                val button = type.getDeclaredField("phonePairButton").apply { isAccessible = true }.get(activity) as android.widget.Button
                type.getDeclaredMethod("renderAccessControls").apply { isAccessible = true }.invoke(activity)
                check(code.isEnabled && code.isShown && button.isEnabled && button.isShown) { "Active notification disabled the app reply form" }
                code.setText("")
                button.performClick()
                check(code.error != null && PhonePairingService.replyAttempt() == attempt)
                if (submit) {
                    code.setText("123456") // Synthetic fixture value; no system pairing dialog is open.
                    button.performClick()
                    check(code.text.isEmpty()) { "App did not submit to the current reply capability" }
                }
            }
            result.putBoolean("appAndNotificationShareEditableAttempt", true)
        } finally { test.runOnMainSync { activity.finish() } }
    }

    private fun currentService(test: Instrumentation): PhonePairingService {
        var found: PhonePairingService? = null
        test.runOnMainSync {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            val services = type.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
            found = services.values.filterIsInstance<PhonePairingService>().single()
        }
        return checkNotNull(found)
    }
    /** Change only this disposable fixture's notification settings, then restore them in finally. */
    private fun verifyNotificationGates(test: Instrumentation, ui: UiAutomation, manager: NotificationManager, result: Bundle) {
        val context = test.targetContext
        val pkg = context.packageName
        val uid = context.applicationInfo.uid
        val parameters = arrayOf(String::class.java, Int::class.javaPrimitiveType!!, NotificationChannel::class.java)
        // Android 17 filters the old Binder declaration (MAX-TARGET-O), while its concrete
        // NotificationManager TestApi wrapper remains available to instrumentation.
        val managerUpdate = runCatching { NotificationManager::class.java.getMethod("updateNotificationChannel", *parameters) }.getOrNull()
        val updateTarget: Any
        val updateChannel: java.lang.reflect.Method
        if (managerUpdate != null) {
            updateTarget = manager
            updateChannel = managerUpdate
        } else {
            updateTarget = NotificationManager::class.java.getDeclaredMethod("getService").invoke(null)
            updateChannel = Class.forName("android.app.INotificationManager")
                .getMethod("updateNotificationChannelForPackage", *parameters)
        }
        val permissionType = runCatching { Class.forName("android.permission.PermissionManager") }.getOrNull()
        val permissionManager = permissionType?.let { context.getSystemService(it) }
        // Ordinary POST_NOTIFICATIONS revocation kills the instrumented process. Android supplies
        // this local-test-only API specifically to test denial without turning it into a process-death test.
        val revokeWithoutKill = permissionType?.let { runCatching {
            it.getMethod("revokePostNotificationPermissionWithoutKillForTest", String::class.java, Int::class.javaPrimitiveType)
        }.getOrNull() }
        val codeId = "headswap_desk_pairing"
        val progressId = "headswap_desk_activation"
        val codeImportance = checkNotNull(manager.getNotificationChannel(codeId)).importance
        val progressImportance = checkNotNull(manager.getNotificationChannel(progressId)).importance
        val appEnabled = manager.areNotificationsEnabled()
        var appPermissionChanged = false
        fun channel(id: String, importance: Int) {
            val channel = checkNotNull(manager.getNotificationChannel(id))
            channel.importance = importance
            updateChannel.invoke(updateTarget, pkg, uid, channel)
        }
        fun blocked(expected: PairingNotificationBlock) {
            check(PhonePairingService.notificationBlock(context, true) == expected)
            test.runOnMainSync { PhonePairingService.start(context, true) }
            SystemClock.sleep(350)
            check(!PhonePairingService.active && manager.activeNotifications.none { it.id == 71 || it.id == 72 }) {
                "Blocked notification settings started an invisible pairing worker"
            }
        }
        try {
            channel(codeId, NotificationManager.IMPORTANCE_NONE)
            blocked(PairingNotificationBlock.CODE_CHANNEL)
            val channelSettings = PhonePairingService.notificationSettings(context, PairingNotificationBlock.CODE_CHANNEL)
            check(channelSettings.action == android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS &&
                channelSettings.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID) == codeId)
            check(PhonePairingService.notificationBlock(context, false) == null)
            channel(codeId, codeImportance)
            channel(progressId, NotificationManager.IMPORTANCE_NONE)
            blocked(PairingNotificationBlock.PROGRESS_CHANNEL)
            channel(progressId, progressImportance)
            if (revokeWithoutKill == null || permissionManager == null) {
                result.putString("appNotificationGate", "NOT_RUN: no no-kill notification TestApi; policy unit test covers app denial")
            } else {
                val failure = runCatching { revokeWithoutKill.invoke(permissionManager, pkg, uid / 100000) }.exceptionOrNull()
                val cause = (failure as? java.lang.reflect.InvocationTargetException)?.targetException ?: failure
                if (cause is SecurityException || cause is IllegalAccessException || cause is UnsupportedOperationException) {
                    result.putString("appNotificationGate", "NOT_RUN: notification TestApi denied (${cause.javaClass.simpleName}); policy unit test covers app denial")
                } else {
                    if (cause != null) throw cause
                    appPermissionChanged = true
                    // Do not let the probe's adopted permission identity mask the denied app permission.
                    ui.dropShellPermissionIdentity()
                    blocked(PairingNotificationBlock.APP)
                    ui.adoptShellPermissionIdentity()
                    ui.grantRuntimePermission(pkg, android.Manifest.permission.POST_NOTIFICATIONS)
                    result.putString("appNotificationGate", "PASS: denied app notifications cannot start a pairing worker")
                }
            }
            check(PhonePairingService.notificationBlock(context, true) == null)
            result.putString("notificationGates", "pairing channel off; progress channel off blocked; enabled allowed; exact channel settings")
        } finally {
            ui.adoptShellPermissionIdentity()
            if (appPermissionChanged) {
                if (appEnabled) ui.grantRuntimePermission(pkg, android.Manifest.permission.POST_NOTIFICATIONS)
                else revokeWithoutKill?.invoke(permissionManager, pkg, uid / 100000)
            }
            channel(codeId, codeImportance)
            channel(progressId, progressImportance)
            manager.cancel(73)
        }
    }
    private fun await(condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+5000
        while (!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timed out waiting for notification state" }; SystemClock.sleep(50) }
    }
    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { String(it.readBytes()) }
}
