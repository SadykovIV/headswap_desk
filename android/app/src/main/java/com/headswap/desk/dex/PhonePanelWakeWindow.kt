package com.headswap.desk.dex

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One nonexported Activity on display 0, in an empty private task, for the lifetime of the lease. */
internal class PhonePanelWakeWindow(
    private val app: Context, private val onDoubleTap: () -> Unit, private val onWindowLost: () -> Unit,
) {
    companion object {
        private val next = AtomicLong()
        private val active = AtomicReference<PhonePanelWakeWindow?>()
        private val main = Handler(Looper.getMainLooper())
        internal const val EXTRA = "panel_request"
        internal fun request(id: Long): PhonePanelWakeWindow? = active.get()?.takeIf { it.id == id }
        @Suppress("DEPRECATION")
        internal fun topOnPhone(context: Context): ComponentName? = runCatching {
            context.getSystemService(ActivityManager::class.java).getRunningTasks(100).firstOrNull {
                it.isVisible && FrameworkTaskIdentityReader.read(it)?.displayId == Display.DEFAULT_DISPLAY
            }?.topActivity
        }.getOrNull()
    }
    private val id = next.incrementAndGet()
    private val ready = CountDownLatch(1)
    @Volatile private var presented = false
    @Volatile private var closed = false
    private var activity: PhonePanelWakeActivity? = null // main thread only

    fun show() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Запуск окна ожидается из службы панели" }
        check(mayStart()) { "На телефоне открыт системный запрос. Таймер попробует снова после его закрытия." }
        check(active.compareAndSet(null, this)) { "На телефоне уже есть окно пробуждения" }
        try {
            check(!closed) { "Запрос гашения отменён" }
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }
            app.startActivity(Intent(app, PhonePanelWakeActivity::class.java).setFlags(flags).putExtra(EXTRA, id), options.toBundle())
            check(ready.await(5, TimeUnit.SECONDS) && presented && active.get() === this) {
                "Android не показал окно пробуждения на экране телефона"
            }
        } catch (error: Throwable) { close(); throw error }
    }
    // Inspect only the top component on the phone's own display. Never read window text or log
    // the other app's identity. Do not place an Activity over a permission, unlock or install UI.
    @Suppress("DEPRECATION")
    private fun mayStart(): Boolean = runCatching {
        val lock = app.getSystemService(KeyguardManager::class.java)
        if (lock.isKeyguardLocked || lock.isDeviceLocked || !DelegatedPanelAccess.outputIsFresh()) return false
        val top = topOnPhone(app) ?: return false
        val pkg = top.packageName
        val sensitive = pkg == "android" || pkg == "com.android.systemui" ||
            pkg.endsWith(".permissioncontroller") || pkg.endsWith(".packageinstaller") ||
            pkg == "com.samsung.android.biometrics.app.setting" ||
            pkg == "com.android.settings" && listOf("ConfirmLock", "ChooseLock", "Credential", "Biometric").any { top.className.contains(it) }
        !sensitive
    }.getOrDefault(false)
    fun bind(a: PhonePanelWakeActivity): Boolean {
        if (active.get() !== this || activity != null) return false
        activity = a
        return true
    }
    fun presented(a: PhonePanelWakeActivity) {
        if (active.get() !== this || activity !== a || presented) return
        presented = true
        ready.countDown()
        Log.i("HeadSwapPanel", "Phone wake Activity committed and ready on display 0")
    }
    fun hasSurface(a: PhonePanelWakeActivity) = active.get() === this && activity === a && presented
    fun awaitingSurface(a: PhonePanelWakeActivity) = !closed && active.get() === this && activity === a && !presented
    fun doubleTap(a: PhonePanelWakeActivity) { if (hasSurface(a)) onDoubleTap() }
    fun lost(a: PhonePanelWakeActivity) {
        if (active.get() !== this || activity !== a) return
        val wasPresented = presented
        presented = false
        ready.countDown()
        if (wasPresented) onWindowLost()
    }
    fun close() {
        closed = true
        presented = false
        ready.countDown()
        if (!active.compareAndSet(this, null)) return
        main.post {
            activity?.let { a -> if (!a.isFinishing && !a.isDestroyed) a.finishAndRemoveTask() }
            activity = null
        }
    }
}

