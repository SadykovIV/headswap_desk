package com.headswap.desk.dex

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import com.headswap.desk.update.ComponentUpdatesActivity

/** Short, optional setup. Every Android permission is requested only by its own explicit button. */
class DesktopOnboardingActivity : Activity() {
    private val ui by lazy { DesktopSetupViews(this) }
    private var step = 0
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt("step", 0) ?: 0
        ready = true
    }
    override fun onResume() { super.onResume(); if (ready) render() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("step", step); super.onSaveInstanceState(outState) }

    private fun render() {
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
        setContentView(scroll)
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.brand(header, 64)
        ui.text(header, "HeadSwap Desktop", 27f, true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.text(body, "Первоначальная настройка · ${step + 1} из 3", 15f)
        when (step) {
            0 -> permissions(body)
            1 -> components(body)
            else -> activation(body)
        }
        if (step < 2) ui.button(body, "Далее") { step++; render() }
        if (step > 0) ui.button(body, "Назад") { step--; render() }
        ui.button(body, "Вернуться на главный экран") { finish() }
    }
    private fun permissions(parent: LinearLayout) {
        val card = ui.section(parent, "1. Работа во время поездки")
        ui.text(card, "Разрешите уведомления для ввода кода сопряжения без переключения между окнами. Исключение из экономии батареи помогает Android сохранять активное соединение с магнитолой.")
        val notices = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        ui.button(card, if (notices) "Уведомления разрешены ✓" else "Разрешить уведомления") {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val battery = batteryExempt(this)
        ui.button(card, if (battery) "Работа в фоне разрешена ✓" else "Снять ограничение батареи") { requestBatteryExemption(this) }
        ui.text(card, "Эти разрешения можно настроить позже: «Настройки» → «Разрешения приложения».", 13f)
    }
    private fun components(parent: LinearLayout) {
        val card = ui.section(parent, "2. Рабочий стол")
        ui.text(card, "Android Desktop уже встроен. Для другого вида рабочего стола установите DriveDeck · HeadSwap или совместимый профиль из каталога компонентов. Обычный DriveDeck не заменяет адаптированную сборку.")
        ui.button(card, "Компоненты HeadSwap Desktop") { startActivity(Intent(this, ComponentUpdatesActivity::class.java)) }
        ui.text(card, "Профиль можно выбрать на главном экране после установки компонента.", 13f)
    }
    private fun activation(parent: LinearLayout) {
        val card = ui.section(parent, "3. Выдать права приложению")
        ui.text(card, "По Wi-Fi: подключите телефон к сети и включите «Беспроводную отладку» в параметрах разработчика. При первом сопряжении откройте окно с шестизначным кодом. Его можно ввести в HeadSwap или в уведомлении — оба способа дают один и тот же доступ.")
        ui.button(card, "По Wi-Fi — открыть активацию") { openActivation(computer = false) }
        ui.text(card, "С компьютера: установите Android Platform Tools, подключите телефон USB-кабелем и подтвердите USB-отладку. HeadSwap покажет команды для ручной активации.")
        ui.button(card, "С компьютера — показать команды") { openActivation(computer = true) }
        ui.text(card, "После перезагрузки телефона или обновления APK повторите активацию; сохранённое сопряжение обычно остаётся. На Android 17 для восстановления независимого экрана после перезапуска службы может понадобиться включённая беспроводная отладка.", 14f)
        ui.text(card, "При выбранных опциях HeadSwap получает временный доступ к воспроизводимому звуку и управлению панелью. Во время подключения экран магнитолы может оставаться доступным при блокировке телефона. Выбор меняется в настройках.", 13f)
    }
    private fun openActivation(computer: Boolean) {
        startActivity(Intent(this, DexSetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(DexSetupActivity.EXTRA_SHOW_ACTIVATION, true)
            .putExtra(DexSetupActivity.EXTRA_COMPUTER_ACTIVATION, computer))
        finish()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATIONS) render()
    }
    @Suppress("DEPRECATION") override fun onBackPressed() { if (step > 0) { step--; render() } else finish() }

    companion object {
        private const val NOTIFICATIONS = 401
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
