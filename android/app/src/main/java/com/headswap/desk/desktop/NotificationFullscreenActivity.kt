package com.headswap.desk.desktop

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import com.headswap.desk.R
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.notify.HuNotificationLaunch

/** Private managed fullscreen child; neither a component nor a notification supplies an Intent. */
class NotificationFullscreenActivity : Activity() {
    private lateinit var pane: DesktopAppPane
    private var hardwareBack: FullscreenHardwareBack? = null
    private var proof: Any? = null
    private var mode = -1
    @Volatile internal var launchVisible = false
        private set
    private val parentId get() = display?.displayId ?: -1
    private fun current() = proof?.let { EmbeddedSessionRegistry.isCurrentParent(parentId, it) } == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ticket = HuNotificationLaunch.consume(intent, parentId, this) ?: run { finish(); return }
        proof = ticket.parentProof
        mode = EmbeddedSessionRegistry.parentMode(parentId) ?: run { finish(); return }
        if (mode != DesktopModePolicy.ANDROID_DESKTOP && mode != DesktopModePolicy.DRIVEDECK) { finish(); return }
        setShowWhenLocked(true)
        if (Build.VERSION.SDK_INT == 33) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pane = DesktopAppPane(this, onOpened = {}, onHome = ::home,
            onError = { Toast.makeText(this, "Уведомление больше недоступно или приложение не открылось на ГУ", Toast.LENGTH_LONG).show(); home() },
            fullscreenDriveDeck = true, ownDesktopProfile = mode == DesktopModePolicy.ANDROID_DESKTOP)
        root.addView(pane.view, FrameLayout.LayoutParams(-1, -1))
        root.addView(button("Назад", R.drawable.desktop_back) { pane.back() }, position(1))
        root.addView(button("Домой", R.drawable.desktop_home, ::home), position(0))
        setContentView(root)
        hardwareBack = FullscreenHardwareBack.attach(this, pane, ::current)
            ?: run { finish(); return }
        pane.openNotification(ticket)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::pane.isInitialized || !current()) { finish(); return }
        val ticket = HuNotificationLaunch.consume(intent, parentId, this) ?: return
        pane.openNotification(ticket)
    }
    override fun onResume() {
        super.onResume()
        if (!current()) { finish(); return }
        launchVisible = true
        hardwareBack?.resume()
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        if (::pane.isInitialized) pane.refreshBuffer()
    }
    override fun onPause() {
        launchVisible = false
        hardwareBack?.pause()
        super.onPause()
    }
    override fun onDestroy() {
        hardwareBack?.close()
        hardwareBack = null
        super.onDestroy()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) hardwareBack?.touchDown()
        return super.dispatchTouchEvent(event)
    }
    @Deprecated("Back belongs to this exact managed child")
    override fun onBackPressed() { if (::pane.isInitialized && current()) pane.back() else finish() }
    private fun home() {
        if (isFinishing) return
        proof?.let { EmbeddedSessionRegistry.requestHome(parentId, it) }
        finish()
    }
    private fun button(label: String, resource: Int, action: () -> Unit) = ImageButton(this).apply {
        contentDescription = label
        setImageResource(resource)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE9212932.toInt()); setStroke(dp(1), 0x66FFFFFF) }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { if (current()) action() else finish() }
    }
    private fun position(row: Int) = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START).apply {
        marginStart = dp(12); bottomMargin = dp(12 + row * 56)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}
