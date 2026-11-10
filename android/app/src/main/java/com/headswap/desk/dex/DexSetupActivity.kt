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
import com.headswap.desk.BuildConfig
import com.headswap.desk.BridgeService
import com.headswap.desk.aa.AaPrefs
import com.headswap.desk.aa.DesktopResolution
import com.headswap.desk.CarLifeState
import com.headswap.desk.component.DesktopComponentEntry
import com.headswap.desk.component.DesktopComponents
import com.headswap.desk.MainActivity
import com.headswap.desk.ui.Actions
import com.headswap.desk.link.car.CarLifeConnectionMode
import com.headswap.desk.link.car.WirelessPrefs
import com.headswap.desk.link.car.WirelessCarLife
import com.headswap.desk.link.car.WirelessSetupActivity
import com.headswap.desk.ui.LogShare
import com.headswap.desk.update.ComponentPackages
import com.headswap.desk.update.ComponentUpdatesActivity
import com.headswap.desk.update.DesktopComponent
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
    private lateinit var installedVersions: TextView
    private var driveDeckInstalled = false
    private lateinit var installProfileButton: Button
    private lateinit var connectionModeChoice: Button
    private lateinit var wirelessSetupButton: Button
    private var pairingPermissionAction: (() -> Unit)? = null
    private lateinit var initialSetupSection: LinearLayout
    private lateinit var activationStatus: TextView
    private lateinit var activationStatusHint: TextView
    private lateinit var activationProgress: ProgressBar
    private lateinit var initialSetupIntro: LinearLayout
    private lateinit var setupAccessButton: Button
    private lateinit var setupDoneButton: Button
    private var setupRequested = false
    private var phoneReplyAttempt: String? = null
    private lateinit var connectButton: Button
    private lateinit var connectButtonHint: TextView
    private lateinit var phoneActivationSection: LinearLayout
    private lateinit var computerActivationSection: LinearLayout
    private lateinit var computerActivationInstructions: TextView
    private lateinit var phoneActivationButton: Button
    private lateinit var cancelPhoneActivationButton: Button
    private lateinit var phonePairSection: LinearLayout
    private lateinit var phonePairCode: EditText
    private lateinit var phoneActivationHint: TextView
    private lateinit var activationRepairSection: LinearLayout
    private lateinit var activationRepairHint: TextView
    private lateinit var activationRepairButton: Button
    private var phoneLaunchCommitted = false
    private var endingComputerAccess = false
    private var restoringDebugMode = false
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
            if (DesktopComponent.entries.any { it.pkg == intent.data?.schemeSpecificPart }) renderInstalledVersions()
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
    private lateinit var settingsNavigator: DesktopSettingsNavigator
    private lateinit var activationAccessRow: DesktopSetupViews.PermissionRow
    private lateinit var notificationAccessRow: DesktopSetupViews.PermissionRow
    private lateinit var overlayAccessRow: DesktopSetupViews.PermissionRow
    private lateinit var batteryAccessRow: DesktopSetupViews.PermissionRow
    private lateinit var notificationDeliveryRow: DesktopSetupViews.PermissionRow
    private lateinit var noticesHint: TextView
    private lateinit var autoMusicChoice: DesktopSetupViews.Toggle
    private lateinit var imageSettings: LinearLayout
    private lateinit var audioSettings: LinearLayout
    private lateinit var phoneSettings: LinearLayout
    private lateinit var phoneControlDetails: LinearLayout
    private lateinit var accessChoice: Button
    private lateinit var adbOptions: Button
    private var manualAdbOpen = false
    private lateinit var adbAdvanced: LinearLayout
    private lateinit var finishComputerAccess: Button
    private lateinit var accessHint: TextView
    private lateinit var accessChangeHint: TextView
    private lateinit var densityChoice: Button
    private lateinit var resolutionChoice: Button
    private lateinit var fpsChoice: Button
    private lateinit var videoTuningHint: TextView
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
    private var launchInitialSetup = false
    private var activationStopPending = false
    private var setupResumeScheduled = false

    companion object {
        private const val REQUEST_SETTINGS_NOTIFICATIONS = 1307

        const val EXTRA_SHOW_ACTIVATION = "com.headswap.desk.SHOW_ACTIVATION"
        const val EXTRA_COMPUTER_ACTIVATION = "com.headswap.desk.COMPUTER_ACTIVATION"
        const val EXTRA_SHOW_PERMISSIONS = "com.headswap.desk.SHOW_PERMISSIONS"
        const val EXTRA_RETURN_TO_SETUP = "com.headswap.desk.RETURN_TO_SETUP"
        private const val REQUEST_NETWORK = 2
        private const val REQUEST_PAIRING_NOTIFICATIONS = 21
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
            if (activationStopPending || permissionAction != null) {
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            if (observePhoneActivation()) {
                main.postDelayed(this, 500)
                return
            }
            if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) {
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            if (!WirelessCarLife.preferUsb(this@DexSetupActivity)) {
                continuePendingConnection()
                when {
                    stopped -> {
                        observedStop = true
                        cancelContinuation()
                        showState("Остановлено", "Нажмите «Подключить», чтобы начать беспроводное соединение.")
                    }
                    desktopConnectionReady() -> showState("$desktopName подключён", "Изображение передаётся в CarLife. ${DesktopAudioState.status}" + lockIsolationHint())
                    BridgeService.alive -> showState("Подключение по Wi-Fi…", CarLifeState.status, busy = true)
                    workerBusy.get() || permissionAction != null || shizukuPermissionToken != null ||
                        flow.phase in setOf(DexSetupFlow.Phase.FIND_PAIRING, DexSetupFlow.Phase.PAIRING,
                            DexSetupFlow.Phase.FIND_CONNECT, DexSetupFlow.Phase.PROBING, DexSetupFlow.Phase.ACTIVATING) -> Unit
                    !DesktopAccess.isReadyForProjection(this@DexSetupActivity) -> showAccessSetup()
                    else -> { armed = false; monitorBridge = false; showState("Готов к Wi-Fi CarLife", CarLifeState.status) }
                }
                renderAccessControls()
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
            if (WirelessPrefs(this@DexSetupActivity).mode == CarLifeConnectionMode.USB && Actions.isUsbDetachedIdle()) {
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
            continuePendingConnection()
            if (!stopped && restartAt == 0L && (monitorBridge || BridgeService.alive)) {
                monitorBridge = true
                when {
                    DexState.error != null -> {
                        lastError = DexState.error.orEmpty()
                        armed = false
                        showState("Рабочий стол не запустился", "$lastError\n\nНажмите «Подключить», чтобы повторить. Если ошибка повторится, отправьте журнал.")
                    }
                    desktopConnectionReady() -> showState("$desktopName подключён",
                        "Изображение рабочего стола передаётся в CarLife. " + (if (prefs.usbPlaybackAudio)
                            DesktopAudioState.status else "Звук остаётся на обычном выходе телефона.") + lockIsolationHint())
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
        launchInitialSetup = savedInstanceState == null && prefs.initialSetupAutoPending &&
            !prefs.initialSetupCompleted && !intent.getBooleanExtra(EXTRA_SHOW_ACTIVATION, false) &&
            !intent.getBooleanExtra(EXTRA_SHOW_PERMISSIONS, false) &&
            !intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)
        prefs.enabled = true
        setupRequested = savedInstanceState?.getBoolean("setup_requested") == true || intent.getBooleanExtra(EXTRA_SHOW_ACTIVATION, false)
        manualAdbOpen = savedInstanceState?.getBoolean("manual_adb_open") == true
        buildView()
        openSettings(savedInstanceState?.getBoolean("settings_open") == true)
        settingsNavigator.restore(savedInstanceState)
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_SHOW_PERMISSIONS, false)) openPermissionSettings()
            else if (setupRequested) openActivationSetup()
        }
        if (savedInstanceState != null && homePage.visibility == View.VISIBLE) {
            val y = savedInstanceState.getInt("page_scroll")
            homePage.post { homePage.scrollTo(0, y) }
        }
        if (intent.getBooleanExtra(EXTRA_COMPUTER_ACTIVATION, false)) homePage.post { showComputerActivationHelp() }
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

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_ACTIVATION, false)) {
            setupRequested = true
            openActivationSetup()
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_PERMISSIONS, false)) openPermissionSettings()
        if (intent.getBooleanExtra(EXTRA_COMPUTER_ACTIVATION, false)) showComputerActivationHelp()
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED && visible) usbAttached()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(profilePackageChange, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        visible = true
        if (launchInitialSetup) {
            launchInitialSetup = false
            startActivity(Intent(this, DesktopOnboardingActivity::class.java))
            return
        }
        Actions.observeUsbConnection(this)
        main.post(refresh)
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false) && !setupRequested) return
        if (observePhoneActivation(force = true)) return
        when {
            setupRequested && prefs.useDelegated && !DelegatedDesktopAccess.isReady(this) -> showDelegatedSetup()
            stopped -> showState("Остановлено", "Подключите USB заново или нажмите «Подключить».")
            WirelessPrefs(this).mode == CarLifeConnectionMode.USB && Actions.isUsbDetachedIdle() -> showUsbDetached()
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
        activationStopPending = false
        main.removeCallbacks(refresh)
        cancelContinuation()
        code.text.clear()
        phonePairCode.text.clear()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        selectedPanel = DesktopComponents.selected(this)
        renderInstalledVersions()
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
        settingsNavigator.save(outState)
        outState.putInt("page_scroll", if (settingsOpen) settingsPage.scrollY else homePage.scrollY)
        outState.putBoolean("manual_adb_open", manualAdbOpen)
        outState.putBoolean("setup_requested", setupRequested)
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
        ui.brand(header, 48)
        ui.text(header, "HeadSwap Desktop", 23f, true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.button(header, "Настройки") { openSettings(true) }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        installedVersions = ui.text(home, "", 12f)
        renderInstalledVersions()
        initialSetupSection = ui.section(home)
        ui.text(initialSetupSection, "Первоначальная настройка", 18f, true)
        initialSetupIntro = ui.group(initialSetupSection)
        ui.text(initialSetupIntro, "Три коротких шага: DriveDeck, разрешения и активация. Пройдите настройку перед первым подключением к магнитоле.", 14f)
        ui.button(initialSetupIntro, "Настроить приложение") {
            startActivity(Intent(this, DesktopOnboardingActivity::class.java))
        }
        settingsNavigator = DesktopSettingsNavigator(ui, settingsPage, settings) {
            if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) finish() else openSettings(false)
        }
        val activation = settingsNavigator.subpage("activation", "Активация", "")
        activationStatus = ui.text(activation, "", 20f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        activationStatusHint = ui.text(activation, "", 14f)
        activationProgress = ProgressBar(this).apply {
            visibility = View.GONE
            activation.addView(this, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        val connection = ui.section(home, "Подключение")
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
        phoneScreenButton = ui.button(connection, "Выключить панель телефона") { togglePhoneScreen() }
        phoneCurtainButton = ui.button(connection, "Погасить экран телефона") {
            phoneIdle.showNow(manualCurtain = true)
        }
        phoneScreenHint = ui.text(connection, "", 13f)
        setupAccessButton = ui.button(connection, "Активировать") { openActivationSetup() }

        val profile = ui.section(home, "Профиль")
        desktopChoice = ui.button(profile, "Профиль: $desktopProfileName ▾") {
            if (!accessChoiceLocked()) DesktopProfilePicker.show(this, prefs.desktopMode, { !accessChoiceLocked() }, ::selectDesktopProfile)
        }.apply { setTextColor(Color.WHITE) }
        desktopChoiceHint = ui.text(profile, "", 13f)
        installProfileButton = ui.button(profile, "Установить DriveDeck · HeadSwap") {
            startActivity(Intent(this, ComponentUpdatesActivity::class.java))
        }
        val transport = ui.section(home, "Тип подключения")
        connectionModeChoice = ui.button(transport, "") {
            if (!accessChoiceLocked()) {
                val wireless = WirelessPrefs(this)
                val choices = CarLifeConnectionMode.entries.toTypedArray()
                AlertDialog.Builder(this).setTitle("Тип подключения")
                    .setSingleChoiceItems(arrayOf("USB", "Wi-Fi", "Автоматически"), choices.indexOf(wireless.mode)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) wireless.mode = choices[index]
                        renderAccessControls()
                    }.setNegativeButton("Отмена", null).show()
            }
        }
        wirelessSetupButton = ui.button(transport, "Настроить беспроводную магнитолу") {
            startActivity(Intent(this, WirelessSetupActivity::class.java))
        }
        ui.text(transport, "Автоматически: USB при подключённом кабеле, иначе настроенная беспроводная магнитола.", 13f)
        accessChoice = ui.button(activation, "") { chooseAccessMode() }
        accessChangeHint = ui.text(activation, "", 13f)
        phoneActivationSection = ui.group(activation)
        ui.text(phoneActivationSection,
            "Подключите телефон к Wi-Fi и включите «Беспроводную отладку» в параметрах разработчика. При первом запуске откройте «Подключить устройство с помощью кода сопряжения». Введите 6 цифр здесь или в уведомлении HeadSwap; окно с кодом оставьте открытым.", 14f)
        phoneActivationHint = ui.text(phoneActivationSection, "", 14f)
        phonePairSection = ui.group(phoneActivationSection).apply { visibility = View.GONE }
        ui.text(phonePairSection, "Код показывает Android: «Беспроводная отладка» → «Подключить устройство с помощью кода сопряжения». Оставьте это окно открытым и ответьте в уведомлении HeadSwap. Для ввода здесь используйте разделённый экран. Одного подключения к Wi-Fi недостаточно.", 13f)
        phonePairCode = field(phonePairSection, "Код из 6 цифр", "", 1005, secret = true)
        phoneActivationButton = ui.button(phoneActivationSection, "Активировать") {
            if (PhonePairingService.replyAttempt() != null) beginPhonePair() else beginPhoneActivation()
        }
        cancelPhoneActivationButton = ui.button(phoneActivationSection, "Отменить активацию") {
            PhonePairingService.snapshot.attempt?.let { PhonePairingService.cancel(this, it) }
            renderAccessControls()
        }
        computerActivationSection = ui.group(activation)
        computerActivationInstructions = ui.text(computerActivationSection, "", 14f).apply { setTextIsSelectable(true) }
        ui.text(computerActivationSection,
            "Команды выдают доступ приложению. На Android 17 для автоматического восстановления после переподключения USB дополнительно нужна активация способом Wi-Fi: ключ компьютера не заменяет сопряжение самого HeadSwap.", 13f)
        ui.button(computerActivationSection, "Скачать Android Platform Tools") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DesktopActivationGuide.PLATFORM_TOOLS_URL))) }
                .onFailure { android.widget.Toast.makeText(this, "Откройте developer.android.com/tools/releases/platform-tools", android.widget.Toast.LENGTH_LONG).show() }
        }
        shizukuSetup = ui.group(activation).apply { visibility = View.GONE }

        shizukuHelp = ui.text(shizukuSetup, "Запустите Shizuku и один раз разрешите доступ для HeadSwap Desktop. Пока Shizuku работает, подключайте CarLife выбранным способом. После перезагрузки телефона службу нужно запустить снова.")
        shizukuManagerButton = ui.button(shizukuSetup, "Открыть Shizuku") { openShizuku() }
        ui.button(shizukuSetup, "Разрешить доступ Shizuku") { beginShizuku(explicit = true) }

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

        setupDoneButton = ui.button(activation, "На главный экран") {
            if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) {
                finish()
                return@button
            }
            setupRequested = false
            openSettings(false)
            renderAccessControls()
        }
        finishComputerAccess = ui.button(activation, "Завершить доступ и закрыть HeadSwap") {
            finishActivationForBackendChange()
        }
        activationRepairSection = ui.section(activation, "Настройка активации не завершена").apply { visibility = View.GONE }
        activationRepairHint = ui.text(activationRepairSection, "")
        activationRepairButton = ui.button(activationRepairSection, "Исправить настройку активации") { confirmRestoreDebugMode() }
        detail = ui.text(adbAdvanced, "", 12f).apply { setTextIsSelectable(true) }
        watch(host) { hostEdited = true }
        watch(pairPort) { pairPortEdited = true }
        watch(connectPort) { connectPortEdited = true }
        setup.visibility = if (prefs.useDesktopService || prefs.hasPaired) View.GONE else View.VISIBLE

        buildDisplaySettings()
        buildOutputSettings()
        buildPermissionSettings()
        val components = settingsNavigator.block("Компоненты HeadSwap Desktop")
        ui.button(components, "Установить и обновить компоненты") {
            startActivity(Intent(this, com.headswap.desk.update.ComponentUpdatesActivity::class.java))
        }
        val maintenance = settingsNavigator.block("Обслуживание")
        ui.button(maintenance, "Повторить заново первоначальную настройку") {
            prefs.initialSetupInProgress = false
            setupResumeScheduled = false
            startActivity(Intent(this, DesktopOnboardingActivity::class.java))
        }
        ui.button(maintenance, "Отправить журнал") { LogShare.shareLog(this) }
        renderAccessControls()
    }

    private fun buildDisplaySettings() {
        imageSettings = settingsNavigator.block("Параметры изображения")
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
                val values = (listOf(1, 2, 4, 6, 8, 12, 16, 20) + prefs.desktopBitrateMbps).distinct().sorted()
                AlertDialog.Builder(this).setTitle("Скорость видеопотока")
                    .setSingleChoiceItems((values.map { "$it Мбит/с" } + "Своя скорость…").toTypedArray(),
                        values.indexOf(prefs.desktopBitrateMbps)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) {
                            if (index == values.size) showCustomBitrate()
                            else prefs.desktopBitrateMbps = values[index]
                        }
                        renderAccessControls()
                    }.setNegativeButton("Отмена", null).show()
            }
        }
        videoTuningHint = ui.text(imageSettings, "", 13f)
        densityHint = ui.text(imageSettings, "", 13f)
        ui.text(imageSettings, "Выбранный DPI задаёт размер интерфейса на магнитоле. Масштаб экрана телефона сохраняется.", 13f)
        dpiAppsButton = ui.button(imageSettings, "Исправить DPI приложения") {
            startActivity(Intent(this, DpiCorrectionActivity::class.java))
        }
    }

    /** Local installed package versions only; never called from the periodic status renderer. */
    private fun renderInstalledVersions() {
        driveDeckInstalled = runCatching { ComponentPackages.trustedInstalled(this, DesktopComponent.DRIVEDECK) != null }.getOrDefault(false)
        val components = DesktopComponent.entries.mapNotNull { spec ->
            val info = try { ComponentPackages.installed(this, spec) }
                catch (_: SecurityException) { null }
            info?.let {
                val version = it.versionName?.takeIf(String::isNotBlank)?.let { name -> "$name (${it.longVersionCode})" }
                    ?: "код ${it.longVersionCode}"
                "${spec.title.substringBefore(" · ")} $version"
            }
        }
        installedVersions.updateText((listOf("Версия ${BuildConfig.VERSION_NAME}") + components).joinToString("  ·  "))
    }

    private fun buildOutputSettings() {
        audioSettings = settingsNavigator.block("Звук")
        usbAudioChoice = ui.toggle(audioSettings, "Передавать звук в CarLife", prefs.usbPlaybackAudio) { checked ->
            if (!applyingAccessChoice && !accessChoiceLocked() && prefs.useDelegated) {
                prefs.usbPlaybackAudio = checked
                renderAccessControls()
            }
        }
        usbAudioHint = ui.text(audioSettings, "", 13f)
        autoMusicChoice = ui.toggle(audioSettings, "Автоматически продолжать музыку при подключении", prefs.autoMusicPlayback) {
            prefs.autoMusicPlayback = it
        }
        ui.text(audioSettings, "В режиме «Доступ HeadSwap» музыка продолжается после готовности изображения и звука. Сначала откройте плеер и включите трек: функция возобновляет существующее воспроизведение. В режиме Shizuku автопродолжение пока недоступно.", 13f)
        phoneSettings = ui.group(imageSettings)
        panelControlChoice = ui.toggle(phoneSettings, "Управление дисплеем телефона", prefs.desktopPanelControl) { checked ->
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
        val notices = settingsNavigator.block("Уведомления на магнитоле")
        ui.toggle(notices, "Показывать шторку уведомлений", false) {}.setEnabled(false)
        noticesHint = ui.text(notices, "Шторка временно отключена — её интерфейс будет переработан.", 14f)
        val hud = settingsNavigator.block("Приборная панель и HUD")
        navigationChoice = ui.toggle(hud, "Подсказки навигации на приборной панели и HUD", clusterPrefs.metaNavi) {
            clusterPrefs.metaNavi = it
            renderSettingsOverview()
        }
        ui.button(hud, "Настройка уведомлений приложений") {
            openNotificationSettings(Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS),
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
                "Откройте настройки уведомлений приложений на телефоне и разрешите уведомления навигатору.")
        }
        ui.navigation(hud, "Доступ HeadSwap к уведомлениям", "Разрешение для подсказок навигации") { settingsNavigator.open("permissions") }
        ui.text(hud, "Разрешите HeadSwap доступ к уведомлениям и начните ведение по маршруту. На Android 16 и новее HeadSwap также может взять поворот и расстояние из карточки Навигатора или Карт в открытом окне ГУ. Подсказки отображаются, если магнитола подписана на навигацию.", 13f)
        ui.text(hud, "Название трека и доступная обложка передаются из медиасессии Android. Их отображение зависит от возможностей приборной панели.", 13f)
    }

    private fun buildPermissionSettings() {
        val permissions = settingsNavigator.subpage("permissions", "Разрешения приложения", "")
        ui.text(permissions, "Зелёная галочка — доступ есть, красный крестик — нет. Нажмите строку, чтобы настроить разрешение. Статус обновится после возврата.", 14f)
        activationAccessRow = ui.permission(permissions, "Доступ к рабочему столу") { openActivationSetup() }
        notificationAccessRow = ui.permission(permissions, "Доступ HeadSwap к уведомлениям") { openNotificationAccess() }
        overlayAccessRow = ui.permission(permissions, "Показ поверх приложений") { openShadeOverlayPermission() }
        notificationDeliveryRow = ui.permission(permissions, "Уведомления HeadSwap") {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_SETTINGS_NOTIFICATIONS)
            else openNotificationSettings(PhonePairingService.notificationSettings(this,
                PhonePairingService.notificationBlock(this, needsCode = true) ?: PairingNotificationBlock.APP),
                Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
                "Откройте настройки уведомлений HeadSwap на телефоне.")
        }
        batteryAccessRow = ui.permission(permissions, "Работа без ограничений батареи") {
            if (DesktopOnboardingActivity.batteryExempt(this))
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            else DesktopOnboardingActivity.requestBatteryExemption(this)
        }
    }

    private fun hasNotificationAccess(): Boolean = runCatching {
        getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(
            android.content.ComponentName(this, com.headswap.desk.cluster.ClusterNavigationListener::class.java))
    }.getOrDefault(false)

    private fun renderSettingsOverview() {
        if (!::settingsNavigator.isInitialized || settingsPage.visibility != View.VISIBLE) return
        val listener = hasNotificationAccess()
        val overlay = Settings.canDrawOverlays(this)
        val battery = DesktopOnboardingActivity.batteryExempt(this)
        val notificationBlock = PhonePairingService.notificationBlock(this, needsCode = true)
        noticesHint.updateText("Шторка временно отключена — её интерфейс будет переработан.")
        val missing = mutableListOf<String>()
        if (!DesktopAccess.isReady(this)) missing += "активацию приложения"
        if (clusterPrefs.metaNavi && !listener) missing += "доступ к уведомлениям"
        if (notificationBlock != null) missing += when (notificationBlock) {
            PairingNotificationBlock.CODE_CHANNEL -> "уведомление ввода кода"
            PairingNotificationBlock.PROGRESS_CHANNEL -> "уведомления активации"
            PairingNotificationBlock.APP -> "уведомления HeadSwap"
        }
        settingsNavigator.summary("permissions", when {
            missing.isNotEmpty() -> "Нужно настроить: ${missing.joinToString(", ")}"
            !battery -> "Разрешения выданы · можно снять ограничение батареи"
            else -> "Всё готово"
        })
        val access = DesktopAccess.isReady(this)
        activationAccessRow.update(access, if (access) "Права выданы" else "Нужна активация приложения")
        notificationAccessRow.update(listener, if (listener) "Разрешён · подсказки навигации" else "Не выдан · нужен для подсказок навигации")
        overlayAccessRow.update(overlay, if (overlay) "Разрешён · шторка сейчас отключена" else "Не выдан · для работы без шторки не требуется")
        notificationDeliveryRow.update(notificationBlock == null, when (notificationBlock) {
            null -> "Разрешены · состояние активации и ввод кода"
            PairingNotificationBlock.APP -> "Выключены · ввод кода в уведомлении недоступен"
            PairingNotificationBlock.PROGRESS_CHANNEL -> "Канал «Активация HeadSwap» выключен"
            PairingNotificationBlock.CODE_CHANNEL -> "Канал «Ввод кода сопряжения» выключен"
        })
        batteryAccessRow.update(battery, if (battery) "Без ограничений" else "Действует оптимизация Android")
    }

    private fun openShadeOverlayPermission() {
        AlertDialog.Builder(this).setTitle("Показ поверх приложений")
            .setMessage("Это разрешение было нужно для шторки уведомлений на магнитоле. Сейчас шторка отключена, поэтому выдавать доступ не требуется. Состояние разрешения можно изменить в настройках Android.")
            .setPositiveButton("Открыть разрешение") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: RuntimeException) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }.setNegativeButton("Позже", null).show()
    }

    private fun openNotificationAccess() {
        val component = android.content.ComponentName(this, com.headswap.desk.cluster.ClusterNavigationListener::class.java)
        openNotificationSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()),
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            "Откройте «Доступ к уведомлениям» в настройках телефона и выберите HeadSwap.")
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
        if (open && settingsPage.visibility != View.VISIBLE) settingsNavigator.open(null)
        homePage.visibility = if (open) View.GONE else View.VISIBLE
        settingsPage.visibility = if (open) View.VISIBLE else View.GONE
        setTitle(if (open) "Настройки" else "HeadSwap Desktop")
        if (open) renderSettingsOverview()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsPage.visibility == View.VISIBLE) settingsNavigator.back() else super.onBackPressed()
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
        DesktopAccessMode.LEGACY_ADB -> "Встроенный ADB (резервный)"
    }

    private fun chooseAccessMode() {
        if (accessChoiceLocked()) return
        val methods = ActivationMethod.entries
        AlertDialog.Builder(this).setTitle("Способ активации")
            .setSingleChoiceItems(methods.map { it.label }.toTypedArray(), methods.indexOf(prefs.activationMethod)) { dialog, index ->
                dialog.dismiss()
                val method = methods[index]
                if (accessChoiceLocked() || method == prefs.activationMethod) return@setSingleChoiceItems
                val backend = method.backend(prefs.desktopMode)
                if (backend == null) {
                    AlertDialog.Builder(this).setTitle("Samsung DeX")
                        .setMessage("Для этого профиля используйте Shizuku или сопряжение по Wi-Fi. Активация с компьютера доступна для Android Desktop и компонентов.")
                        .setPositiveButton("Понятно", null).show()
                    return@setSingleChoiceItems
                }
                if (requireBackendHandoff(backend)) return@setSingleChoiceItems
                cancelContinuation()
                prefs.activationMethod = method
                manualAdbOpen = false
                renderAccessControls()
                showAccessSetup()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun selectDesktopProfile(mode: Int, entry: DesktopComponentEntry?, mediaAllowed: Boolean) {
        if (accessChoiceLocked()) return
        if (requireBackendHandoff(prefs.accessModeFor(mode))) return
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
        renderAccessControls()
        showAccessSetup()
    }

    private fun requireBackendHandoff(next: DesktopAccessMode): Boolean {
        if (!DesktopBackendTransitionPolicy.requiresActivationEnd(DelegatedDesktopAccess.ownsActivation,
                prefs.accessMode, next)) return false
        showBackendHandoff(next)
        return true
    }

    private fun showBackendHandoff(next: DesktopAccessMode) {
        AlertDialog.Builder(this).setTitle("Переключение на ${accessLabel(next)}")
            .setMessage("Сначала завершите действующую активацию. HeadSwap закроется и освободит службу рабочего стола. Затем откройте приложение и повторите выбор.")
            .setPositiveButton("Завершить доступ и закрыть") { _, _ -> finishActivationForBackendChange() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun finishActivationForBackendChange() {
        if (accessChoiceLocked() || !DelegatedDesktopAccess.ownsActivation) return
        if (DelegatedDesktopAccess.requestStop()) {
            endingComputerAccess = true
            cancelContinuation()
            val recovery = if (IndependentRecoveryAccess.hasManagedState(this))
                " Собственная дополнительная отладка будет закрыта, если её владелец подтверждён. Если возврат не подтверждён, при следующем запуске останется сообщение о незавершённой настройке." else ""
            showState("Завершаю доступ HeadSwap…", "Приложение закроется после освобождения рабочего стола.$recovery", busy = true)
            renderAccessControls()
        }
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

    private fun showCustomBitrate() {
        val value = numberField("Мбит/с", prefs.desktopBitrateMbps, 2)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@DexSetupActivity).apply {
                text = "Целое число от 1 до 20 Мбит/с. Частота кадров подстраивается автоматически. Реальная скорость может снижаться по возможностям магнитолы и соединения."
            })
            addView(value)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Своя скорость видеопотока")
            .setView(fields).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (accessChoiceLocked()) { dialog.dismiss(); renderAccessControls(); return@setOnClickListener }
                val mbps = value.text.toString().trim().toIntOrNull()
                if (mbps == null || mbps !in 1..20) {
                    value.error = "Введите целое число от 1 до 20"; return@setOnClickListener
                }
                prefs.desktopBitrateMbps = mbps
                dialog.dismiss()
                renderAccessControls()
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

    /** Every backend prepares its access first; only this continuation chooses a transport. */
    private fun readyWithDesktop() {
        if (!visible || stopped) return
        if (!DesktopAccess.isReadyForProjection(this)) { showAccessSetup(); return }
        setup.visibility = View.GONE
        shizukuSetup.visibility = View.GONE
        armed = true
        // No local ADB work is needed after activation, irrespective of the CarLife transport.
        if (!WirelessCarLife.preferUsb(this)) {
            if (retryUsbPermission && WirelessCarLife.missingPermissions(this).isNotEmpty()) {
                armed = false
                WirelessCarLife.start(this)
                return
            }
            if (WirelessCarLife.startReady(this)) {
                monitorBridge = true
                bridgeStartedAt = SystemClock.elapsedRealtime()
                showState("Подключение по Wi-Fi…", "Откройте беспроводной CarLife на магнитоле.", busy = true)
            } else {
                armed = false
                showState("Настройте Wi-Fi CarLife", WirelessCarLife.unavailableReason(this) ?: CarLifeState.status)
            }
        } else scanUsb(retryUsbPermission)
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
            if (explicit) openActivationSetup()
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
            showState("Доступ HeadSwap активен", "Подключите CarLife выбранным способом. Рабочий стол запустится автоматически.")
        } else {
            showState("Нужна активация", "Активируйте HeadSwap, затем подключите CarLife выбранным способом.")
        }
    }

    private fun openActivationSetup() {
        setupRequested = true
        openSettings(true)
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) settingsNavigator.openEntry("activation")
        else settingsNavigator.open("activation")
        renderAccessControls()
    }

    private fun openPermissionSettings() {
        openSettings(true)
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) settingsNavigator.openEntry("permissions")
        else settingsNavigator.open("permissions")
        renderSettingsOverview()
    }

    private fun showComputerActivationHelp() {
        if (accessChoiceLocked() || requireBackendHandoff(DesktopAccessMode.COMPUTER)) return
        if (ActivationMethod.COMPUTER.backend(prefs.desktopMode) == null) return
        prefs.activationMethod = ActivationMethod.COMPUTER
        openActivationSetup()
    }

    private fun beginPhoneActivation() {
        if (canRetryFailedProjection()) {
            stopProjectionForActivation()
            return
        }
        if (!canStartPhoneActivation()) return
        if (DesktopActivationCoordinator.currentStep(this) == DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED) {
            showState("Нужно обновить доступ", "Выбранным функциям нужны дополнительные права. Завершите текущий доступ кнопкой ниже, откройте HeadSwap и активируйте снова.")
            return
        }
        if (independentRecoveryApplicable() && !prefs.independentRecoveryWithoutWifi) {
            AlertDialog.Builder(this).setTitle("Автоматическое восстановление")
                .setMessage("Для восстановления после отключения USB HeadSwap включит сетевую ADB-отладку для ранее разрешённых ключей. Она доступна через сетевые интерфейсы, без TLS. Режим действует до возврата обычного режима ADB или перезагрузки.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Разрешить и активировать") { _, _ ->
                    if (canStartPhoneActivation()) {
                        prefs.independentRecoveryWithoutWifi = true
                        startNotificationActivation(openSettings = !prefs.hasPaired)
                    }
                }.show()
        } else startNotificationActivation(openSettings = !prefs.hasPaired)
    }

    private fun startNotificationActivation(openSettings: Boolean, allowWithoutNotification: Boolean = false,
        requestNotificationPermission: Boolean = true) {
        if (!canStartPhoneActivation()) return
        if (DesktopActivationCoordinator.isReady(this)) {
            prepareAttempt(explicit = true)
            PhonePairingService.start(this, allowWithoutNotification = allowWithoutNotification)
            observePhoneActivation(force = true)
            return
        }
        if (!allowWithoutNotification && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            requestNotificationPermission) {
            pairingPermissionAction = { startNotificationActivation(openSettings, requestNotificationPermission = false) }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PAIRING_NOTIFICATIONS)
            return
        }
        val block = PhonePairingService.notificationBlock(this, !prefs.hasPaired)
        if (!allowWithoutNotification && block != null) {
            showPairingNotificationBlocked(block, openSettings)
            return
        }
        withNetworkPermission {
            if (!visible || !canStartPhoneActivation()) return@withNetworkPermission
            prepareAttempt(explicit = true)
            if (PhonePairingService.start(this, allowWithoutNotification = allowWithoutNotification) == null) {
                PhonePairingService.notificationBlock(this, !prefs.hasPaired)?.let { showPairingNotificationBlocked(it, openSettings) }
                return@withNetworkPermission
            }
            showState("Активация на телефоне", "Проверяю доступ. Если понадобится новый код, его можно ввести здесь или в уведомлении HeadSwap.", busy = true)
            if (openSettings) openDebugSettings()
        }
    }

    private fun showPairingNotificationBlocked(block: PairingNotificationBlock, openSettings: Boolean = !prefs.hasPaired) {
        val reason = when (block) {
            PairingNotificationBlock.APP -> "Уведомления HeadSwap отключены."
            PairingNotificationBlock.CODE_CHANNEL -> "Канал «Ввод кода сопряжения» отключён."
            PairingNotificationBlock.PROGRESS_CHANNEL -> "Канал «Активация HeadSwap» отключён."
        }
        AlertDialog.Builder(this).setTitle("Ввод кода сопряжения")
            .setMessage("$reason Включите уведомления или продолжите с вводом кода в приложении. Это одна и та же активация.")
            .setPositiveButton("Настройки уведомлений") { _, _ ->
                runCatching { startActivity(PhonePairingService.notificationSettings(this, block)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            }
            .setNegativeButton("Ввести в приложении") { _, _ ->
                startNotificationActivation(openSettings, allowWithoutNotification = true)
            }.show()
    }

    private fun beginPhonePair() {
        val value = PairingReplyPolicy.code(phonePairCode.text)
        if (value == null) {
            phonePairCode.error = "Введите код из 6 цифр"
            phonePairCode.requestFocus()
            return
        }
        val attempt = phoneReplyAttempt
        if (attempt != null && PhonePairingService.submitCode(this, attempt, value)) phonePairCode.text.clear()
        else phonePairCode.error = "Этот ввод уже завершён. Дождитесь результата активации."
    }

    private fun canStartPhoneActivation(): Boolean {
        if (!visible || activationStopPending || !prefs.useDelegated || prefs.activationMethod != ActivationMethod.WIFI || phoneLaunchCommitted || PhonePairingService.active) return false
        if (BridgeService.alive || DexState.running) {
            showState("Сначала остановите подключение", "Остановите сеанс кнопкой на главном экране, затем повторите активацию.")
            return false
        }
        if (workerBusy.get()) {
            showState("Предыдущая операция ещё завершается", "Дождитесь её завершения и нажмите «Активировать» снова.")
            return false
        }
        return true
    }

    /** A failed projection must not trap the user behind a disabled reactivation button. */
    private fun canRetryFailedProjection(): Boolean = visible && !endingComputerAccess && permissionAction == null && prefs.useDelegated &&
        prefs.activationMethod == ActivationMethod.WIFI && !activationStopPending &&
        !phoneLaunchCommitted && !PhonePairingService.active && !workerBusy.get() &&
        DexState.error != null && !desktopConnectionReady() && (BridgeService.alive || DexState.running)

    private fun stopProjectionForActivation() {
        cancelContinuation()
        activationStopPending = true
        Actions.stopBridge(this)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        showState("Готовлю повторную активацию…", "Завершаю неудавшееся подключение. Выданные права сохраняются.", busy = true)
        main.post(object : Runnable {
            override fun run() {
                if (!activationStopPending || !visible || isDestroyed) return
                if (!BridgeService.alive && !DexState.running) {
                    activationStopPending = false
                    screenBusy = false
                    beginPhoneActivation()
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    activationStopPending = false
                    showState("Подключение ещё завершается", "Дождитесь остановки и нажмите «Повторная активация».")
                } else main.postDelayed(this, 100)
            }
        })
    }

    private fun confirmRestoreDebugMode() {
        if (accessChoiceLocked() || !DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.snapshot(this).kind)) return
        AlertDialog.Builder(this).setTitle("Вернуть обычный режим ADB?")
            .setMessage("Дополнительный сетевой режим ADB, который использует HeadSwap, будет отключён. Соединения отладки, в том числе компьютера и других приложений, могут прерваться. Настройка «Беспроводная отладка» Android сохранится. Для рабочего стола может потребоваться повторная активация по Wi-Fi.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Вернуть обычный режим") { _, _ -> restoreDebugMode() }.show()
    }

    private fun restoreDebugMode() {
        if (accessChoiceLocked() || !workerBusy.compareAndSet(false, true)) return
        val app = applicationContext
        restoringDebugMode = true
        showState("Возвращаю обычный режим ADB…", "Дождитесь проверки закрытия дополнительного порта.", busy = true)
        worker.execute {
            val result = runCatching { IndependentRecoveryAccess.restoreExplicitly(app) }
            workerBusy.set(false)
            main.post {
                if (isFinishing || isDestroyed) return@post
                restoringDebugMode = false
                screenBusy = false
                result.fold(
                    onSuccess = { showState("Обычный режим ADB восстановлен", "Дополнительный порт закрыт, восстановление без Wi-Fi выключено. Настройка «Беспроводная отладка» Android сохранена. При необходимости снова активируйте рабочий стол по Wi-Fi.") },
                    onFailure = { showState("Возврат режима не подтверждён", it.message ?: "Не удалось подтвердить возврат обычного режима. Попробуйте снова или перезагрузите телефон.") }
                )
                renderAccessControls()
            }
        }
    }

    private fun observePhoneActivation(force: Boolean = false): Boolean {
        if (!prefs.useDelegated) return false
        if (!PhonePairingService.active && PhonePairingService.resumeCommittedActivation(this)) {
            phoneLaunchCommitted = false
            showState("Проверяю рабочий стол…", "Права выданы. Проверяю независимый экран и автоматическое восстановление.", busy = true)
            return true
        }
        val attempt = PhonePairingService.snapshot
        if (PhonePairingService.active) {
            phoneLaunchCommitted = attempt.phase == PhonePairingService.Phase.DISPATCHED
            showState("Активация на телефоне", attempt.message, busy = true)
            return true
        }
        val status = PhoneActivationStatus.read(this)
        // The new process may already hold grants while its original launcher is still checking them.
        // Keep that exact transaction busy; do not offer a second activation in this interval.
        if (status.phase == PhoneActivationStatus.Phase.PENDING &&
            status.activationBinding != PhoneCommittedActivationPolicy.Binding.OTHER) {
            phoneLaunchCommitted = true
            showState("Включаю доступ HeadSwap…", "Проверяю запуск независимого экрана. Активация продолжится автоматически.", busy = true)
            return true
        }
        val ready = DesktopAccess.activationSetupComplete(this)
        val baseReady = DelegatedDesktopAccess.isReady(this)
        // A receipt describes one earlier attempt. Live capabilities take precedence after restart.
        if (ready) {
            phoneLaunchCommitted = false
            if (!setupResumeScheduled && prefs.initialSetupInProgress && prefs.initialSetupStep == 2 &&
                !intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) {
                setupResumeScheduled = true
                startActivity(Intent(this, DesktopOnboardingActivity::class.java))
                return true
            }
            if (force || phoneStatusSeen != "live-ready") {
                phoneStatusSeen = "live-ready"
                cancelContinuation()
                showState("Доступ HeadSwap активен", "Подключите CarLife выбранным способом. Повторная активация проверит доступ без лишнего сопряжения.")
                if (!setupRequested && !stopped && visible && !intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) readyWithDesktop()
                return true
            }
            return false
        }
        val liveStatus = activationUiStatus(ready)
        if (liveStatus.state == DesktopActivationUiPolicy.State.CONNECTED ||
            liveStatus.state == DesktopActivationUiPolicy.State.ACCESS_ACTIVE) {
            phoneLaunchCommitted = false
            val key = "live:${liveStatus.state}:${liveStatus.recovery}"
            if (force || phoneStatusSeen != key) {
                phoneStatusSeen = key
                showState(activationSummary(liveStatus), activationExplanation(liveStatus))
                return true
            }
            return false
        }
        if (attempt.phase == PhonePairingService.Phase.FAILED || attempt.phase == PhonePairingService.Phase.CANCELLED) {
            val key = "service:${attempt.attempt}:${attempt.phase}:${attempt.message}"
            if (force || phoneStatusSeen != key) {
                phoneStatusSeen = key
                phoneLaunchCommitted = false
                cancelContinuation()
                showState(if (attempt.phase == PhonePairingService.Phase.FAILED) "Активация не завершена" else "Активация отменена", attempt.message)
                return true
            }
            return false
        }
        if (baseReady) {
            phoneLaunchCommitted = false
            if (force || phoneStatusSeen != "live-access") {
                phoneStatusSeen = "live-access"
                showState("Подготовка рабочего стола не завершена", "Права приложения сохранены. Нажмите «Повторная активация», чтобы проверить соединение и подготовить независимый экран.")
                return true
            }
            return false
        }
        val key = "${status.phase}:${status.startedElapsedMs}:${status.message}"
        when (status.phase) {
            PhoneActivationStatus.Phase.PENDING -> {
                phoneLaunchCommitted = true
                showState("Включаю доступ HeadSwap…", "Дождитесь перезапуска и результата активации.", busy = true)
                return true
            }
            PhoneActivationStatus.Phase.FAILED -> if (force || phoneStatusSeen != key || phoneLaunchCommitted) {
                phoneStatusSeen = key
                phoneLaunchCommitted = false
                cancelContinuation()
                showState("Активация не завершена", status.message)
                return true
            }
            PhoneActivationStatus.Phase.READY, PhoneActivationStatus.Phase.IDLE -> {
                phoneLaunchCommitted = false
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
            DexPrivilegeAccess.Status.READY -> "Подключите CarLife выбранным способом — рабочий стол запустится автоматически. При USB-подключении работающей службе Shizuku Wi-Fi не нужен."
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

    private fun accessChoiceLocked(): Boolean = endingComputerAccess || activationStopPending || PhonePairingService.active || phoneLaunchCommitted || screenBusy || armed || monitorBridge || workerBusy.get() ||
        BridgeService.alive || DexState.running || restartAt != 0L || permissionAction != null || shizukuPermissionToken != null

    private fun independentRecoveryApplicable(): Boolean = Build.VERSION.SDK_INT >= 37 && prefs.useDelegated &&
        prefs.desktopMode != DesktopModePolicy.SAMSUNG_DEX

    private fun activationUiStatus(setupReady: Boolean = DesktopAccess.activationSetupComplete(this)): DesktopActivationUiPolicy.Status {
        val facts = if (prefs.useDelegated) DesktopActivationCoordinator.facts(this) else null
        val needsNewGrants = facts != null && facts.grants &&
            (prefs.usbPlaybackAudio && !facts.audio || prefs.desktopPanelControl && !facts.panel && !facts.panelUnsupported)
        return DesktopActivationUiPolicy.status(
            inProgress = restoringDebugMode || activationStopPending || PhonePairingService.active || phoneLaunchCommitted,
            committedBlocked = PhonePairingService.blocksProjectionForCommittedActivation(this),
            accessReady = DesktopAccess.isReady(this), needsNewGrants = needsNewGrants,
            hostMissing = facts != null && facts.hostRequired && !facts.host,
            projectionReady = desktopConnectionReady(), setupReady = setupReady,
            attemptFailed = !setupReady && PhonePairingService.snapshot.phase == PhonePairingService.Phase.FAILED,
            recoveryRequested = IndependentRecoveryAccess.requested(this),
            recoveryNeedsApply = facts?.modePending == true,
            recoveryNeedsVerification = facts?.recoveryNeedsVerification == true)
    }

    private fun activationSummary(status: DesktopActivationUiPolicy.Status): String = when (status.state) {
        DesktopActivationUiPolicy.State.ACTIVATING -> "Выполняется…"
        DesktopActivationUiPolicy.State.INCOMPLETE -> "Подготовка не завершена"
        DesktopActivationUiPolicy.State.NEEDS_ACCESS -> "Нужна активация"
        DesktopActivationUiPolicy.State.NEEDS_UPDATED_ACCESS -> "Нужно обновить доступ"
        DesktopActivationUiPolicy.State.DESKTOP_UNAVAILABLE -> "Рабочий стол недоступен"
        DesktopActivationUiPolicy.State.CONNECTED -> "Рабочий стол подключён"
        DesktopActivationUiPolicy.State.READY, DesktopActivationUiPolicy.State.ACCESS_ACTIVE -> "Доступ активен"
        DesktopActivationUiPolicy.State.FAILED -> "Активация не завершена"
    }

    private fun recoveryExplanation(status: DesktopActivationUiPolicy.Status): String = when (status.recovery) {
        DesktopActivationUiPolicy.Recovery.NEEDS_SETUP -> "Восстановление после отключения требует настройки. Повторная активация проверит выбранный способ восстановления."
        DesktopActivationUiPolicy.Recovery.UNVERIFIED -> "Готовность следующего переподключения пока не подтверждена."
        DesktopActivationUiPolicy.Recovery.NOT_REQUIRED, DesktopActivationUiPolicy.Recovery.READY -> ""
    }

    private fun activationExplanation(status: DesktopActivationUiPolicy.Status): String = when (status.state) {
        DesktopActivationUiPolicy.State.ACTIVATING -> hint.text.toString()
        DesktopActivationUiPolicy.State.INCOMPLETE -> "Права сохранены, но проверка текущей активации не завершена. Нажмите «Повторная активация»."
        DesktopActivationUiPolicy.State.NEEDS_ACCESS -> "Активируйте HeadSwap выбранным способом, затем подключите CarLife."
        DesktopActivationUiPolicy.State.NEEDS_UPDATED_ACCESS -> "Выбранным функциям нужны дополнительные права. Завершите текущий доступ кнопкой ниже, откройте HeadSwap и активируйте снова."
        DesktopActivationUiPolicy.State.DESKTOP_UNAVAILABLE -> "Права приложения сохранены, но независимый экран недоступен. Повторите подключение; если восстановление не завершится, выполните повторную активацию."
        DesktopActivationUiPolicy.State.CONNECTED -> listOf("Изображение передаётся в CarLife. ${DesktopAudioState.status}", recoveryExplanation(status)).filter(String::isNotBlank).joinToString("\n")
        DesktopActivationUiPolicy.State.ACCESS_ACTIVE -> listOf("Права приложения и рабочий стол доступны.", recoveryExplanation(status)).filter(String::isNotBlank).joinToString("\n")
        DesktopActivationUiPolicy.State.READY -> "Подключите CarLife выбранным способом. Повторная активация потребуется после перезагрузки телефона или обновления приложения."
        DesktopActivationUiPolicy.State.FAILED -> PhonePairingService.snapshot.message
    }

    private fun renderAccessControls() {
        val desktop = prefs.useDesktopService
        val enabled = !accessChoiceLocked()
        val computerReady = prefs.useDelegated && DelegatedDesktopAccess.isReady(this)
        val accessReady = DesktopAccess.isReady(this)
        initialSetupSection.visibility = if (prefs.initialSetupCompleted) View.GONE else View.VISIBLE
        setupDoneButton.visibility = if (!PhonePairingService.active && !phoneLaunchCommitted) View.VISIBLE else View.GONE
        setupDoneButton.updateText(if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) "Вернуться к настройке" else "На главный экран")
        val activationReady = DesktopAccess.activationSetupComplete(this)
        val activationUi = activationUiStatus(activationReady)
        setupAccessButton.visibility = if (activationReady || activationUi.state == DesktopActivationUiPolicy.State.CONNECTED) View.GONE else View.VISIBLE
        setupAccessButton.updateText(if (computerReady) "Повторная активация" else "Активировать")
        setupAccessButton.isEnabled = !PhonePairingService.active && !phoneLaunchCommitted
        val activationInProgress = restoringDebugMode || activationStopPending || PhonePairingService.active || phoneLaunchCommitted
        settingsNavigator.summary("activation", activationSummary(activationUi))
        activationProgress.visibility = if (activationInProgress) View.VISIBLE else View.GONE
        activationStatus.updateText(if (activationInProgress) title.text.toString() else activationSummary(activationUi))
        activationStatusHint.updateText(activationExplanation(activationUi))
        val wirelessPrefs = WirelessPrefs(this)
        val wirelessSelected = wirelessPrefs.enabled
        val transportLabel = when (wirelessPrefs.mode) {
            CarLifeConnectionMode.USB -> "USB"
            CarLifeConnectionMode.WIFI -> "Wi-Fi"
            CarLifeConnectionMode.AUTO -> "Автоматически"
        }
        connectionModeChoice.updateText("$transportLabel ▾")
        connectionModeChoice.isEnabled = enabled
        wirelessSetupButton.visibility = if (wirelessSelected) View.VISIBLE else View.GONE
        wirelessSetupButton.isEnabled = enabled
        accessHint.updateText(when {
            computerReady && !DelegatedDesktopInstrumentation.keyboardRoutingReady -> "HeadSwap ожидает клавиатуру для экрана магнитолы."
            desktop -> "$transportLabel CarLife · ${prefs.desktopResolutionLabel}"
            else -> "$transportLabel CarLife · DeX по ADB · 1280 × 720"
        })
        if (desktop) setup.visibility = View.GONE
        adbOptions.visibility = if (desktop) View.GONE else View.VISIBLE
        adbAdvanced.visibility = if (!desktop && manualAdbOpen) View.VISIBLE else View.GONE
        adbOptions.updateText(if (manualAdbOpen) "Скрыть ручной ввод" else "Ввести адрес и порты вручную")
        desktopChoice.updateText("Профиль: $desktopProfileName ▾")
        desktopChoice.isEnabled = enabled
        installProfileButton.visibility = if (prefs.desktopMode == DesktopModePolicy.DRIVEDECK && !driveDeckInstalled) View.VISIBLE else View.GONE
        desktopChoiceHint.updateText(if (prefs.desktopMode == DesktopModePolicy.DRIVEDECK && !driveDeckInstalled)
            "Для этого профиля установите DriveDeck · HeadSwap из компонентов. Обычная версия DriveDeck не подходит."
            else if (!enabled) "Для смены профиля остановите подключение."
            else when (prefs.desktopMode) {
                DesktopModePolicy.DRIVEDECK -> "Карта и плеер рядом на экране магнитолы."
                DesktopModePolicy.SAMSUNG_DEX -> "Рабочий стол Samsung на экране магнитолы."
                else -> "Панель приложений и рабочий стол Android на магнитоле."
            })
        accessChoice.updateText("Способ: ${prefs.activationMethod.label} ▾")
        accessChoice.isEnabled = enabled
        accessChangeHint.visibility = if (enabled) View.GONE else View.VISIBLE
        accessChangeHint.updateText("Для смены способа активации остановите подключение.")
        activationRepairSection.visibility = if (!activationInProgress &&
            DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.snapshot(this).kind)) View.VISIBLE else View.GONE
        activationRepairButton.isEnabled = enabled
        activationRepairHint.updateText("Закрытие дополнительной сетевой отладки не подтверждено. " +
            if (enabled) "Исправление запросит отдельное подтверждение: оно может прервать соединения отладки компьютера и других приложений."
            else "Сначала остановите подключение. Затем можно исправить настройку с отдельным подтверждением.")
        finishComputerAccess.visibility = if (DelegatedDesktopAccess.ownsActivation) View.VISIBLE else View.GONE
        finishComputerAccess.isEnabled = enabled && DelegatedDesktopAccess.ownsActivation
        densityChoice.isEnabled = enabled
        dpiAppsButton.isEnabled = desktop
        resolutionChoice.isEnabled = enabled && desktop
        fpsChoice.isEnabled = enabled && desktop
        resolutionChoice.updateText("Разрешение: ${prefs.desktopResolutionLabel} ▾")
        densityChoice.updateText("Размер интерфейса: ${if (prefs.desktopDensity == 0) "авто" else "${prefs.desktopDensity} DPI"} ▾")
        fpsChoice.updateText("Скорость видеопотока: ${prefs.desktopBitrateMbps} Мбит/с ▾")
        videoTuningHint.updateText(if (desktop)
            "Частота кадров подстраивается под изображение, возможности телефона и магнитолы. Выбранная скорость задаёт верхний предел видеопотока."
            else "В резервном ADB-режиме DeX автоматический FPS и выбранный битрейт недоступны. Для этих настроек в DeX используйте Shizuku.")
        densityHint.updateText(when {
            !enabled -> "Остановите подключение, чтобы изменить изображение."
            !desktop -> "DeX по ADB использует 1280 × 720. Выбранный DPI применяется при следующем подключении."
            else -> "Изменения вступят в силу при следующем подключении. «Авто» подбирает DPI по разрешению."
        })
        applyingAccessChoice = true
        try { usbAudioChoice.control.isChecked = prefs.usbPlaybackAudio } finally { applyingAccessChoice = false }
        usbAudioChoice.setEnabled(enabled && prefs.useDelegated)
        autoMusicChoice.setEnabled(prefs.useDelegated)
        usbAudioHint.updateText(when {
            !prefs.useDelegated -> "Передача звука в CarLife доступна в режиме «Доступ HeadSwap». Здесь можно оставить звук через Bluetooth автомобиля."
            !enabled -> "Чтобы изменить передачу звука, остановите подключение. ${if (prefs.usbPlaybackAudio) DesktopAudioState.status else "Звук остаётся на обычном выходе телефона."}"
            !prefs.usbPlaybackAudio -> "Звук остаётся на обычном выходе телефона, например Bluetooth автомобиля."
            !DesktopAudioAccess.hasPermission(this) -> "Передача звука выбрана. Активируйте доступ, чтобы передавать музыку и подсказки телефона в CarLife."
            else -> DesktopAudioState.status
        })
        applyingAccessChoice = true
        try { panelControlChoice.control.isChecked = prefs.desktopPanelControl } finally { applyingAccessChoice = false }
        panelControlChoice.setEnabled(enabled && desktop)
        phoneControlDetails.visibility = View.VISIBLE
        idleModeChoice.isEnabled = desktop && prefs.desktopPanelControl
        idleTimeoutChoice.isEnabled = desktop && prefs.desktopPanelControl
        panelControlHint.visibility = panelControlChoice.row.visibility
        panelControlHint.updateText(when {
            !desktop -> "Управление экраном недоступно в резервном ADB-режиме DeX. Используйте Shizuku или профиль Android Desktop."
            !enabled -> "Чтобы изменить доступ к управлению экраном, остановите подключение. Способ и таймер уже включённой функции можно настроить ниже."
            !prefs.desktopPanelControl -> "Включите управление экраном, чтобы изменить способ гашения и таймер. Сохранённые значения показаны ниже."
            prefs.phoneScreenMode == PhoneIdleMode.CURTAIN -> "Оставьте HeadSwap открытым на телефоне. Двойное касание снимает затемнение."
            PhonePanelPolicy.prefersCurtain -> "На этой модели аппаратное выключение может погасить изображение на ГУ. Для сохранения изображения выберите «Затемнять экран»."
            DelegatedPanelAccess.unsupportedThisActivation -> PhoneActivationPolicy.PANEL_MESSAGE
            !DelegatedPanelAccess.isAvailable(this) -> "Для таймера и двойного касания повторите активацию на телефоне или с --screen на компьютере. HeadSwap запросит управление панелью и чтение времени активности экрана."
            else -> "Перед выключением панели HeadSwap открывает свой экран для двойного касания. Прежнее приложение телефона может приостановиться до пробуждения."
        })
        renderConnectionControl()
        renderPhoneActivationControls()
        renderPhoneScreenControl()
        renderSettingsOverview()
    }

    private fun renderPhoneActivationControls() {
        val method = prefs.activationMethod
        val desktopWifi = prefs.useDelegated && method == ActivationMethod.WIFI
        phoneActivationSection.visibility = if (desktopWifi) View.VISIBLE else View.GONE
        phoneActivationSection.keepScreenOn = desktopWifi && (PhonePairingService.active || phoneLaunchCommitted)
        computerActivationSection.visibility = if (method == ActivationMethod.COMPUTER) View.VISIBLE else View.GONE
        if (method == ActivationMethod.COMPUTER) {
            computerActivationInstructions.updateText(DesktopActivationGuide.instructions(prefs.usbPlaybackAudio, prefs.desktopPanelControl))
        }
        shizukuSetup.visibility = if (method == ActivationMethod.SHIZUKU) View.VISIBLE else View.GONE
        val nextReplyAttempt = PhonePairingService.replyAttempt()
        if (phoneReplyAttempt != nextReplyAttempt) { phonePairCode.text.clear(); phonePairCode.error = null }
        phoneReplyAttempt = nextReplyAttempt
        phonePairSection.visibility = if (phoneReplyAttempt != null) View.VISIBLE else View.GONE
        phonePairCode.isEnabled = phoneReplyAttempt != null
        val baseReady = DelegatedDesktopAccess.isReady(this)
        val attempt = PhonePairingService.snapshot
        phoneActivationButton.updateText(when {
            phoneReplyAttempt != null -> "Подтвердить код"
            activationStopPending || PhonePairingService.active || phoneLaunchCommitted -> "Активация…"
            baseReady -> "Повторная активация"
            else -> "Активировать"
        })
        phoneActivationButton.isEnabled = phoneReplyAttempt != null || canRetryFailedProjection() ||
            (!activationStopPending && !PhonePairingService.active && !phoneLaunchCommitted &&
            !screenBusy && !workerBusy.get() && permissionAction == null && !BridgeService.alive && !DexState.running)
        cancelPhoneActivationButton.visibility = if (PhonePairingService.active && attempt.phase != PhonePairingService.Phase.DISPATCHED) View.VISIBLE else View.GONE
        phoneActivationHint.updateText(when {
            PhonePairingService.active -> attempt.message
            canRetryFailedProjection() -> "Повторная активация остановит неудавшееся подключение и заново проверит рабочий стол."
            BridgeService.alive || DexState.running -> "Для повторной активации сначала остановите подключение на главном экране."
            baseReady -> "Сначала проверяется действующий доступ. Если он готов, новые коды и подключение к Wi-Fi не нужны."
            prefs.hasPaired -> "Сохранённое сопряжение проверится автоматически. Новый код нужен только если Android удалил разрешение."
            else -> "Код вводится один раз. При повторной активации HeadSwap использует сохранённое сопряжение."
        })
    }

    private fun lockIsolationHint(): String {
        if (prefs.desktopMode == DesktopModePolicy.SAMSUNG_DEX) return ""
        return "\n" + when (DexState.independentPhoneLock) {
            true -> "Блокировка телефона не выключает экран ГУ."
            false -> "Для этой прошивки используйте гашение экрана HeadSwap."
            null -> "Независимость экрана от блокировки пока не подтверждена."
        }
    }

    private fun renderConnectionControl() {
        val wireless = !WirelessCarLife.preferUsb(this)
        val state = DesktopConnectionButtonPolicy.state(
            stopped = stopped, detached = !wireless && Actions.isUsbDetachedIdle(),
            serviceAlive = BridgeService.alive, desktopRunning = DexState.running,
            carLifeRunning = CarLifeState.running,
            videoFlowing = DesktopConnectionState.videoIsFlowing(SystemClock.elapsedRealtime()),
            failed = DexState.error != null || !wireless && Actions.lastUsbResult.state == Actions.UsbScanState.FAILED,
            working = screenBusy || deferredConnect || restartAt != 0L ||
                permissionAction != null || shizukuPermissionToken != null,
            connectionPending = armed || monitorBridge && BridgeService.alive,
        )
        connectionButtonState = state
        connectButton.updateText(state.label)
        val color = when (state) {
            DesktopConnectionButtonPolicy.State.IDLE -> Color.rgb(30, 120, 65)
            DesktopConnectionButtonPolicy.State.CONNECTING, DesktopConnectionButtonPolicy.State.CONNECTED -> Color.rgb(183, 40, 43)
        }
        if (connectButton.backgroundTintList?.defaultColor != color) {
            connectButton.backgroundTintList = ColorStateList.valueOf(color)
        }
        connectButton.setTextColor(Color.WHITE)
        connectButton.isEnabled = !phoneLaunchCommitted && !PhonePairingService.active
        connectButton.contentDescription = state.label
        connectButton.tooltipText = null
        connectButtonHint.updateText("Соединение ещё не готово. Ожидание можно остановить.")
        connectButtonHint.visibility = if (state == DesktopConnectionButtonPolicy.State.CONNECTING && !phoneLaunchCommitted) View.VISIBLE else View.GONE

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
        if (!visible || endingComputerAccess || phoneLaunchCommitted || PhonePairingService.active) return
        if (prefs.desktopMode == DesktopModePolicy.DRIVEDECK && !driveDeckInstalled) {
            showState("Установите DriveDeck · HeadSwap", "Откройте компоненты и установите адаптированный DriveDeck, затем вернитесь к подключению.")
            return
        }
        if (DesktopBackendTransitionPolicy.blocksProjection(DelegatedDesktopAccess.ownsActivation, prefs.accessMode)) {
            if (explicit) showBackendHandoff(prefs.accessMode)
            else showState("Завершите предыдущую активацию", "Нажмите «Завершить доступ и закрыть HeadSwap», затем откройте приложение для подключения через ${accessLabel(prefs.accessMode)}.")
            return
        }
        if (WirelessPrefs(this).mode != CarLifeConnectionMode.WIFI) Actions.observeUsbConnection(this)
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

    private fun continuePendingConnection() {
        if (stopped) return
        if (restartAt != 0L) {
            if (!BridgeService.alive) {
                restartAt = 0
                beginConnect(explicit = true)
            } else if (SystemClock.elapsedRealtime() - restartAt > 5_000) {
                restartAt = 0
                showState("Не удалось остановить рабочий стол", "Нажмите «Остановить» и повторите подключение.")
            }
        } else if (deferredConnect && !workerBusy.get()) {
            deferredConnect = false
            if (prefs.useDesktopService || prefs.hasPaired) beginConnect(explicit = false) else showSetup()
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
                    setup.visibility = View.GONE
                    execute(flow.paired(command.token))
                }
            }
            is DexSetupFlow.Command.Probe -> {
                cancelDeadline()
                showState("Проверяю телефон…", "После проверки подключение к CarLife продолжится автоматически.", busy = true)
                launch(command.token, DexSetupFlow.Phase.PROBING, {
                    DexAccess.probe(applicationContext, command.endpoint.port, command.endpoint.host)
                    prefs.host = command.endpoint.host
                    prefs.connectPort = command.endpoint.port
                }) { execute(flow.verified(command.token)) }
            }
            is DexSetupFlow.Command.Ready -> {
                closeDiscovery()
                readyWithDesktop()
            }
            is DexSetupFlow.Command.ProbeFailed -> showSetupFailure(command.error, DexSetupFlow.Phase.PROBING)
            is DexSetupFlow.Command.NotFound -> {
                closeDiscovery()
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
                    if (phase == DexSetupFlow.Phase.PROBING && failure is DexAccessException) {
                        execute(flow.probeFailed(token, failure))
                        return@fold
                    }
                    flow.failed(token)
                    showSetupFailure(failure, phase)
                })
            }
        }
    }

    private fun showSetupFailure(failure: Throwable, phase: DexSetupFlow.Phase) {
        closeDiscovery()
        armed = false
        lastError = failure.message ?: failure.javaClass.simpleName
        if (phase == DexSetupFlow.Phase.PAIRING) {
            code.text.clear()
            setup.visibility = View.VISIBLE
        }
        showState(if (phase == DexSetupFlow.Phase.PAIRING) "Сопряжение не завершено" else "Телефон пока не готов", friendlyFailure(failure, phase))
        AppLog.i("DEX setup failed: ${failure.javaClass.simpleName}")
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
        if (phoneLaunchCommitted || PhonePairingService.active || WirelessPrefs(this).mode == CarLifeConnectionMode.WIFI) return
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
        if (!visible || stopped || !armed || WirelessPrefs(this).mode == CarLifeConnectionMode.WIFI) return
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
            DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.TLS_VERSION_UNSUPPORTED, DexAccessReason.TLS_PROTOCOL,
            DexAccessReason.LOCAL_ADB_CONFIGURATION, DexAccessReason.LOCAL_ADB_RESTORE_REQUIRED -> problem?.message.orEmpty()
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
        if (requestCode == REQUEST_SETTINGS_NOTIFICATIONS) {
            renderSettingsOverview()
            return
        }
        if (requestCode == REQUEST_PAIRING_NOTIFICATIONS) {
            val action = pairingPermissionAction
            pairingPermissionAction = null
            action?.invoke()
            return
        }
        if (requestCode != REQUEST_NETWORK) return
        val action = permissionAction
        permissionAction = null
        if (hasNetworkPermission() && visible) action?.invoke() else if (visible) {
            showState("Нужно разрешение локальной сети", "Разрешите его в настройках приложения, затем повторите действие.")
        }
    }

    private fun savedEndpoint(): DexEndpoint? {
        return if (prefs.connectPort in 1..65535 && DexAccess.acceptsHost(prefs.host)) DexEndpoint(prefs.host, prefs.connectPort) else null
    }
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
