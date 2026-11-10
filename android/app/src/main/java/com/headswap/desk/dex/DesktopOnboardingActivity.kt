package com.headswap.desk.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import com.headswap.desk.update.ComponentUpdatesActivity
import com.headswap.desk.update.ComponentPackages
import com.headswap.desk.update.DesktopComponent
import com.headswap.desk.component.DesktopComponents
import com.headswap.desk.CarLifeState

/** Resumable first-run setup. Android permissions are granted only through explicit system UI. */
class DesktopOnboardingActivity : Activity() {
    private val ui by lazy { DesktopSetupViews(this) }
    private val prefs by lazy { DexPrefs(this) }
    private var step = 0
    private var ready = false
    private var positions = IntArray(2)
    private var displayedStep = -1
    private var viewport: ScrollView? = null
    private fun rememberScroll() {
        if (displayedStep in positions.indices) viewport?.let { positions[displayedStep] = it.scrollY }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        positions = savedInstanceState?.getIntArray("positions")?.takeIf { it.size == 2 } ?: positions
        step = (savedInstanceState?.getInt("step")
            ?: if (prefs.initialSetupInProgress) prefs.initialSetupStep else 0).coerceIn(0, 2)
        prefs.initialSetupAutoPending = false
        prefs.initialSetupInProgress = true
        saveProgress()
        ready = true
    }
    override fun onResume() { super.onResume(); if (ready) { if (step == 2) openActivationStep() else render() } }
    override fun onSaveInstanceState(outState: Bundle) { rememberScroll(); outState.putIntArray("positions", positions); outState.putInt("step", step); super.onSaveInstanceState(outState) }

    private fun render() {
        rememberScroll()
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.background)
            isFillViewport = true
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        window.setDecorFitsSystemWindows(false)
        viewport = scroll; displayedStep = step
        setContentView(scroll)
        val y = positions.getOrNull(step) ?: 0
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.brand(header, 64)
        ui.text(header, "HeadSwap Desktop", 27f, true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.text(body, "Первоначальная настройка · ${step + 1} из 3", 15f)
        ui.text(body, "Компоненты → Разрешения → Активация", 13f)
        when (step) {
            0 -> components(body)
            1 -> permissions(body)
            else -> return
        }
        if (step < 2) ui.button(body, "Далее") { moveTo(step + 1) }
            .isEnabled = step != 0 || profileAvailable()
        if (step > 0) ui.button(body, "Назад") { moveTo(step - 1) }
        ui.button(body, "Закрыть настройку") { closeSetup() }
    }
    private fun permissions(parent: LinearLayout) {
        val list = DesktopPermissionList(this, ui)
        list.headswap(ui.section(parent, "Разрешения HeadSwap Desktop"))
        list.drivedeck(ui.section(parent, "Разрешения DriveDesk"))
    }
    private fun components(parent: LinearLayout) {
        val card = ui.section(parent, "1. Компоненты")
        val selected = DesktopComponents.selected(this).takeIf { prefs.desktopMode == DesktopModePolicy.ANDROID_DESKTOP }
        val name = selected?.let(DesktopProfilePicker::name) ?: when (prefs.desktopMode) {
            DesktopModePolicy.DRIVEDECK -> "DriveDeck"
            DesktopModePolicy.SAMSUNG_DEX -> "Samsung DeX"
            else -> "Android Desktop"
        }
        ui.text(card, "Рабочий стол: $name", 18f, true)
        ui.text(card, when (prefs.desktopMode) {
            DesktopModePolicy.DRIVEDECK -> "Для выбранного рабочего стола установите DriveDeck · HeadSwap из комплекта или каталога ниже. Он показывает карту и музыку рядом. Обычная сборка DriveDeck не заменяет адаптированный компонент."
            DesktopModePolicy.SAMSUNG_DEX -> "Используется рабочий стол Samsung. Для него отдельный компонент не нужен; способ доступа будет выбран на шаге активации."
            else -> if (selected != null) "Выбран установленный совместимый компонент. Его обновления и разрешения доступны в каталоге."
                else "Android Desktop уже встроен. Дополнительные компоненты устанавливать не обязательно."
        })
        ui.button(card, "Компоненты HeadSwap Desktop") { startActivity(Intent(this, ComponentUpdatesActivity::class.java)) }
        ui.button(card, "Выбрать рабочий стол") { chooseProfile() }
        ui.text(card, if (profileAvailable()) "Рабочий стол готов к настройке ✓"
            else "Установите выбранный компонент или выберите встроенный Android Desktop, чтобы продолжить.", 14f)
    }

