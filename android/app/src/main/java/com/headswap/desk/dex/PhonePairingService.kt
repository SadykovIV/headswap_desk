package com.headswap.desk.dex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.headswap.desk.AppLog
import com.headswap.desk.BridgeService
import com.headswap.desk.BrandArtwork
import com.headswap.desk.R
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** The code is transient. A notification reply never carries an address, command or requested permission. */
internal object PairingReplyPolicy {
    fun code(value: CharSequence?): String? = value?.toString()?.filterNot(Char::isWhitespace)
        ?.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
    fun accepts(expected: String?, actual: String?, waiting: Boolean): Boolean =
        waiting && !expected.isNullOrEmpty() && expected == actual
}

internal enum class PairingNotificationBlock { APP, PROGRESS_CHANNEL, CODE_CHANNEL }

internal object PairingNotificationPolicy {
    /** A missing channel will be created by the service; an existing user-blocked channel stays blocked. */
    fun block(appAllowed: Boolean, progressImportance: Int?, codeImportance: Int?, needsCode: Boolean): PairingNotificationBlock? = when {
        !appAllowed -> PairingNotificationBlock.APP
        progressImportance == NotificationManager.IMPORTANCE_NONE -> PairingNotificationBlock.PROGRESS_CHANNEL
        needsCode && codeImportance == NotificationManager.IMPORTANCE_NONE -> PairingNotificationBlock.CODE_CHANNEL
        else -> null
    }
}

