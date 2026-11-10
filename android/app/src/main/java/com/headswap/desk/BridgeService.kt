package com.headswap.desk

import android.app.Notification
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.aa.AaPrefs
import com.headswap.desk.aa.AaState
import com.headswap.desk.aa.BridgeHost
import com.headswap.desk.aa.CarPreset
import com.headswap.desk.aa.CallStateSource
import com.headswap.desk.aa.MetaRelay
import com.headswap.desk.aa.MicMode
import com.headswap.desk.aa.RefDecoder
import com.headswap.desk.aa.VideoGeometry
import com.headswap.desk.aa.VideoMode
import com.headswap.desk.aa.DesktopVideoSelection
import com.headswap.desk.aa.DesktopVideoGeometry
import com.headswap.desk.cluster.ClusterMediaSource
import com.headswap.desk.cluster.CarLifeForegroundGuidance
import com.headswap.desk.cluster.ClusterNavigationSource
import com.headswap.desk.dex.DexConnector
import com.headswap.desk.dex.ShizukuDexConnector
import com.headswap.desk.dex.DelegatedDexConnector
import com.headswap.desk.dex.DesktopAccess
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.PhoneIdleMode
import com.headswap.desk.dex.DexState
import com.headswap.desk.dex.DesktopRecoveryPolicy
import com.headswap.desk.dex.DesktopAudioCapture
import com.headswap.desk.link.CarTransport
import com.headswap.desk.link.LinkSetupException
import com.headswap.desk.link.StreamTransport
import com.headswap.desk.link.Tcp6Listener
import com.headswap.desk.link.TransportCaps
import com.headswap.desk.link.bt.CarBtState
import com.headswap.desk.link.bt.CarlifeBtProbe
import com.headswap.desk.link.car.AutoOffDebounce
import com.headswap.desk.link.car.BT_HINT_MS
import com.headswap.desk.link.car.BtReconnectHint
import com.headswap.desk.link.car.CAR_NET_DELAY_MS
import com.headswap.desk.link.car.CAR_NET_TIMEOUT_MS
import com.headswap.desk.link.car.CarConnMode
import com.headswap.desk.link.car.CarMac
import com.headswap.desk.link.car.CarNetEvent
import com.headswap.desk.link.car.CarNetPolicy
import com.headswap.desk.link.car.CarNetState
import com.headswap.desk.link.car.CarNetwork
import com.headswap.desk.link.car.CarProfile
import com.headswap.desk.link.car.CarWifi
import com.headswap.desk.link.car.WirelessPrefs
import com.headswap.desk.link.car.CarLifeConnectionMode
import com.headswap.desk.link.car.WirelessCarLife
import com.headswap.desk.link.car.WirelessConnectionPolicy
import com.headswap.desk.link.diag.NetProbe
import com.headswap.desk.link.diag.WLog
import com.headswap.desk.link.wifi.HotspotBeacon
import com.headswap.desk.link.wifi.LanIface
import com.headswap.desk.ui.HeadswapNotification
import com.headswap.desk.ui.Actions
import com.headswap.desk.ui.UsbDetachPolicy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Foreground-сервис (connectedDevice): держит транспорт (USB-аксессуар или debug-TCP) и сессию с ГУ.
 * AA-сторона моста (BridgeHost) живёт на время сервиса, а не одной USB-сессии: обрыв USB закрывает
 * CarLife-сессию, AA остаётся живой BridgeHost.GRACE_MS и подхватывается следующей USB-сессией.
 * Всё состояние сервиса (session, host, stopRequested) трогает только главная нить.
 */