class PhonePanelWakeActivity : Activity() {
    private var owner: PhonePanelWakeWindow? = null
    private var committed = false
    private var checkReady: Runnable? = null
    private val presentation = if (Build.VERSION.SDK_INT >= 35) PhoneIdleTrustedWindow(this) { checkReady?.run() } else null

    private fun eligible(): Boolean {
        val lock = getSystemService(KeyguardManager::class.java)
        return !isFinishing && !isDestroyed && display?.displayId == Display.DEFAULT_DISPLAY &&
            getSystemService(PowerManager::class.java).isInteractive && !lock.isKeyguardLocked && !lock.isDeviceLocked &&
            DelegatedPanelAccess.outputIsFresh() && DelegatedDesktopAccess.isReady(this)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val request = PhonePanelWakeWindow.request(intent.getLongExtra(PhonePanelWakeWindow.EXTRA, -1L))
        if (request == null || !eligible() || !request.bind(this)) { finishAndRemoveTask(); return }
        owner = request
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val view = object : View(this) {
            val cfg = ViewConfiguration.get(context)
            val taps = PhonePanelTaps(40, ViewConfiguration.getDoubleTapTimeout().toLong(),
                ViewConfiguration.getLongPressTimeout().toLong(), cfg.scaledTouchSlop, cfg.scaledDoubleTapSlop)
            var reported = false
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val device = event.device
                val accept = request.hasSurface(this@PhonePanelWakeActivity) && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                    device != null && !device.isVirtual && !device.isExternal && event.pointerCount == 1 &&
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                    event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) == 0
                if (!reported && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    reported = true; Log.i("HeadSwapPanel", "First wake-activity contact eligible=$accept")
                }
                if (!accept) { taps.cancel(); return true }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> taps.down(event.eventTime, event.x, event.y)
                    MotionEvent.ACTION_MOVE -> taps.move(event.x, event.y)
                    MotionEvent.ACTION_UP -> if (taps.up(event.eventTime, event.x, event.y)) request.doubleTap(this@PhonePanelWakeActivity)
                    else -> taps.cancel()
                }
                return true
            }
        }
        view.setBackgroundColor(Color.BLACK)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setContentView(view)
        // After the first frame, accept local focus or a fully presented phone window
        // with focus on our car display. A system dialog must still prevent blanking.
        checkReady = object : Runnable {
            override fun run() {
                view.removeCallbacks(this)
                if (!request.awaitingSurface(this@PhonePanelWakeActivity) || !committed) return
                if (!hasWindowFocus() && Build.VERSION.SDK_INT >= 35) presentation?.observe()
                val ownsFocus = hasWindowFocus() || (Build.VERSION.SDK_INT >= 35 && presentation?.ownsRemoteFocus() == true)
                if (eligible() && ownsFocus && view.isShown && view.isAttachedToWindow &&
                    view.windowVisibility == View.VISIBLE && !isInMultiWindowMode && !isInPictureInPictureMode &&
                    PhonePanelWakeWindow.topOnPhone(this@PhonePanelWakeActivity) == componentName) {
                    if (Build.VERSION.SDK_INT >= 35) presentation?.close()
                    request.presented(this@PhonePanelWakeActivity)
                } else {
                    view.postDelayed(this, 120)
                }
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                try {
                    check(view.isHardwareAccelerated)
                    view.viewTreeObserver.registerFrameCommitCallback {
                        view.post {
                            committed = true
                            Log.i("HeadSwapPanel", "Phone wake Activity first frame committed")
                            checkReady?.run()
                        }
                    }
                } catch (failure: RuntimeException) {
                    Log.w("HeadSwapPanel", "Cannot confirm wake activity", failure)
                    request.lost(this@PhonePanelWakeActivity)
                    finishAndRemoveTask()
                }
                return true
            }
        })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkReady?.run()
        else if (owner?.hasSurface(this) == true && (Build.VERSION.SDK_INT < 35 ||
                !PhoneIdleTrustedWindow.ownedDisplayHasFocus() || PhonePanelWakeWindow.topOnPhone(this) != componentName)) {
            // A phone dialog or another primary window has taken focus. Let it be used normally.
            owner?.lost(this)
        }
    }
    override fun onPause() { owner?.lost(this); super.onPause() }
    override fun onStop() { owner?.lost(this); super.onStop() }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 35) presentation?.close()
        checkReady = null
        owner?.lost(this)
        super.onDestroy()
    }
}