/** User-started foreground activation continues while Android's one-time pairing sheet stays open. */
class PhonePairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val attemptLock = Any()
    private val replies = ArrayBlockingQueue<String>(1)
    private var discovery: DexDiscovery? = null
    @Volatile private var pairing: DexEndpoint? = null
    @Volatile private var connection: DexEndpoint? = null
    @Volatile private var cancelled = false
    @Volatile private var waitingCode = false
    @Volatile private var dispatched = false
    @Volatile private var destroyed = false
    @Volatile private var finished = false
    private var worker: Thread? = null
    private var replyToken: String? = null
    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        notifications = getSystemService(NotificationManager::class.java)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                // START is sent with startForegroundService. A settings change can race the
                // caller's gate: acknowledge its FGS obligation even when rejecting it below.
                // REPLY/CANCEL use ordinary service PendingIntents and impose no such obligation.
                if (replyToken == null) replyToken = UUID.randomUUID().toString()
                startForeground(PROGRESS_ID, progress(status.ifBlank { "Подготовка активации…" }), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                if (finished || destroyed) {
                    // A queued START may still reach this instance between finish and onDestroy.
                    // It needs an FGS acknowledgement but cannot reopen a terminal attempt.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (worker != null) return START_NOT_STICKY
                val prefs = DexPrefs(this)
                if (!prefs.useDelegated || BridgeService.alive || DexState.running || DelegatedDesktopAccess.isReady(this)) {
                    finish("Остановите соединение перед активацией либо используйте уже активный доступ.")
                    return START_NOT_STICKY
                }
                val forcePair = intent.getBooleanExtra(FORCE_PAIR, false)
                if (notificationBlock(this, forcePair || !prefs.hasPaired) != null) {
                    finish("Уведомления активации отключены. Откройте HeadSwap и выберите ручной ввод или настройку уведомлений.")
                    return START_NOT_STICKY
                }
                cancelled = false
                active = true
                PhoneActivationStatus.clearFinished(this)
                worker = Thread({ activate(forcePair) }, "headswap-desk-activation").also { it.start() }
            }
            REPLY -> {
                if (PairingReplyPolicy.accepts(replyToken, intent.getStringExtra(TOKEN), waitingCode)) {
                    val value = PairingReplyPolicy.code(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE))
                    // Clear SystemUI's reply spinner immediately, without echoing the code in history.
                    notifications.notify(CODE_ID, codeNotification("Проверяю код…", allowReply = false))
                    if (value == null) showCode("Нужны ровно 6 цифр из открытого окна сопряжения.")
                    else { waitingCode = false; replies.offer(value) }
                } else if (worker == null) stopSelf()
            }
            CANCEL -> {
                if (worker == null) stopSelf()
                else {
                    val cancelledWorker = synchronized(attemptLock) {
                        if (intent.getStringExtra(TOKEN) == replyToken && !dispatched) {
                            cancelled = true
                            worker
                        } else null
                    }
                    cancelledWorker?.interrupt()
                }
            }
            else -> if (worker == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun activate(forcePair: Boolean) {
        var failure: String? = null
        try {
            val prefs = DexPrefs(this)
            val audio = prefs.usbPlaybackAudio
            val screen = prefs.desktopPanelControl
            postAttempt {
                discovery = DexDiscovery(this) { p, c, _ ->
                    if (!destroyed && !finished && !cancelled) { pairing = p; connection = c }
                }.also { it.start() }
            }
            if (forcePair || !prefs.hasPaired) pairWithNotification()
            update("Ищу беспроводную отладку…")
            val endpoint = awaitEndpoint(20_000) { connection }
                ?: DexEndpoint(prefs.host, prefs.connectPort).takeIf { it.port in 1..65535 && DexAccess.acceptsHost(it.host) }
                ?: error("Включите Wi-Fi и беспроводную отладку. Основной порт пока не найден.")
            checkActive()
            update("Проверяю сохранённое сопряжение…")
            DexAccess.probe(this, endpoint.port, endpoint.host)
            prefs.host = endpoint.host
            prefs.connectPort = endpoint.port
            checkActive()
            check(prefs.persistActivationSelection(audio, screen)) { "Настройки активации изменились. Повторите запуск." }
            // Cancellation and the final dispatch boundary must be ordered, not two racy flags.
            synchronized(attemptLock) { checkActive(); dispatched = true }
            update("Включаю доступ. HeadSwap сейчас перезапустится…")
            postAttempt { notifications.cancel(CODE_ID) }
            runBlocking { DexAccess.activatePhone(applicationContext, endpoint, audio, screen) }
            val deadline = SystemClock.elapsedRealtime() + PhoneActivationPolicy.DEADLINE_MS
            while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                when (val state = PhoneActivationStatus.read(this)) {
                    is PhoneActivationStatus.Snapshot -> when (state.phase) {
                        PhoneActivationStatus.Phase.READY -> return
                        PhoneActivationStatus.Phase.FAILED -> error(state.message)
                        else -> Thread.sleep(250)
                    }
                }
            }
            if (!cancelled) error("Результат запуска пока не подтверждён. Откройте HeadSwap.")
        } catch (_: InterruptedException) {
            // The operation was explicitly cancelled; no automatic retry.
        } catch (error: Exception) {
            AppLog.i("PHONE notification activation failed: ${DexAccessDiagnostics.safeTrace(error)}")
            failure = if (error is DexAccessException) error.message else
                "Активация не завершена. Проверьте Wi-Fi, беспроводную отладку и повторите сопряжение в HeadSwap."
        } finally {
            val attempt = replyToken
            main.post {
                if (!destroyed && replyToken == attempt) {
                    if (!cancelled || !dispatched) finish(failure) else stopSelf()
                }
            }
        }
    }

    private fun pairWithNotification() {
        val deadline = SystemClock.elapsedRealtime() + CODE_TIMEOUT_MS
        var problem: String? = null
        while (true) {
            checkActive()
            val left = deadline - SystemClock.elapsedRealtime()
            check(left > 0) { "Время ввода кода истекло" }
            update("Откройте в отладке окно с кодом. Введите его в уведомлении HeadSwap.")
            showCode(problem)
            val code = replies.poll(left, TimeUnit.MILLISECONDS) ?: error("Время ввода кода истекло")
            checkActive()
            update("Сопрягаю headswap-desk…")
            val endpoint = awaitEndpoint(10_000) { pairing }
            if (endpoint == null) {
                problem = "Окно с кодом не найдено. Откройте его заново в беспроводной отладке."
                continue
            }
            try {
                runBlocking { DexAccess.pair(applicationContext, endpoint.port, code, endpoint.host) }
                checkActive()
                postAttempt { notifications.cancel(CODE_ID) }
                return
            } catch (e: DexAccessException) {
                if (e.reason !in setOf(DexAccessReason.PAIR_REJECTED, DexAccessReason.REFUSED,
                    DexAccessReason.TLS_HANDSHAKE, DexAccessReason.CONNECTION_CLOSED)) throw e
                problem = "Сопряжение не принято. Откройте новое окно с кодом и повторите."
            }
        }
    }

    private fun awaitEndpoint(timeoutMs: Long, value: () -> DexEndpoint?): DexEndpoint? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            checkActive()
            value()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(100)
        }
    }

    private fun checkActive() { if (cancelled || destroyed || finished || Thread.currentThread().isInterrupted) throw InterruptedException() }
    private fun postAttempt(action: () -> Unit) {
        val attempt = replyToken
        main.post {
            if (!destroyed && !finished && !cancelled && attempt != null && replyToken == attempt) action()
        }
    }
    private fun update(message: String) = postAttempt {
        status = message
        notifications.notify(PROGRESS_ID, progress(message))
    }
    private fun showCode(problem: String?) = postAttempt {
        waitingCode = true
        notifications.notify(CODE_ID, codeNotification(problem ?: "1. В отладке выберите «Подключить устройство с помощью кода».\n2. Не закрывая это окно, раскройте шторку.\n3. Нажмите «Ввести 6 цифр» ниже."))
    }
    private fun progress(text: String): Notification = Notification.Builder(this, PROGRESS_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_headswap)
        .setLargeIcon(BrandArtwork.bitmap(resources))
        .setContentTitle("Активация HeadSwap Desktop")
        .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(openApp(this)).setOnlyAlertOnce(true).setOngoing(true)
        .setVisibility(Notification.VISIBILITY_SECRET)
        .apply { if (!dispatched) addAction(Notification.Action.Builder(null, "Отменить", serviceAction(CANCEL, 2, false)).build()) }
        .build()

    private fun codeNotification(text: String, allowReply: Boolean = true): Notification {
        val settings = PendingIntent.getActivity(this, 8, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CODE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setLargeIcon(BrandArtwork.bitmap(resources))
            .setContentTitle("HeadSwap · введите код сопряжения")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply {
                if (allowReply) {
                    val reply = RemoteInput.Builder(CODE).setLabel("6 цифр, например 123456")
                        .setAllowFreeFormInput(true).build()
                    addAction(Notification.Action.Builder(Icon.createWithResource(this@PhonePairingService, android.R.drawable.ic_menu_edit),
                        "Ввести 6 цифр", serviceAction(REPLY, 1, true)).addRemoteInput(reply)
                        .setAllowGeneratedReplies(false).setAuthenticationRequired(true).build())
                }
            }.build()
    }
    private fun serviceAction(action: String, request: Int, mutable: Boolean) = PendingIntent.getService(this, request,
        Intent(this, PhonePairingService::class.java).setAction(action)
            // Extras are not part of PendingIntent identity. A new attempt must not update an old reply/cancel token.
            .setData(Uri.Builder().scheme("headswap-desk").authority("activation")
                .appendPath(checkNotNull(replyToken)).appendPath(action).build())
            .putExtra(TOKEN, replyToken),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    private fun finish(failure: String?) {
        if (destroyed || finished) return
        finished = true
        active = false
        status = failure.orEmpty()
        waitingCode = false
        notifications.cancel(CODE_ID)
        if (failure != null) notifyResult(this, failure)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (currentInstance === this) currentInstance = null
        destroyed = true
        finished = true
        synchronized(attemptLock) { cancelled = true }
        active = false
        waitingCode = false
        replyToken = null
        worker?.interrupt()
        replies.clear()
        discovery?.close()
        discovery = null
        notifications.cancel(CODE_ID)
        super.onDestroy()
    }

    companion object {
        @Volatile private var currentInstance: PhonePairingService? = null

        /** Same one-use reply capability as SystemUI; never persisted or logged. */
        internal fun replyAttempt(): String? = currentInstance?.let {
            if (it.waitingCode && !it.cancelled && !it.finished && !it.destroyed) it.replyToken else null
        }

        internal fun submitCode(context: Context, attempt: String, value: String): Boolean {
            val code = PairingReplyPolicy.code(value) ?: return false
            if (!PairingReplyPolicy.accepts(replyAttempt(), attempt, active)) return false
            val intent = Intent(context, PhonePairingService::class.java).setAction(REPLY).putExtra(TOKEN, attempt)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(CODE).build()), intent,
                android.os.Bundle().apply { putCharSequence(CODE, code) })
            return runCatching { context.startService(intent) != null }.getOrDefault(false)
        }

        private const val START = "com.headswap.desk.activation.START"
        private const val REPLY = "com.headswap.desk.activation.REPLY"
        private const val CANCEL = "com.headswap.desk.activation.CANCEL"
        private const val FORCE_PAIR = "force_pair"
        private const val TOKEN = "attempt"
        private const val CODE = "headswap_desk_pairing_code"
        private const val PROGRESS_CHANNEL = "headswap_desk_activation"
        private const val CODE_CHANNEL = "headswap_desk_pairing"
        private const val PROGRESS_ID = 71
        private const val CODE_ID = 72
        private const val RESULT_ID = 73
        private const val CODE_TIMEOUT_MS = 5 * 60_000L
        @Volatile var active = false
            private set
        @Volatile var status = ""
            private set
        fun start(context: Context, forcePair: Boolean): android.content.ComponentName? {
            if (notificationBlock(context, forcePair || !DexPrefs(context).hasPaired) != null) {
                status = "Уведомления активации отключены. Откройте HeadSwap и выберите ручной ввод или настройку уведомлений."
                return null
            }
            return context.startForegroundService(
                Intent(context, PhonePairingService::class.java).setAction(START).putExtra(FORCE_PAIR, forcePair))
        }
        internal fun notificationBlock(context: Context, needsCode: Boolean): PairingNotificationBlock? {
            val manager = context.getSystemService(NotificationManager::class.java)
            return PairingNotificationPolicy.block(manager.areNotificationsEnabled(),
                manager.getNotificationChannel(PROGRESS_CHANNEL)?.importance,
                manager.getNotificationChannel(CODE_CHANNEL)?.importance, needsCode)
        }
        internal fun notificationSettings(context: Context, block: PairingNotificationBlock): Intent =
            if (block == PairingNotificationBlock.APP)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, if (block == PairingNotificationBlock.CODE_CHANNEL) CODE_CHANNEL else PROGRESS_CHANNEL)
        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "Активация HeadSwap", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CODE_CHANNEL, "Ввод кода сопряжения", NotificationManager.IMPORTANCE_HIGH))
        }
        private fun openApp(context: Context) = PendingIntent.getActivity(context, 7,
            Intent(context, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        private fun notifyResult(context: Context, text: String) {
            createChannels(context)
            context.getSystemService(NotificationManager::class.java).notify(RESULT_ID, Notification.Builder(context, PROGRESS_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_headswap)
                .setLargeIcon(BrandArtwork.bitmap(context.resources)).setContentTitle("HeadSwap Desktop")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context)).setAutoCancel(true).build())
        }
        /** Called after the intentional process restart, only when access is actually confirmed. */
        fun onAccessReady(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.activeNotifications.none { it.id in setOf(PROGRESS_ID, CODE_ID) }) return
            manager.cancel(PROGRESS_ID)
            manager.cancel(CODE_ID)
            notifyResult(context, "Доступ активен. Можно подключать CarLife.")
        }
    }
}
