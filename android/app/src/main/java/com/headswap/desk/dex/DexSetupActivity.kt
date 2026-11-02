package com.headswap.desk.dex

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.headswap.desk.AppLog
import com.headswap.desk.BridgeService
import com.headswap.desk.BuildConfig
import com.headswap.desk.aa.DesktopResolution
import com.headswap.desk.aa.FpsSetting
import com.headswap.desk.CarLifeState
import com.headswap.desk.MainActivity
import com.headswap.desk.ui.Actions
import com.headswap.desk.ui.LogShare
import com.headswap.desk.update.Updater
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku

/** USB desktop setup; local ADB activation is a separate, explicit action. */
class DexSetupActivity : Activity() {
    private lateinit var prefs: DexPrefs
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var connectButton: Button
    private lateinit var connectButtonHint: TextView
    private lateinit var cancelWaitingButton: Button
    private lateinit var phoneActivationSection: LinearLayout
    private lateinit var phoneActivationButton: Button
    private lateinit var phoneActivationDetails: LinearLayout
    private lateinit var phonePairSection: LinearLayout
    private lateinit var phonePairCode: EditText
    private lateinit var phoneActivationHint: TextView
    private var phoneSetupOpen = false
    private var phonePairOpen = false
    private var phoneActivationRequested = false
    private var phoneLaunchCommitted = false
    private var phoneStatusSeen = ""
    private var connectionButtonState = DesktopConnectionButtonPolicy.State.IDLE
    private lateinit var phoneScreenButton: Button
    private lateinit var phoneScreenHint: TextView
    private lateinit var phoneCurtainButton: Button
    private lateinit var idleModeChoice: Button
    private lateinit var idleTimeoutChoice: Button
    private lateinit var idleModeHint: TextView
    private val phoneIdle by lazy {
        PhoneScreenIdleController(this,
            isEnabled = { when (prefs.phoneIdleMode) {
                PhoneIdleMode.CURTAIN -> true
                PhoneIdleMode.PANEL -> !CarLifePhoneIdleTimer.ownsPhysicalTimer && prefs.useDelegated && prefs.desktopPanelControl && DelegatedPanelAccess.hasPermission(this)
                PhoneIdleMode.DISABLED -> false
            } },
            isConnected = { prefs.useDesktopService && !stopped && desktopIdleSessionReady() },
            prefersPhysicalOff = { prefs.phoneIdleMode == PhoneIdleMode.PANEL },
            requestPhysicalOff = {
                prefs.phoneIdleMode == PhoneIdleMode.PANEL && prefs.useDelegated && desktopIdleSessionReady() &&
                    DelegatedPanelAccess.isAvailable(this) && runCatching {
                    startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_PHONE_SCREEN_OFF)) != null
                }.getOrDefault(false)
            },
            panelBusy = { DexState.phoneScreenBusy }, panelOff = { DexState.phoneScreenOff })
    }
    private lateinit var desktopChoice: Button
    private lateinit var desktopChoiceHint: TextView
    private val desktopModes = listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.SAMSUNG_DEX, DesktopModePolicy.DRIVEDECK)
    private val desktopName: String get() = if (prefs.useDesktopService) DesktopModePolicy.label(prefs.desktopMode) else "DeX"
    private lateinit var setup: LinearLayout
    private lateinit var shizukuSetup: LinearLayout
    private lateinit var shizukuHelp: TextView
    private lateinit var shizukuManagerButton: Button
    private lateinit var advanced: LinearLayout
    private lateinit var developer: LinearLayout
    private lateinit var adbAdvanced: LinearLayout
    private lateinit var builtinAccess: CheckBox
    private lateinit var computerAccess: CheckBox
    private lateinit var computerAccessStatus: TextView
    private lateinit var finishComputerAccess: Button
    private lateinit var accessHint: TextView
    private lateinit var accessChangeHint: TextView
    private lateinit var densitySection: LinearLayout
    private lateinit var densityChoice: Button
    private lateinit var resolutionChoice: Button
    private lateinit var fpsChoice: Button
    private lateinit var usbAudioChoice: CheckBox
    private lateinit var usbAudioHint: TextView
    private lateinit var panelControlChoice: CheckBox
    private lateinit var panelControlHint: TextView
    private lateinit var densityHint: TextView
    private lateinit var mapSymbols: CheckBox
    private lateinit var mapSymbolsHint: TextView
    private val densityPresets = listOf(0, 128, 160, 200)
    private lateinit var code: EditText
    private lateinit var host: EditText
    private lateinit var pairPort: EditText
    private lateinit var connectPort: EditText
    private lateinit var detail: TextView
    private val inputViews = mutableListOf<View>()
    private val main = Handler(Looper.getMainLooper())
    private val flow = DexSetupFlow()
    private var discovery: DexDiscovery? = null
    private var discoveryToken = -1L
    private var discoveryDeadline: Runnable? = null
    private var permissionAction: (() -> Unit)? = null
    private var visible = false
    private var receiverRegistered = false
    private val stopped: Boolean get() = Actions.isUsbAutoStartStopped()
    private var observedStop = false
    private var armed = false
    private var monitorBridge = false
    private var screenBusy = false
    private var retryUsbPermission = false
    private var deferredConnect = false
    private var restartAt = 0L
    private var bridgeStartedAt = 0L
    private var lastUsbScanAt = 0L
    private var applyingFields = false
    private var hostEdited = false
    private var pairPortEdited = false
    private var connectPortEdited = false
    private var lastDiscovery = ""
    private var lastError = ""
    private var applyingAccessChoice = false
    private var shizukuPermissionToken: Long? = null
    private var shizukuListenersRegistered = false

    companion object {
        private const val REQUEST_NETWORK = 2
        private const val REQUEST_SHIZUKU = 1005
        private const val DISCOVERY_TIMEOUT_MS = 12_000L
        // Survives Activity recreation: returning to the app cannot start a second ADB worker.
        private val workerBusy = AtomicBoolean(false)
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dex-setup").apply { isDaemon = true } }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            phoneIdle.refresh()
            if (observePhoneActivation()) {
                main.postDelayed(this, 500)
                return
            }
            Actions.observeUsbConnection(this@DexSetupActivity)
            if (stopped) {
                observedStop = true
                cancelContinuation()
                showState("Остановлено", "Подключите USB заново или нажмите «Подключить».")
                main.postDelayed(this, 500)
                return
            }
            if (Actions.isUsbDetachedIdle()) {
                showUsbDetached()
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            if (observedStop) {
                observedStop = false
                if (prefs.useDesktopService) beginConnect(explicit = false)
            }
            val now = SystemClock.elapsedRealtime()
            if (restartAt != 0L) {
                if (!BridgeService.alive) {
                    restartAt = 0
                    beginConnect(explicit = true)
                } else if (now - restartAt > 5_000) {
                    restartAt = 0
                    showState("Не удалось остановить рабочий стол", "Нажмите «Остановить» и повторите подключение.")
                }
            } else if (deferredConnect && !workerBusy.get()) {
                deferredConnect = false
                if ((prefs.useDesktopService || prefs.hasPaired) && !stopped) beginConnect(explicit = false) else showSetup()
            }
            if (!stopped && restartAt == 0L && (monitorBridge || BridgeService.alive)) {
                monitorBridge = true
                when {
                    DexState.error != null -> {
                        lastError = DexState.error.orEmpty()
                        armed = false
                        showState("Рабочий стол не запустился", "$lastError\n\nНажмите «Подключить», чтобы повторить. Если ошибка повторится, отправьте журнал.")
                    }
                    desktopConnectionReady() -> showState("$desktopName подключён",
                        "Изображение рабочего стола передаётся в CarLife. " + if (prefs.usbPlaybackAudio)
                            DesktopAudioState.status else "Звук остаётся на обычном выходе телефона.")
                    DexState.running && CarLifeState.running -> showState("Ожидаю CarLife…",
                        "Магнитола пока не принимает изображение. Откройте CarLife или остановите подключение.", busy = true)
                    Actions.lastUsbResult.state == Actions.UsbScanState.FAILED && !BridgeService.alive -> {
                        armed = false
                        lastError = Actions.lastUsbResult.detail.orEmpty()
                        showState("USB не подключился", "Переподключите кабель и нажмите «Подключить».")
                    }
                    Actions.lastUsbResult.state == Actions.UsbScanState.WAITING_FOR_ACCESSORY -> {
                        showUsbResult(Actions.lastUsbResult)
                    }
                    bridgeStartedAt != 0L && now - bridgeStartedAt > 40_000 -> {
                        armed = false
                        showState("Изображение рабочего стола не появилось", "Нажмите «Подключить», чтобы повторить, или отправьте журнал.")
                    }
                    BridgeService.alive -> showState("Запускаю $desktopName…", "Ожидаю соединение CarLife и изображение телефона.", busy = true)
                }
            }
            // This only checks USB, never ADB. The USB gate deduplicates permission dialogs/starts.
            if (armed && !screenBusy && !CarLifeState.running && !monitorBridge && now - lastUsbScanAt >= 1_500) {
                scanUsb(retryPermission = false)
            }
            detail.updateText(buildString {
                if (lastDiscovery.isNotBlank()) append(lastDiscovery).append('\n')
                if (lastError.isNotBlank()) append(lastError).append('\n')
                append("Рабочий стол: ").append(DexState.status)
                append("\nCarLife: ").append(CarLifeState.status)
            })
            renderAccessControls()
            main.postDelayed(this, 500)
        }
    }

    private val shizukuReceived = Shizuku.OnBinderReceivedListener {
        main.post {
            if (visible && !stopped && prefs.useShizuku && !screenBusy && !armed && !BridgeService.alive) {
                beginConnect(explicit = false)
            }
        }
    }
    private val shizukuDead = Shizuku.OnBinderDeadListener {
        main.post {
            if (!isDestroyed && prefs.useShizuku) {
                cancelContinuation()
                Actions.stopBridge(this, userInitiated = false)
                if (visible && !stopped) showShizukuSetup(DexPrivilegeAccess.status(this))
            }
        }
    }
    private val shizukuPermissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        main.post {
            val token = shizukuPermissionToken
            if (requestCode != REQUEST_SHIZUKU || token == null || token != flow.token ||
                !visible || isDestroyed || stopped || !prefs.useShizuku) return@post
            shizukuPermissionToken = null
            if (grantResult == PackageManager.PERMISSION_GRANTED && DexPrivilegeAccess.isReady(this)) {
                readyWithDesktop()
            } else {
                showShizukuSetup(DexPrivilegeAccess.status(this), denied = true)
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_PERMISSION -> Actions.handleUsbPermissionResult(context, intent)?.let {
                    if (visible && !stopped) showUsbResult(it)
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> if (visible) usbAttached()
            }
        }
    }

    // API 30–32 have no NOT_EXPORTED contract; USB grant callbacks also verify a nonce and UsbManager permission.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        prefs = DexPrefs(this)
        prefs.enabled = true
        phoneSetupOpen = savedInstanceState?.getBoolean("phone_setup_open") == true
        phonePairOpen = savedInstanceState?.getBoolean("phone_pair_open") == true
        buildView()
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addBinderDeadListener(shizukuDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult)
        shizukuListenersRegistered = true
        val usbFilter = IntentFilter().apply {
            addAction(MainActivity.ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbReceiver, usbFilter)
        receiverRegistered = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED && visible) usbAttached()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        Actions.observeUsbConnection(this)
        main.post(refresh)
        if (observePhoneActivation(force = true)) return
        when {
            phoneSetupOpen && prefs.useDelegated && !DelegatedDesktopAccess.isReady(this) -> showPhoneActivationSetup()
            stopped -> showState("Остановлено", "Подключите USB заново или нажмите «Подключить».")
            Actions.isUsbDetachedIdle() -> showUsbDetached()
            BridgeService.alive && CarLifeState.running -> { armed = true; monitorBridge = true; bridgeStartedAt = SystemClock.elapsedRealtime() }
            prefs.useDesktopService -> beginConnect(explicit = false)
            workerBusy.get() -> {
                deferredConnect = true
                showState("Завершаю предыдущую проверку…", "Подключение продолжится автоматически.", busy = true)
            }
            prefs.hasPaired -> beginConnect(explicit = false)
            else -> showSetup()
        }
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(refresh)
        cancelContinuation()
        code.text.clear()
        phonePairCode.text.clear()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        phoneIdle.onStart()
    }

    override fun onPause() {
        phoneIdle.onStop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::prefs.isInitialized) phoneIdle.onFocusChanged(hasFocus)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::prefs.isInitialized) phoneIdle.onUserInteraction()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("phone_setup_open", phoneSetupOpen)
        outState.putBoolean("phone_pair_open", phonePairOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closeDiscovery()
        if (shizukuListenersRegistered) {
            Shizuku.removeBinderReceivedListener(shizukuReceived)
            Shizuku.removeBinderDeadListener(shizukuDead)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult)
        }
        if (receiverRegistered) unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun buildView() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(16, 21, 30))
            addView(body)
        }
        window.setDecorFitsSystemWindows(false)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)

        fun text(parent: LinearLayout, value: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(231, 237, 246))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(7))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        fun button(parent: LinearLayout, label: String, input: Boolean = false, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            if (input) inputViews.add(this)
        }
        fun section(parent: LinearLayout) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        fun field(parent: LinearLayout, label: String, initial: String, id: Int, secret: Boolean = false, address: Boolean = false) = EditText(this).apply {
            this.id = id
            hint = label
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setSingleLine(true)
            inputType = when {
                address -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                secret -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_NUMBER
            }
            filters = arrayOf(InputFilter.LengthFilter(if (address) 80 else if (secret) 6 else 5))
            setText(initial)
            isSaveEnabled = !secret
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
            inputViews.add(this)
        }

        text(body, "HeadSwap Desktop", 28f, true)
        text(body, "Рабочий стол Android → USB CarLife", 15f)
        desktopChoice = button(body, "Профиль: ${DesktopModePolicy.label(prefs.desktopMode)} ▾") {
            if (accessChoiceLocked() || !prefs.useDesktopService) {
                renderAccessControls()
            } else {
                val availableModes = desktopModes.filter { it != DesktopModePolicy.SAMSUNG_DEX || SamsungDexCapability.isAvailable(this) }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Профиль рабочего стола")
                    .setSingleChoiceItems(availableModes.map { if (it == DesktopModePolicy.SAMSUNG_DEX) "Samsung DeX · Shizuku" else DesktopModePolicy.label(it) }.toTypedArray(),
                        availableModes.indexOf(prefs.desktopMode)) { dialog, position ->
                        dialog.dismiss()
                        val mode = availableModes.getOrNull(position) ?: return@setSingleChoiceItems
                        // USB may have connected while this dialog was open.
                        if (!accessChoiceLocked() && prefs.useDesktopService && mode != prefs.desktopMode) {
                            selectDesktopMode(mode)
                        }
                        renderAccessControls()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }.apply { setTextColor(Color.WHITE) }
        desktopChoiceHint = text(body, "", 13f)
        title = text(body, "Готов к настройке", 22f, true)
        hint = text(body, "", 15f)
        accessHint = text(body, "", 13f)
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            body.addView(this, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        connectButton = button(body, "Подключить") {
            // Honor the action the user saw, even if USB disconnected since the last refresh.
            if (connectionButtonState.stopsSession) stop() else beginConnect(explicit = true)
        }
        connectButtonHint = text(body, "Нажмите ещё раз, чтобы отменить подключение.", 13f).apply { visibility = View.GONE }
        cancelWaitingButton = button(body, "Остановить ожидание") { stop() }.apply { visibility = View.GONE }
        phoneActivationSection = section(body)
        text(phoneActivationSection, "Доступ HeadSwap", 19f, true)
        phoneActivationHint = text(phoneActivationSection,
            "После перезагрузки доступ можно включить прямо на телефоне. Wi-Fi нужен только на время активации.", 14f)
        phoneActivationButton = button(phoneActivationSection, "Активировать с телефона") { beginPhoneActivation() }
        phoneActivationDetails = section(phoneActivationSection).apply { visibility = View.GONE }
        text(phoneActivationDetails,
            "Подключитесь к Wi-Fi и включите «Беспроводную отладку» в настройках разработчика. После активации Wi-Fi можно отключить: изображение и звук идут по USB.", 14f)
        button(phoneActivationDetails, "Открыть настройки отладки") { openDebugSettings() }
        phonePairSection = section(phoneActivationDetails).apply { visibility = View.GONE }
        text(phonePairSection,
            "Первый раз выберите «Подключить устройство с помощью кода сопряжения». Оставьте Настройки и HeadSwap в разделённом экране и введите код. Адрес и порт найдутся автоматически.", 14f)
        phonePairCode = field(phonePairSection, "Код из 6 цифр", "", 1005, secret = true)
        button(phonePairSection, "Сопрячь и активировать", input = true) { beginPhonePair() }
        button(phoneActivationDetails, "Сопрячь заново") {
            if (!phoneLaunchCommitted && !workerBusy.get()) {
                cancelContinuation()
                phoneSetupOpen = true
                phonePairOpen = true
                showPhoneActivationSetup()
            }
        }
        button(phoneActivationSection, "Активация с компьютера") { showComputerActivationHelp() }
        phoneCurtainButton = button(body, "Чёрный экран · двойное касание для возврата") { phoneIdle.showNow() }
        phoneScreenHint = text(body, "Гасит экран без блокировки; рабочий стол продолжает работать.", 13f)

        shizukuSetup = section(body).apply { visibility = View.GONE }
        text(shizukuSetup, "Доступ через Shizuku", 19f, true)
        shizukuHelp = text(shizukuSetup, "Запустите Shizuku и один раз разрешите доступ для HeadSwap Desktop. Пока Shizuku работает, достаточно подключить USB. После перезагрузки телефона службу нужно запустить снова.")
        shizukuManagerButton = button(shizukuSetup, "Открыть Shizuku") { openShizuku() }

        button(body, "Компоненты") { startActivity(Intent(this, com.headswap.desk.update.ComponentUpdatesActivity::class.java)) }
        button(body, "Дополнительно ▾") {
            val open = advanced.visibility != View.VISIBLE
            advanced.visibility = if (open) View.VISIBLE else View.GONE
            (itButton(body, "Дополнительно") ?: return@button).text = if (open) "Дополнительно ▴" else "Дополнительно ▾"
        }
        advanced = section(body).apply { visibility = View.GONE }
        computerAccess = CheckBox(this).apply {
            text = "Доступ HeadSwap — без Shizuku"
            tintChoice(this)
            isChecked = prefs.useDelegated
            advanced.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, checked ->
                if (applyingAccessChoice) return@setOnCheckedChangeListener
                if (accessChoiceLocked()) { renderAccessControls(); return@setOnCheckedChangeListener }
                cancelContinuation()
                prefs.useDelegated = checked
                renderAccessControls()
                showAccessSetup()
            }
        }
        text(advanced, "Доступ включается на телефоне через временный Wi-Fi или с компьютера по USB. Он сохраняется, пока приложение работает; после перезагрузки или полного завершения нужна повторная активация. Поддерживаются Android Desktop и DriveDeck.", 13f)
        computerAccessStatus = text(advanced, "", 13f)
        finishComputerAccess = button(advanced, "Завершить доступ и закрыть HeadSwap") {
            if (!accessChoiceLocked() && DelegatedDesktopAccess.isReady(this)) {
                DelegatedDesktopAccess.requestStop()
            }
        }
        accessChangeHint = text(advanced, "", 13f)
        val imageSettings = section(advanced)
        text(imageSettings, "Изображение и звук", 19f, true)
        resolutionChoice = button(imageSettings, "") {
            if (!accessChoiceLocked()) {
                val values = DesktopResolution.entries
                android.app.AlertDialog.Builder(this).setTitle("Разрешение экрана магнитолы")
                    .setSingleChoiceItems(values.map { it.title }.toTypedArray(), values.indexOf(prefs.desktopResolution)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) {
                            if (values[index] == DesktopResolution.CUSTOM) showCustomResolution()
                            else prefs.desktopResolution = values[index]
                        }
                        renderAccessControls()
                    }.setNegativeButton("Отмена", null).show()
            }
        }
        densityChoice = button(imageSettings, "") {
            if (!accessChoiceLocked()) {
                val densityValues = (densityPresets + prefs.desktopDensity).distinct()
                android.app.AlertDialog.Builder(this).setTitle("Размер интерфейса на магнитоле")
                    .setSingleChoiceItems((densityValues.map { if (it == 0) "Авто — под выбранное разрешение" else "$it DPI" } + "Свой размер…").toTypedArray(),
                        densityValues.indexOf(prefs.desktopDensity)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) {
                            if (index == densityValues.size) showCustomDensity()
                            else prefs.desktopDensity = densityValues[index]
                        }
                        renderAccessControls()
                    }.setNegativeButton("Отмена", null).show()
            }
        }
        fpsChoice = button(imageSettings, "") {
            if (!accessChoiceLocked()) {
                val values = listOf(FpsSetting.FPS60, FpsSetting.FPS30, FpsSetting.FPS15, FpsSetting.AUTO)
                android.app.AlertDialog.Builder(this).setTitle("Частота кадров")
                    .setSingleChoiceItems(arrayOf("60 кадров/с", "30 кадров/с", "15 кадров/с", "Авто — по запросу магнитолы"),
                        values.indexOf(prefs.desktopFps)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) prefs.desktopFps = values[index]
                        renderAccessControls()
                    }.setNegativeButton("Отмена", null).show()
            }
        }
        densityHint = text(imageSettings, "", 13f)
        mapSymbols = CheckBox(this).apply {
            text = "Уменьшать знаки и указатель в Яндекс Картах и Навигаторе"
            tintChoice(this)
            isChecked = prefs.compactMapSymbols
            imageSettings.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, checked ->
                if (!accessChoiceLocked()) prefs.compactMapSymbols = checked
            }
        }
        mapSymbolsHint = text(imageSettings, "Кнопки сохраняют выбранный размер. Снимите галочку, чтобы карты тоже получали точный DPI рабочего стола.", 13f)
        densitySection = section(advanced)
        button(densitySection, "Подсказки навигации на приборной панели") {
            val component = android.content.ComponentName(this, com.headswap.desk.cluster.ClusterNavigationListener::class.java)
            val details = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            try { startActivity(details) }
            catch (_: android.content.ActivityNotFoundException) {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { showState("Доступ к уведомлениям", "Откройте в настройках телефона раздел «Доступ к уведомлениям» и выберите HeadSwap.") }
            }
        }
        text(densitySection, "Для подсказок разрешите HeadSwap доступ к уведомлениям. Во время CarLife приложение обрабатывает только уведомления Яндекс Карт и Навигатора с ближайшим манёвром и расстоянием. Поддержка приборной панели и HUD зависит от магнитолы.", 13f)
        button(densitySection, getString(com.headswap.desk.R.string.ime_outside_setup)) {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, getString(com.headswap.desk.R.string.ime_outside_hint), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        text(densitySection, getString(com.headswap.desk.R.string.ime_outside_hint), 13f)
        usbAudioChoice = CheckBox(this).apply {
            text = "Передавать звук по USB"
            tintChoice(this)
            isChecked = prefs.usbPlaybackAudio
            densitySection.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, checked ->
                if (applyingAccessChoice) return@setOnCheckedChangeListener
                if (!accessChoiceLocked() && prefs.useDelegated) prefs.usbPlaybackAudio = checked
                renderAccessControls()
            }
        }
        usbAudioHint = text(densitySection, "", 13f)
        panelControlChoice = CheckBox(this).apply {
            text = "Управление экраном телефона"
            tintChoice(this)
            isChecked = prefs.desktopPanelControl
            densitySection.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, checked ->
                if (applyingAccessChoice) return@setOnCheckedChangeListener
                if (!accessChoiceLocked() && prefs.useDelegated) prefs.desktopPanelControl = checked
                renderAccessControls()
            }
        }
        panelControlHint = text(densitySection, "", 13f)
        phoneScreenButton = button(densitySection, "Выключить панель телефона") { togglePhoneScreen() }
        idleModeChoice = button(densitySection, "Таймер экрана телефона ▾") {
            android.app.AlertDialog.Builder(this).setTitle("Что делать при бездействии телефона")
                .setSingleChoiceItems(PhoneIdleMode.entries.map { it.label }.toTypedArray(), prefs.phoneIdleMode.ordinal) { dialog, index ->
                prefs.phoneIdleMode = PhoneIdleMode.entries[index]
                dialog.dismiss()
                phoneIdle.refresh()
                renderPhoneScreenControl()
            }.setNegativeButton("Отмена", null).show()
        }
        idleTimeoutChoice = button(densitySection, "Гасить через ▾") {
            android.app.AlertDialog.Builder(this).setTitle("Гасить экран при подключении к CarLife")
                .setSingleChoiceItems(PhoneIdleTimeout.entries.map { it.label }.toTypedArray(), prefs.phoneIdleTimeout.ordinal) { dialog, index ->
                    prefs.phoneIdleTimeout = PhoneIdleTimeout.entries[index]
                    dialog.dismiss()
                    phoneIdle.refresh()
                    renderPhoneScreenControl()
                }.setNegativeButton("Отмена", null).show()
        }
        idleModeHint = text(densitySection, "", 13f)
        button(advanced, "Для разработчиков ▾") {
            val open = developer.visibility != View.VISIBLE
            developer.visibility = if (open) View.VISIBLE else View.GONE
            itButton(advanced, "Для разработчиков")?.text = if (open) "Для разработчиков ▴" else "Для разработчиков ▾"
        }
        developer = section(advanced).apply { visibility = View.GONE }
        builtinAccess = CheckBox(this).apply {
            text = "Старый встроенный ADB-режим (720p)"
            tintChoice(this)
            isChecked = !prefs.useDesktopService
            developer.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, checked ->
                if (applyingAccessChoice) return@setOnCheckedChangeListener
                if (accessChoiceLocked()) { renderAccessControls(); return@setOnCheckedChangeListener }
                cancelContinuation()
                prefs.useShizuku = !checked
                renderAccessControls()
                if (prefs.useDesktopService) showAccessSetup()
                else if (prefs.hasPaired) {
                    setup.visibility = View.GONE
                    showState("Встроенное подключение", "Нажмите «Подключить». Сохранённое сопряжение будет проверено автоматически.")
                } else showSetup()
            }
        }
        setup = section(developer)
        text(setup, "Первое подключение", 19f, true)
        text(setup, "Включите Wi-Fi и беспроводную отладку. Откройте Настройки и HeadSwap Desktop в разделённом экране, затем выберите в отладке «Подключить устройство с помощью кода сопряжения».")
        text(setup, "Оставьте окно с кодом открытым до завершения. Здесь нужен только код — приложение найдёт телефон само.", 14f, true)
        button(setup, "Открыть настройки отладки") { withNetworkPermission { openDebugSettings() } }
        code = field(setup, "Код из 6 цифр", "", 1003, secret = true)
        button(setup, "Сопрячь и подключить", input = true) { beginPair(manual = false) }

        adbAdvanced = section(developer)
        text(adbAdvanced, "Обычно эти поля не нужны. Здесь можно вручную указать данные этого телефона из настроек отладки.", 13f)
        host = field(adbAdvanced, "IP этого телефона", prefs.host, 1004, address = true)
        pairPort = field(adbAdvanced, "Порт из окна с кодом", "", 1002).apply { isSaveEnabled = false }
        connectPort = field(adbAdvanced, "Порт из основного окна отладки", prefs.connectPort.takeIf { it > 0 }?.toString().orEmpty(), 1001)
        button(adbAdvanced, "Проверить вручную", input = true) {
            val endpoint = manualEndpoint(connectPort) ?: return@button
            withNetworkPermission(endpoint.host) {
                if (!canBeginWork()) return@withNetworkPermission
                prepareAttempt(explicit = true)
                execute(flow.beginConnect(manual = endpoint))
            }
        }
        button(adbAdvanced, "Сопряжение с указанным портом", input = true) {
            setup.visibility = View.VISIBLE
            beginPair(manual = true)
        }
        button(adbAdvanced, "Настроить сопряжение заново", input = true) {
            cancelContinuation()
            showSetup()
        }
        button(adbAdvanced, "Открыть настройки отладки") { withNetworkPermission { openDebugSettings() } }
        text(developer, "Встроенный ADB — прежний способ запуска DeX через беспроводную отладку. Для «Доступа HeadSwap» используйте отдельную кнопку активации выше.", 13f)
        detail = text(developer, "", 12f).apply { setTextIsSelectable(true) }
        button(advanced, "Проверить обновления HeadSwap Desktop") { Updater.check(this, manual = true) }
        button(advanced, "Отправить журнал") { LogShare.shareLog(this) }
        text(advanced, "Версия ${BuildConfig.VERSION_NAME}. Настройки экрана применяются при следующем подключении. Микрофон по USB не передаётся.", 12f)
        watch(host) { hostEdited = true }
        watch(pairPort) { pairPortEdited = true }
        watch(connectPort) { connectPortEdited = true }
        setup.visibility = if (prefs.useDesktopService || prefs.hasPaired) View.GONE else View.VISIBLE
        renderAccessControls()
    }

    private fun itButton(parent: LinearLayout, prefix: String): Button? = (0 until parent.childCount)
        .map { parent.getChildAt(it) }.filterIsInstance<Button>().firstOrNull { it.text.startsWith(prefix) }

    private fun tintChoice(checkBox: CheckBox) {
        checkBox.setTextColor(Color.WHITE)
        checkBox.textSize = 16f
        checkBox.minHeight = dp(48)
        // Keep the selected state visible while controls are disabled during a session.
        checkBox.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.rgb(54, 211, 126), Color.rgb(171, 185, 201)),
        )
    }

    private fun selectDesktopMode(mode: Int) {
        if (mode == DesktopModePolicy.SAMSUNG_DEX && prefs.useDelegated) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Samsung DeX")
                .setMessage("DeX на этом телефоне запускается через Shizuku. Переключить способ доступа? «Доступ HeadSwap» без Shizuku останется доступен для Android Desktop и DriveDeck.")
                .setPositiveButton("Использовать Shizuku") { _, _ ->
                    if (!accessChoiceLocked() && SamsungDexCapability.isAvailable(this)) {
                        cancelContinuation()
                        prefs.useShizuku = true
                        prefs.desktopMode = mode
                        renderAccessControls()
                        showAccessSetup()
                    }
                }
                .setNegativeButton("Отмена", null).show()
        } else prefs.desktopMode = mode
    }

    private fun numberField(label: String, value: Int, maxDigits: Int) = EditText(this).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(maxDigits))
        setSingleLine(true)
        setText(value.toString())
        selectAll()
    }

    private fun showCustomResolution() {
        val width = numberField("Ширина, пиксели", prefs.customDesktopWidth, 5)
        val height = numberField("Высота, пиксели", prefs.customDesktopHeight, 5)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@DexSetupActivity).apply {
                text = "Ширина 640–1920, высота 480–1080. Горизонтальный экран, чётные значения. Размер должен поддерживаться телефоном и магнитолой."
            })
            addView(width)
            addView(height)
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Своё разрешение")
            .setView(fields).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (accessChoiceLocked()) { dialog.dismiss(); renderAccessControls(); return@setOnClickListener }
                val w = width.text.toString().toIntOrNull()
                val h = height.text.toString().toIntOrNull()
                if (w == null || w !in 640..1920 || w % 2 != 0) {
                    width.error = "Введите чётную ширину от 640 до 1920"; return@setOnClickListener
                }
                if (h == null || h !in 480..1080 || h % 2 != 0 || h > w) {
                    height.error = "Чётная высота 480–1080, не больше ширины"; return@setOnClickListener
                }
                runCatching { prefs.setCustomDesktopSize(w, h) }
                    .onSuccess { dialog.dismiss(); renderAccessControls() }
                    .onFailure { height.error = it.message ?: "Размер не поддерживается" }
            }
        }
        dialog.show()
    }

    private fun showCustomDensity() {
        val value = numberField("DPI", prefs.desktopDensity.takeIf { it > 0 } ?: 160, 4)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@DexSetupActivity).apply {
                text = "От 96 до 640 DPI. Больше число — крупнее интерфейс. Настройка действует только на рабочий стол магнитолы."
            })
            addView(value)
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Свой размер интерфейса")
            .setView(fields).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (accessChoiceLocked()) { dialog.dismiss(); renderAccessControls(); return@setOnClickListener }
                val dpi = value.text.toString().toIntOrNull()
                if (dpi == null || dpi !in 96..640) {
                    value.error = "Введите число от 96 до 640"; return@setOnClickListener
                }
                prefs.desktopDensity = dpi
                dialog.dismiss()
                renderAccessControls()
            }
        }
        dialog.show()
    }

    private fun beginShizuku(explicit: Boolean) {
        if (!canBeginWork()) return
        prepareAttempt(explicit)
        when (val status = DexPrivilegeAccess.status(this)) {
            DexPrivilegeAccess.Status.READY -> readyWithDesktop()
            DexPrivilegeAccess.Status.PERMISSION_REQUIRED -> {
                showShizukuSetup(status)
                if (!explicit) return
                shizukuPermissionToken = flow.token
                showState("Разрешите доступ Shizuku", "Подтвердите запрос для HeadSwap Desktop. После этого подключение продолжится автоматически.", busy = true)
                try { DexPrivilegeAccess.requestPermission(REQUEST_SHIZUKU) }
                catch (_: Exception) {
                    shizukuPermissionToken = null
                    showShizukuSetup(DexPrivilegeAccess.status(this))
                }
            }
            else -> showShizukuSetup(status)
        }
    }

    private fun readyWithDesktop() {
        if (!visible || stopped || !prefs.useDesktopService) return
        if (!DesktopAccess.isReady(this)) { showAccessSetup(); return }
        setup.visibility = View.GONE
        shizukuSetup.visibility = View.GONE
        armed = true
        // No local-network discovery, pairing code, or ADB probe in this path.
        scanUsb(retryUsbPermission)
    }

    private fun beginDelegated(explicit: Boolean) {
        if (!canBeginWork()) return
        prepareAttempt(explicit)
        if (prefs.desktopMode == DesktopModePolicy.SAMSUNG_DEX) {
            showState("Выберите Android Desktop или DriveDeck", "Доступ HeadSwap без Shizuku пока не поддерживает Samsung DeX. Измените профиль рабочего стола.")
            return
        }
        if (DelegatedDesktopAccess.isReady(this)) readyWithDesktop() else showDelegatedSetup()
    }

    private fun showAccessSetup() {
        when {
            prefs.useDelegated -> showDelegatedSetup()
            prefs.useShizuku -> showShizukuSetup(DexPrivilegeAccess.status(this))
            else -> showSetup()
        }
    }

    private fun showDelegatedSetup() {
        setup.visibility = View.GONE
        shizukuSetup.visibility = View.GONE
        if (DelegatedDesktopAccess.isReady(this)) {
            showState("Доступ HeadSwap активен", "Подключите USB к CarLife. Рабочий стол запустится автоматически.")
        } else {
            showState("Активируйте доступ HeadSwap", "Нажмите «Активировать с телефона»: потребуется временный Wi-Fi и беспроводная отладка. Можно также активировать с компьютера по USB. Повторите после перезагрузки или полного завершения приложения.")
        }
    }

    private fun showPhoneActivationSetup() {
        phoneSetupOpen = true
        if (!prefs.hasPaired) phonePairOpen = true
        showState(if (phonePairOpen) "Сопрягите этот телефон" else "Активация на телефоне",
            if (phonePairOpen) "Откройте окно с кодом в настройках беспроводной отладки и введите его ниже."
            else "Подключитесь к Wi-Fi, включите беспроводную отладку и нажмите «Активировать с телефона». Сохранённое сопряжение будет проверено автоматически.")
    }

    private fun showComputerActivationHelp() {
        android.app.AlertDialog.Builder(this).setTitle("Активация с компьютера")
            .setMessage("Подключите телефон к компьютеру по USB, разрешите USB-отладку и выполните команду из инструкции в комплекте HeadSwap. Wi-Fi не нужен.\n\nДля звука используйте --audio. Для управления панелью используйте --screen. Это временно разрешает управление панелью и чтение времени активности экрана из системной диагностики. Сначала включите соответствующие опции в «Дополнительно».")
            .setPositiveButton("Понятно", null).show()
    }

    private fun beginPhoneActivation() {
        if (!canStartPhoneActivation()) return
        phoneSetupOpen = true
        if (!prefs.hasPaired || phonePairOpen) {
            phonePairOpen = true
            showPhoneActivationSetup()
            return
        }
        startPhoneSetupAttempt(null)
    }

    private fun beginPhonePair() {
        if (!canStartPhoneActivation()) return
        val value = phonePairCode.text.toString()
        if (!value.matches(Regex("[0-9]{6}"))) {
            phonePairCode.error = "Введите код из 6 цифр"
            phonePairCode.requestFocus()
            return
        }
        startPhoneSetupAttempt(value)
    }

    private fun canStartPhoneActivation(): Boolean {
        if (!visible || !prefs.useDelegated || phoneLaunchCommitted) return false
        if (prefs.desktopMode == DesktopModePolicy.SAMSUNG_DEX) {
            showState("Выберите Android Desktop или DriveDeck", "Samsung DeX использует отдельный режим Shizuku.")
            return false
        }
        if (BridgeService.alive || DexState.running) {
            showState("Сначала остановите подключение", "Активация перезапускает HeadSwap. Остановите сеанс и повторите.")
            return false
        }
        if (workerBusy.get()) {
            showState("Предыдущая операция ещё завершается", "Дождитесь её завершения и нажмите «Активировать с телефона» снова.")
            return false
        }
        if (DelegatedDesktopAccess.isReady(this)) {
            showDelegatedSetup()
            return false
        }
        return true
    }

    private fun startPhoneSetupAttempt(pairingCode: String?) {
        prepareAttempt(explicit = true)
        phoneActivationRequested = true
        phoneSetupOpen = true
        PhoneActivationStatus.clearFinished(this)
        phoneStatusSeen = ""
        withNetworkPermission {
            if (!visible || !phoneActivationRequested || !prefs.useDelegated) return@withNetworkPermission
            execute(if (pairingCode == null) flow.beginConnect(saved = savedEndpoint()) else flow.beginPair(pairingCode))
        }
    }

    private fun launchPhoneActivation(endpoint: DexEndpoint) {
        val token = flow.token
        if (!phoneActivationRequested || !visible || stopped || !prefs.useDelegated || !flow.activating(token)) return
        // After this handoff an independent shell runner may restart this process. Do not promise cancellation.
        phoneLaunchCommitted = true
        val audio = prefs.usbPlaybackAudio
        val screen = prefs.desktopPanelControl
        showState("Включаю доступ HeadSwap…", "Приложение сейчас перезапустится. Дождитесь результата; Wi-Fi пока не отключайте.", busy = true)
        launch(token, DexSetupFlow.Phase.ACTIVATING, {
            check(prefs.persistActivationSelection(audio, screen)) { "Не удалось сохранить выбранные настройки активации. Повторите попытку." }
            runBlocking { DexAccess.activatePhone(applicationContext, endpoint, audio, screen) }
        }) {
            // ACCEPTED is only submission. The independent status/permission check owns success.
            observePhoneActivation(force = true)
        }
    }

    private fun observePhoneActivation(force: Boolean = false): Boolean {
        if (!prefs.useDelegated) return false
        val status = PhoneActivationStatus.read(this)
        val key = "${status.phase}:${status.startedElapsedMs}:${status.message}"
        if (phoneStatusSeen != key && status.phase in setOf(PhoneActivationStatus.Phase.READY, PhoneActivationStatus.Phase.FAILED)) {
            AppLog.i("PHONE ACTIVATION ${status.phase}: ${PhoneActivationPolicy.logMessage(status.message)}", false)
        }
        when (status.phase) {
            PhoneActivationStatus.Phase.PENDING -> {
                phoneLaunchCommitted = true
                showState("Включаю доступ HeadSwap…", "Дождитесь перезапуска и результата активации. Wi-Fi пока не отключайте.", busy = true)
                return true
            }
            PhoneActivationStatus.Phase.READY -> if (force || phoneStatusSeen != key || phoneLaunchCommitted) {
                phoneStatusSeen = key
                phoneLaunchCommitted = false
                phoneSetupOpen = false
                phonePairOpen = false
                cancelContinuation()
                showState("Доступ HeadSwap активен", status.message + "\nWi-Fi теперь можно отключить. Подключите телефон к CarLife по USB.")
                if (!stopped && visible) readyWithDesktop()
                return true
            }
            PhoneActivationStatus.Phase.FAILED -> if (force || phoneStatusSeen != key || phoneLaunchCommitted) {
                phoneStatusSeen = key
                phoneLaunchCommitted = false
                cancelContinuation()
                phoneSetupOpen = true
                // A recreated Activity has no local handoff flag. Base grants cannot prove that
                // this attempt confirmed its requested audio/panel capabilities or final result.
                showState("Активация не завершена", status.message + "\n\nПовторите активацию или воспользуйтесь компьютером.")
                return true
            }
            PhoneActivationStatus.Phase.IDLE -> if (phoneLaunchCommitted && !workerBusy.get()) {
                // A settings-write/preflight failure can precede the persisted request. Its callback
                // may have been retired while Settings/Home hid this Activity; never leave it locked.
                phoneLaunchCommitted = false
                cancelContinuation()
                phoneSetupOpen = true
                showState("Активация не завершена", "Запуск не был подтверждён. Нажмите «Активировать с телефона» снова.")
                return true
            }
        }
        return false
    }

    private fun showShizukuSetup(status: DexPrivilegeAccess.Status, denied: Boolean = false) {
        setup.visibility = View.GONE
        shizukuSetup.visibility = if (status == DexPrivilegeAccess.Status.READY) View.GONE else View.VISIBLE
        shizukuManagerButton.updateText(if (status == DexPrivilegeAccess.Status.NOT_INSTALLED) "Установить Shizuku" else "Открыть Shizuku")
        val instruction = when (status) {
            DexPrivilegeAccess.Status.NOT_INSTALLED -> "Установите Shizuku с официальной страницы, запустите его и вернитесь сюда."
            DexPrivilegeAccess.Status.NOT_RUNNING -> "Служба Shizuku не запущена. Откройте её и запустите; после возврата HeadSwap продолжит подключение автоматически."
            DexPrivilegeAccess.Status.PERMISSION_REQUIRED -> if (denied) "Доступ не разрешён. Нажмите «Подключить», чтобы повторить запрос." else "Нажмите «Подключить» и разрешите доступ для HeadSwap Desktop в запросе Shizuku."
            DexPrivilegeAccess.Status.DENIED_PERMANENTLY -> "Откройте Shizuku → «Авторизованные приложения» и разрешите HeadSwap Desktop. Затем вернитесь сюда."
            DexPrivilegeAccess.Status.READY -> "Подключите USB к CarLife — Рабочий стол запустится автоматически. Для работающей службы Shizuku Wi-Fi не нужен."
        }
        shizukuHelp.updateText(if (status == DexPrivilegeAccess.Status.NOT_INSTALLED)
            "Настройка выполняется в официальном приложении Shizuku."
        else "После перезагрузки телефона Shizuku нужно запустить снова.")
        val heading = when (status) {
            DexPrivilegeAccess.Status.NOT_INSTALLED -> "Нужен Shizuku"
            DexPrivilegeAccess.Status.NOT_RUNNING -> "Запустите Shizuku"
            DexPrivilegeAccess.Status.PERMISSION_REQUIRED, DexPrivilegeAccess.Status.DENIED_PERMANENTLY -> "Разрешите доступ Shizuku"
            DexPrivilegeAccess.Status.READY -> "Shizuku готов"
        }
        showState(heading, instruction)
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(DexPrivilegeAccess.MANAGER_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(DexPrivilegeAccess.DOWNLOAD_URL))
        try { startActivity(intent) }
        catch (_: Exception) { showState("Не удалось открыть Shizuku", "Откройте приложение Shizuku на телефоне и запустите службу.") }
    }

    private fun accessChoiceLocked(): Boolean = phoneLaunchCommitted || screenBusy || armed || monitorBridge || workerBusy.get() ||
        BridgeService.alive || DexState.running || restartAt != 0L || permissionAction != null || shizukuPermissionToken != null

    private fun renderAccessControls() {
        val usingShizuku = prefs.useDesktopService
        accessHint.updateText(when {
            prefs.useDelegated && DelegatedDesktopAccess.isReady(this) && !ShellPanelClient.isConnected ->
                "$desktopName · ${prefs.desktopResolutionLabel}\nДля клавиатуры на магнитоле завершите доступ в «Дополнительно» и снова активируйте HeadSwap."
            prefs.useDelegated -> "$desktopName · доступ HeadSwap · ${prefs.desktopResolutionLabel}"
            usingShizuku -> "$desktopName · через Shizuku · ${prefs.desktopResolutionLabel}"
            else -> "Встроенное подключение · видео 1280×720"
        })
        if (usingShizuku) setup.visibility = View.GONE else shizukuSetup.visibility = View.GONE
        val visibility = if (usingShizuku) View.GONE else View.VISIBLE
        if (adbAdvanced.visibility != visibility) adbAdvanced.visibility = visibility
        applyingAccessChoice = true
        try {
            if (builtinAccess.isChecked == usingShizuku) builtinAccess.isChecked = !usingShizuku
            if (computerAccess.isChecked != prefs.useDelegated) computerAccess.isChecked = prefs.useDelegated
        } finally { applyingAccessChoice = false }
        val enabled = !accessChoiceLocked()
        desktopChoice.updateText("Профиль: ${DesktopModePolicy.label(prefs.desktopMode)} ▾")
        desktopChoice.isEnabled = enabled && usingShizuku
        desktopChoice.visibility = if (usingShizuku) View.VISIBLE else View.GONE
        desktopChoiceHint.visibility = desktopChoice.visibility
        desktopChoiceHint.updateText(if (!enabled) "Для смены рабочего стола сначала остановите подключение."
            else when (prefs.desktopMode) {
                DesktopModePolicy.DRIVEDECK -> "DriveDeck на экране магнитолы. Домашний экран телефона не меняется."
                DesktopModePolicy.SAMSUNG_DEX -> "Системный рабочий стол Samsung DeX."
                else -> "Собственное меню и полноэкранные приложения. Samsung DeX не используется."
            })
        if (builtinAccess.isEnabled != enabled) builtinAccess.isEnabled = enabled
        computerAccess.isEnabled = enabled
        val computerReady = DelegatedDesktopAccess.isReady(this)
        computerAccessStatus.visibility = if (computerReady) View.VISIBLE else View.GONE
        computerAccessStatus.updateText(if (enabled) "Доступ HeadSwap активен. Кнопка ниже завершит его; затем можно активировать на телефоне или с компьютера."
            else "Доступ HeadSwap активен. Для его завершения сначала остановите подключение.")
        finishComputerAccess.visibility = if (computerReady) View.VISIBLE else View.GONE
        finishComputerAccess.isEnabled = enabled && computerReady
        val densityVisibility = if (usingShizuku) View.VISIBLE else View.GONE
        if (densitySection.visibility != densityVisibility) densitySection.visibility = densityVisibility
        resolutionChoice.visibility = densityVisibility
        fpsChoice.visibility = densityVisibility
        val embeddedMaps = usingShizuku && prefs.desktopMode != DesktopModePolicy.SAMSUNG_DEX
        mapSymbols.visibility = if (embeddedMaps) View.VISIBLE else View.GONE
        mapSymbolsHint.visibility = mapSymbols.visibility
        mapSymbols.isEnabled = enabled && embeddedMaps
        densityChoice.isEnabled = enabled
        resolutionChoice.isEnabled = enabled
        fpsChoice.isEnabled = enabled
        resolutionChoice.updateText("Разрешение: ${prefs.desktopResolutionLabel} ▾")
        densityChoice.updateText("Размер интерфейса: ${if (prefs.desktopDensity == 0) "авто" else "${prefs.desktopDensity} DPI"} ▾")
        fpsChoice.updateText("Частота: ${prefs.desktopFps.fixed?.let { "$it кадров/с" } ?: "авто"} ▾")
        densityHint.updateText(if (enabled)
            "Применится при следующем подключении. Авто DPI подбирает масштаб под разрешение; настройки экрана телефона сохраняются."
        else "Для изменения изображения остановите подключение, выберите настройки и подключитесь снова.")
        applyingAccessChoice = true
        try { usbAudioChoice.isChecked = prefs.usbPlaybackAudio } finally { applyingAccessChoice = false }
        usbAudioChoice.isEnabled = enabled && prefs.useDelegated
        usbAudioHint.updateText(when {
            !prefs.useDelegated -> "USB-звук доступен в режиме «Доступ HeadSwap». Здесь можно оставить звук через Bluetooth автомобиля."
            !prefs.usbPlaybackAudio -> "Звук остаётся на обычном выходе телефона, например Bluetooth автомобиля."
            !DesktopAudioAccess.hasPermission(this) -> "USB-звук выбран. Активируйте доступ, чтобы передавать музыку и подсказки телефона в CarLife."
            else -> DesktopAudioState.status
        })
        applyingAccessChoice = true
        try { panelControlChoice.isChecked = prefs.desktopPanelControl } finally { applyingAccessChoice = false }
        panelControlChoice.isEnabled = enabled && prefs.useDelegated
        panelControlChoice.visibility = if (prefs.useDelegated) View.VISIBLE else View.GONE
        panelControlHint.visibility = panelControlChoice.visibility
        panelControlHint.updateText(when {
            !prefs.desktopPanelControl -> "HeadSwap получит временный доступ к панели и системной диагностике, чтобы узнать время последней активности телефона. Перед выключением панели он откроет свой экран для двойного касания."
            DelegatedPanelAccess.unsupportedThisActivation -> PhoneActivationPolicy.PANEL_MESSAGE
            !DelegatedPanelAccess.isAvailable(this) -> "Для таймера и двойного касания повторите активацию на телефоне или с --screen на компьютере. HeadSwap запросит управление панелью и чтение времени активности экрана."
            else -> "Перед выключением панели HeadSwap открывает свой экран для двойного касания. Прежнее приложение телефона может приостановиться до пробуждения."
        })
        accessChangeHint.updateText(if (enabled) "Способ подключения меняется только здесь; автоматического переключения нет." else "Чтобы сменить способ подключения, остановите его и дождитесь завершения.")
        renderConnectionControl()
        renderPhoneActivationControls()
        renderPhoneScreenControl()
    }

    private fun renderPhoneActivationControls() {
        val ready = DelegatedDesktopAccess.isReady(this)
        phoneActivationSection.visibility = if (prefs.useDelegated && (!ready || phoneLaunchCommitted)) View.VISIBLE else View.GONE
        phoneActivationDetails.visibility = if (phoneSetupOpen && !phoneLaunchCommitted) View.VISIBLE else View.GONE
        phonePairSection.visibility = if (phonePairOpen) View.VISIBLE else View.GONE
        phoneActivationButton.isEnabled = !phoneLaunchCommitted && !screenBusy && !workerBusy.get() &&
            permissionAction == null && !BridgeService.alive && !DexState.running
        phoneActivationHint.updateText(if (phoneLaunchCommitted)
            "Запуск уже передан Android; дождитесь подтверждения. Он завершится или покажет ошибку не позднее чем через 90 секунд."
        else buildString {
            append("Wi-Fi нужен на время активации. HeadSwap получит временный доступ к управлению плеером и перезапустится.")
            append(" Штатная клавиатура будет открываться в окне приложения на магнитоле.")
            if (prefs.usbPlaybackAudio) append(" Для звука по USB HeadSwap получит временный доступ к воспроизведению и направит музыку и подсказки в CarLife. Микрофон не используется.")
            if (prefs.desktopPanelControl) append(" Для таймера HeadSwap получит доступ к панели и времени активности экрана. Двойное касание вернёт изображение.")
            append(" Выбор можно изменить перед активацией в «Дополнительно».")
        })
    }

    private fun renderConnectionControl() {
        val passiveWaiting = armed && !BridgeService.alive && !CarLifeState.running && !screenBusy &&
            Actions.lastUsbResult.state == Actions.UsbScanState.WAITING_FOR_ACCESSORY
        val state = DesktopConnectionButtonPolicy.state(
            stopped = stopped, detached = Actions.isUsbDetachedIdle(),
            serviceAlive = BridgeService.alive, desktopRunning = DexState.running,
            carLifeRunning = CarLifeState.running,
            videoFlowing = DesktopConnectionState.videoIsFlowing(SystemClock.elapsedRealtime()),
            failed = DexState.error != null || Actions.lastUsbResult.state == Actions.UsbScanState.FAILED,
            working = screenBusy || deferredConnect || restartAt != 0L ||
                permissionAction != null || shizukuPermissionToken != null,
            connectionPending = (armed && !passiveWaiting) || monitorBridge && BridgeService.alive,
        )
        connectionButtonState = state
        connectButton.updateText(state.label)
        val color = when (state) {
            DesktopConnectionButtonPolicy.State.IDLE -> Color.rgb(30, 120, 65)
            DesktopConnectionButtonPolicy.State.CONNECTING -> Color.rgb(70, 80, 95)
            DesktopConnectionButtonPolicy.State.CONNECTED -> Color.rgb(183, 40, 43)
        }
        if (connectButton.backgroundTintList?.defaultColor != color) {
            connectButton.backgroundTintList = ColorStateList.valueOf(color)
        }
        connectButton.setTextColor(Color.WHITE)
        connectButton.isEnabled = !phoneLaunchCommitted
        val actionHint = if (state == DesktopConnectionButtonPolicy.State.CONNECTING)
            "Подключение… Нажмите, чтобы остановить подключение" else state.label
        connectButton.contentDescription = actionHint
        connectButton.tooltipText = if (state == DesktopConnectionButtonPolicy.State.CONNECTING)
            "Остановить подключение" else null
        connectButtonHint.visibility = if (state == DesktopConnectionButtonPolicy.State.CONNECTING && !phoneLaunchCommitted) View.VISIBLE else View.GONE
        // Passive auto-start waiting still needs cancellation so settings can be changed.
        cancelWaitingButton.visibility = if (passiveWaiting && state == DesktopConnectionButtonPolicy.State.IDLE &&
            !stopped && !Actions.isUsbDetachedIdle()) View.VISIBLE else View.GONE
    }

    private fun desktopIdleSessionReady(): Boolean = BridgeService.alive && DexState.running &&
        CarLifeState.running && DexState.error == null && DesktopConnectionState.aliveForIdleTimer(SystemClock.elapsedRealtime())

    private fun desktopConnectionReady(): Boolean = BridgeService.alive && DexState.running &&
        CarLifeState.running && DexState.error == null && DesktopConnectionState.videoIsFlowing(SystemClock.elapsedRealtime())

    private fun renderPhoneScreenControl() {
        val available = prefs.useDesktopService && BridgeService.alive && DexState.running && !stopped
        val visibility = if (available) View.VISIBLE else View.GONE
        val canBlankPanel = available && (prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.isAvailable(this))
        val mode = prefs.phoneIdleMode
        idleModeChoice.updateText("При бездействии: ${mode.label} ▾")
        idleTimeoutChoice.visibility = if (mode == PhoneIdleMode.DISABLED) View.GONE else View.VISIBLE
        idleTimeoutChoice.updateText("Гасить через: ${prefs.phoneIdleTimeout.label} ▾")
        idleModeHint.updateText(when (mode) {
            PhoneIdleMode.CURTAIN -> "Пока HeadSwap открыт и CarLife подключён, через выбранный интервал экран станет чёрным с минимальной яркостью. Сенсор останется активным; двойное касание вернёт интерфейс. Панель физически не выключается."
            PhoneIdleMode.PANEL -> if (prefs.useDelegated && DelegatedPanelAccess.unsupportedThisActivation)
                PhoneActivationPolicy.PANEL_MESSAGE
                else if (!prefs.useDelegated || !prefs.desktopPanelControl || !DelegatedPanelAccess.hasPermission(this))
                "Для автоматического выключения панели выберите «Доступ HeadSwap», включите управление экраном и повторите активацию."
                else "Во время работы CarLife панель гаснет через выбранный интервал бездействия телефона. Дважды коснитесь экрана для возврата. Если сенсор не реагирует, отсоедините USB."
            PhoneIdleMode.DISABLED -> "HeadSwap не использует таймер. Действуют обычные настройки блокировки телефона."
        })
        phoneCurtainButton.visibility = if (available && mode == PhoneIdleMode.CURTAIN && !DexState.phoneScreenOff) View.VISIBLE else View.GONE
        phoneCurtainButton.isEnabled = desktopConnectionReady() && !DexState.phoneScreenBusy
        phoneScreenButton.visibility = if (canBlankPanel) View.VISIBLE else View.GONE
        phoneScreenHint.visibility = visibility
        phoneScreenButton.updateText(when {
            DexState.phoneScreenBusy -> "Меняю состояние экрана…"
            DexState.phoneScreenOff -> "Включить экран телефона"
            else -> "Выключить панель телефона"
        })
        val enabled = canBlankPanel && !DexState.phoneScreenBusy
        if (phoneScreenButton.isEnabled != enabled) phoneScreenButton.isEnabled = enabled
        phoneScreenHint.updateText(DexState.phoneScreenError ?: if (mode == PhoneIdleMode.CURTAIN && !DexState.phoneScreenOff)
            "Для режима «Чёрный экран» оставьте HeadSwap открытым на телефоне. Двойное касание вернёт изображение. Кнопка питания блокирует телефон."
            else if (mode == PhoneIdleMode.PANEL) idleModeHint.text.toString()
            else if (prefs.useDelegated && !DelegatedPanelAccess.isAvailable(this))
            "Для выключения панели включите «Управление экраном телефона» и активируйте доступ с параметром --screen."
            else "Выключайте экран этой кнопкой: кнопка питания блокирует и рабочий стол. Панель включится при отключении CarLife.")
    }

    private fun togglePhoneScreen() {
        if (!(prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.isAvailable(this)) || !BridgeService.alive || !DexState.running || stopped || DexState.phoneScreenBusy) return
        val action = if (DexState.phoneScreenOff) BridgeService.ACTION_PHONE_SCREEN_ON else BridgeService.ACTION_PHONE_SCREEN_OFF
        try { startService(Intent(this, BridgeService::class.java).setAction(action)) }
        catch (_: Exception) {
            android.app.AlertDialog.Builder(this)
                .setMessage("Не удалось изменить состояние экрана телефона. Повторите после подключения рабочего стола.")
                .setPositiveButton("Понятно", null).show()
        }
    }

    private fun beginConnect(explicit: Boolean) {
        if (!visible || phoneLaunchCommitted) return
        Actions.observeUsbConnection(this)
        // A deliberate retry must not be superseded by the next refresh noticing
        // the previous Stop (especially while a permission dialog is open).
        if (explicit || !stopped) observedStop = false
        when (Actions.connectDecision(explicit, BridgeService.alive, desktopConnectionReady())) {
            DexAutoConnectPolicy.Decision.STOPPED -> return
            DexAutoConnectPolicy.Decision.MONITOR -> {
                armed = true
                monitorBridge = true
                if (bridgeStartedAt == 0L) bridgeStartedAt = SystemClock.elapsedRealtime()
                if (desktopConnectionReady()) {
                    showState("$desktopName подключён", "Изображение уже передаётся через CarLife.")
                } else {
                    showState("Запускаю $desktopName…", "Подключение уже выполняется. Дождитесь изображения.", busy = true)
                }
                return
            }
            DexAutoConnectPolicy.Decision.RESTART -> {
                Actions.stopBridge(this, userInitiated = false)
                cancelContinuation()
                restartAt = SystemClock.elapsedRealtime()
                showState("Перезапускаю подключение…", "Это займёт несколько секунд.", busy = true)
                return
            }
            DexAutoConnectPolicy.Decision.CONNECT -> Unit
        }
        if (prefs.useDelegated) { beginDelegated(explicit); return }
        if (prefs.useShizuku) { beginShizuku(explicit); return }
        if (!prefs.hasPaired) { showSetup(); return }
        if (!canBeginWork()) {
            deferredConnect = true
            return
        }
        withNetworkPermission {
            prepareAttempt(explicit)
            execute(flow.beginConnect(saved = savedEndpoint()))
        }
    }

    private fun beginPair(manual: Boolean) {
        if (prefs.useDesktopService) return
        val value = code.text.toString()
        if (!value.matches(Regex("[0-9]{6}"))) {
            code.error = "Введите код из 6 цифр"
            code.requestFocus()
            showState("Введите код сопряжения", "Код показан в открытом окне беспроводной отладки.")
            return
        }
        val endpoint = if (manual) manualEndpoint(pairPort) ?: return else null
        if (!canBeginWork()) return
        withNetworkPermission(endpoint?.host) {
            prepareAttempt(explicit = true)
            execute(flow.beginPair(value, manual = endpoint))
        }
    }

    private fun prepareAttempt(explicit: Boolean) {
        cancelContinuation()
        if (explicit) {
            observedStop = false
            Actions.resumeUsbAutoStart()
        }
        retryUsbPermission = explicit
        lastError = ""
        monitorBridge = false
        bridgeStartedAt = 0
    }

    private fun canBeginWork(): Boolean {
        if (!workerBusy.get()) return true
        deferredConnect = true // Only a connection may resume; never repeat pairing automatically.
        showState("Завершаю предыдущую проверку…", "Дождитесь её завершения. Повторное сопряжение само не запускается.", busy = true)
        return false
    }

    private fun execute(command: DexSetupFlow.Command) {
        when (command) {
            DexSetupFlow.Command.None -> Unit
            is DexSetupFlow.Command.Discover -> {
                if (command.token != flow.token) return
                if (!hasNetworkPermission()) {
                    withNetworkPermission { execute(command) }
                    return
                }
                val pairing = command.kind == DexService.PAIRING
                showState(if (pairing) "Ищу окно сопряжения…" else "Ищу телефон…",
                    if (pairing) "Оставьте окно с кодом открытым в разделённом экране." else "Проверяю сохранённое подключение. Включите Wi-Fi и беспроводную отладку.", busy = true)
                startDiscovery(command.token)
                cancelDeadline()
                discoveryDeadline = Runnable { execute(flow.expired(command.token, command.kind)) }.also { main.postDelayed(it, DISCOVERY_TIMEOUT_MS) }
            }
            is DexSetupFlow.Command.Pair -> {
                cancelDeadline()
                showState("Сопрягаю телефон…", "Не закрывайте окно с кодом в Настройках.", busy = true)
                launch(command.token, DexSetupFlow.Phase.PAIRING, {
                    runBlocking { DexAccess.pair(applicationContext, command.endpoint.port, command.code, command.endpoint.host) }
                }) {
                    code.text.clear()
                    phonePairCode.text.clear()
                    if (phoneActivationRequested) phonePairOpen = false
                    setup.visibility = View.GONE
                    execute(flow.paired(command.token))
                }
            }
            is DexSetupFlow.Command.Probe -> {
                cancelDeadline()
                showState("Проверяю телефон…", if (phoneActivationRequested)
                    "Проверяю сохранённое сопряжение перед активацией HeadSwap."
                    else "После проверки подключение к CarLife продолжится автоматически.", busy = true)
                launch(command.token, DexSetupFlow.Phase.PROBING, {
                    DexAccess.probe(applicationContext, command.endpoint.port, command.endpoint.host)
                    prefs.host = command.endpoint.host
                    prefs.connectPort = command.endpoint.port
                }) { execute(flow.verified(command.token)) }
            }
            is DexSetupFlow.Command.Ready -> {
                closeDiscovery()
                if (phoneActivationRequested) {
                    launchPhoneActivation(command.endpoint)
                    return
                }
                setup.visibility = View.GONE
                armed = true
                scanUsb(retryUsbPermission)
            }
            is DexSetupFlow.Command.NotFound -> {
                closeDiscovery()
                if (phoneActivationRequested) {
                    phoneActivationRequested = false
                    phoneSetupOpen = true
                    if (command.kind == DexService.PAIRING) phonePairOpen = true
                    showState(if (command.kind == DexService.PAIRING) "Окно сопряжения не найдено" else "Беспроводная отладка не найдена",
                        if (command.kind == DexService.PAIRING) "Откройте новое окно с кодом в разделённом экране и повторите «Сопрячь и активировать»."
                        else "Подключитесь к Wi-Fi, включите беспроводную отладку и повторите «Активировать с телефона». Новый код обычно не нужен.")
                    return
                }
                if (command.kind == DexService.PAIRING) {
                    setup.visibility = View.VISIBLE
                    showState("Окно сопряжения не найдено", "Откройте окно с кодом в разделённом экране, введите свежий код и нажмите «Сопрячь и подключить».")
                } else {
                    showState("Телефон пока не найден", "Включите Wi-Fi и беспроводную отладку, затем нажмите «Подключить». Повторное сопряжение обычно не требуется.")
                }
            }
        }
    }

    private fun launch(token: Long, phase: DexSetupFlow.Phase, work: () -> Unit, success: () -> Unit) {
        if (!workerBusy.compareAndSet(false, true)) {
            if (phase == DexSetupFlow.Phase.ACTIVATING) phoneLaunchCommitted = false
            flow.cancel()
            closeDiscovery()
            showState("Предыдущая проверка ещё завершается", "Через несколько секунд нажмите нужную кнопку ещё раз.")
            return
        }
        worker.execute {
            val result = runCatching(work)
            workerBusy.set(false)
            main.post {
                if (!visible || isDestroyed || stopped || !flow.isCurrent(token, phase)) return@post
                result.fold(onSuccess = { success() }, onFailure = { failure ->
                    if (phase == DexSetupFlow.Phase.ACTIVATING) {
                        // A lost submission response may still have started the independent runner.
                        if (observePhoneActivation(force = true)) return@fold
                        phoneLaunchCommitted = false
                    }
                    flow.failed(token)
                    closeDiscovery()
                    armed = false
                    lastError = failure.message ?: failure.javaClass.simpleName
                    if (phoneActivationRequested || phase == DexSetupFlow.Phase.ACTIVATING) {
                        phoneActivationRequested = false
                        phoneSetupOpen = true
                        if (phase == DexSetupFlow.Phase.PAIRING) {
                            phonePairOpen = true
                            phonePairCode.text.clear()
                        }
                        showState(if (phase == DexSetupFlow.Phase.ACTIVATING) "Активация не завершена" else "Проверка отладки не завершена",
                            if (phase == DexSetupFlow.Phase.ACTIVATING) lastError + "\n\nПовторите активацию или воспользуйтесь компьютером."
                            else friendlyFailure(failure, phase).replace("«Подключить»", "«Активировать с телефона»") +
                                "\nЕсли телефон удалил ключ, выберите «Сопрячь заново».")
                        AppLog.i("DEX phone activation setup failed: ${failure.javaClass.simpleName}")
                        return@fold
                    }
                    if (phase == DexSetupFlow.Phase.PAIRING) {
                        code.text.clear()
                        setup.visibility = View.VISIBLE
                    }
                    showState(if (phase == DexSetupFlow.Phase.PAIRING) "Сопряжение не завершено" else "Телефон пока не готов", friendlyFailure(failure, phase))
                    AppLog.i("DEX setup failed: ${failure.javaClass.simpleName}")
                })
            }
        }
    }

    private fun startDiscovery(token: Long) {
        if (discovery != null && discoveryToken == token) return
        closeDiscovery()
        discoveryToken = token
        discovery = DexDiscovery(this) { pairing, connection, error ->
            if (!visible || token != flow.token) return@DexDiscovery
            applyingFields = true
            try {
                (pairing ?: connection)?.let { if (!hostEdited) host.setText(it.host) }
                if (!pairPortEdited) pairPort.setText(pairing?.port?.toString().orEmpty())
                if (!connectPortEdited && connection != null) connectPort.setText(connection.port.toString())
            } finally { applyingFields = false }
            lastDiscovery = "Сопряжение: ${pairing?.let { "${it.host}:${it.port}" } ?: "не найдено"}\nПодключение: ${connection?.let { "${it.host}:${it.port}" } ?: "не найдено"}"
            if (error != null) lastError = error
            execute(flow.discovered(token, pairing, connection))
        }.also { it.start() }
    }

    private fun closeDiscovery() {
        cancelDeadline()
        discovery?.close()
        discovery = null
        discoveryToken = -1
    }
    private fun cancelDeadline() {
        discoveryDeadline?.let(main::removeCallbacks)
        discoveryDeadline = null
    }

    private fun cancelContinuation() {
        flow.cancel()
        closeDiscovery()
        armed = false
        monitorBridge = false
        deferredConnect = false
        screenBusy = false
        restartAt = 0
        permissionAction = null
        shizukuPermissionToken = null
        phoneActivationRequested = false
    }

    private fun stop() {
        if (phoneLaunchCommitted) return
        cancelContinuation()
        code.text.clear()
        phonePairCode.text.clear()
        Actions.stopBridge(this)
        showState("Остановлено", "Подключите USB заново или нажмите «Подключить».")
    }

    private fun usbAttached() {
        if (phoneActivationRequested || phoneLaunchCommitted) return
        Actions.observeUsbConnection(this)
        if (stopped) return
        when {
            !CarLifeState.running && BridgeService.alive && prefs.useDesktopService && DesktopAccess.isReady(this) -> {
                armed = true
                scanUsb(retryPermission = false)
            }
            armed -> scanUsb(retryPermission = false)
            screenBusy -> Unit // The active flow will scan USB after its single probe completes.
            prefs.useDesktopService || prefs.hasPaired -> beginConnect(explicit = false)
            else -> showSetup()
        }
    }

    private fun scanUsb(retryPermission: Boolean) {
        if (!visible || stopped || !armed) return
        if (prefs.useDesktopService && !DesktopAccess.isReady(this)) {
            cancelContinuation()
            showAccessSetup()
            return
        }
        lastUsbScanAt = SystemClock.elapsedRealtime()
        showUsbResult(Actions.scan(this, "DeX: телефон готов", retryPermission))
    }

    private fun showUsbResult(result: Actions.UsbScanResult) {
        when (result.state) {
            Actions.UsbScanState.DETACHED -> showUsbDetached()
            Actions.UsbScanState.WAITING_FOR_ACCESSORY -> {
                monitorBridge = false
                bridgeStartedAt = 0
                showState("Готов к автоматическому подключению", if (prefs.useDesktopService)
                    "Откройте CarLife на магнитоле и подключите USB-кабель. Рабочий стол запустится автоматически; Wi-Fi и сопряжение для подключения не нужны."
                else "Откройте CarLife на магнитоле и подключите USB-кабель с передачей данных.")
            }
            Actions.UsbScanState.PERMISSION_REQUESTED -> showState("Разрешите доступ к USB", "Подтвердите системный запрос на телефоне. После этого рабочий стол запустится автоматически.")
            Actions.UsbScanState.STARTING, Actions.UsbScanState.ALREADY_RUNNING -> {
                monitorBridge = true
                if (bridgeStartedAt == 0L) bridgeStartedAt = SystemClock.elapsedRealtime()
                showState("Запускаю $desktopName…", "Ожидаю соединение CarLife и изображение телефона.", busy = true)
            }
            Actions.UsbScanState.FAILED -> {
                armed = false
                lastError = result.detail.orEmpty()
                showState("USB не подключился", "Разрешите доступ к USB или переподключите кабель, затем нажмите «Подключить».")
            }
        }
    }

    private fun showUsbDetached() {
        cancelContinuation()
        bridgeStartedAt = 0
        lastError = ""
        if (BridgeService.alive) {
            showState("USB отключён", "Короткая пауза для переподключения. Если кабель не вернётся, сеанс завершится автоматически.")
        } else {
            showState("Сеанс остановлен", "Телефон можно подключить к компьютеру. При следующем подключении к CarLife рабочий стол запустится автоматически, если доступ активен.")
        }
    }

    private fun showSetup() {
        if (prefs.useDelegated) { showDelegatedSetup(); return }
        if (prefs.useShizuku) { showShizukuSetup(DexPrivilegeAccess.status(this)); return }
        setup.visibility = View.VISIBLE
        showState("Нужно первое подключение", "Один раз введите код из настроек отладки. Затем приложение будет подключаться автоматически.")
    }

    private fun showState(value: String, explanation: String, busy: Boolean = false) {
        title.updateText(value)
        val panelWarning = prefs.useDelegated && DelegatedPanelAccess.unsupportedThisActivation &&
            !explanation.contains(PhoneActivationPolicy.PANEL_MESSAGE)
        hint.updateText(if (panelWarning) explanation + "\n" + PhoneActivationPolicy.PANEL_MESSAGE else explanation)
        screenBusy = busy
        val visibility = if (busy) View.VISIBLE else View.GONE
        if (progress.visibility != visibility) progress.visibility = visibility
        inputViews.forEach { if (it.isEnabled == busy) it.isEnabled = !busy }
        renderAccessControls()
    }

    private fun friendlyFailure(error: Throwable, phase: DexSetupFlow.Phase): String {
        val pairing = phase == DexSetupFlow.Phase.PAIRING
        val problem = error as? DexAccessException
        return when (problem?.reason) {
            DexAccessReason.PERMISSION_DENIED -> "Разрешите приложению доступ к локальной сети, затем повторите подключение."
            DexAccessReason.INVALID_HOST, DexAccessReason.NETWORK_UNREACHABLE -> "Подключитесь к Wi-Fi и включите беспроводную отладку, затем повторите."
            DexAccessReason.IDENTITY_FAILURE -> "Не удалось открыть сохранённый ключ. Отправьте журнал из раздела «Дополнительно»."
            DexAccessReason.PAIR_REJECTED -> "Откройте новое окно сопряжения и введите свежий код. Оставьте это окно открытым до завершения."
            else -> if (pairing) "Откройте новое окно с кодом в разделённом экране и повторите. Если ошибка останется, отправьте журнал." else "Проверьте, что беспроводная отладка включена, и нажмите «Подключить». Если телефон удалил сопряжение, настройте его заново в разделе «Дополнительно»."
        }
    }

    private fun hasNetworkPermission() = Build.VERSION.SDK_INT < 37 ||
        checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun withNetworkPermission(address: String? = null, action: () -> Unit) {
        if (hasNetworkPermission() || address == "127.0.0.1" || address == "::1") { action(); return }
        permissionAction = action
        showState("Разрешите найти этот телефон", "Для автоматического подключения нужен доступ к локальной сети.")
        if (Build.VERSION.SDK_INT >= 37) requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), REQUEST_NETWORK)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NETWORK) return
        val action = permissionAction
        permissionAction = null
        if (stopped) return
        if (hasNetworkPermission() && visible) action?.invoke() else if (visible) {
            showState("Нужно разрешение локальной сети", "Разрешите его в настройках приложения, затем нажмите «Подключить».")
        }
    }

    private fun savedEndpoint(): DexEndpoint? = if (prefs.connectPort in 1..65535 && DexAccess.acceptsHost(prefs.host)) DexEndpoint(prefs.host, prefs.connectPort) else null
    private fun manualEndpoint(port: EditText): DexEndpoint? {
        val address = host.text.toString().trim()
        val number = port.text.toString().toIntOrNull()
        if (!DexAccess.acceptsHost(address)) {
            host.error = "Нужен IP именно этого телефона"
            return null
        }
        if (number == null || number !in 1..65535) { port.error = "Введите порт от 1 до 65535"; return null }
        return DexEndpoint(address, number)
    }

    private fun openDebugSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    private fun watch(field: EditText, edited: () -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!applyingFields) edited() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun TextView.updateText(value: String) { if (text.toString() != value) text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