class BridgeService : Service() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: CarLifeSession? = null
    private var clusterMedia: ClusterMediaSource? = null
    private var notificationShade: com.headswap.desk.notify.HuNotificationShade? = null
    private var clusterNavigation: ClusterNavigationSource? = null
    private var foregroundGuidance: CarLifeForegroundGuidance? = null
    private var unobserveNavigationHints: (() -> Unit)? = null
    private var unobserveWirelessEnabled: (() -> Unit)? = null
    private var host: BridgeHost? = null
    private var powerDiagnostics: com.headswap.desk.dex.DesktopPowerDiagnostics? = null
    private var phoneIdleTimer: com.headswap.desk.dex.CarLifePhoneIdleTimer? = null
    private val desktopAudio by lazy { DesktopAudioCapture(applicationContext) }
    private var stopRequested = false
    private var lastSessionWasUsb = false
    private var physicalUsbDetached = false
    private val desktopRecovery = DesktopRecoveryPolicy()
    private data class Recovery(val attempt: DesktopRecoveryPolicy.Attempt, val session: CarLifeSession,
        val owner: BridgeHost, val isolatedSession: Boolean)
    private var pendingDesktopRecovery: Recovery? = null
    /** debug tcp6: слушатель ждёт 6/6 (нить debug-tcp6); Стоп его отменяет. */
    @Volatile private var pendingTcp6: Tcp6Listener? = null
    private val linkLock = Any()
    @Volatile private var linkEpoch = 0L
    /** Идёт подъём tcp6/беспровода (от старта до 6/6 или отказа); сервис в это время не гасим. Только главная нить. */
    private var linkWaiting = false
    /** Беспровод (бета, ветка B) включён пользователем: после обрыва снова ждём машину. Пишет главная нить, читает и коннектор. */
    @Volatile private var wifiBMode = false
    /**
     * Авторежим: выбранная машина рядом по Bluetooth (ACL_CONNECTED), ожидание по Wi-Fi держим, пока она рядом.
     * Пишет главная нить, читают колбэки статуса. Зеркало в CarLifeState.carAuto для отчёта.
     */
    @Volatile private var autoMode = false
        set(v) {
            field = v
            CarLifeState.carAuto = v
        }
    /** Ожидание запущено/принято авторежимом: после ухода машины его не возобновляем. Только главная нить. */
    private var autoStarted = false
    /** USB-сессия кончилась при машине рядом: через [USB_TO_AIR_MS] начнём ждать по воздуху. Только главная нить. */
    private var usbToAirPending = false
    private var usbHandoffFrom: CarLifeSession? = null
    private val serviceStarts = ServiceStartOwnership()
    private val usbToAirTick = Runnable { usbToAir() }
    private val autoOff = AutoOffDebounce(AUTO_OFF_DEBOUNCE_MS) { SystemClock.elapsedRealtime() }
    private val autoOffTick = Runnable { if (autoOff.fire()) applyAutoOff() }
    /** Подсказка «подключи телефон к Wi-Fi машины» уже показана (один раз на заход авторежима). */
    private var hintShown = false
    private val hintTick = Runnable { showWifiHint() }
    /** Вход в Wi-Fi машины (WifiNetworkSpecifier); создаётся при первом запросе: без сети машины (и на USB) его нет вовсе. */
    private var carNetImpl: CarNetwork? = null
    private val carNet: CarNetwork
        get() = carNetImpl ?: CarNetwork(this) { CarLifeState.carNet = it.label }.also { carNetImpl = it }
    private val carNetBusy: Boolean
        get() = carNetImpl?.state.let { it == CarNetState.REQUESTING || it == CarNetState.AVAILABLE }
    /** Начало ожидания по Wi-Fi (авто или ручного): от него считается [CAR_NET_DELAY_MS]. Только главная нить. */
    private var waitSince = 0L
    /** Когда машина последний раз была на связи (сессия USB/воздух, сеть машины); 0 = не была. Только главная нить. */
    private var carSeenAt = 0L
    /** В этом ожидании выбранная машина оказалась подключена по Bluetooth. Только главная нить. */
    private var carBtNear = false
    /** 6/6 получены, сессия вот-вот стартует: запрос сети машины уже не нужен. Только главная нить. */
    private var sixReady = false
    /** Ожидание сознательно прерывается ради нового маяка в сети машины: ветка отказа в startTcp6 это не сдача. Только главная нить. */
    private var restartingForCarNet = false
    private val carNetTick = Runnable { requestCarNet() }
    /** В этом ожидании ГУ прислала TARGET_INFO по Bluetooth: Wi-Fi Direct, сеть машины не нужна. Только главная нить. */
    private var p2pThisWait = false
    /** Wi-Fi Direct-машина (TARGET_INFO в этом ожидании или в профиле): сеть машины не запрашиваем, wifi-подсказку не показываем. */
    private val wifiDirectCar: Boolean
        get() = p2pThisWait || CarProfile(this).connMode == CarConnMode.BT_CARLIFE
    /** `car-skip` уже записан в этом ожидании. Только главная нить. */
    private var carSkipLogged = false
    /** Подключений ГУ к RFCOMM `carlife` в этом ожидании и показана ли подсказка «переподключи Bluetooth». Только главная нить. */
    private var btAcceptsThisWait = 0
    private var btHintShown = false
    /** Висит уведомление «включите Wi-Fi» (Wi-Fi Direct упёрся в выключенный Wi-Fi). Только главная нить. */
    private var wifiOffShown = false
    private val btHintTick = Runnable { checkBtHint() }
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDiag = AudioDiag()
    /** Ветка A, диагностика: RFCOMM `carlife` слушаем, пока ждём машину по Wi-Fi. Колбэки с bt-нити перекидываем на главную. */
    private val btProbe by lazy {
        CarlifeBtProbe(
            this,
            onAccept = { main.post { onBtAccept() } },
            onTargetInfo = { mac -> main.post { onBtTargetInfo(mac) } },
            onP2pBusy = { main.post { onP2pBusy() } },
            onWifi = { on -> main.post { onP2pWifi(on) } },
        )
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-grace").also { it.isDaemon = true } }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MainActivity.ACTION_PERMISSION) {
                val result = Actions.handleUsbPermissionResult(this@BridgeService, intent)
                if (result?.state == Actions.UsbScanState.FAILED && session == null && host == null && !linkWaiting) finish()
                return
            }
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED &&
                WirelessPrefs(this@BridgeService).mode == CarLifeConnectionMode.AUTO && !stopRequested) {
                // The live foreground service can request the standard USB permission without a second transport owner.
                Actions.scan(this@BridgeService, "AUTO: USB attached")
            }
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                AppLog.i("USB_ACCESSORY_DETACHED")
                // A queued old broadcast must not stop a new accessory or a wireless session.
                observePhysicalUsbDetach("USB accessory detached broadcast")
            }
        }
    }

    /** USB transport stops; the existing desktop grace and separate activation remain intact. */
    private fun observePhysicalUsbDetach(reason: String): Boolean {
        val present = runCatching {
            getSystemService(UsbManager::class.java).accessoryList.orEmpty().any(Actions::isCompatibleAccessory)
        }.getOrNull()
        if (!UsbDetachPolicy.confirmedDetached(lastSessionWasUsb, present)) return false
        if (physicalUsbDetached) return true
        physicalUsbDetached = true
        com.headswap.desk.dex.DesktopMusicPlayback.physicalDetach(this)
        cancelDesktopRecovery()
        Actions.onUsbDetached()
        // Preserve a deliberate wireless opt-in when the already-selected car is still nearby.
        // An unplug from an ordinary computer never arms wireless on its own.
        autoMode = WirelessConnectionPolicy.retainAutoAfterUsbDetach(
            WirelessPrefs(this).enabled, CarProfile(this).isSet, autoMode)
        wifiBMode = false
        autoStarted = false
        usbToAirPending = false
        main.removeCallbacks(usbToAirTick)
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.STOP, "usb-detach")
        cancelLinkWait()
        CarLifeState.wireless = null
        btProbe.stop("USB отключён")
        AppLog.i("DESKTOP USB detached: keep 8s desktop grace and activation ($reason)", false)
        val current = session
        if (current?.transportKind == TransportCaps.USB_AOA.kind) current.close("физически отключён USB")
        else if (host == null) finish() // Existing host grace/teardown owns its own deadline and ACK.
        return true
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        AppLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_bridge), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detachReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(MainActivity.ACTION_PERMISSION)
        }, Context.RECEIVER_EXPORTED)
        audioDiag.start(this)
        notificationShade = com.headswap.desk.notify.HuNotificationShade(this).also { it.start() }
        unobserveWirelessEnabled = WirelessPrefs(this).observeEnabled { enabled ->
            if (!enabled) main.post {
                if (unobserveWirelessEnabled != null && !WirelessPrefs(this).enabled) onWirelessDisabled()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceStarts.delivered(startId)
        if (intent?.action == ACTION_DESKTOP_HOME) {
            val accepted = !stopRequested && session != null && host?.showDesktop() == true
            AppLog.i("DESKTOP home request accepted=$accepted", false)
            if (session == null && host == null && !linkWaiting) stopSelfResult(startId)
            return stickiness()
        }
        // A panel command belongs only to an existing session. A late UI click must
        // never create a foreground service, acquire USB, or start a new desktop.
        if (intent?.action == ACTION_PHONE_SCREEN_OFF || intent?.action == ACTION_PHONE_SCREEN_ON) {
            val off = intent.action == ACTION_PHONE_SCREEN_OFF
            val panelSelected = !off || DexPrefs(this).let { it.desktopPanelControl && it.phoneScreenMode == PhoneIdleMode.PANEL }
            val accepted = panelSelected && !stopRequested && session != null && host?.setPhoneScreenOff(off) == true
            AppLog.i("DEX phone panel request off=$off accepted=$accepted", false)
            if (session == null && host == null && !linkWaiting) stopSelfResult(startId)
            return stickiness()
        }
        // startForegroundService требует startForeground в течение 5 с, делаем сразу
        val n = HeadswapNotification.build(this, CHANNEL, NOTIF_ID)
        if (intent == null) {
            // процесс убит системой (LMK посреди сессии по воздуху, h610), Android поднял сервис по START_STICKY
            try {
                startForegroundTyped(n)
            } catch (e: Exception) {
                AppLog.e("сервис: перезапуск системой, foreground не дали", e)
                WLog.ev(WLog.TRIGGER, "sticky-fail", "err" to e.javaClass.simpleName)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            onStickyRestart()
            return stickiness()
        }
        startForegroundTyped(n)
        command(intent)
        return stickiness()
    }

    /**
     * Беспровод или авторежим включён: после убийства процесса Android перезапустит сервис ([onStickyRestart]).
     * Сам ACL_CONNECTED не придёт - Bluetooth машины не рвался, и без этого воздух не поднимался до следующей поездки (h610).
     */
    private fun stickiness() = if ((wifiBMode || autoMode) && !stopRequested) START_STICKY else START_NOT_STICKY

    /** Перезапуск после убийства процесса: состояние потеряно; выбранная машина рядом по Bluetooth - ждём её, как по ACL_CONNECTED. */
    private fun onStickyRestart() {
        AppLog.i("сервис: перезапущен системой после убийства процесса")
        val mac = CarProfile(this).btMac
        if (!BuildConfig.WIRELESS_BETA || mac == null || WirelessCarLife.unavailableReason(this) != null) {
            WLog.ev(WLog.TRIGGER, "sticky-restart", "resume" to false, "why" to "no-car")
            finish()
            return
        }
        CarBtState.check(this, mac) { near ->
            WLog.ev(WLog.TRIGGER, "sticky-restart", "near" to near)
            if (session != null || wifiBMode || stopRequested) return@check      // пока проверяли, пришла команда (ACL, кабель, Стоп)
            if (near) onCarAuto() else if (host == null && !linkWaiting) finish()
        }
    }

    private fun command(intent: Intent?) {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("сервис: стоп по команде")
            stopRequested = true
            usbHandoffFrom = null
            com.headswap.desk.dex.DesktopMusicPlayback.cancel("explicit Stop")
            cancelDesktopRecovery()
            cancelWirelessWaiting("стоп")
            val s = session
            if (s != null) {
                s.close("остановлено пользователем")        // onSessionClosed увидит stopRequested: AA закрывается сразу
            } else {
                val h = host
                if (h != null) h.shutdown("стоп пользователем") else finish()
            }
            return
        }
        // автостарт по Bluetooth машины: обрабатываем до проверки session, при идущей сессии нужен только флаг
        if (session == null && com.headswap.desk.dex.PhonePairingService.blocksProjectionForCommittedActivation(this)) {
            CarLifeState.status = "Завершаю проверку активации рабочего стола…"
            AppLog.i("DESKTOP transport start deferred until phone activation completes", false)
            failStart()
            return
        }
        if (intent?.action == ACTION_WIFI_B_AUTO) {
            onCarAuto()
            return
        }
        if (intent?.action == ACTION_WIFI_B_AUTO_OFF) {
            onCarAutoOff()
            return
        }
        val existing = session
        if (existing != null && intent?.action == null) {
            val usb = getSystemService(UsbManager::class.java)
            val accessory = usb.accessoryList.orEmpty().firstOrNull(Actions::isCompatibleAccessory)
            if (WirelessConnectionPolicy.mayHandoffToUsb(WirelessPrefs(this).mode,
                    existing.transportKind == TransportCaps.TCP6.kind, accessory != null,
                    accessory?.let(usb::hasPermission) == true)) {
                if (usbHandoffFrom == null) {
                    usbHandoffFrom = existing
                    cancelWirelessWaiting("автоматическое переключение на USB")
                    existing.close("автоматическое переключение на USB")
                }
                return
            }
        }
        if (session != null) {
            AppLog.i("сервис: сессия уже идёт, повторный старт игнорирую")
            return
        }

        // только debug-сборка: вместо USB-аксессуара TCP к эмулятору ГУ на ПК (adb reverse), в release port() всегда null
        val dbgPort = DebugTransport.port(intent)
        if (dbgPort != null) {
            AppLog.i("сервис: debug-транспорт, подключаюсь к 127.0.0.1:$dbgPort")
            Thread({
                val pfd = DebugTransport.connect(dbgPort)
                main.post {
                    if (pfd == null) {
                        CarLifeState.status = "debug TCP: нет соединения"
                        failStart()
                    } else if (session != null) {
                        pfd.close()
                    } else {
                        startSession(StreamTransport.fromPfd(pfd, TransportCaps.TCP_DEBUG, "TCP 127.0.0.1:$dbgPort"))
                    }
                }
            }, "debug-tcp").start()
            return
        }

        // только debug-сборка: беспроводной формат, 6 TCP-сокетов на 127.0.0.1 (adb forward), ГУ подключается сама
        if (DebugTransport.tcp6(intent)) {
            startTcp6Debug(DebugTransport.tcp6Bind(intent))
            return
        }
        // беспровод, ветка B (хотспот телефона + маяк): кнопка в настройках бета-сборки или debug-вход
        if (intent?.action == ACTION_WIFI_B || DebugTransport.wifiB(intent)) {
            if (WirelessPrefs(this).mode == CarLifeConnectionMode.AUTO && WirelessCarLife.hasCompatibleUsb(this)) {
                Actions.scan(this, "AUTO: USB before wireless start")
                return
            }
            val unavailable = WirelessCarLife.unavailableReason(this)
            if (unavailable != null) {
                CarLifeState.status = unavailable
                failStart()
                return
            }
            if (wifiBMode && linkWaiting && !stopRequested) {
                // повторное нажатие, ожидание уже идёт: счётчики accept/TARGET_INFO не сбрасываем (иначе ложный bt-hint, h126)
                autoStarted = false
                WLog.ev(WLog.TRIGGER, "manual-mark", "why" to "waiting")
                return
            }
            wifiBMode = true
            autoStarted = false      // ручной запуск: уход машины по Bluetooth его не гасит
            stopRequested = false
            beginWait()
            startWifiB()
            btProbe.start()      // после WLog.begin в startWifiB: t= в строках WL bt от начала попытки
            return
        }

        if (WirelessPrefs(this).mode == CarLifeConnectionMode.WIFI) {
            CarLifeState.status = "Выбрано подключение CarLife по Wi-Fi"
            failStart()
            return
        }
        val usb = getSystemService(UsbManager::class.java)
        val acc = usb.accessoryList?.firstOrNull(Actions::isCompatibleAccessory)
        if (acc == null) {
            AppLog.i("сервис: аксессуара нет, останавливаюсь")
            CarLifeState.status = "аксессуара нет"
            failStart()
            return
        }
        if (!usb.hasPermission(acc)) {
            AppLog.i("сервис: нет разрешения на аксессуар, останавливаюсь")
            CarLifeState.status = "нет разрешения на аксессуар"
            failStart()
            return
        }
        val pfd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            AppLog.e("openAccessory", e)
            null
        }
        if (pfd == null) {
            AppLog.i("сервис: openAccessory вернул null")
            CarLifeState.status = "openAccessory: ошибка"
            failStart()
            return
        }
        AppLog.i("сервис: аксессуар открыт: ${MainActivity.describe(acc)}")
        if (wifiBMode || pendingTcp6 != null) {
            // кабель побеждает воздух: ожидание машины по Wi-Fi отменяем; после USB-сессии возобновим, только если
            // машина рядом по Bluetooth и кабеля нет (usbToAir)
            AppLog.i("сервис: кабель подключён, ожидание по Wi-Fi отменяю (USB побеждает)")
            WLog.ev(WLog.LINK, "usb-wins")
            wifiBMode = false
            autoStarted = false
            cancelHint()
            stopCarNet(CarNetEvent.USB_WINS, "usb")
            CarLifeState.wireless = null
            btProbe.stop("кабель")
            cancelLinkWait()
        }
        startSession(StreamTransport.fromPfd(pfd, TransportCaps.USB_AOA, MainActivity.describe(acc)))
    }

    /** debug tcp6: слушать 6 портов на loopback, ждать ГУ (pc-headunit --transport tcp6), затем обычная сессия. */
    private fun startTcp6Debug(bind: String) = startTcp6("tcp6", bind, TCP6_WAIT_MS, beacon = null)

    /**
     * Ветка B (хотспот телефона): маяк «CarlifeHost» на 7999 и ответчик 8999, 6 портов на 0.0.0.0, пускаем только
     * адреса из подсетей, куда идёт маяк. Хотспот включает человек. Пока только debug-вход (`--ez debug_wifi_b true`).
     */
    private fun startWifiB() {
        CarLifeState.wireless = waitingText("…")
        // телефон уже в сети машины (WifiNetworkSpecifier): маяк и ответчик только в ней, сокеты привязаны к этой сети
        val carIface = carNetImpl?.ifaceName
        val carNetwork = if (carIface != null) carNetImpl?.current else null
        val beacon = HotspotBeacon(
            ifaces = { LanIface.scan().let { all -> if (carNetwork != null) all.filter { it.name == carIface } else all } },
            bindNetwork = carNetwork,
            onIfaces = { list ->
                // Wi-Fi Direct-машине хотспот не нужен, а включённый он занимает Wi-Fi Direct (h214): не просим его включить
                if (wifiBMode && !stopRequested) {
                    CarLifeState.wireless = if (list.isEmpty()) {
                        if (wifiDirectCar) waitingText("Wi-Fi Direct") else getString(R.string.wl_state_no_hotspot)
                    } else waitingText(list.joinToString(", ") { it.name })
                }
            },
        )
        startTcp6("wifi-b", "0.0.0.0", WIFI_B_WAIT_MS, beacon)
        if (linkWaiting) WLog.ev(WLog.LINK, "car-wifi", "carWifi" to if (CarWifi(this).isSet) "set" else "unset", "bound" to (carNetwork != null))
        if (autoMode && !hintShown && linkWaiting) {
            main.removeCallbacks(hintTick)
            main.postDelayed(hintTick, AUTO_HINT_MS)
        }
        if (linkWaiting) scheduleCarNet()
    }

    /** Новое ожидание по Wi-Fi (авто или ручное): сброс счётчиков и таймер подсказки про Bluetooth. */
    private fun beginWait() {
        waitSince = SystemClock.elapsedRealtime()
        carBtNear = false
        p2pThisWait = false
        carSkipLogged = false
        btAcceptsThisWait = 0
        btHintShown = false
        main.removeCallbacks(btHintTick)
        main.postDelayed(btHintTick, BT_HINT_MS)
    }

    private fun markCarSeen() {
        carSeenAt = SystemClock.elapsedRealtime()
    }

    private fun sinceCarMs(): Long? = if (carSeenAt == 0L) null else SystemClock.elapsedRealtime() - carSeenAt

    /**
     * Запрос сети машины: через [CAR_NET_DELAY_MS] от начала ожидания (если сеть задана), а если машина только что была
     * на связи - сразу ([CarNetPolicy.delayMs]). Wi-Fi Direct-машине не нужен.
     */
    private fun scheduleCarNet() {
        main.removeCallbacks(carNetTick)
        if (!CarWifi(this).isSet) return
        if (wifiDirectCar) {
            logCarSkip()
            return
        }
        val since = sinceCarMs()
        val proven = CarWifi(this).proven
        val delay = CarNetPolicy.delayMs(since, carBtNear, proven)
        if (delay == 0L) WLog.ev(WLog.LINK, "car-net-now", "sinceCarMs" to since, "carBt" to carBtNear, "proven" to proven)
        main.postDelayed(carNetTick, (delay - (SystemClock.elapsedRealtime() - waitSince)).coerceAtLeast(0))
        if (delay > 0L) checkCarBtNear()
    }

    /** Выбранная машина подключена по Bluetooth: она рядом, сеть машины просим сразу. Без выбранной машины не смотрим (наушники). */
    private fun checkCarBtNear() {
        val mac = CarProfile(this).btMac ?: return
        CarBtState.check(this, mac) { near ->
            if (!near || carBtNear || !wifiBMode || stopRequested || !linkWaiting || session != null) return@check
            carBtNear = true
            scheduleCarNet()
        }
    }

    private fun requestCarNet() {
        val w = CarWifi(this)
        val ssid = w.ssid ?: return
        val ok = CarNetPolicy.shouldRequest(
            carWifiSet = w.isSet,
            autoOrManualWaiting = wifiBMode && linkWaiting && !stopRequested,
            sessionActive = session != null,
            usbActive = session?.transportKind == TransportCaps.USB_AOA.kind,
            alreadyRequesting = carNetBusy,
            waitedMs = SystemClock.elapsedRealtime() - waitSince,
            sixOfSixReady = sixReady,
            wifiDirectCar = wifiDirectCar,
            delayMs = CarNetPolicy.delayMs(sinceCarMs(), carBtNear, w.proven),
        )
        if (!ok) return
        carNet.request(ssid, w.pass, CAR_NET_TIMEOUT_MS, ::onCarNetAvailable, ::onCarNetUnavailable, ::onCarNetLost)
    }

    /** Остановка ожидания (или смерть сервиса): снять отложенный запрос и, по [CarNetPolicy], сам запрос сети. */
    private fun stopCarNet(event: CarNetEvent, why: String) {
        main.removeCallbacks(carNetTick)
        restartingForCarNet = false
        if (CarNetPolicy.shouldRelease(event)) carNetImpl?.release(why)
    }

    private fun logCarSkip() {
        if (carSkipLogged) return
        carSkipLogged = true
        WLog.ev(WLog.LINK, "car-skip", "why" to "wifi-direct")
    }

    /** ГУ прислала TARGET_INFO (Wi-Fi Direct): сеть машины не нужна, wifi-подсказка тоже. */
    private fun onBtTargetInfo(mac: String?) {
        if (!wifiBMode || stopRequested) return
        val profile = CarProfile(this)
        // машина выбрана и это она: запоминаем навсегда; не выбрана или чужая - только на это ожидание
        if (CarMac.same(profile.btMac, mac)) profile.connMode = CarConnMode.BT_CARLIFE
        p2pThisWait = true
        main.removeCallbacks(carNetTick)
        if (carNetBusy) carNetImpl?.release("p2p")      // колбэки снимаются без вызовов: onUnavailable/onLost не придут
        if (CarWifi(this).isSet) logCarSkip()
        cancelHint()
    }

    /** discoverPeers вернул BUSY: на Samsung это включённая точка доступа, просим её выключить. */
    private fun onP2pBusy() {
        if (!wifiBMode || stopRequested || !linkWaiting) return
        CarLifeState.wireless = getString(R.string.wl_state_p2p_busy)
    }

    private fun onBtAccept() {
        if (!wifiBMode || stopRequested) return
        btAcceptsThisWait++
        cancelBtHint()
    }

    private fun onCarNetAvailable(@Suppress("UNUSED_PARAMETER") network: Network) {
        markCarSeen()
        restartForCarNet()
    }

    /** Сеть машины появилась или пропала, пока ждём: ожидание заново, чтобы маяк шёл (или перестал идти) через неё. Сессию не трогаем. */
    private fun restartForCarNet() {
        if (!wifiBMode || stopRequested || session != null || sixReady || !linkWaiting || wifiDirectCar) return
        val l = pendingTcp6
        if (l == null) {      // слушатель ещё открывается (или уже отдал результат): повторим, пока ждём
            main.postDelayed({ restartForCarNet() }, 300)
            return
        }
        restartingForCarNet = true
        WLog.ev(WLog.LINK, "rearm", "after" to "car-net")
        l.abort()
    }

    private fun onCarNetUnavailable(reason: String) {
        if (wifiDirectCar) return
        if (autoMode && !hintShown) showWifiHint()      // сети машины не будет: пусть человек подключит Wi-Fi сам
    }

    private fun onCarNetLost() {
        markCarSeen()
        if (!wifiBMode || stopRequested || session != null) return
        restartForCarNet()
        scheduleCarNet()
    }

    /** Статус ожидания; в авторежиме с пометкой, что машина рядом по Bluetooth. */
    private fun waitingText(ifaces: String) = getString(if (autoMode) R.string.wl_state_auto else R.string.wl_state_waiting, ifaces)

    /** ACL_CONNECTED выбранной машины: ждать по Wi-Fi, пока она рядом. Идущую сессию (USB или воздух) не трогаем. */
    private fun onCarAuto() {
        if (WirelessPrefs(this).mode == CarLifeConnectionMode.AUTO && WirelessCarLife.hasCompatibleUsb(this)) {
            Actions.scan(this, "AUTO: USB before Bluetooth wireless trigger")
            return
        }
        val unavailable = WirelessCarLife.unavailableReason(this)
        if (unavailable != null) {
            CarLifeState.status = unavailable
            failStart()
            return
        }
        cancelAutoOff()
        autoMode = true
        autoStarted = true
        if (session != null) {
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "session", "kind" to session?.transportKind)
            return
        }
        if (wifiBMode && linkWaiting) {
            // всплеск ACL: ожидание уже идёт. Без RFCOMM и группы перезапускаем пробник - ГУ открывает SPP на свежих слушателях (h211)
            val restart = btProbe.idle()
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "waiting", "btRestart" to restart)
            if (restart) btProbe.restart("ACL вернулся")
            return
        }
        hintShown = false
        wifiBMode = true
        stopRequested = false
        beginWait()
        startWifiB()
        btProbe.start()
        WLog.ev(WLog.TRIGGER, "auto-start")
    }

    /** ACL_DISCONNECTED выбранной машины: гасим не сразу (всплески ACL у ГУ), а через [AUTO_OFF_DEBOUNCE_MS]. */
    private fun onCarAutoOff() {
        if (session == null && host == null && !linkWaiting && !wifiBMode) {
            // сервис поднят с нуля только ради отбоя (процесс убит, ресивер дошёл до пустого сервиса): гасить нечего
            autoMode = false
            finish()
            return
        }
        autoOff.arm()
        main.removeCallbacks(autoOffTick)
        main.postDelayed(autoOffTick, AUTO_OFF_DEBOUNCE_MS)
        WLog.ev(WLog.TRIGGER, "auto-off-armed", "ms" to AUTO_OFF_DEBOUNCE_MS)
    }

    private fun cancelAutoOff() {
        autoOff.cancel()
        main.removeCallbacks(autoOffTick)
    }

    /** Машина ушла по Bluetooth и новый ACL_CONNECTED не пришёл: авторежим выключаем. USB-сессию не трогаем никогда. */
    private fun applyAutoOff() {
        autoMode = false
        cancelHint()
        if (session != null) {
            // сессия идёт (воздух: ГУ может держать Wi-Fi дольше Bluetooth; кабель - не наше дело); после её конца ожидание не возобновим
            WLog.ev(WLog.TRIGGER, "auto-off", "session" to true)
            return
        }
        if (wifiBMode && autoStarted) {
            // порядок важен: режим выключен до abort, ветка отказа в startTcp6 увидит wifiBMode=false
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            cancelLinkWait()
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to true)
            failStart()
        } else {
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to false)
        }
    }

    /**
     * Через [USB_TO_AIR_MS] после конца USB-сессии: кабеля нет (на G6S EIO часто тут же переподключает аксессуар),
     * машина рядом по Bluetooth - уведомление «пробую по воздуху» и ожидание, как по ACL_CONNECTED.
     */
    private fun usbToAir() {
        usbToAirPending = false
        val fallback = WirelessConnectionPolicy.fallbackAfterUsb(WirelessPrefs(this).mode,
            WirelessCarLife.hasConfiguredCar(this), autoMode)
        if (session != null || wifiBMode || !fallback || stopRequested) {
            if (session == null && host == null && !linkWaiting && !wifiBMode) finish()
            return
        }
        if (getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true) {
            WLog.ev(WLog.LINK, "usb-to-air", "skip" to "кабель на месте")
            if (session == null && host == null) finish()
            return
        }
        WLog.ev(WLog.LINK, "usb-to-air")
        AppLog.i("сервис: кабель отключён, машина рядом по Bluetooth - жду её по воздуху")
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, com.headswap.desk.dex.DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setLargeIcon(BrandArtwork.bitmap(resources))
            .setContentTitle(getString(R.string.wl_usb_to_air_title))
            .setContentText(getString(R.string.wl_usb_to_air_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(USB_TO_AIR_NOTIF_MS)
            .build()
        getSystemService(NotificationManager::class.java).notify(USB_TO_AIR_NOTIF_ID, n)
        onCarAuto()
    }

    /** Снять обе подсказки ожидания (про Wi-Fi и про Bluetooth) и их таймеры. */
    private fun cancelHint() {
        main.removeCallbacks(hintTick)
        getSystemService(NotificationManager::class.java).cancel(HINT_NOTIF_ID)
        cancelBtHint()
    }

    private fun cancelBtHint() {
        main.removeCallbacks(btHintTick)
        getSystemService(NotificationManager::class.java).cancel(BT_HINT_NOTIF_ID)
        cancelWifiOff()
    }

    private fun cancelWifiOff() {
        if (!wifiOffShown) return
        wifiOffShown = false
        getSystemService(NotificationManager::class.java).cancel(WIFI_OFF_NOTIF_ID)
    }

    /**
     * Вход в группу ГУ упёрся в выключенный Wi-Fi (Android держит P2P выключенным, discover отвечает BUSY): просим включить
     * Wi-Fi и выключить точку доступа. Wi-Fi включили - снимаем просьбу, P2pJoin входит сам. Нажатие открывает панель Wi-Fi.
     */
    private fun onP2pWifi(on: Boolean) {
        if (on) {
            if (CarLifeState.wireless == getString(R.string.wl_state_wifi_off)) CarLifeState.wireless = waitingText("…")
            cancelWifiOff()
            return
        }
        if (!wifiBMode || stopRequested || session != null) return
        wifiOffShown = true
        CarLifeState.wireless = getString(R.string.wl_state_wifi_off)
        val open = PendingIntent.getActivity(
            this, 4, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setLargeIcon(BrandArtwork.bitmap(resources))
            .setContentTitle(getString(R.string.wl_wifi_off_title))
            .setContentText(getString(R.string.wl_wifi_off_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.wl_wifi_off_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WIFI_OFF_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-off-hint")
    }

    /** 30 с ожидания в авторежиме, а 6/6 нет: ГУ, возможно, не в сети телефона. Нажатие открывает панель Wi-Fi. */
    private fun showWifiHint() {
        if (!autoMode || !wifiBMode || !linkWaiting || session != null || hintShown) return
        if (wifiDirectCar) return      // Wi-Fi Direct: телефон сам входит в группу ГУ, Wi-Fi подключать не нужно
        // сеть машины запрашивается или уже получена: человеку подключать Wi-Fi не нужно, подсказка придёт по onUnavailable
        if (carNetBusy) return
        hintShown = true
        val open = PendingIntent.getActivity(
            this, 2, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setLargeIcon(BrandArtwork.bitmap(resources))
            .setContentTitle(getString(R.string.wl_hint_title))
            .setContentText(getString(R.string.wl_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(HINT_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-hint")
    }

    /** 15 с ожидания, а ГУ к RFCOMM `carlife` не подключалась: если телефон с машиной по Bluetooth, просим переподключить его. */
    private fun checkBtHint() {
        if (!wifiBMode || stopRequested || session != null || btHintShown || btAcceptsThisWait > 0 || p2pThisWait) return
        CarBtState.check(this, CarProfile(this).btMac) { carBt ->
            val mode = CarProfile(this).connMode
            val show = BtReconnectHint.shouldShow(
                waiting = wifiBMode && linkWaiting && !stopRequested,
                sessionActive = session != null,
                waitedMs = SystemClock.elapsedRealtime() - waitSince,
                acceptsThisWait = btAcceptsThisWait,
                targetInfoThisWait = p2pThisWait,
                carBtConnected = carBt,
                mode = mode,
                alreadyShown = btHintShown,
            )
            if (show) showBtHint(carBt, mode)
        }
    }

    /** Нажатие открывает настройки Bluetooth. */
    private fun showBtHint(carBt: Boolean, mode: CarConnMode) {
        btHintShown = true
        val open = PendingIntent.getActivity(
            this, 3, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setLargeIcon(BrandArtwork.bitmap(resources))
            .setContentTitle(getString(R.string.wl_bt_hint_title))
            .setContentText(getString(R.string.wl_bt_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(BT_HINT_NOTIF_ID, n)
        CarLifeState.btProbe = "машина подключена по Bluetooth, но carlife не открыла: переподключите Bluetooth"
        WLog.ev(WLog.TRIGGER, "bt-hint", "carBt" to carBt, "mode" to mode.name)
    }

    /** Invalidates both a waiting listener and a worker which has not opened its sockets yet. */
    private fun cancelLinkWait() {
        synchronized(linkLock) {
            linkEpoch++
            pendingTcp6?.abort()
            pendingTcp6 = null
        }
        linkWaiting = false
        restartingForCarNet = false
    }

    /** All wireless startup resources have the same owner and cancellation boundary. */
    private fun cancelWirelessWaiting(reason: String) {
        wifiBMode = false
        autoMode = false
        autoStarted = false
        usbToAirPending = false
        main.removeCallbacks(usbToAirTick)
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.STOP, reason)
        CarLifeState.wireless = null
        btProbe.stop(reason)
        cancelLinkWait()
    }

    /** Opting out must cancel pending connections immediately without stopping an active USB link. */
    private fun onWirelessDisabled() {
        AppLog.i("WIRELESS disabled: cancelling wireless output and startup", false)
        if (session?.transportKind == TransportCaps.TCP6.kind) {
            command(Intent(this, BridgeService::class.java).setAction(ACTION_STOP))
        } else {
            cancelWirelessWaiting("беспроводное подключение выключено")
            // A warm desktop, if any, retains its existing grace period and teardown ACK.
            if (session == null && host == null) finish()
        }
    }

    private fun startTcp6(kind: String, bind: String, waitMs: Long, beacon: HotspotBeacon?) {
        if (linkWaiting) {
            AppLog.i("сервис: $kind: уже жду ГУ, повторный старт игнорирую")
            return
        }
        linkWaiting = true
        val attempt = synchronized(linkLock) { ++linkEpoch }
        sixReady = false
        WLog.begin(kind, "bind" to bind, "ACCESS_LOCAL_NETWORK" to NetProbe.localNetworkGranted(this))
        AppLog.i("сервис: $kind, слушаю 6 портов на $bind, жду ГУ ${waitMs / 1000} с")
        CarLifeState.status = "$kind: жду ГУ"
        val waitStart = SystemClock.elapsedRealtime()
        Thread({
            var hotspotPeer = false      // 6/6 собрала не P2P-группа ГУ, а подсеть маяка
            val result: Any = try {
                val peerOk: (java.net.InetAddress) -> Boolean =
                    if (beacon == null) { _ -> true } else { a -> beacon.current.any { it.contains(a) } || btProbe.ownsPeer(a) }
                val l = synchronized(linkLock) {
                    if (attempt != linkEpoch) throw LinkSetupException("подключение отменено")
                    Tcp6Listener(java.net.InetAddress.getByName(bind), peerOk = peerOk).open().also {
                        pendingTcp6 = it
                        beacon?.start()
                    }
                }
                l.await(waitMs, "$kind $bind").also { hotspotPeer = beacon != null && l.peerAddress?.let { !btProbe.ownsPeer(it) } == true }
            } catch (e: Exception) {
                e
            } finally {
                synchronized(linkLock) { if (attempt == linkEpoch) pendingTcp6 = null }
            }
            beacon?.stop(if (result is CarTransport) "6/6" else "нет ГУ")
            main.post {
                if (attempt != linkEpoch || stopRequested) {
                    if (result is CarTransport) result.close()
                    return@post
                }
                linkWaiting = false
                val restarting = restartingForCarNet
                restartingForCarNet = false
                when {
                    result is CarTransport && !WirelessConnectionPolicy.acceptAttempt(attempt, linkEpoch, stopRequested, session != null) -> result.close()
                    result is CarTransport -> {
                        sixReady = true
                        // подключение без Wi-Fi Direct: этой машине P2P не нужен (если в этом ожидании TARGET_INFO был, режим не трогаем)
                        if (hotspotPeer && !p2pThisWait) CarProfile(this).connMode = CarConnMode.HOTSPOT
                        // 6/6 пришли из сети машины (маяк ходил только в ней): дальше просим её сразу, без CAR_NET_DELAY_MS
                        if (hotspotPeer && carNetImpl?.current != null) CarWifi(this).let { w ->
                            if (w.isSet && !w.proven) {
                                w.markProven()
                                WLog.ev(WLog.LINK, "car-net-proven")
                            }
                        }
                        if (beacon != null) {
                            cancelHint()      // 6/6: подсказка про Wi-Fi больше не нужна
                            CarLifeState.wireless = getString(R.string.wl_state_connected)
                        }
                        startSession(result)
                    }
                    // abort ради нового маяка в сети машины: не сдача, режим и статус не трогаем, через паузу ждём заново
                    restarting && beacon != null && wifiBMode && !stopRequested && session == null -> {
                        main.postDelayed({
                            if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                        }, WIFI_B_REARM_MS)
                    }
                    else -> {
                        val msg = (result as Exception).message
                        if (result !is LinkSetupException) AppLog.e(kind, result)
                        AppLog.i("сервис: $msg")
                        // авторежим: машина рядом по Bluetooth, таймаут не сдача, а новый круг ожидания. Отказ раньше срока
                        // (порт занят и т.п.) сюда не попадает: иначе цикл раз в 1,5 с
                        val timedOut = SystemClock.elapsedRealtime() - waitStart >= waitMs
                        if (beacon != null && wifiBMode && autoMode && !stopRequested && session == null && timedOut) {
                            WLog.ev(WLog.LINK, "rearm", "after" to "timeout-auto")
                            main.postDelayed({
                                if (wifiBMode && autoMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                            }, WIFI_B_REARM_MS)
                            return@post
                        }
                        if (beacon != null && wifiBMode) {
                            wifiBMode = false
                            autoStarted = false
                            cancelHint()
                            stopCarNet(CarNetEvent.AUTO_OFF, "gave-up")      // ожидание закончилось: сеть машины больше не нужна
                            btProbe.stop("не дождались")
                            CarLifeState.wireless = getString(R.string.wl_state_gave_up, (WIFI_B_WAIT_MS / 60_000).toInt())
                        }
                        // отмена ожидания кабелем: статус уже USB-сессии, не перетираем
                        if (session == null) CarLifeState.status = msg ?: "$kind: ошибка"
                        failStart()
                    }
                }
            }
        }, "link-$kind").start()
    }

    private fun startSession(transport: CarTransport) {
        lastSessionWasUsb = transport.caps.kind == TransportCaps.USB_AOA.kind
        physicalUsbDetached = false
        main.removeCallbacks(usbToAirTick)
        usbToAirPending = false
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        main.removeCallbacks(carNetTick)      // сессия идёт: новых запросов сети машины нет (сам запрос держим, CarNetEvent.SESSION_START)
        cancelBtHint()
        CarLifeState.accessory = transport.label
        CarLifeState.wirelessSession = transport.caps.kind == TransportCaps.TCP6.kind
        if (transport.caps.kind != TransportCaps.TCP6.kind) WLog.begin(transport.caps.kind)

        val aaPrefs = AaPrefs(this)
        val dexPrefs = DexPrefs(this)
        val desktopSelection = dexPrefs.desktopVideoSelection()
        val desktopVideo = desktopSelection.takeIf { dexPrefs.useDesktopService }
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val desktopOutput = transport.caps.kind == TransportCaps.USB_AOA.kind ||
            transport.caps.kind == TransportCaps.TCP6.kind
        val clusterTransport = desktopOutput || transport.caps.kind == TransportCaps.TCP_DEBUG.kind
        val cfg = SessionConfig(
            screenOn, false, if (desktopVideo != null) com.headswap.desk.aa.FpsSetting.AUTO else aaPrefs.fpsSetting,
            metaMusic = clusterTransport && aaPrefs.metaMusic, metaNavi = clusterTransport && aaPrefs.metaNavi,
            naviField6 = aaPrefs.naviField6, desktopVideo = desktopVideo,
            desktopBitrateBps = dexPrefs.desktopBitrateMbps * 1_000_000,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // A fresh physical/transport session may retry twice; recovering a helper never resets this budget.
        cancelDesktopRecovery()
        desktopRecovery.newConnection()
        // Keep the DeX source through a short USB reconnect, using BridgeHost's existing grace lifecycle.
        var h = host
        val wantMode: VideoGeometry = desktopVideo?.initialGeometry() ?: VideoMode.R720
        val wantDensity = desktopSelection.densityFor(wantMode)
        // Cancellation and the alive check must hold the same host lock. Otherwise the
        // grace timer can retire it between isDead and the later onUsbSessionStarted call.
        if (h == null || !h.reserveSession()) {
            h = createHost(aaPrefs, wantMode, wantDensity, previous = h)
            host = h
            if (h == null) {
                // Source construction failed; do not leave a USB transport open without a video source.
                transport.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return
            }
        } else {
            AppLog.i("DEX: живой источник закреплён за новым соединением")
        }
        // Preserve CarLife pacing, repeated static frames and the established H.264 encoder.
        h.core.transcodeEnabled = true
        h.core.spliceMs = 0L
        h.core.carVwId = aaPrefs.carPreset == CarPreset.VW_ID_UNYX
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, h.core, PhoneThermal(this), CallStateSource.NONE,
            brandArtwork = BrandArtwork.bitmap(resources),
            onHuSize = { w, hh -> aaPrefs.saveHuSize(w, hh) },
            onNavigationInterestChanged = { main.post { if (!stopRequested && session === sref[0]) clusterNavigation?.subscriptionChanged() } },
            onDesktopOutputLost = { owner, hadPicture -> main.post {
                if (!stopRequested && session === owner)
                    com.headswap.desk.dex.DesktopMusicPlayback.usbOutputLost(this, owner, hadPicture && DexState.running)
            } }
        ) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        com.headswap.desk.dex.DesktopMusicPlayback.attach(applicationContext, this, s,
            desktopOutput,
            ready = { session === s && DexState.running && s.hasFreshDesktopVideo() },
            audioReady = { !DexPrefs(this).usbPlaybackAudio || !h.core.audioViaUsb ||
                com.headswap.desk.dex.DesktopAudioState.running })
        desktopAudio.attach(h.core, desktopOutput) {
            session === s && s.canCaptureDesktopAudio()
        }
        com.headswap.desk.dex.DelegatedPanelAccess.attachOutput(s) {
            session === s && desktopOutput && s.canCaptureDesktopAudio()
        }
        powerDiagnostics?.stop()
        powerDiagnostics = if (desktopOutput) com.headswap.desk.dex.DesktopPowerDiagnostics(this).also { it.start() } else null
        phoneIdleTimer?.stop()
        phoneIdleTimer = if (desktopOutput) {
            com.headswap.desk.dex.CarLifePhoneIdleTimer(this,
                sessionReady = { !stopRequested && session === s && DexState.running && s.healthyDesktopForIdleTimer() },
                requestOff = { session === s && h.setPhoneScreenOff(true) }).also { it.start() }
        } else null
        s.start()
        startClusterSources(s, cfg, clusterTransport, aaPrefs)
        if (!h.onUsbSessionStarted()) {
            s.close("источник рабочего стола завершился перед подключением")
            h.shutdown("завершившийся источник при подключении")
            return
        }
        if (transport.caps.kind == TransportCaps.USB_AOA.kind) Actions.onUsbSessionStarted()
    }

    private fun startClusterSources(owner: CarLifeSession, config: SessionConfig, clusterTransport: Boolean, prefs: AaPrefs) {
        stopClusterSources()
        if (config.metaMusic) {
            clusterMedia = ClusterMediaSource(applicationContext) { value ->
                if (!stopRequested && session === owner) owner.onClusterMedia(value)
            }.also { source ->
                runCatching { source.start() }.onFailure { AppLog.e("CLUSTER: music source unavailable", it) }
            }
        }
        if (clusterTransport) {
            unobserveNavigationHints = prefs.observeNavigationHints { enabled ->
                if (!stopRequested && session === owner) updateNavigationHints(owner, enabled)
            }
        }
    }

    private fun updateNavigationHints(owner: CarLifeSession, enabled: Boolean) {
        owner.setNavigationHints(enabled)
        if (!enabled) {
            clusterNavigation?.close()
            clusterNavigation = null
            foregroundGuidance?.close()
            foregroundGuidance = null
        } else if (clusterNavigation == null) {
            val foreground = if (android.os.Build.VERSION.SDK_INT >= 36)
                CarLifeForegroundGuidance(applicationContext) { owner.guidanceInterest } else null
            foregroundGuidance = foreground
            val source = ClusterNavigationSource(applicationContext, { owner.guidanceInterest }, foreground?.let { it::request }) { hint ->
                if (!stopRequested && session === owner) owner.onClusterNavigation(hint)
            }
            runCatching { source.start(); clusterNavigation = source }.onFailure {
                source.close()
                foreground?.close()
                foregroundGuidance = null
                AppLog.e("CLUSTER: navigation source unavailable", it)
            }
        }
    }

    private fun stopClusterSources() {
        unobserveNavigationHints?.invoke()
        unobserveNavigationHints = null
        runCatching { clusterMedia?.close() }.onFailure { AppLog.e("CLUSTER: music source cleanup", it) }
        runCatching { clusterNavigation?.close() }.onFailure { AppLog.e("CLUSTER: navigation source cleanup", it) }
        foregroundGuidance?.close()
        foregroundGuidance = null
        clusterMedia = null
        clusterNavigation = null
    }

    /** DeX uses the existing CarLife video pipeline; Android Auto certificates are not involved. */
    private fun createHost(aaPrefs: AaPrefs, mode: VideoGeometry, densityOverride: Int, previous: BridgeHost?,
        recoveredCore: AaBridgeCore? = null): BridgeHost? {
        return try {
            AaState.setupError = null
            AaState.reset()
            AaState.cert = "не требуется (DeX)"
            AaState.status = "DeX: подключение"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = recoveredCore ?: AaBridgeCore(
                log,
                clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 },
                connectingText = "Подключаю DeX…",
                waitingText = "DeX: жду кадр…",
                disconnectedText = "DeX отключился. Остановите и подключите заново.",
            )
            core.micModeProvider = { MicMode.CAR }
            core.phoneMic = null
            core.usbAudioEnabled = false
            core.mapHomeKey = true
            core.desktopInputEnabled = true
            core.touchDecimationProvider = { aaPrefs.touchDecimation }
            core.transcodeEnabled = true
            core.refDecoderFactory = { sink -> RefDecoder(sink) { AppLog.i(it, false) } }
            core.videoMode = mode
            core.densityOverride = densityOverride
            core.marginsAtBottom = false
            core.spliceMs = 0L
            core.expectFeatureConfig = false
            val ref = arrayOfNulls<BridgeHost>(1)
            val access = DexPrefs(applicationContext)
            val conn = when {
                access.useDelegated -> DelegatedDexConnector(applicationContext, core) { reason ->
                    val mapped = if (reason == DelegatedDexConnector.Failure.HELPER_UNAVAILABLE)
                        ShizukuDexConnector.Failure.HELPER_UNAVAILABLE else ShizukuDexConnector.Failure.OTHER
                    main.post { ref[0]?.let { onDesktopSourceFailed(it, mapped) } }
                }
                access.useShizuku -> ShizukuDexConnector(applicationContext, core) { reason ->
                    main.post { ref[0]?.let { onDesktopSourceFailed(it, reason) } }
                }
                else -> DexConnector(applicationContext, core)
            }
            AppLog.i("DEX: видео ${mode.outW}x${mode.outH} через CarLife; USB playback audio opt-in; микрофон CarLife не передаётся")
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post {
                    ref[0]?.let(::onDesktopHostTornDown)
                }
            })
            ref[0] = h
            h
        } catch (e: Exception) {
            AppLog.e("DEX: мост не стартовал", e)
            DexState.error = "${e.javaClass.simpleName}: ${e.message}"
            AaState.setupError = DexState.error
            AaState.status = "ошибка: DeX не стартовал"
            null
        }
    }

    /** All recovery state belongs to the foreground service, never the setup Activity. */
    private fun canRecoverDesktop(current: CarLifeSession, isolatedSession: Boolean): Boolean = runCatching {
        alive && !stopRequested && session === current && DexPrefs(this).useDesktopService &&
            (!isolatedSession || com.headswap.desk.dex.DesktopDisplayBackendPolicy.requiresVdm(
                android.os.Build.VERSION.SDK_INT, DexPrefs(this).desktopMode)) &&
            DesktopAccess.isReady(this) && DesktopRecoveryPolicy.phoneStateEligible(
                getSystemService(PowerManager::class.java).isInteractive,
                getSystemService(KeyguardManager::class.java).let { it.isDeviceLocked || it.isKeyguardLocked },
                isolatedSession) &&
            DesktopRecoveryPolicy.transportEligible(current.transportKind, current.outputConnected,
                usbAutoStartStopped = Actions.isUsbAutoStartStopped(),
                permittedAccessoryPresent = current.transportKind == TransportCaps.USB_AOA.kind &&
                    getSystemService(UsbManager::class.java).let { usb ->
                        usb.accessoryList.orEmpty().any { Actions.isCompatibleAccessory(it) && usb.hasPermission(it) }
                    })
    }.getOrDefault(false)

    private fun onDesktopSourceFailed(owner: BridgeHost, reason: ShizukuDexConnector.Failure) {
        if (host !== owner) {
            // A reconnect may already have replaced the terminal host before this callback.
            // Complete ONLY the old host's latch, which its successor may still be awaiting.
            owner.shutdown("завершение предыдущего источника")
            return
        }
        val current = session
        val isolatedSession = owner.canRecoverWhilePhoneLocked
        val attempt = desktopRecovery.begin(reason == ShizukuDexConnector.Failure.HELPER_UNAVAILABLE &&
            current != null && canRecoverDesktop(current, isolatedSession))
        if (attempt != null && current != null) {
            pendingDesktopRecovery = Recovery(attempt, current, owner, isolatedSession)
            AppLog.i("DESKTOP recovery planned attempt=${attempt.number}/2; server ready, keep CarLife transport", false)
        }
        // TerminalSource prevents grace reuse even before this main-looper callback arrives.
        owner.shutdown("источник рабочего стола завершился")
    }

    private fun onDesktopHostTornDown(owner: BridgeHost) {
        // Its latch is already acknowledged by BridgeHost; a predecessor cannot stop
        // the service or change recovery after a newer source became the owner.
        if (host !== owner) return
        host = null
        AppLog.i("DEX: источник закрыт")
        val recovery = pendingDesktopRecovery
        if (recovery != null && recovery.owner === owner && desktopRecovery.isCurrent(recovery.attempt)) {
            // The callback acknowledges BOTH privileged producer closure and encoder/Surface disposal.
            DexState.error = null
            DexState.status = "Восстанавливаю рабочий стол (${recovery.attempt.number}/2)…"
            main.postDelayed({ recoverDesktop(recovery) }, recovery.attempt.delayMs)
        }
        if (session == null && host == null && !linkWaiting && !wifiBMode && !usbToAirPending) finish()
    }

    private fun recoverDesktop(recovery: Recovery) {
        if (pendingDesktopRecovery !== recovery || !desktopRecovery.consume(recovery.attempt)) return
        pendingDesktopRecovery = null
        if (host != null || session !== recovery.session || !recovery.owner.isTornDown) return
        // Stop, detach and revoked access cancel even isolated recovery. Phone lock does not.
        if (!canRecoverDesktop(recovery.session, recovery.isolatedSession)) {
            DexState.error = "Восстановление остановлено. Проверьте соединение CarLife и доступ к рабочему столу" +
                if (recovery.isolatedSession) "" else "; телефон должен быть разблокирован"
            AppLog.i("DESKTOP recovery cancelled: current session/access no longer eligible", false)
            return
        }
        val core = recovery.owner.core
        if (!core.rearmDesktopSurfaceAfterStop()) {
            DexState.error = "Предыдущий экран ещё не освобождён; автоматический запуск отменён"
            return
        }
        val replacement = createHost(AaPrefs(this), core.videoMode, core.densityOverride,
            previous = recovery.owner, recoveredCore = core) ?: return
        host = replacement
        AppLog.i("DESKTOP recovery bind attempt=${recovery.attempt.number}/2; fresh producer and encoder generation", false)
        replacement.onUsbSessionStarted()
    }

    private fun cancelDesktopRecovery() {
        pendingDesktopRecovery = null
        desktopRecovery.cancel()
    }

    private fun startForegroundTyped(n: Notification) {
        // This fork does not capture phone or car microphone audio.
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    /** Старт не удался: если жив AA-хост (идёт grace), сервис не гасим, иначе finish. */
    private fun failStart() {
        Actions.onUsbStartFailed(CarLifeState.status)
        if (linkWaiting) return      // идёт ожидание машины по воздуху: сервис нужен
        val h = host
        if (h != null && !h.isDead) return
        if (h == null) finish()
    }

    private fun onSessionClosed(s: CarLifeSession, reason: String) {
        AppLog.i("сервис: сессия завершена ($reason)")
        if (session !== s) return
        stopClusterSources()
        if (!stopRequested && s.transportKind == TransportCaps.USB_AOA.kind) {
            observePhysicalUsbDetach("USB session closed and accessory absent")
        }
        powerDiagnostics?.stop()
        powerDiagnostics = null
        phoneIdleTimer?.stop()
        phoneIdleTimer = null
        com.headswap.desk.dex.DelegatedPanelAccess.detachOutput(s)
        desktopAudio.detach()
        com.headswap.desk.dex.DesktopMusicPlayback.linkClosed(s)
        cancelDesktopRecovery()
        session = null
        CarLifeState.running = false
        if (s.transportKind == TransportCaps.USB_AOA.kind) Actions.onUsbSessionClosed()
        markCarSeen()
        CarLifeState.wirelessSession = false
        if (wifiBMode && autoStarted && !autoMode && !stopRequested) {
            // машина ушла по Bluetooth, пока шла беспроводная сессия (ГУ держала Wi-Fi): ожидание заново не начинаем
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            WLog.ev(WLog.TRIGGER, "auto-off", "after" to "session")
        }
        if (!wifiBMode && !stopRequested && BuildConfig.WIRELESS_BETA && s.transportKind == TransportCaps.USB_AOA.kind &&
            WirelessConnectionPolicy.fallbackAfterUsb(WirelessPrefs(this).mode, WirelessCarLife.hasConfiguredCar(this), autoMode)) {
            // кабель выдернули, а машина рядом по Bluetooth: новый ACL не придёт, ждать по воздуху начнём сами (h214)
            usbToAirPending = true
            main.removeCallbacks(usbToAirTick)
            main.postDelayed(usbToAirTick, USB_TO_AIR_MS)
            WLog.ev(WLog.LINK, "usb-to-air-armed", "ms" to USB_TO_AIR_MS)
        }
        val h = host
        if (h == null) {
            if (((!wifiBMode && !usbToAirPending) || stopRequested) && usbHandoffFrom !== s) finish()
        } else {
            // The user chose to retain the existing 8-second desktop grace on physical detach.
            // Only explicit Stop tears it down immediately; reconnect can reuse the warm host.
            h.onUsbSessionClosed(immediate = stopRequested)
            if (!stopRequested && s.transportKind == TransportCaps.USB_AOA.kind) {
                // Some firmware updates accessoryList shortly after reporting the read error.
                for (delay in longArrayOf(250, 1000)) main.postDelayed({
                    if (!stopRequested && session == null && host === h) observePhysicalUsbDetach("post-close USB absence check")
                }, delay)
            }
        }
        if (usbHandoffFrom === s) {
            usbHandoffFrom = null
            if (!stopRequested && WirelessPrefs(this).mode == CarLifeConnectionMode.AUTO) {
                if (WirelessCarLife.hasCompatibleUsb(this)) command(Intent(this, BridgeService::class.java))
                else if (WirelessCarLife.unavailableReason(this) == null) command(Intent(this, BridgeService::class.java).setAction(ACTION_WIFI_B))
                else failStart()
            } else failStart()
            return
        }
        if (wifiBMode && !stopRequested) {
            // беспровод: ГУ ушла (обрыв Wi-Fi, выключили машину) - снова маяк и ожидание, AA пока тёплая (grace)
            CarLifeState.wireless = getString(R.string.wl_state_lost)
            WLog.ev(WLog.LINK, "rearm", "after" to reason)
            main.postDelayed({
                if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
            }, WIFI_B_REARM_MS)
        }
    }

    private fun finish() {
        val stopped = serviceStarts.finish(::stopSelfResult) {
            cancelDesktopRecovery()
            stopCarNet(CarNetEvent.STOP, "finish")
            CarLifeState.running = false
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {
            }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (!stopped) AppLog.i("сервис: завершение отложено, Android принял более новый запуск", false)
    }

    override fun onDestroy() {
        unobserveWirelessEnabled?.invoke()
        unobserveWirelessEnabled = null
        try {
            unregisterReceiver(detachReceiver)
        } catch (_: Exception) {
        }
        stopRequested = true
        notificationShade?.close()
        notificationShade = null
        stopClusterSources()
        cancelDesktopRecovery()
        alive = false
        autoMode = false
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.DESTROY, "destroy")
        cancelLinkWait()
        btProbe.stop("сервис уничтожен")
        powerDiagnostics?.stop()
        powerDiagnostics = null
        phoneIdleTimer?.stop()
        phoneIdleTimer = null
        audioDiag.stop()
        com.headswap.desk.dex.DelegatedPanelAccess.restoreAll()
        desktopAudio.close()
        com.headswap.desk.dex.DesktopMusicPlayback.serviceDestroyed(this, physicalUsbDetached)
        session?.close("сервис уничтожен")
        host?.shutdown("сервис уничтожен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.headswap.desk.STOP"
        const val ACTION_PHONE_SCREEN_OFF = "com.headswap.desk.PHONE_SCREEN_OFF"
        const val ACTION_PHONE_SCREEN_ON = "com.headswap.desk.PHONE_SCREEN_ON"
        const val ACTION_DESKTOP_HOME = "com.headswap.desk.DESKTOP_HOME"
        /** Беспровод (бета): ждать машину по Wi-Fi, ветка B (хотспот телефона + маяк). */
        const val ACTION_WIFI_B = "com.headswap.desk.WIFI_B"
        /** Выбранная машина подключилась по Bluetooth (ACL_CONNECTED): ждать по Wi-Fi, пока она рядом. */
        const val ACTION_WIFI_B_AUTO = "com.headswap.desk.WIFI_B_AUTO"
        /** Машина отключилась по Bluetooth (ACL_DISCONNECTED): через debounce снять ожидание. */
        const val ACTION_WIFI_B_AUTO_OFF = "com.headswap.desk.WIFI_B_AUTO_OFF"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val HINT_NOTIF_ID = 2
        private const val USB_TO_AIR_NOTIF_ID = 5
        /** Пауза после конца USB-сессии перед ожиданием по воздуху: успеть увидеть переподключение аксессуара. */
        private const val USB_TO_AIR_MS = 3_000L
        private const val USB_TO_AIR_NOTIF_MS = 60_000L
        private const val BT_HINT_NOTIF_ID = 3
        private const val WIFI_OFF_NOTIF_ID = 4

        /** Сервис создан и не уничтожен (один процесс): ресиверу ACL незачем будить пустой сервис ради отбоя. */
        @Volatile var alive = false
            private set
        /** debug tcp6: сколько ждать все 6 сокетов ГУ. */
        private const val TCP6_WAIT_MS = 60_000L
        /** Ветка B: сколько маячить и ждать ГУ (человек включает хотспот и открывает CarLife на ГУ). */
        private const val WIFI_B_WAIT_MS = 10 * 60_000L
        /** Пауза перед новым ожиданием после обрыва беспроводной сессии. */
        private const val WIFI_B_REARM_MS = 1_500L
        /** Авторежим: сколько ждать нового ACL_CONNECTED после ACL_DISCONNECTED, прежде чем гасить ожидание. */
        private const val AUTO_OFF_DEBOUNCE_MS = 10_000L
        /** Авторежим: через сколько ожидания без 6/6 показать подсказку про Wi-Fi. */
        private const val AUTO_HINT_MS = 30_000L
    }
}
