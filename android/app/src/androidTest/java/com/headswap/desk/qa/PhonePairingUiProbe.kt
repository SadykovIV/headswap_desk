package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.RemoteInput
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.headswap.desk.dex.PhonePairingService

/** Exercises the real foreground service and inline reply without ever supplying a valid pairing code. */
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
            // PendingIntent from the old attempt must not resurrect a worker or a new pairing operation.
            action.actionIntent.send(context,0,fillIn)
            SystemClock.sleep(500)
            check(!PhonePairingService.active)
            check(manager.activeNotifications.none { it.id == 71 || it.id == 72 })
            result.putBoolean("staleReplyRejected", true)
        } catch (error: Throwable) { result.putString("error",android.util.Log.getStackTraceString(error)) }
        finally {
            context.stopService(Intent(context, PhonePairingService::class.java))
            ui.dropShellPermissionIdentity()
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK,result)
    }
    private fun await(condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+5000
        while (!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timed out waiting for notification state" }; SystemClock.sleep(50) }
    }
    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { String(it.readBytes()) }
}
