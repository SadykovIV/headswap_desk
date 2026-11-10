package com.headswap.desk.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbAccessory
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.headswap.desk.MainActivity
import com.headswap.desk.AppLocale
import com.headswap.desk.AppLog
import com.headswap.desk.BridgeService
import com.headswap.desk.CarLifeState
import com.headswap.desk.R
import com.headswap.desk.aa.AaCreds
import com.headswap.desk.aa.AaServerCheck
import com.headswap.desk.aa.ManualServerCheck
import com.headswap.desk.aa.ProbeResult
import com.headswap.desk.aa.TranscodeSelfCheck
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.dex.DesktopAccess
import com.headswap.desk.dex.DexAutoConnectPolicy
import com.headswap.desk.link.car.CarLifeConnectionMode
import com.headswap.desk.link.car.WirelessPrefs

/**
 * Действия, которые раньше жили прямо в MainActivity (кнопки UI и команды intent):
 * сканирование USB, старт/стоп сервиса моста, запуск сервера ГУ, диагностика.
 */
object Actions {
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"

    enum class UsbScanState { DETACHED, WAITING_FOR_ACCESSORY, PERMISSION_REQUESTED, STARTING, ALREADY_RUNNING, FAILED }
    data class UsbScanResult(val state: UsbScanState, val detail: String? = null)
    @Volatile var lastUsbResult = UsbScanResult(UsbScanState.WAITING_FOR_ACCESSORY, "Телефон готов. Ожидаю USB CarLife")
        private set
    private val usbGate = UsbScanGate { SystemClock.elapsedRealtime() }
    private val autoConnect = DexAutoConnectPolicy()
    private val detachedIdle = UsbDetachedIdle()
    private var observingUsb = false
    private const val USB_PERMISSION_TOKEN = "headswap.usb.permission.token"

    fun isCompatibleAccessory(accessory: UsbAccessory): Boolean = UsbAccessoryPolicy.accepts(accessory.manufacturer, accessory.model)
    private fun accessoryKey(accessory: UsbAccessory): String = UsbAccessoryPolicy.key(
        accessory.manufacturer, accessory.model, accessory.description, accessory.version, accessory.uri)

    @Synchronized fun isUsbAutoStartStopped(): Boolean = autoConnect.stopped

    @Synchronized fun isUsbDetachedIdle(): Boolean = detachedIdle.idle

    @Synchronized internal fun connectDecision(explicit: Boolean, serviceAlive: Boolean, sessionReady: Boolean): DexAutoConnectPolicy.Decision {
        if (explicit) detachedIdle.explicitConnect()
        return autoConnect.decide(explicit, serviceAlive, sessionReady)
    }

    @Synchronized fun resumeUsbAutoStart() { detachedIdle.explicitConnect(); autoConnect.resume() }

