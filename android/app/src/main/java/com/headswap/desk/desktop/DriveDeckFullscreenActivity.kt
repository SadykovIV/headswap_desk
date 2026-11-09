package com.headswap.desk.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.headswap.desk.R
import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.embed.EmbeddedGatewayPolicy
import com.headswap.desk.embed.EmbeddedSessionRegistry
import kotlin.math.roundToInt

/** Signature-protected launcher for one full-screen DriveDeck app on its active HU display. */
class DriveDeckFullscreenActivity : Activity() {
    private lateinit var pane: DesktopAppPane
    private lateinit var message: TextView
    private lateinit var back: ImageButton
    private lateinit var home: ImageButton
    private var leaving = false
    private var target: ComponentName? = null

    private val parentId get() = display?.displayId ?: -1
    private fun config(pkg: String? = null) = runCatching {
        EmbeddedSessionRegistry.renderConfig(parentId, ownDesktop = false, packageName = pkg)
    }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (config() == null) { finish(); return }
        setShowWhenLocked(true)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            // A touchable host can otherwise take focus from the Android 13 child between DOWN and UP.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { v, i ->
                val bars = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                i
            }
        }
        pane = DesktopAppPane(this, onOpened = { message.visibility = View.GONE }, onHome = ::goHome,
            onError = { error ->
                if (tryNativeLaunch()) {
                    Toast.makeText(this, "Приложение открыто напрямую на магнитоле", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    goHome()
                }
            }, fullscreenDriveDeck = true)
        root.addView(pane.view, FrameLayout.LayoutParams(-1, -1))
        message = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            text = "Открывается приложение…"
        }
        root.addView(message, FrameLayout.LayoutParams(-1, -1))
        back = button("Назад", R.drawable.desktop_back) { pane.back() }
        home = button("Домой в DriveDeck", R.drawable.desktop_home) { goHome() }
        root.addView(back, buttonPosition(aboveHome = false))
        root.addView(home, buttonPosition(aboveHome = false))
        setContentView(root)
        openTarget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::pane.isInitialized && !isFinishing) openTarget(intent)
    }

    private fun openTarget(intent: Intent) {
        val component = ComponentName.unflattenFromString(intent.getStringExtra("component").orEmpty())
        if (component == null || !EmbeddedGatewayPolicy.allowsLauncherPackage(component.packageName, packageName)) {
            finish(); return
        }
        // Only accept exactly the exported MAIN+LAUNCHER component from DriveDeck's app picker.
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.packageName)
        val entry = packageManager.queryIntentActivities(launch, 0).firstOrNull {
            val info = it.activityInfo
            info.name == component.className && info.packageName == component.packageName &&
                info.exported && info.enabled && info.applicationInfo.enabled &&
                (info.permission == null || checkSelfPermission(info.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        } ?: run { goHome(); return }
        target = component
        val backEnabled = intent.getBooleanExtra("back", false)
        val homeEnabled = intent.getBooleanExtra("home", false)
        val policy = config(component.packageName) ?: run { finish(); return }
        if (!backEnabled && !homeEnabled && policy[1] == 0) {
            // No overlay or app-specific rendering is needed on the parent display.
            if (!tryNativeLaunch()) goHome()
            return
        }
        back.visibility = if (backEnabled) View.VISIBLE else View.GONE
        home.visibility = if (homeEnabled) View.VISIBLE else View.GONE
        back.layoutParams = buttonPosition(aboveHome = homeEnabled)
        message.visibility = View.VISIBLE
        val label = entry.loadLabel(packageManager).toString()
        pane.open(DesktopAppCatalog.Entry(component.packageName, component.className, label))
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::pane.isInitialized) return
        if (config() == null) { finish(); return }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        pane.refreshBuffer()
    }

    @Deprecated("Route Back to the owned child display")
    override fun onBackPressed() {
        if (!::pane.isInitialized || config() == null) finish() else pane.back()
    }

    private fun tryNativeLaunch(): Boolean {
        val component = target ?: return false
        if (leaving || config() == null) return false
        return runCatching {
            startActivity(Intent.makeMainActivity(component), ActivityOptions.makeBasic().setLaunchDisplayId(parentId).toBundle())
            leaving = true
            finish()
        }.isSuccess
    }

    private fun goHome() {
        if (leaving || isFinishing) return
        leaving = true
        if (config() != null) {
            val target = ComponentName(DesktopModePolicy.DRIVEDECK_PACKAGE, DesktopModePolicy.DRIVEDECK_HOME_CLASS)
            val home = Intent.makeMainActivity(target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { startActivity(home, ActivityOptions.makeBasic().setLaunchDisplayId(parentId).toBundle()) }
        }
        finish()
    }

    private fun button(label: String, icon: Int, click: () -> Unit) = ImageButton(this).apply {
        contentDescription = label
        setImageResource(icon)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xD9212932.toInt())
            setStroke(dp(1), 0x66FFFFFF)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), circle, null)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val inset = dp(12)
        setPadding(inset, inset, inset, inset)
        setOnClickListener { if (config() != null) click() else finish() }
    }

    private fun buttonPosition(aboveHome: Boolean): FrameLayout.LayoutParams {
        val diameter = dp(48)
        val edge = dp(12)
        return FrameLayout.LayoutParams(diameter, diameter, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = edge
            bottomMargin = edge + if (aboveHome) diameter + dp(8) else 0
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
