package com.headswap.desk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import com.headswap.desk.aa.AaProbe
import com.headswap.desk.link.car.CarPairing
import com.headswap.desk.link.diag.NetProbe
import com.headswap.desk.ui.Actions
import com.headswap.desk.ui.DevScreen
import com.headswap.desk.ui.DiagScreen
import com.headswap.desk.ui.HomeScreen
import com.headswap.desk.ui.OnboardingScreen
import com.headswap.desk.ui.Screen
import com.headswap.desk.ui.SettingsScreen
import com.headswap.desk.ui.SettingsSection
import com.headswap.desk.ui.SettingsSectionScreen
import com.headswap.desk.ui.StepId
import com.headswap.desk.ui.UiPrefs
import com.headswap.desk.ui.UpgradeNotice
import com.headswap.desk.dex.DexSetupActivity

/**
 * Единственная activity: хранит стек экранов (главный, настройки, для разработчиков, онбординг) и принимает команды intent
 * (`--es cmd ...`). Вся работа с сервисами и USB - в ui.Actions, экраны - в пакете ui.
 */
class MainActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppTheme.wrap(AppLocale.wrap(base)))
    }

    private lateinit var root: FrameLayout
    private val stack = ArrayList<Screen>()
    private val keys = ArrayList<String>()
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            current()?.tick()
            handler.postDelayed(this, 500)
        }
    }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Actions.handleUsbPermissionResult(context, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep historical explicit intents working, but never expose AA setup or
        // upstream APK updates from this DeX fork.
        forwardToDex(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardToDex(intent)
    }

    private fun forwardToDex(incoming: Intent?) {
        startActivity(Intent(this, DexSetupActivity::class.java).apply {
            if (incoming?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
                action = incoming.action
                incoming.extras?.let { putExtras(it) }
            }
        })
        finish()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        current()?.onShow()
        handler.post(refresh)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        current()?.onHide()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SCREENS, keys)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(permReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current()?.onBack() == true) return
        if (stack.size > 1) back() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        current()?.onPermissionResult()
        if (requestCode == REQ_LOCAL_NETWORK) AppLog.i("ACCESS_LOCAL_NETWORK: ${NetProbe.localNetworkGranted(this)}")
        if (requestCode == REQ_PHONE_STATE) {
            AppLog.i("звонки: READ_PHONE_STATE ${if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) "выдано" else "отказано"}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CarPairing.REQ_CAR_PAIR) {
            CarPairing.onResult(this, resultCode, data)
            current()?.onActivityResultDone()
        }
    }

    /** Отладка беспровода: системный запрос ACCESS_LOCAL_NETWORK (Android 17+). Онбординг с ним появится в Э4. */
    private fun requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < 37) {
            AppLog.i("ACCESS_LOCAL_NETWORK: API ${Build.VERSION.SDK_INT}, разрешения нет, локальная сеть открыта")
            return
        }
        requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), REQ_LOCAL_NETWORK)
    }

    // --- навигация ---

    private fun current(): Screen? = stack.lastOrNull()

    private fun push(key: String, s: Screen) {
        if (resumed) current()?.onHide()
        stack.add(s)
        keys.add(key)
        root.removeAllViews()
        root.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (resumed) s.onShow()
    }

    fun back() {
        if (stack.size <= 1) return
        if (resumed) current()?.onHide()
        stack.removeAt(stack.size - 1)
        keys.removeAt(keys.size - 1)
        val s = stack.last()
        root.removeAllViews()
        root.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (resumed) s.onShow()
    }

    /** Свернуть стек до главного экрана. */
    private fun toHome() {
        while (stack.size > 1) back()
        if (stack.isEmpty()) push("home", HomeScreen(this))
    }

    fun showSettings() = push("settings", SettingsScreen(this))

    fun showSettingsSection(s: SettingsSection) = push("settings:${s.key}", SettingsSectionScreen(this, s))

    fun showDev() = push("dev", DevScreen(this))

    fun showDiag() = push("diag", DiagScreen(this))

    /** rerun = показать все шаги; checklist = сразу чеклист готовности. */
    fun showOnboarding(rerun: Boolean = false, checklist: Boolean = false, startAt: StepId? = null) {
        push(if (checklist) "checklist" else if (rerun) "onboarding_all" else "onboarding", OnboardingScreen(this, rerun, checklist, startAt))
    }

    private fun restoreScreens(saved: Bundle?) {
        push("home", HomeScreen(this))
        val savedKeys = saved?.getStringArrayList(STATE_SCREENS) ?: return
        for (k in savedKeys.drop(1)) {
            when (k) {
                "settings" -> showSettings()
                "dev" -> showDev()
                "diag" -> showDiag()
                "onboarding" -> showOnboarding()
                "onboarding_all" -> showOnboarding(rerun = true)
                "checklist" -> showOnboarding(checklist = true)
                else -> SettingsSection.byKey(k.removePrefix("settings:"))?.let { showSettingsSection(it) }     // раздел настроек после смены темы/языка
            }
        }
    }

    // --- команды intent ---

    private fun handleIntent(intent: Intent?, reason: String) {
        val cmd = intent?.getStringExtra(EXTRA_CMD)
        if (intent != null && cmd != null) {
            intent.removeExtra(EXTRA_CMD)        // не повторять команду при пересоздании activity
            AppLog.i("команда из intent: $cmd")
            when (cmd) {
                "aa_selftest" -> Actions.aaSelftest(this)
                "aa_probe" -> AaProbe.run(this)                // проба AA-сессии к 127.0.0.1:5277 без CarLife (targetSdk 37)
                // проба локальной сети для беспровода: маяк UDP 7999, ответчик 8999 (--ei seconds N --es to <ip>)
                "net_probe" -> NetProbe.run(this, intent.getIntExtra("seconds", 20), intent.getStringExtra("to"))
                "net_perm" -> requestLocalNetwork()
                "open_aa" -> Actions.openAaSettings(this)
                // автостарт беспровода без Bluetooth машины: то же, что ACL_CONNECTED / ACL_DISCONNECTED выбранной машины
                "car_auto" -> startForegroundService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO))
                "car_auto_off" -> startForegroundService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO_OFF))
                "mic_test" -> Actions.micTest(this)
                "transcode_test" -> Actions.transcodeTest(this, intent.getStringExtra("opts") ?: "")
                "screen_main" -> toHome()
                "screen_settings" -> {                         // --es section appearance|video|sound|headunit|apps|updates
                    toHome()
                    showSettings()
                    SettingsSection.byKey(intent.getStringExtra("section"))?.let { showSettingsSection(it) }
                }
                "screen_dev" -> { toHome(); showDev() }
                "onboarding" -> {
                    toHome()
                    // --es step autostart|notif|... открывает онбординг сразу с нужного шага
                    val step = StepId.values().firstOrNull { it.key == intent.getStringExtra("step") }
                    showOnboarding(rerun = true, startAt = step)
                }
                "checklist" -> { toHome(); showOnboarding(checklist = true) }
                "update_check" -> AppLog.i("DeX fork: обновления upstream отключены")
                "a11y_notice" -> UpgradeNotice.show(this)      // окно «Отказ от спецвозможностей» принудительно, флаг не трогает
                "theme" -> {                                   // --es mode system|light|medium|dark
                    AppTheme.set(this, intent.getStringExtra("mode") ?: AppTheme.SYSTEM)
                    recreate()
                }
                else -> AppLog.i("неизвестная команда '$cmd' (есть aa_selftest, aa_probe, net_probe, net_perm, mic_test, transcode_test, open_aa, car_auto, car_auto_off, a11y_notice)")
            }
            if (intent.hasExtra("scroll_y")) current()?.scrollTo(intent.getIntExtra("scroll_y", 0))     // отладка: снимки нижней части экрана
            return
        }
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val acc = accessoryFromIntent(intent)
            AppLog.i("== USB_ACCESSORY_ATTACHED, в intent: ${if (acc != null) describe(acc) else "аксессуара нет"}")
        }
        Actions.scan(this, reason)
    }

    @Suppress("DEPRECATION")
    private fun accessoryFromIntent(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    companion object {
        const val ACTION_PERMISSION = "com.headswap.desk.USB_PERMISSION"
        private const val REQ_LOCAL_NETWORK = 37
        private const val REQ_PHONE_STATE = 40      // 38 - диалог CDM (CarPairing), 39 - BLUETOOTH_CONNECT

        /** adb shell am start -n com.headswap.desk/.MainActivity --es cmd aa_selftest */
        const val EXTRA_CMD = "cmd"
        private const val STATE_SCREENS = "screens"

        /** Все поля аксессуара одной строкой. Серийник без разрешения на аксессуар бросает SecurityException. */
        fun describe(a: UsbAccessory): String {
            val serial = try { "\"${a.serial}\"" } catch (_: SecurityException) { "(нет разрешения)" }
            return "manufacturer=\"${a.manufacturer}\" model=\"${a.model}\" description=\"${a.description}\" " +
                "version=\"${a.version}\" uri=\"${a.uri}\" serial=$serial"
        }
    }
}
