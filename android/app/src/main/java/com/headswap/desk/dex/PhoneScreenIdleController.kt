package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.database.ContentObserver
import android.graphics.Color
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.headswap.desk.AppLog

/** Tracks this phone window's idle time and hands hardware OFF requests to the existing panel lease. */
internal class PhoneScreenIdleController(
    private val activity: Activity,
    private val isEnabled: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val onStateChanged: () -> Unit = {},
    private val prefersPhysicalOff: () -> Boolean = { false },
    private val requestPhysicalOff: () -> Boolean = { false },
    private val panelBusy: () -> Boolean = { false },
    private val panelOff: () -> Boolean = { false },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = PhoneScreenIdlePolicy()
    private val presentation = if (Build.VERSION.SDK_INT >= 35) PhoneIdleTrustedWindow(activity, ::refresh) else null
    private var started = false
    private var focused = false
    private var watching = false
    private val prefs = DexPrefs(activity)
    private var systemTimeoutMs = 0L
    private var selectedTimeout: PhoneIdleTimeout? = null
    private val timeoutMs: Long get() = prefs.phoneIdleTimeout.effectiveMs(systemTimeoutMs)
    private var keepAwakeOwned = false
    private var keepAwakeWasSet = false
    private var lastFocusReport = ""
    private var curtain: CurtainView? = null
    private var manualOutsideTimer = false
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private data class BarAppearance(val statusColor: Int, val navColor: Int, val dividerColor: Int,
        val statusContrast: Boolean, val navContrast: Boolean, val lightFlags: Int, val hidStatus: Boolean)
    private var savedBars: BarAppearance? = null
    private var savedFocus: View? = null
    private val savedAccessibility = ArrayList<Pair<View, Int>>()
    private val deadline = Runnable { refresh() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { readTimeout(); refresh() }
    }

    val isCurtainVisible: Boolean get() = curtain != null

    /** Call from Activity.onResume; onStop must be called from Activity.onPause. */
    fun onStart() {
        if (!started) {
            started = true
            activity.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, observer,
            )
            watching = true
            readTimeout()
        }
        if (activity.display?.displayId == Display.DEFAULT_DISPLAY) phoneWindow = this
        focused = activity.hasWindowFocus()
        refresh()
    }

    fun onStop() {
        if (phoneWindow === this) phoneWindow = null
        started = false
        manualOutsideTimer = false
        focused = false
        if (Build.VERSION.SDK_INT >= 35) presentation?.close()
        if (watching) {
            activity.contentResolver.unregisterContentObserver(observer)
            watching = false
        }
        apply(policy.refresh(false, now(), 0))
    }

    fun onFocusChanged(hasFocus: Boolean) {
        if (focused && !hasFocus) {
            // Discard proof made before focus moved. A phone dialog and a focus move to a CarLife
            // widget start identically; only a new unobscured presentation may arm another timer.
            if (Build.VERSION.SDK_INT >= 35) presentation?.close()
            apply(policy.refresh(false, now(), timeoutMs))
        }
        focused = hasFocus
        refresh()
    }

    /** Called only by this phone Activity, never by the CarLife touch path. */
    fun onUserInteraction() {
        // Activity dispatches this callback before CurtainView sees a tap. A single tap must not wake it.
        if (eligible()) apply(policy.onUserInteraction(now(), panelBusy() || panelOff()))
        else apply(policy.refresh(false, now(), timeoutMs))
    }

    fun refresh() {
        updateEligibility()
    }

    private fun updateEligibility(): Boolean {
        val timestamp = now()
        val selected = prefs.phoneIdleTimeout
        if (selected != selectedTimeout) {
            // Rearm a running countdown without dismissing a curtain or interrupting a panel request.
            policy.onUserInteraction(timestamp, panelBusy() || panelOff())
            selectedTimeout = selected
        }
        return apply(policy.refresh(eligible(), timestamp, selected.effectiveMs(systemTimeoutMs),
            !manualOutsideTimer && prefersPhysicalOff(), panelBusy(), panelOff()))
    }

    private fun readTimeout() {
        systemTimeoutMs = Settings.System.getLong(activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0L)
    }

    fun showNow(manualCurtain: Boolean = false): Boolean {
        // Explicit dimming still works with the automatic timer disabled.
        manualOutsideTimer = manualCurtain && !isEnabled() && prefs.desktopPanelControl
        if (updateEligibility()) return true
        return apply(policy.showNow(now())).also { if (!it) manualOutsideTimer = false }
    }

    fun dismiss() {
        manualOutsideTimer = false
        apply(policy.dismiss(now()))
        refresh()
    }

    private fun eligible(): Boolean {
        if (!started || activity.isFinishing || activity.isDestroyed || activity.display?.displayId != Display.DEFAULT_DISPLAY ||
            !prefs.desktopPanelControl || (!isEnabled() && !manualOutsideTimer) || !isConnected()) return false
        if (Build.VERSION.SDK_INT >= 35) presentation?.observe()
        if (!focused) {
            if (Build.VERSION.SDK_INT < 35) return false
            val owned = presentation?.ownsRemoteFocus() == true
            val report = "phoneFocus=false presented=${presentation?.hasPresentationProof} topDisplay=${presentation?.lastTopDisplay} owned=$owned"
            if (report != lastFocusReport) { lastFocusReport = report; android.util.Log.i("HeadSwapPhoneIdle", report) }
            if (!owned) return false
        } else lastFocusReport = ""
        val power = activity.getSystemService(PowerManager::class.java) ?: return false
        val keyguard = activity.getSystemService(KeyguardManager::class.java) ?: return false
        return power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
    }

    private fun apply(next: PhoneScreenIdlePolicy.State): Boolean {
        handler.removeCallbacks(deadline)
        // Service dispatch only. Actual panel power, USB/grant validation and cleanup belong to
        // the existing phone-panel lease. A rejected request cannot fall back to a surprise curtain.
        val accepted = next.requestPhysicalOff && runCatching(requestPhysicalOff).getOrDefault(false)
        val state = if (next.requestPhysicalOff) policy.physicalRequestResult(accepted, now()) else next
        val wasVisible = isCurtainVisible
        if (state.keepAwake && !keepAwakeOwned) {
            keepAwakeWasSet = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            keepAwakeOwned = true
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (state.curtainVisible && curtain == null) showCurtain()
        if (!state.curtainVisible && curtain != null) hideCurtain()
        if (!state.keepAwake && keepAwakeOwned) {
            if (!keepAwakeWasSet) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepAwakeOwned = false
        }
        state.delayMs?.let { handler.postDelayed(deadline, it.coerceAtLeast(1L)) }
        if (wasVisible != isCurtainVisible) {
            if (!isCurtainVisible) manualOutsideTimer = false
            AppLog.i("DESKTOP PANEL: phone window curtain=${isCurtainVisible}; ${phoneDisplayStatus(activity)}", false)
            onStateChanged()
        }
        return accepted || isCurtainVisible
    }

    private fun showCurtain() {
        val decor = activity.window.decorView as? ViewGroup ?: return
        savedBrightness = activity.window.attributes.screenBrightness
        savedFocus = activity.currentFocus
        savedAccessibility.clear()
        for (i in 0 until decor.childCount) {
            val child = decor.getChildAt(i)
            savedAccessibility += child to child.importantForAccessibility
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val view = CurtainView(activity, ::dismiss)
        curtain = view
        decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.requestFocus()
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0f }
        darkenBars(decor)
    }

    private fun hideCurtain() {
        val view = curtain ?: return
        curtain = null
        (view.parent as? ViewGroup)?.removeView(view)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = savedBrightness }
        restoreBars()
        savedAccessibility.forEach { (child, value) -> child.importantForAccessibility = value }
        savedAccessibility.clear()
        savedFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        savedFocus = null
    }

    @Suppress("DEPRECATION") // These window colors still apply on pre-edge-to-edge Android devices.
    private fun darkenBars(decor: View) {
        val window = activity.window
        val bars = window.insetsController ?: return
        val insets = decor.rootWindowInsets
        val hideStatus = insets != null && insets.isVisible(WindowInsets.Type.navigationBars()) && insets.isVisible(WindowInsets.Type.statusBars())
        savedBars = BarAppearance(window.statusBarColor, window.navigationBarColor, window.navigationBarDividerColor,
            window.isStatusBarContrastEnforced, window.isNavigationBarContrastEnforced, bars.systemBarsAppearance and LIGHT_BARS, hideStatus)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.navigationBarDividerColor = Color.BLACK
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        bars.setSystemBarsAppearance(LIGHT_BARS, LIGHT_BARS)
        // Hiding navigation starts Android's first-use immersive tutorial. That system window
        // takes focus and would immediately dismiss the curtain on a newly configured phone.
        if (hideStatus) bars.hide(WindowInsets.Type.statusBars())
    }

    @Suppress("DEPRECATION")
    private fun restoreBars() {
        val saved = savedBars ?: return
        savedBars = null
        val window = activity.window
        window.statusBarColor = saved.statusColor
        window.navigationBarColor = saved.navColor
        window.navigationBarDividerColor = saved.dividerColor
        window.isStatusBarContrastEnforced = saved.statusContrast
        window.isNavigationBarContrastEnforced = saved.navContrast
        window.insetsController?.let { bars ->
            bars.setSystemBarsAppearance(saved.lightFlags, LIGHT_BARS)
            if (saved.hidStatus) bars.show(WindowInsets.Type.statusBars())
        }
    }

    private class CurtainView(activity: Activity, private val wake: () -> Unit) : View(activity) {
        private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onDoubleTap(event: MotionEvent): Boolean = true
            override fun onDoubleTapEvent(event: MotionEvent): Boolean {
                // Keep the curtain as touch target until the second finger is up.
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        })

        init {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusableInTouchMode = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Чёрный экран HeadSwap. Дважды коснитесь, чтобы вернуться."
        }

        @SuppressLint("ClickableViewAccessibility") // Physical single taps are intentionally swallowed; accessibility ACTION_CLICK still works.
        override fun onTouchEvent(event: MotionEvent): Boolean { detector.onTouchEvent(event); return true }

        override fun performClick(): Boolean {
            super.performClick()
            wake()
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Вернуться в HeadSwap"))
        }
    }

    private fun now(): Long = SystemClock.uptimeMillis()
    companion object {
        // Main thread only. Reuse the resumed primary HeadSwap window and its existing eligibility checks.
        private var phoneWindow: PhoneScreenIdleController? = null
        fun phoneCurtainVisible(): Boolean? = phoneWindow?.isCurtainVisible
        fun setPhoneCurtain(show: Boolean): Boolean {
            val window = phoneWindow ?: return false
            if (!window.prefs.desktopPanelControl || window.prefs.phoneScreenMode != PhoneIdleMode.CURTAIN) return false
            if (show) return window.showNow(manualCurtain = true)
            if (window.isCurtainVisible) window.dismiss()
            return true
        }
        private const val LIGHT_BARS = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    }
}
