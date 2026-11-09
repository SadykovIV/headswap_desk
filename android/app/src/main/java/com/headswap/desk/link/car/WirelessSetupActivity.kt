package com.headswap.desk.link.car

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.headswap.desk.AppLocale
import com.headswap.desk.CarLifeState
import com.headswap.desk.dex.DesktopSetupViews

/** Configuration only. The foreground BridgeService owns all connection resources. */
class WirelessSetupActivity : Activity() {
    private lateinit var ui: DesktopSetupViews
    private lateinit var status: TextView
    private lateinit var selectedCar: TextView
    private lateinit var network: TextView
    private val main = Handler(Looper.getMainLooper())
    private var afterPermission: (() -> Unit)? = null
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); main.postDelayed(this, 1000) }
    }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = DesktopSetupViews(this)
        val content = ui.column().apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(ui.background); addView(content) }
        setContentView(scroll)
        ui.button(content, "‹ Подключение") { finish() }
        ui.text(content, "CarLife по Wi-Fi", 26f, true)
        ui.text(content, "Для магнитолы с беспроводным CarLife. Активация HeadSwap — отдельный шаг на главном экране.")
        val connection = ui.section(content, "Подключение")
        ui.toggle(connection, "Беспроводное подключение", WirelessPrefs(this).enabled) {
            WirelessPrefs(this).enabled = it
            updateStatus()
        }
        status = ui.text(connection, "")
        ui.button(connection, "Разрешить подключение") { withPermissions { updateStatus() } }
        ui.button(connection, "Подключить по Wi-Fi") { withPermissions { WirelessCarLife.startReady(this); updateStatus() } }
        ui.text(connection, "Включите Wi-Fi и Bluetooth телефона. На магнитоле откройте беспроводной CarLife. Соединение, изображение и звук передаются по Wi-Fi.")
        ui.button(connection, "Открыть Wi-Fi") { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
        ui.button(connection, "Открыть Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }

        val car = ui.section(content, "Моя магнитола")
        selectedCar = ui.text(car, "")
        ui.text(car, "Выберите сопряжённое Bluetooth-устройство магнитолы. При её появлении HeadSwap сможет начать соединение автоматически, если доступ к рабочему столу активен.")
        ui.button(car, "Выбрать магнитолу") { withPermissions { CarPairing.start(this) } }
        ui.button(car, "Забыть магнитолу") { CarPairing.forget(this); updateStatus() }

        val wifi = ui.section(content, "Сеть магнитолы")
        network = ui.text(wifi, "")
        ui.text(wifi, "Wi-Fi Direct настраивается через Bluetooth автоматически. Если магнитола использует собственную точку доступа, укажите её сеть. Если она подключается к точке доступа телефона, включите её в настройках Android.")
        ui.button(wifi, "Указать сеть магнитолы") { editNetwork() }
        ui.button(wifi, "Удалить сеть") { CarWifi(this).clear(); updateStatus() }
        ui.text(content, "Режим требует поддержки беспроводного CarLife самой магнитолой. Одного наличия Bluetooth недостаточно.")
    }

    override fun onResume() { super.onResume(); main.removeCallbacks(refresh); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        status.text = WirelessCarLife.unavailableReason(this) ?: when {
            CarLifeState.wirelessSession && CarLifeState.running -> "CarLife подключён по Wi-Fi"
            CarLifeState.wireless != null -> CarLifeState.wireless
            else -> "Готово к подключению"
        }
        selectedCar.text = CarProfile(this).let { it.btName ?: if (it.isSet) "Магнитола выбрана" else "Магнитола пока не выбрана" }
        network.text = CarWifi(this).ssid?.let { "Сохранённая сеть: $it" } ?: "Сеть вручную не задана"
    }

    private fun withPermissions(action: () -> Unit) {
        if (!WirelessPrefs(this).enabled) {
            status.text = "Сначала включите беспроводное подключение"
            return
        }
        val missing = WirelessCarLife.missingPermissions(this)
        if (missing.isEmpty()) { action(); return }
        afterPermission = action
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val next = afterPermission
        afterPermission = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
            WirelessCarLife.missingPermissions(this).isEmpty()) next?.invoke()
        updateStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CarPairing.REQ_CAR_PAIR) { CarPairing.onResult(this, resultCode, data); updateStatus() }
    }

    private fun editNetwork() {
        val saved = CarWifi(this)
        val box = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val name = EditText(this).apply {
            hint = "Имя Wi-Fi сети магнитолы"
            setSingleLine(true)
            setText(saved.ssid)
        }
        val password = EditText(this).apply {
            hint = "Пароль, пусто для открытой сети"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            isSaveEnabled = false
            setText(saved.pass)
        }
        box.addView(name); box.addView(password)
        val dialog = AlertDialog.Builder(this).setTitle("Сеть магнитолы").setView(box)
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = name.text.toString()
                val pass = password.text.toString()
                if (ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size > 32) {
                    name.error = "Введите имя сети до 32 байт"
                } else if (pass.isNotEmpty() && pass.length !in 8..63) {
                    password.error = "Пароль должен содержать 8–63 символа"
                } else {
                    saved.save(ssid, pass)
                    dialog.dismiss()
                    updateStatus()
                }
            }
        }
        dialog.show()
    }

    companion object { private const val REQUEST_PERMISSIONS = 196 }
}