    private fun profileAvailable() = profileAvailable(this, prefs)

    private fun choiceUnlocked(): Boolean = !CarLifeState.running && !PhonePairingService.active && !DexState.running

    private fun chooseProfile() {
        if (!choiceUnlocked()) return
        DesktopProfilePicker.show(this, prefs.desktopMode, ::choiceUnlocked) { mode, entry, media ->
            if (DesktopBackendTransitionPolicy.requiresActivationEnd(DelegatedDesktopAccess.ownsActivation,
                    prefs.accessMode, prefs.accessModeFor(mode))) {
                AlertDialog.Builder(this).setTitle("Смена способа доступа")
                    .setMessage("Сначала завершите действующую активацию на экране «Активация», затем выберите этот рабочий стол.")
                    .setPositiveButton("Открыть активацию") { _, _ -> openActivation() }
                    .setNegativeButton("Отмена", null).show()
                return@show
            }
            if (entry == null) DesktopComponents.clear(this)
            else if (!DesktopComponents.select(this, entry, media)) {
                AlertDialog.Builder(this).setTitle("Компонент изменился")
                    .setMessage("Откройте список профилей заново и проверьте установленный компонент.")
                    .setPositiveButton("Понятно", null).show()
                return@show
            }
            prefs.desktopMode = mode
            render()
        }
    }

    private fun openActivationStep() {
        if (isFinishing) return
        startActivity(Intent(this, DesktopWizardActivationActivity::class.java)
            .putExtra(DexSetupActivity.EXTRA_SHOW_ACTIVATION, true)
            .putExtra(DexSetupActivity.EXTRA_RETURN_TO_SETUP, true))
        finish()
    }

    private fun saveProgress() {
        prefs.initialSetupStep = step
        if (step == 2) prefs.initialSetupReviewed = true
    }
    private fun moveTo(next: Int) { step = next.coerceIn(0, 2); saveProgress(); if (step == 2) openActivationStep() else render() }
    private fun closeSetup() { prefs.initialSetupInProgress = false; finish() }
    private fun openActivation() {
        saveProgress()
        startActivity(Intent(this, DexSetupActivity::class.java)
            .putExtra(DexSetupActivity.EXTRA_SHOW_ACTIVATION, true)
            .putExtra(DexSetupActivity.EXTRA_RETURN_TO_SETUP, true))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DesktopPermissionList.REQUEST && step == 1) render()
    }
    @Suppress("DEPRECATION") override fun onBackPressed() { if (step > 0) moveTo(step - 1) else closeSetup() }

    companion object {
        internal fun profileAvailable(context: Context, prefs: DexPrefs): Boolean = when (prefs.desktopMode) {
            DesktopModePolicy.DRIVEDECK -> runCatching { ComponentPackages.trustedInstalled(context, DesktopComponent.DRIVEDECK) != null }.getOrDefault(false)
            DesktopModePolicy.SAMSUNG_DEX -> SamsungDexCapability.isAvailable(context)
            else -> true
        }
        fun batteryExempt(context: Context): Boolean = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        fun requestBatteryExemption(activity: Activity) {
            if (batteryExempt(activity)) return
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
            for (intent in listOf(request, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))) {
                try { activity.startActivity(intent); return } catch (_: RuntimeException) { }
            }
            AlertDialog.Builder(activity).setTitle("Работа в фоне")
                .setMessage("Откройте настройки телефона → Приложения → HeadSwap Desktop → Батарея и разрешите работу без ограничений.")
                .setPositiveButton("Понятно", null).show()
        }
    }
}
