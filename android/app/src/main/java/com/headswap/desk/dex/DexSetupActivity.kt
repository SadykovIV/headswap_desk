package com.headswap.desk.dex

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.headswap.desk.AppLog
import com.headswap.desk.BridgeService
import com.headswap.desk.aa.AaPrefs
import com.headswap.desk.aa.DesktopResolution
import com.headswap.desk.aa.FpsSetting
import com.headswap.desk.CarLifeState
import com.headswap.desk.component.DesktopComponentEntry
import com.headswap.desk.component.DesktopComponents
import com.headswap.desk.MainActivity
import com.headswap.desk.ui.Actions
import com.headswap.desk.ui.LogShare
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku

/** USB desktop setup; local ADB activation is a separate, explicit action. */
class DexSetupActivity : Activity() {
    private lateinit var prefs: DexPrefs
    private val clusterPrefs by lazy { AaPrefs(this) }
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
                PhoneIdleMode.CURTAIN -> prefs.desktopPanelControl
                PhoneIdleMode.PANEL -> !CarLifePhoneIdleTimer.ownsPhysicalTimer && prefs.desktopPanelControl &&
                    (prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.hasPermission(this))
                PhoneIdleMode.DISABLED -> false
            } },
            isConnected = { prefs.useDesktopService && !stopped && desktopIdleSessionReady() },
            prefersPhysicalOff = { prefs.phoneIdleMode == PhoneIdleMode.PANEL },
            requestPhysicalOff = {
                prefs.phoneIdleMode == PhoneIdleMode.PANEL && prefs.desktopPanelControl && desktopIdleSessionReady() &&
                    (prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.isAvailable(this)) && runCatching {
                    startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_PHONE_SCREEN_OFF)) != null
                }.getOrDefault(false)
            },
            panelBusy = { DexState.phoneScreenBusy }, panelOff = { DexState.phoneScreenOff })
    }
    private lateinit var desktopChoice: Button
    private lateinit var desktopChoiceHint: TextView
    private var selectedPanel: DesktopComponentEntry? = null
    private val profilePackageChange = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == selectedPanel?.packageName) {
                selectedPanel = DesktopComponents.selected(this@DexSetupActivity)
                renderAccessControls()
            }
        }
    }
    private val desktopProfileName: String get() = selectedPanel?.takeIf { prefs.desktopMode == DesktopModePolicy.ANDROID_DESKTOP }
        ?.let(DesktopProfilePicker::name) ?: DesktopModePolicy.label(prefs.desktopMode)
    private val desktopName: String get() = if (prefs.useDesktopService) desktopProfileName else "DeX"
    private lateinit var setup: LinearLayout
    private lateinit var shizukuSetup: LinearLayout
    private lateinit var shizukuHelp: TextView
    private lateinit var shizukuManagerButton: Button
    private val ui by lazy { DesktopSetupViews(this) }
    private lateinit var homePage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var audioSettings: LinearLayout
    private lateinit var phoneSettings: LinearLayout
    private lateinit var phoneControlDetails: LinearLayout
    private lateinit var accessChoice: Button
    private lateinit var adbOptions: Button
    private var manualAdbOpen = false
    private lateinit var adbAdvanced: LinearLayout
    private lateinit var computerAccessStatus: TextView
    private lateinit var finishComputerAccess: Button
    private lateinit var accessHint: TextView
    private lateinit var accessChangeHint: TextView
    private lateinit var densityChoice: Button
    private lateinit var resolutionChoice: Button
    private lateinit var fpsChoice: Button
    private lateinit var usbAudioChoice: DesktopSetupViews.Toggle
    private lateinit var usbAudioHint: TextView
    private lateinit var panelControlChoice: DesktopSetupViews.Toggle
    private lateinit var navigationChoice: DesktopSetupViews.Toggle
    private lateinit var panelControlHint: TextView
    private lateinit var densityHint: TextView
    private lateinit var dpiAppsButton: Button
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
        manualAdbOpen = savedInstanceState?.getBoolean("manual_adb_open") == true
        buildView()
        openSettings(savedInstanceState?.getBoolean("settings_open") == true)
        if (savedInstanceState != null) {
            val page = if (settingsPage.visibility == View.VISIBLE) settingsPage else homePage
            val y = savedInstanceState.getInt("page_scroll")
            page.post { page.scrollTo(0, y) }
        }
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
        registerReceiver(profilePackageChange, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
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
        unregisterReceiver(profilePackageChange)
        visible = false
        main.removeCallbacks(refresh)
        cancelContinuation()
        code.text.clear()
        phonePairCode.text.clear()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        selectedPanel = DesktopComponents.selected(this)
        renderAccessControls()
        navigationChoice.control.isChecked = clusterPrefs.metaNavi
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
        val settingsOpen = settingsPage.visibility == View.VISIBLE
        outState.putBoolean("settings_open", settingsOpen)
        outState.putInt("page_scroll", if (settingsOpen) settingsPage.scrollY else homePage.scrollY)
        outState.putBoolean("manual_adb_open", manualAdbOpen)
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
        val frame = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        window.setDecorFitsSystemWindows(false)
        frame.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun page(content: LinearLayout): ScrollView = ScrollView(this).apply {
            isFillViewport = true
            content.setPadding(dp(16), dp(12), dp(16), dp(20))
            content.isFocusableInTouchMode = true
            addView(content)
            frame.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        val home = ui.column()
        val settings = ui.column()
        homePage = page(home)
        settingsPage = page(settings)
        setContentView(frame)

        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            home.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.text(header, "HeadSwap Desktop", 23f, true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.button(header, "Настройки") { openSettings(true) }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        val profile = ui.section(home)
        desktopChoice = ui.button(profile, "Профиль: $desktopProfileName ▾") {
            if (!accessChoiceLocked()) DesktopProfilePicker.show(this, prefs.desktopMode, { !accessChoiceLocked() }, ::selectDesktopProfile)
        }.apply { setTextColor(Color.WHITE) }
        desktopChoiceHint = ui.text(profile, "", 13f)
        val connection = ui.section(home, "Подключение к CarLife")
        title = ui.text(connection, "Готов к настройке", 22f, true)
        hint = ui.text(connection, "", 15f)
        accessHint = ui.text(connection, "", 13f)
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            connection.addView(this, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        connectButton = ui.button(connection, "Подключить") {
            // Honor the action the user saw, even if USB disconnected since the last refresh.
            if (connectionButtonState.stopsSession) stop() else beginConnect(explicit = true)
        }
        connectButtonHint = ui.text(connection, "Нажмите ещё раз, чтобы отменить подключение.", 13f).apply { visibility = View.GONE }
        cancelWaitingButton = ui.button(connection, "Остановить ожидание") { stop() }.apply { visibility = View.GONE }
        phoneScreenButton = ui.button(connection, "Выключить панель телефона") { togglePhoneScreen() }
        phoneCurtainButton = ui.button(connection, "Погасить экран телефона") {
            phoneIdle.showNow(manualCurtain = true)
        }
        phoneScreenHint = ui.text(connection, "", 13f)
        val activation = ui.section(home, "Активация")
        accessChoice = ui.button(activation, "") { chooseAccessMode() }
        accessChangeHint = ui.text(activation, "", 13f)
        phoneActivationSection = ui.group(activation)

        phoneActivationButton = ui.button(phoneActivationSection, "Активировать с телефона") { beginPhoneActivation() }
        phoneActivationHint = ui.text(phoneActivationSection, "", 14f)
        phoneActivationDetails = ui.group(phoneActivationSection).apply { visibility = View.GONE }
        ui.text(phoneActivationDetails,
            "Подключитесь к Wi-Fi и включите «Беспроводную отладку» в настройках разработчика. После активации Wi-Fi можно отключить: изображение и звук идут по USB.", 14f)
        ui.button(phoneActivationDetails, "Открыть настройки отладки") { openDebugSettings() }
        phonePairSection = ui.group(phoneActivationDetails).apply { visibility = View.GONE }
        ui.text(phonePairSection,
            "Первый раз выберите «Подключить устройство с помощью кода сопряжения». Оставьте Настройки и HeadSwap в разделённом экране и введите код. Адрес и порт найдутся автоматически.", 14f)
        phonePairCode = field(phonePairSection, "Код из 6 цифр", "", 1005, secret = true)
        ui.button(phonePairSection, "Сопрячь и активировать") { beginPhonePair() }.also(inputViews::add)
        ui.button(phoneActivationDetails, "Сопрячь заново") {
            if (!phoneLaunchCommitted && !workerBusy.get()) {
                cancelContinuation()
                phoneSetupOpen = true
                phonePairOpen = true
                showPhoneActivationSetup()
            }
        }
        ui.button(phoneActivationSection, "Активация с компьютера") { showComputerActivationHelp() }
        shizukuSetup = ui.group(activation).apply { visibility = View.GONE }

        shizukuHelp = ui.text(shizukuSetup, "Запустите Shizuku и один раз разрешите доступ для HeadSwap Desktop. Пока Shizuku работает, достаточно подключить USB. После перезагрузки телефона службу нужно запустить снова.")
        shizukuManagerButton = ui.button(shizukuSetup, "Открыть Shizuku") { openShizuku() }

        setup = ui.group(activation)
        ui.text(setup, "Сопряжение DeX по ADB", 16f, true)
        ui.text(setup, "Включите Wi-Fi и беспроводную отладку. Откройте Настройки и HeadSwap Desktop в разделённом экране, затем выберите в отладке «Подключить устройство с помощью кода сопряжения».")
        ui.text(setup, "Оставьте окно с кодом открытым до завершения. Здесь нужен только код — приложение найдёт телефон само.", 14f, true)
        ui.button(setup, "Открыть настройки отладки") { withNetworkPermission { openDebugSettings() } }
        code = field(setup, "Код из 6 цифр", "", 1003, secret = true)
        ui.button(setup, "Сопрячь и подключить") { beginPair(manual = false) }.also(inputViews::add)

        adbOptions = ui.button(activation, "Ввести адрес и порты вручную") {
            manualAdbOpen = !manualAdbOpen
            renderAccessControls()
        }
        adbAdvanced = ui.group(activation)
        ui.text(adbAdvanced, "Обычно эти поля не нужны. Здесь можно вручную указать данные этого телефона из настроек отладки.", 13f)
        host = field(adbAdvanced, "IP этого телефона", prefs.host, 1004, address = true)
        pairPort = field(adbAdvanced, "Порт из окна с кодом", "", 1002).apply { isSaveEnabled = false }
        connectPort = field(adbAdvanced, "Порт из основного окна отладки", prefs.connectPort.takeIf { it > 0 }?.toString().orEmpty(), 1001)
        ui.button(adbAdvanced, "Проверить вручную") {
            val endpoint = manualEndpoint(connectPort) ?: return@button
            withNetworkPermission(endpoint.host) {
                if (!canBeginWork()) return@withNetworkPermission
                prepareAttempt(explicit = true)
                execute(flow.beginConnect(manual = endpoint))
            }
        }.also(inputViews::add)
        ui.button(adbAdvanced, "Сопряжение с указанным портом") {
            setup.visibility = View.VISIBLE
            beginPair(manual = true)
        }.also(inputViews::add)
        ui.button(adbAdvanced, "Настроить сопряжение заново") {
            cancelContinuation()
            showSetup()
        }.also(inputViews::add)
        ui.button(adbAdvanced, "Открыть настройки отладки") { withNetworkPermission { openDebugSettings() } }

        computerAccessStatus = ui.text(activation, "", 13f)
        finishComputerAccess = ui.button(activation, "Завершить доступ и закрыть HeadSwap") {
            if (!accessChoiceLocked() && DelegatedDesktopAccess.isReady(this)) DelegatedDesktopAccess.requestStop()
        }
        detail = ui.text(adbAdvanced, "", 12f).apply { setTextIsSelectable(true) }
        watch(host) { hostEdited = true }
        watch(pairPort) { pairPortEdited = true }
        watch(connectPort) { connectPortEdited = true }
        setup.visibility = if (prefs.useDesktopService || prefs.hasPaired) View.GONE else View.VISIBLE

        val settingsHeader = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            settings.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.button(settingsHeader, "Назад") { openSettings(false) }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        ui.text(settingsHeader, "Настройки", 24f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        buildDisplaySettings(settings)
        buildOutputSettings(settings)
        val app = ui.section(settings, "Приложение")
        ui.button(app, "Компоненты HeadSwap Desktop") { startActivity(Intent(this, com.headswap.desk.update.ComponentUpdatesActivity::class.java)) }
        ui.button(app, "Отправить журнал") { LogShare.shareLog(this) }
        renderAccessControls()
    }

    private fun buildDisplaySettings(settings: LinearLayout) {
        val imageSettings = ui.section(settings, "Изображение на магнитоле")
        resolutionChoice = ui.button(imageSettings, "") {
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
        densityChoice = ui.button(imageSettings, "") {
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
        fpsChoice = ui.button(imageSettings, "") {
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
        densityHint = ui.text(imageSettings, "", 13f)
        ui.text(imageSettings, "Выбранный DPI задаёт размер интерфейса на магнитоле. Масштаб экрана телефона сохраняется.", 13f)
        dpiAppsButton = ui.button(imageSettings, "Исправить DPI приложения") {
            startActivity(Intent(this, DpiCorrectionActivity::class.java))
        }
    }

    private fun buildOutputSettings(settings: LinearLayout) {
        audioSettings = ui.section(settings, "Звук")
        usbAudioChoice = ui.toggle(audioSettings, "Передавать звук по USB", prefs.usbPlaybackAudio) { checked ->
            if (!applyingAccessChoice && !accessChoiceLocked() && prefs.useDelegated) {
                prefs.usbPlaybackAudio = checked
                renderAccessControls()
            }
        }
        usbAudioHint = ui.text(audioSettings, "", 13f)
        phoneSettings = ui.section(settings, "Экран телефона")
        panelControlChoice = ui.toggle(phoneSettings, "Управление экраном телефона", prefs.desktopPanelControl) { checked ->
            if (!applyingAccessChoice && !accessChoiceLocked()) {
                prefs.desktopPanelControl = checked
                phoneIdle.refresh()
                renderAccessControls()
            }
        }
        phoneControlDetails = ui.group(phoneSettings)
        panelControlHint = ui.text(phoneControlDetails, "", 13f)
        idleModeChoice = ui.button(phoneControlDetails, "Способ отключения экрана") {
            val modes = listOf(PhoneIdleMode.PANEL, PhoneIdleMode.CURTAIN)
            android.app.AlertDialog.Builder(this).setTitle("Способ отключения экрана телефона")
                .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(prefs.phoneScreenMode)) { dialog, index ->
                    prefs.phoneScreenMode = modes[index]
                    dialog.dismiss()
                    phoneIdle.refresh()
                    renderAccessControls()
                }.setNegativeButton("Отмена", null).show()
        }
        idleTimeoutChoice = ui.button(phoneControlDetails, "Гасить через ▾") {
            val choices = PhoneIdleTimeout.entries
            android.app.AlertDialog.Builder(this).setTitle("Гасить экран при подключении к CarLife")
                .setSingleChoiceItems((choices.map { it.label } + "Без таймера").toTypedArray(),
                    if (prefs.phoneTimerEnabled) prefs.phoneIdleTimeout.ordinal else choices.size) { dialog, index ->
                    prefs.phoneTimerEnabled = index < choices.size
                    if (index < choices.size) prefs.phoneIdleTimeout = choices[index]
                    dialog.dismiss()
                    phoneIdle.refresh()
                    renderPhoneScreenControl()
                }.setNegativeButton("Отмена", null).show()
        }
        idleModeHint = ui.text(phoneControlDetails, "", 13f)
        val hud = ui.section(settings, "Приборная панель и HUD")
        navigationChoice = ui.toggle(hud, "Подсказки навигации на приборной панели и HUD", clusterPrefs.metaNavi) {
            clusterPrefs.metaNavi = it
        }
        ui.button(hud, "Настройка уведомлений приложений") {
            openNotificationSettings(Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS),
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
                "Откройте настройки уведомлений приложений на телефоне и разрешите уведомления навигатору.")
        }
        ui.button(hud, "Доступ HeadSwap к уведомлениям") {
            val component = android.content.ComponentName(this, com.headswap.desk.cluster.ClusterNavigationListener::class.java)
            val details = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            openNotificationSettings(details, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                "Откройте в настройках телефона раздел «Доступ к уведомлениям» и выберите HeadSwap.")
        }
        ui.text(hud, "Разрешите HeadSwap доступ к уведомлениям и начните ведение по маршруту. На Android 16 и новее HeadSwap также может взять поворот и расстояние из карточки Навигатора или Карт в открытом окне ГУ. Подсказки отображаются, если магнитола подписана на навигацию.", 13f)
    }

    private fun openNotificationSettings(primary: Intent, fallback: String, instructions: String) {
        for (intent in listOf(primary, Intent(fallback))) {
            try {
                startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Some vendor Settings packages omit the more specific screen.
            } catch (_: SecurityException) {
                // The OEM may also expose a screen that only system apps can start.
            }
        }
        android.app.AlertDialog.Builder(this).setTitle("Настройки уведомлений").setMessage(instructions)
            .setPositiveButton("Закрыть", null).show()
    }

    private fun openSettings(open: Boolean) {
        homePage.visibility = if (open) View.GONE else View.VISIBLE
        settingsPage.visibility = if (open) View.VISIBLE else View.GONE
        setTitle(if (open) "Настройки" else "HeadSwap Desktop")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsPage.visibility == View.VISIBLE) openSettings(false) else super.onBackPressed()
    }

    private fun field(parent: LinearLayout, label: String, initial: String, id: Int, secret: Boolean = false, address: Boolean = false) = EditText(this).apply {
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


    private fun accessLabel(mode: DesktopAccessMode): String = when (mode) {
        DesktopAccessMode.COMPUTER -> "Доступ HeadSwap"
        DesktopAccessMode.SHIZUKU -> "Shizuku"
        DesktopAccessMode.LEGACY_ADB -> "Встроенный ADB"
    }

    private fun chooseAccessMode() {
        if (accessChoiceLocked()) return
        val modes = DesktopAccessMode.optionsFor(prefs.desktopMode)
        android.app.AlertDialog.Builder(this).setTitle("Способ активации")
            .setSingleChoiceItems(modes.map(::accessLabel).toTypedArray(), modes.indexOf(prefs.accessMode)) { dialog, index ->
                dialog.dismiss()
                if (accessChoiceLocked() || modes[index] == prefs.accessMode) return@setSingleChoiceItems
                cancelContinuation()
                prefs.accessMode = modes[index]
                manualAdbOpen = false
                phoneSetupOpen = false
                phonePairOpen = false
                renderAccessControls()
                showAccessSetup()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun selectDesktopProfile(mode: Int, entry: DesktopComponentEntry?, mediaAllowed: Boolean) {
        if (accessChoiceLocked()) return
        if (entry == null) DesktopComponents.clear(this)
        else if (!DesktopComponents.select(this, entry, mediaAllowed)) {
            AlertDialog.Builder(this).setTitle("Компонент изменился")
                .setMessage("Откройте список профилей заново и проверьте пакет и подпись установленного приложения.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        selectedPanel = entry
        cancelContinuation()
        prefs.desktopMode = mode
        manualAdbOpen = false
        phoneSetupOpen = false
        phonePairOpen = false
        renderAccessControls()
        showAccessSetup()
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
        if (DelegatedDesktopAccess.isReady(this)) readyWithDesktop() else {
            showDelegatedSetup()
            if (explicit && homePage.visibility == View.VISIBLE) {
                phoneActivationButton.post { phoneActivationButton.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, phoneActivationButton.width, phoneActivationButton.height), false) }
            }
        }
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
            showState("Нужна активация", "Активируйте HeadSwap, затем подключите телефон по USB.")
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
            .setMessage("Подключите телефон к компьютеру по USB, разрешите USB-отладку и выполните команду из инструкции в комплекте HeadSwap. Wi-Fi не нужен.\n\nДля звука используйте --audio. Для управления панелью используйте --screen. Это временно разрешает управление панелью и чтение времени активности экрана из системной диагностики. Сначала включите нужные опции в настройках.")
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
        val desktop = prefs.useDesktopService
        val enabled = !accessChoiceLocked()
        val computerReady = prefs.useDelegated && DelegatedDesktopAccess.isReady(this)
        accessHint.updateText(when {
            computerReady && !DelegatedDesktopInstrumentation.keyboardRoutingReady -> "HeadSwap ожидает клавиатуру для экрана магнитолы."
            desktop -> "USB CarLife · ${prefs.desktopResolutionLabel}"
            else -> "USB CarLife · DeX по ADB · 1280 × 720"
        })
        if (desktop) setup.visibility = View.GONE else shizukuSetup.visibility = View.GONE
        adbOptions.visibility = if (desktop) View.GONE else View.VISIBLE
        adbAdvanced.visibility = if (!desktop && manualAdbOpen) View.VISIBLE else View.GONE
        adbOptions.updateText(if (manualAdbOpen) "Скрыть ручной ввод" else "Ввести адрес и порты вручную")
        desktopChoice.updateText("Профиль: $desktopProfileName ▾")
        desktopChoice.isEnabled = enabled
        desktopChoiceHint.updateText(if (!enabled) "Для смены профиля остановите подключение."
            else when (prefs.desktopMode) {
                DesktopModePolicy.DRIVEDECK -> "Карта и плеер рядом на экране магнитолы."
                DesktopModePolicy.SAMSUNG_DEX -> "Рабочий стол Samsung на экране магнитолы."
                else -> "Панель приложений и рабочий стол Android на магнитоле."
            })
        accessChoice.updateText("Способ: ${accessLabel(prefs.accessMode)} ▾")
        accessChoice.isEnabled = enabled
        accessChangeHint.visibility = if (enabled) View.GONE else View.VISIBLE
        accessChangeHint.updateText("Для смены способа активации остановите подключение.")
        computerAccessStatus.visibility = if (computerReady) View.VISIBLE else View.GONE
        computerAccessStatus.updateText("Доступ активен. Следующая активация потребуется после перезагрузки телефона или обновления HeadSwap.")
        finishComputerAccess.visibility = if (computerReady) View.VISIBLE else View.GONE
        finishComputerAccess.isEnabled = enabled && computerReady
        resolutionChoice.visibility = if (desktop) View.VISIBLE else View.GONE
        fpsChoice.visibility = resolutionChoice.visibility
        audioSettings.visibility = if (desktop) View.VISIBLE else View.GONE
        phoneSettings.visibility = if (desktop) View.VISIBLE else View.GONE
        densityChoice.isEnabled = enabled
        dpiAppsButton.visibility = if (desktop) View.VISIBLE else View.GONE
        resolutionChoice.isEnabled = enabled
        fpsChoice.isEnabled = enabled
        resolutionChoice.updateText("Разрешение: ${prefs.desktopResolutionLabel} ▾")
        densityChoice.updateText("Размер интерфейса: ${if (prefs.desktopDensity == 0) "авто" else "${prefs.desktopDensity} DPI"} ▾")
        fpsChoice.updateText("Частота кадров: ${prefs.desktopFps.fixed?.let { "$it кадров/с" } ?: "авто"} ▾")
        densityHint.updateText(when {
            !enabled -> "Остановите подключение, чтобы изменить изображение."
            !desktop -> "DeX по ADB использует 1280 × 720. Выбранный DPI применяется при следующем подключении."
            else -> "Изменения вступят в силу при следующем подключении. «Авто» подбирает DPI по разрешению."
        })
        applyingAccessChoice = true
        try { usbAudioChoice.control.isChecked = prefs.usbPlaybackAudio } finally { applyingAccessChoice = false }
        usbAudioChoice.setEnabled(enabled && prefs.useDelegated)
        usbAudioHint.updateText(when {
            !prefs.useDelegated -> "USB-звук доступен в режиме «Доступ HeadSwap». Здесь можно оставить звук через Bluetooth автомобиля."
            !prefs.usbPlaybackAudio -> "Звук остаётся на обычном выходе телефона, например Bluetooth автомобиля."
            !DesktopAudioAccess.hasPermission(this) -> "USB-звук выбран. Активируйте доступ, чтобы передавать музыку и подсказки телефона в CarLife."
            else -> DesktopAudioState.status
        })
        applyingAccessChoice = true
        try { panelControlChoice.control.isChecked = prefs.desktopPanelControl } finally { applyingAccessChoice = false }
        panelControlChoice.setEnabled(enabled)
        phoneControlDetails.visibility = if (prefs.desktopPanelControl) View.VISIBLE else View.GONE
        panelControlHint.visibility = panelControlChoice.row.visibility
        panelControlHint.updateText(when {
            prefs.phoneScreenMode == PhoneIdleMode.CURTAIN -> "Оставьте HeadSwap открытым на телефоне. Двойное касание снимает затемнение."
            PhonePanelPolicy.prefersCurtain -> "На этой модели аппаратное выключение может погасить изображение на ГУ. Для сохранения изображения выберите «Затемнять экран»."
            DelegatedPanelAccess.unsupportedThisActivation -> PhoneActivationPolicy.PANEL_MESSAGE
            !DelegatedPanelAccess.isAvailable(this) -> "Для таймера и двойного касания повторите активацию на телефоне или с --screen на компьютере. HeadSwap запросит управление панелью и чтение времени активности экрана."
            else -> "Перед выключением панели HeadSwap открывает свой экран для двойного касания. Прежнее приложение телефона может приостановиться до пробуждения."
        })
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
        else "Для активации с телефона нужен Wi-Fi. После неё подключите USB к магнитоле.")
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
        val available = prefs.useDesktopService && BridgeService.alive && DexState.running && !stopped &&
            (prefs.desktopPanelControl || DexState.phoneScreenOff)
        val visibility = if (available) View.VISIBLE else View.GONE
        val mode = prefs.phoneScreenMode
        val canBlankPanel = available && prefs.desktopPanelControl && (prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.isAvailable(this))
        idleModeChoice.updateText("Способ отключения: ${mode.label} ▾")
        idleTimeoutChoice.updateText("Гасить через: ${if (prefs.phoneTimerEnabled) prefs.phoneIdleTimeout.label else "Без таймера"} ▾")
        idleModeHint.updateText(when {
            mode == PhoneIdleMode.CURTAIN -> "Затемняется окно HeadSwap на телефоне. Оставьте его открытым. Чтобы вернуть изображение, дважды коснитесь экрана."
            prefs.useDelegated && DelegatedPanelAccess.unsupportedThisActivation -> PhoneActivationPolicy.PANEL_MESSAGE
            prefs.useDelegated && !DelegatedPanelAccess.hasPermission(this) -> "Для аппаратного выключения панели повторите активацию с управлением экраном."
            else -> "Питание панели телефона отключается во время проекции. Двойное касание возвращает изображение."
        })
        phoneCurtainButton.visibility = if (available && prefs.desktopPanelControl && mode == PhoneIdleMode.CURTAIN && !DexState.phoneScreenOff) View.VISIBLE else View.GONE
        phoneCurtainButton.updateText("Затемнить экран телефона")
        phoneCurtainButton.isEnabled = desktopConnectionReady() && !DexState.phoneScreenBusy
        phoneScreenButton.visibility = if (DexState.phoneScreenOff || canBlankPanel && mode == PhoneIdleMode.PANEL) View.VISIBLE else View.GONE
        phoneScreenHint.visibility = visibility
        phoneScreenButton.updateText(when {
            DexState.phoneScreenBusy -> "Меняю состояние экрана…"
            DexState.phoneScreenOff -> "Включить экран телефона"
            else -> "Выключить панель телефона"
        })
        val enabled = (canBlankPanel || DexState.phoneScreenOff) && !DexState.phoneScreenBusy
        if (phoneScreenButton.isEnabled != enabled) phoneScreenButton.isEnabled = enabled
        phoneScreenHint.updateText(DexState.phoneScreenError ?: idleModeHint.text.toString())
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
            DexAccessReason.IDENTITY_FAILURE -> "Не удалось открыть сохранённый ключ. Отправьте журнал из настроек."
            DexAccessReason.PAIR_REJECTED -> "Откройте новое окно сопряжения и введите свежий код. Оставьте это окно открытым до завершения."
            else -> if (pairing) "Откройте новое окно с кодом в разделённом экране и повторите. Если ошибка останется, отправьте журнал." else "Проверьте, что беспроводная отладка включена, и нажмите «Подключить». Если телефон удалил сопряжение, настройте его заново в разделе «Активация»."
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
