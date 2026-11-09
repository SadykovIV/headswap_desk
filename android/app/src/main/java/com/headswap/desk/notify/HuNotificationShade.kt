package com.headswap.desk.notify

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.headswap.desk.AppLog
import com.headswap.desk.CarLifeState
import com.headswap.desk.dex.DesktopConnectionState
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.DexState
import com.headswap.desk.embed.EmbeddedSessionRegistry

internal object HuShadePolicy {
    fun canShow(enabled: Boolean, overlayAllowed: Boolean, sessionRunning: Boolean,
                displayId: Int, ownedParent: Boolean): Boolean =
        enabled && overlayAllowed && sessionRunning && displayId > 0 && ownedParent
    fun revealText(deviceLocked: Boolean, keyguardLocked: Boolean): Boolean = !deviceLocked && !keyguardLocked
}

/** A normal Android overlay, scoped to one live CarLife display. No private WindowManager flags. */
internal class HuNotificationShade(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var uiContext: Context? = null
    private var panel: LinearLayout? = null
    private var displayId = -1
    private var expanded = false
    private var lastGeneration = -1L
    private var lastReveal = false
    private var lastConnected = false
    private var closed = false
    private var currentWidth = 0
    private var currentHeight = 0
    private var density = 1f
    private var failedDisplay = -1
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            reconcile()
            main.postDelayed(this, 350)
        }
    }
    fun start() { main.removeCallbacks(tick); main.post(tick) }

    private fun reconcile() {
        val id = DexState.displayId
        val enabled = DexPrefs(app).huNotificationsEnabled
        val live = DexState.running && CarLifeState.running &&
            DesktopConnectionState.aliveForIdleTimer(android.os.SystemClock.elapsedRealtime())
        val allowed = Settings.canDrawOverlays(app)
        val eligible = HuShadePolicy.canShow(enabled, allowed, live, id, EmbeddedSessionRegistry.ownsParentDisplay(id))
        if (!eligible) {
            detach()
            failedDisplay = -1
            HuNotifications.session(app, false)
            return
        }
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        if (display == null || !display.isValid) { detach(); HuNotifications.session(app, false); return }
        if (displayId != id) {
            detach()
            if (failedDisplay == id) return
            try {
                val ctx = app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                uiContext = ctx
                manager = ctx.getSystemService(WindowManager::class.java)
                @Suppress("DEPRECATION") val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
                currentWidth = metrics.widthPixels
                currentHeight = metrics.heightPixels
                density = metrics.density.coerceAtLeast(0.5f)
                displayId = id
                HuNotifications.session(app, true)
                render()
            } catch (error: RuntimeException) {
                AppLog.i("HU notification shade unavailable: ${error.javaClass.simpleName}", false)
                failedDisplay = id
                detach()
                HuNotifications.session(app, false)
                return
            }
        }
        val reveal = mayReveal()
        if (HuNotifications.generation != lastGeneration || reveal != lastReveal || HuNotifications.connected != lastConnected) render()
    }

    private fun mayReveal(): Boolean {
        return runCatching {
            val lock = app.getSystemService(KeyguardManager::class.java)
            HuShadePolicy.revealText(lock.isDeviceLocked, lock.isKeyguardLocked)
        }.getOrDefault(false)
    }

    private fun render() {
        val ctx = uiContext ?: return
        val wm = manager ?: return
        if (!EmbeddedSessionRegistry.ownsParentDisplay(displayId)) { detach(); return }
        val reveal = mayReveal()
        lastReveal = reveal
        lastGeneration = HuNotifications.generation
        lastConnected = HuNotifications.connected
        val root = panel ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(23, 32, 46)) }
            elevation = dp(8).toFloat()
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        while (root.childCount > 1) root.removeViewAt(1)
        var downY = 0f
        val handle = (root.getChildAt(0) as? Button) ?: button(ctx, "Уведомления") {
            expanded = !expanded
            render()
        }.apply {
            contentDescription = if (expanded) "Скрыть уведомления" else "Открыть шторку уведомлений"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downY = event.rawY
                    MotionEvent.ACTION_UP -> {
                        val delta = event.rawY - downY
                        if (kotlin.math.abs(delta) > dp(24)) {
                            expanded = delta > 0
                            render()
                            return@setOnTouchListener true
                        }
                    }
                }
                false
            }
        }
        handle.text = if (expanded) "Уведомления  ▴" else "Уведомления · ${HuNotifications.items.size}  ▾"
        handle.contentDescription = if (expanded) "Скрыть уведомления" else "Открыть шторку уведомлений"
        if (handle.parent == null) root.addView(handle, LinearLayout.LayoutParams(-1, dp(48)))
        if (expanded) {
            val items = HuNotifications.items
            if (!reveal) {
                root.addView(label(ctx, "Телефон заблокирован\nРазблокируйте его, чтобы прочитать уведомления.", 16f))
            } else if (!HuNotifications.connected) {
                root.addView(label(ctx, "Разрешите HeadSwap доступ к уведомлениям в настройках телефона.", 16f))
            } else if (items.isEmpty()) {
                root.addView(label(ctx, "Новых уведомлений нет", 17f))
            } else {
                val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                for (item in items) {
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(35, 48, 65)) }
                    }
                    val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                    top.addView(label(ctx, item.who, 20f, true), LinearLayout.LayoutParams(0, -2, 1f))
                    top.addView(button(ctx, "×") { HuNotifications.dismiss(item.key) }.apply {
                        contentDescription = "Убрать уведомление"
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    card.addView(top)
                    card.addView(label(ctx, item.text, 18f).apply { maxLines = 8 })
                    card.addView(label(ctx, NotificationsLogic.time(item.postTime), 14f))
                    list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                }
                root.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
                root.addView(button(ctx, "Очистить уведомления") { HuNotifications.clearAll() }, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        val width = if (expanded) maxOf(dp(320), (currentWidth * 0.60).toInt()).coerceAtMost(currentWidth - dp(12))
            else minOf(dp(200), currentWidth - dp(12))
        val height = if (expanded) (currentHeight * 0.75).toInt().coerceAtLeast(dp(90)) else dp(58)
        val params = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(3)
                title = "HeadSwapDesk notification shade"
            }
        try {
            if (panel == null) {
                wm.addView(root, params)
                panel = root
            } else wm.updateViewLayout(root, params)
        } catch (error: RuntimeException) {
            AppLog.i("HU notification shade view failed: ${error.javaClass.simpleName}", false)
            failedDisplay = displayId
            detach()
            HuNotifications.session(app, false)
        }
    }
    private fun button(ctx: Context, text: String, action: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 18f
        setTextColor(Color.WHITE)
        setPadding(dp(6), 0, dp(6), 0)
        minWidth = 0; minHeight = 0
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(43, 62, 82))
        setOnClickListener { if (EmbeddedSessionRegistry.ownsParentDisplay(displayId)) action() }
    }
    private fun label(ctx: Context, text: String, size: Float, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(if (bold) Color.WHITE else Color.rgb(206, 220, 236))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    private fun detach() {
        panel?.let { view -> runCatching { manager?.removeViewImmediate(view) } }
        panel = null
        manager = null
        uiContext = null
        displayId = -1
        expanded = false
        lastGeneration = -1
    }
    override fun close() {
        closed = true
        main.removeCallbacks(tick)
        detach()
        HuNotifications.session(app, false)
    }
}