    /** Observe only; never launch background work from this receiver. */
    @Synchronized fun observeUsbConnection(ctx: Context) {
        if (!observingUsb) {
            val app = ctx.applicationContext
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // Use current system state: a queued old detach must not rearm
                    // a stopped connection that is still physically present.
                    observeUsbConnection(context)
                }
            }, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }, Context.RECEIVER_NOT_EXPORTED)
            observingUsb = true
        }
        // A failed query is not evidence that the physical accessory was detached.
        runCatching {
            ctx.getSystemService(UsbManager::class.java).accessoryList.orEmpty()
                .firstOrNull(::isCompatibleAccessory)?.let(::accessoryKey)
        }.onSuccess { key ->
            val reconnected = detachedIdle.idle && key != null
            autoConnect.observeAccessory(key)
            detachedIdle.observeCompatibleAccessory(key != null)
            if (reconnected) publish(UsbScanState.WAITING_FOR_ACCESSORY,
                "USB CarLife обнаружен. Можно восстановить подключение", "новый аксессуар после detach")
        }
    }

    /** Repeated refresh/attach events reuse a pending request. Explicit Connect may retry a denial. */
    @Synchronized fun scan(ctx: Context, reason: String, retryPermission: Boolean = false): UsbScanResult {
        try {
            observeUsbConnection(ctx)
            if (WirelessPrefs(ctx).mode == CarLifeConnectionMode.WIFI) {
                usbGate.reset()
                return publish(UsbScanState.WAITING_FOR_ACCESSORY, "Выбрано подключение CarLife по Wi-Fi", reason)
            }
            if (detachedIdle.idle) {
                return publish(UsbScanState.DETACHED, "USB отключён. Ожидаю переподключения; доступ сохранён", reason)
            }
            if (autoConnect.stopped) {
                return publish(UsbScanState.WAITING_FOR_ACCESSORY, "Подключение остановлено", reason)
            }
            if (com.headswap.desk.dex.PhonePairingService.blocksProjectionForCommittedActivation(ctx)) {
                usbGate.reset()
                return publish(UsbScanState.WAITING_FOR_ACCESSORY, "Завершаю проверку активации рабочего стола…", reason)
            }
            if (DexPrefs(ctx).useDesktopService && !DesktopAccess.isReady(ctx)) {
                usbGate.reset() // A late USB grant must not bypass the selected backend's permission.
                return publish(UsbScanState.FAILED, if (DexPrefs(ctx).useDelegated) "Откройте HeadSwap и выполните активацию" else "Запустите Shizuku и разрешите доступ для HeadSwap Desktop", reason)
            }
            val usb = ctx.getSystemService(UsbManager::class.java)
            val list = usb.accessoryList.orEmpty()
            val acc = list.firstOrNull(::isCompatibleAccessory)
            val active = BridgeService.alive && CarLifeState.running && !CarLifeState.wirelessSession
            val step = usbGate.decide(acc?.let(::accessoryKey), acc?.let(usb::hasPermission) == true, active, retryPermission)
            if (acc != null) CarLifeState.accessory = MainActivity.describe(acc)
            return when (step) {
                UsbScanGate.Step.WAITING -> {
                    CarLifeState.accessory = "не найден"
                    val detail = if (list.isEmpty()) "Телефон готов. Ожидаю USB CarLife" else "Подключён USB-аксессуар, который не поддерживает CarLife"
                    CarLifeState.status = detail
                    publish(UsbScanState.WAITING_FOR_ACCESSORY, detail, reason)
                }
                UsbScanGate.Step.ACTIVE -> publish(UsbScanState.ALREADY_RUNNING, "USB CarLife подключён", reason)
                UsbScanGate.Step.STARTING -> publish(UsbScanState.STARTING, "Запускаю USB CarLife…", reason)
                UsbScanGate.Step.PERMISSION_PENDING -> publish(UsbScanState.PERMISSION_REQUESTED, "Разрешите доступ к USB в системном окне", reason)
                UsbScanGate.Step.DENIED -> publish(UsbScanState.FAILED, "Доступ к USB не разрешён. Нажмите «Подключить», чтобы повторить", reason)
                UsbScanGate.Step.PERMISSION_TIMEOUT -> publish(UsbScanState.FAILED, "Ответ на запрос USB не получен. Закройте системное окно и нажмите «Подключить»", reason)
                UsbScanGate.Step.START_FAILED -> publish(UsbScanState.FAILED, "USB CarLife не запустился. Проверьте кабель и CarLife на магнитоле, затем нажмите «Подключить»", reason)
                UsbScanGate.Step.REQUEST_PERMISSION -> {
                    val token = usbGate.pendingToken!!
                    val pi = PendingIntent.getBroadcast(ctx, token.toInt(),
                        Intent(MainActivity.ACTION_PERMISSION).setPackage(ctx.packageName).putExtra(USB_PERMISSION_TOKEN, token),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    usb.requestPermission(acc!!, pi)
                    publish(UsbScanState.PERMISSION_REQUESTED, "Разрешите доступ к USB в системном окне", reason)
                }
                UsbScanGate.Step.START -> {
                    ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
                    publish(UsbScanState.STARTING, "Запускаю USB CarLife…", reason)
                }
            }
        } catch (e: Exception) {
            usbGate.failed()
            AppLog.i("USB scan failed: ${e.javaClass.simpleName}")
            return publish(UsbScanState.FAILED, "Не удалось открыть USB CarLife (${e.javaClass.simpleName}). Проверьте подключение и разрешение USB", reason)
        }
    }

    /** Verify the actual USB grant, ignore stale callbacks, then continue without another tap. */
    @Synchronized fun handleUsbPermissionResult(ctx: Context, intent: Intent): UsbScanResult? {
        if (intent.action != MainActivity.ACTION_PERMISSION) return null
        try {
            val token = intent.getLongExtra(USB_PERMISSION_TOKEN, -1)
            if (usbGate.pendingToken != token) return null
            val acc = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            if (acc == null) return null
            if (!isCompatibleAccessory(acc)) return null
            val usb = ctx.getSystemService(UsbManager::class.java)
            // UsbAccessory.equals() reads protected serials and throws after permission denial;
            // toString() contains a Binder proxy identity that changes between parcels.
            val key = accessoryKey(acc)
            val present = usb.accessoryList.orEmpty().any { accessoryKey(it) == key }
            val granted = present && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.hasPermission(acc)
            if (!usbGate.permissionResult(key, token, granted)) return null
            return if (!present || granted) scan(ctx, "результат разрешения USB")
                else publish(UsbScanState.FAILED, "Доступ к USB не разрешён. Нажмите «Подключить», чтобы повторить", "разрешение USB отклонено")
        } catch (e: Exception) {
            usbGate.failed()
            AppLog.i("USB permission result failed: ${e.javaClass.simpleName}")
            return publish(UsbScanState.FAILED, "Не удалось проверить разрешение USB. Переподключите кабель и нажмите «Подключить»", "проверка разрешения USB")
        }
    }

    /** Service lifecycle hooks keep the activity informed after scan() has returned. */
    @Synchronized fun onUsbSessionStarted() {
        detachedIdle.observeCompatibleAccessory(true)
        usbGate.started()
        publish(UsbScanState.ALREADY_RUNNING, "USB CarLife подключён", "сессия запущена")
    }

    @Synchronized fun onUsbStartFailed(detail: String) {
        if (!usbGate.isStarting) return
        usbGate.failed()
        publish(UsbScanState.FAILED, detail, "сервис не запущен")
    }

    @Synchronized fun onUsbSessionClosed() {
        usbGate.reset()
        if (detachedIdle.idle) publish(UsbScanState.DETACHED, "USB отключён. Ожидаю переподключения; доступ сохранён", "сессия закрыта после USB detach")
        else publish(UsbScanState.WAITING_FOR_ACCESSORY, "Телефон готов. Ожидаю USB CarLife", "сессия закрыта")
    }

    /** Only BridgeService calls this after confirming physical accessory absence. No privilege teardown. */
    @Synchronized fun onUsbDetached() {
        detachedIdle.detached()
        autoConnect.observeAccessory(null)
        usbGate.reset() // Late USB grants cannot restart an absent accessory.
        publish(UsbScanState.DETACHED, "USB отключён. Ожидаю переподключения; доступ сохранён", "физическое отключение USB")
    }

    private fun publish(state: UsbScanState, detail: String, reason: String): UsbScanResult {
        val result = UsbScanResult(state, detail)
        if (result != lastUsbResult) AppLog.i("USB state=$state ($reason): $detail")
        lastUsbResult = result
        return result
    }

    fun startBridge(ctx: Context) { scan(ctx, "запуск моста") }

    @Synchronized fun stopBridge(ctx: Context, userInitiated: Boolean = true) {
        if (userInitiated) {
            com.headswap.desk.dex.DesktopMusicPlayback.cancel("explicit Stop")
            observeUsbConnection(ctx)
            autoConnect.stop()
            detachedIdle.explicitConnect() // Keep explicit Stop distinct from passive detached idle.
        }
        AppLog.i(if (userInitiated) "кнопка Стоп" else "остановка для восстановления подключения")
        val serviceRequested = BridgeService.alive || usbGate.isStarting
        usbGate.reset() // Any outstanding system USB permission callback is now stale.
        publish(UsbScanState.WAITING_FOR_ACCESSORY, "Подключение остановлено", "стоп")
        if (serviceRequested) ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
    }

    /** Самопроверка сертификатов AA и TLS без сети: результат в лог и AaState.selftest. */
    fun aaSelftest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ AaCreds.selfTest(app) }, "aa-selftest").start()
    }

    fun transcodeTest(ctx: Context, opts: String) = TranscodeSelfCheck.run(ctx, opts)

    /** Настройки Android Auto (сервер ГУ): HyperOS прячет их из системных настроек. */
    fun openAaSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AppLog.i("открыты настройки Android Auto")
        } catch (e: Exception) {
            AppLog.e("не открыть настройки Android Auto", e)
            Toast.makeText(ctx, AppLocale.wrap(ctx).getString(R.string.act_open_aa_failed, "${e.javaClass.simpleName}: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Кнопка «Открыть Android Auto» на главном экране и в онбординге: сервер ГУ включает пользователь сам
     * (⋮ → «Вкл. сервер головного устр-ва»), поэтому только снимаем «Машина отключена» и открываем настройки AA.
     */
    fun startServerFromUi(ctx: Context) {
        HeadswapStatus.markServerRequested()
        openAaSettings(ctx)
    }

    /**
     * Ручная проба сервера ГУ («Для разработчиков»): одна попытка полным рукопожатием, итог тостом и строками SRVCHECK в лог.
     * Только без моста и USB: проба сбивает Android Auto. На состояние главного экрана не влияет.
     */
    fun checkServer(ctx: Context) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        val str = AppLocale.wrap(app)
        val refusal = AaServerCheck.start(app) { result, detail ->
            toast(
                when (result) {
                    ProbeResult.ALIVE -> str.getString(R.string.dev_check_server_alive)
                    ProbeResult.REFUSED -> str.getString(R.string.dev_check_server_refused)
                    ProbeResult.HUNG -> str.getString(R.string.dev_check_server_hung)
                    ProbeResult.ERROR -> str.getString(R.string.dev_check_server_error, detail ?: "?")
                },
            )
        }
        if (refusal != null) {
            toast(
                str.getString(
                    when (refusal) {
                        ManualServerCheck.Refusal.BRIDGE -> R.string.dev_check_server_bridge
                        ManualServerCheck.Refusal.USB -> R.string.dev_check_server_usb
                        ManualServerCheck.Refusal.RUNNING -> R.string.dev_check_server_running
                    },
                ),
            )
        }
    }

    /** Legacy AA diagnostics entry; this fork never records microphone audio. */
    fun micTest(ctx: Context) {
        AppLog.i("HeadSwap DeX: запись микрофона не поддерживается")
    }
}
