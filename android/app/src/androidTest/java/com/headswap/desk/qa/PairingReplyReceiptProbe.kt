package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.DexSetupActivity
import com.headswap.desk.dex.PhonePairingService

/** Notification delivery only, on the user's Samsung. No pairing sheet, grants or ADB mode changes. */
internal object PairingReplyReceiptProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        val context = test.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val pairedBefore = DexPrefs(context).hasPaired
        var screen: Activity? = null
        try {
            check(Build.MANUFACTURER.equals("samsung", true) && Build.MODEL == "SM-S948B") { "User Samsung only" }
            check(manager.areNotificationsEnabled()) { "Notifications must already be allowed" }
            val activity = test.startActivitySync(Intent(context, DexSetupActivity::class.java)
                .putExtra(DexSetupActivity.EXTRA_SHOW_ACTIVATION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            screen = activity
            test.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            test.runOnMainSync { PhonePairingService.start(context, forcePair = true) }
            await { PhonePairingService.phase == PhonePairingService.Phase.WAITING_CODE && notice(manager, 72)?.actions?.size == 1 }
            val code = activity.findViewById<EditText>(1005)
            await { code.isShown && code.isEnabled && activity.hasWindowFocus() }
            test.runOnMainSync {
                check(code.requestFocus()) { "Pairing field could not receive focus" }
                code.setText("12")
                code.requestRectangleOnScreen(android.graphics.Rect(0, 0, code.width, code.height), true)
            }
            test.waitForIdleSync()
            test.runOnMainSync {
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(code, InputMethodManager.SHOW_IMPLICIT)
            }
            await { code.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            val focusDeadline = SystemClock.elapsedRealtime() + 2500
            while (SystemClock.elapsedRealtime() < focusDeadline) {
                test.runOnMainSync {
                    check(code.isFocused && code.isEnabled && code.text.toString() == "12") { "Status refresh interrupted code input" }
                    check(code.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) { "Status refresh dismissed keyboard" }
                }
                SystemClock.sleep(100)
            }
            result.putBoolean("inAppKeyboardSurvivesRefresh", true)
            val old = checkNotNull(notice(manager, 72)).actions.single()
            send(context, old, "12x")
            await { notice(manager, 72)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.contains("ровно 6") == true }
            result.putBoolean("invalidReplyAcknowledged", true)

            // No Android pairing sheet is opened by this probe. An accepted synthetic value
            // is cancelled while looking for a sheet, before any actual pairing transaction.
            send(context, old, "123456")
            await { PhonePairingService.phase == PhonePairingService.Phase.PAIRING && PhonePairingService.replyAttempt() == null }
            check(notice(manager, 72)?.actions.isNullOrEmpty()) { "Accepted reply kept an active send action" }
            result.putBoolean("validReplyDeliveredAndAcknowledged", true)
            checkNotNull(notice(manager, 71)).actions.single().actionIntent.send()
            await { !PhonePairingService.active && notice(manager, 72) == null }

            send(context, old, "123456")
            await { notice(manager, 73)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.contains("прервалась") == true }
            check(!PhonePairingService.active && notice(manager, 72) == null)
            result.putBoolean("expiredReplyExplainedWithoutRestart", true)

            test.runOnMainSync { PhonePairingService.start(context, forcePair = true) }
            await { PhonePairingService.phase == PhonePairingService.Phase.WAITING_CODE && notice(manager, 72)?.actions?.size == 1 }
            val current = checkNotNull(PhonePairingService.replyAttempt())
            val currentAction = checkNotNull(notice(manager, 72)).actions.single().actionIntent
            check(currentAction != old.actionIntent)
            send(context, old, "123456")
            SystemClock.sleep(400)
            check(PhonePairingService.active && PhonePairingService.replyAttempt() == current)
            check(checkNotNull(notice(manager, 72)).actions.single().actionIntent == currentAction)
            result.putBoolean("staleReplyCannotReplaceNewAttempt", true)
            checkNotNull(notice(manager, 71)).actions.single().actionIntent.send()
            await { !PhonePairingService.active && notice(manager, 72) == null }

            // Model an Android service/process loss while the reply is still pending.
            // No code is stored on disk and the synthetic code is cancelled before network pairing.
            test.runOnMainSync { PhonePairingService.start(context, forcePair = true) }
            await { PhonePairingService.phase == PhonePairingService.Phase.WAITING_CODE && notice(manager, 72)?.actions?.size == 1 }
            val interrupted = checkNotNull(notice(manager, 72)).actions.single()
            context.stopService(Intent(context, PhonePairingService::class.java))
            await { !PhonePairingService.active }
            send(context, interrupted, "123456")
            await { PhonePairingService.active && PhonePairingService.phase == PhonePairingService.Phase.PAIRING }
            check(notice(manager, 72)?.actions.isNullOrEmpty())
            result.putBoolean("replyResumesInterruptedAttempt", true)
            checkNotNull(notice(manager, 71)).actions.single().actionIntent.send()
            await { !PhonePairingService.active && notice(manager, 72) == null }
            check(DexPrefs(context).hasPaired == pairedBefore)
            result.putBoolean("savedPairingRetained", true)
        } catch (error: Throwable) {
            test.runOnMainSync {
                val field = screen?.findViewById<EditText>(1005)
                result.putString("fieldState", "shown=${field?.isShown} enabled=${field?.isEnabled} focused=${field?.isFocused} window=${screen?.hasWindowFocus()} phase=${PhonePairingService.phase} prompt=${PhonePairingService.replyAttempt() != null}")
                var view: android.view.View? = field
                val ancestry = mutableListOf<String>()
                while (view != null) {
                    ancestry += "${view.javaClass.simpleName}:${view.visibility}"
                    view = view.parent as? android.view.View
                }
                result.putString("fieldParents", ancestry.joinToString(" / "))
            }
            result.putString("error", android.util.Log.getStackTraceString(error))
        } finally {
            context.stopService(Intent(context, PhonePairingService::class.java))
            manager.cancel(73)
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private fun notice(manager: NotificationManager, id: Int): Notification? =
        manager.activeNotifications.firstOrNull { it.id == id }?.notification

    private fun send(context: Context, action: Notification.Action, code: String) {
        val input = action.remoteInputs.single()
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, code) })
        action.actionIntent.send(context, 0, fillIn)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Notification receipt was not confirmed" }
            SystemClock.sleep(40)
        }
    }
}
