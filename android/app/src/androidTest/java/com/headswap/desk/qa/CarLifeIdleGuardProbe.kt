package com.headswap.desk.qa

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.headswap.desk.dex.CarLifePhoneIdleTimer
import com.headswap.desk.dex.DelegatedDesktopAccess
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.DexState
import com.headswap.desk.dex.PhoneIdleMode
import com.headswap.desk.dex.PhoneIdleTimeout

/** The panel may spend five seconds waiting for its Activity before acquiring a wake lock. */
internal object CarLifeIdleGuardProbe {
    @JvmStatic fun run(test: Instrumentation, ui: UiAutomation) {
        val context = test.targetContext
        val prefs = DexPrefs(context)
        val previousMode = prefs.phoneIdleMode
        val previousTimeout = prefs.phoneIdleTimeout
        val previousAccess = prefs.accessMode
        val previousControl = prefs.desktopPanelControl
        val previousBusy = DexState.phoneScreenBusy
        val access = DelegatedDesktopAccess.begin { }
        val timer = CarLifePhoneIdleTimer(context, sessionReady = { true }, requestOff = { false })
        try {
            ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL)
            prefs.useDelegated = true
            prefs.desktopPanelControl = true
            prefs.phoneIdleMode = PhoneIdleMode.PANEL
            prefs.phoneIdleTimeout = PhoneIdleTimeout.TEN_SECONDS
            DexState.phoneScreenBusy = true // Request accepted; panel's own wake lock not yet acquired.
            test.runOnMainSync(timer::start)
            check(guardHeld(ui)) { "Phone can lock while the panel request is pending" }
            SystemClock.sleep(5_250)
            check(guardHeld(ui)) { "Phone idle guard expired during panel handoff" }
            test.runOnMainSync(timer::stop)
            check(!guardHeld(ui)) { "Phone idle guard outlived the USB session" }
        } finally {
            test.runOnMainSync(timer::stop)
            DexState.phoneScreenBusy = previousBusy
            prefs.accessMode = previousAccess
            prefs.phoneIdleMode = previousMode
            prefs.phoneIdleTimeout = previousTimeout
            prefs.desktopPanelControl = previousControl
            ui.dropShellPermissionIdentity()
            DelegatedDesktopAccess.end(access)
        }
    }

    private fun guardHeld(ui: UiAutomation): Boolean =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys power")).bufferedReader().useLines { lines ->
            lines.any { it.contains("SCREEN_BRIGHT_WAKE_LOCK") && it.contains("HeadSwap:PhoneIdle") && !it.contains("DISABLED") }
        }
}
