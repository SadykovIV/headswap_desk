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
import java.util.concurrent.atomic.AtomicLong

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
    enum class Phase { IDLE, CHECKING, WAITING_CODE, PAIRING, PREPARING_RECOVERY, RECOVERING_DESKTOP, DISPATCHED, READY, FAILED, CANCELLED }
    data class Snapshot(val phase: Phase, val message: String, val attempt: String?, val inAppOnly: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val attemptLock = Any()
    private var discovery: DexDiscovery? = null
    private val discoveryGeneration = AtomicLong()
    private val savedRetry = SavedAdbRetryGate()
    @Volatile private var pairing: DexEndpoint? = null
    @Volatile private var connection: DexEndpoint? = null
    @Volatile private var cancelled = false
    @Volatile private var waitingCode = false
    @Volatile private var dispatched = false
    @Volatile private var destroyed = false
    @Volatile private var finished = false
    private var worker: Thread? = null
    private var replyToken: String? = null
    private var codeGate: PhonePairingAttemptGate? = null
    @Volatile private var pairingRequest: PairingRequest? = null
    @Volatile private var coordinator: DesktopActivationCoordinator.Request? = null
    private var inAppOnly = false
    private var resumingCommitted = false
    private var completionReceiptStarted = Long.MIN_VALUE
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
                if (replyToken == null) {
                    replyToken = UUID.randomUUID().toString()
                    codeGate = PhonePairingAttemptGate(checkNotNull(replyToken))
                }
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
                val forcePair = intent.getBooleanExtra(FORCE_PAIR, false)
                resumingCommitted = intent.getBooleanExtra(RESUME_COMMITTED, false)
                if (resumingCommitted && !DelegatedDesktopAccess.isReady(this)) {
                    finish("Доступ HeadSwap завершён. Нажмите «Активировать», чтобы выдать его снова.")
                    return START_NOT_STICKY
                }
                val step = DesktopActivationCoordinator.currentStep(this)
                if (!prefs.useDelegated || ((BridgeService.alive || DexState.running) &&
                    (forcePair || step in setOf(DesktopReadinessPolicy.Step.ACQUIRE_GRANTS,
                        DesktopReadinessPolicy.Step.APPLY_MODE, DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED)))) {
                    finish("Остановите соединение перед изменением активации.")
                    return START_NOT_STICKY
                }
                inAppOnly = intent.getBooleanExtra(IN_APP_ONLY, false)
                if (!inAppOnly && !DesktopActivationCoordinator.isReady(this) && notificationBlock(this, forcePair || !prefs.hasPaired) != null) {
                    finish("Уведомления активации отключены. Откройте HeadSwap и выберите ввод в приложении.")
                    return START_NOT_STICKY
                }
                cancelled = false
                active = true
                completionReceiptStarted = PhoneActivationStatus.read(this).let {
                    if (it.activationBinding == PhoneCommittedActivationPolicy.Binding.CURRENT) it.startedElapsedMs else Long.MIN_VALUE
                }
                PhoneActivationStatus.clearFinished(this)
                publish(Phase.CHECKING, "Проверяю готовность HeadSwap…")
                if (PhonePairingPromptPolicy.offer(DesktopActivationCoordinator.isReady(this), forcePair,
                        step == DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED)) {
                    showCode(null, optional = true)
                }
                worker = Thread({ activate(forcePair) }, "headswap-desk-activation").also { it.start() }
            }
            REPLY -> {
                val token = intent.getStringExtra(TOKEN)
                val value = PairingReplyPolicy.code(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE))
                if (token != null && value != null) acceptCode(token, value)
                else if (token != null && token == codeGate?.token()) showCode("Нужны ровно 6 цифр из открытого окна сопряжения.", reopen = false)
                if (worker == null) stopSelf()
            }
            CANCEL -> {
                if (worker == null) stopSelf()
                else cancelAttempt(intent.getStringExtra(TOKEN))
            }
            else -> if (worker == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun cancelAttempt(token: String?): Boolean {
        val cancelledWorker = synchronized(attemptLock) {
            if (token == null || token != replyToken || dispatched || cancelled || finished || destroyed) return false
            cancelled = true
            codeGate?.cancel()
            worker
        }
        pairingRequest?.close()
        coordinator?.close()
        cancelledWorker?.interrupt()
        return true
    }

    private class PairingInputSubmitted : Exception()

    private fun activate(forcePair: Boolean) {
        var failure: String? = null
        try {
            val prefs = DexPrefs(this)
            val needs = DesktopReadinessPolicy.Requirements(prefs.usbPlaybackAudio, prefs.desktopPanelControl)
            if (forcePair) pairWithNotification()
            while (true) {
                checkActive()
                codeGate?.takeCode()?.let { pairWithNotification(initialCode = it) }
                savedRetry.markTried(knownConnectEndpoints(), PhoneAdbEndpointRequest.environment(this))
                try {
                    val result = DesktopActivationCoordinator.request(applicationContext, needs, explicit = true,
                        allowAcquireGrants = !resumingCommitted) {
                        !cancelled && !destroyed && !finished && codeGate?.hasCode() != true
                    }.use { request ->
                        coordinator = request
                        try {
                            request.ensureReady({ connection }, ::ensureDiscovery, { stage ->
                                when (stage) {
                                    DesktopActivationCoordinator.Stage.CHECKING -> update("Проверяю сохранённый доступ…", Phase.CHECKING)
                                    DesktopActivationCoordinator.Stage.PREPARING_RECOVERY -> update("Подготавливаю восстановление без Wi-Fi…", Phase.PREPARING_RECOVERY)
                                    DesktopActivationCoordinator.Stage.RECOVERING_DESKTOP -> update("Подготавливаю рабочий стол…", Phase.RECOVERING_DESKTOP)
                                    DesktopActivationCoordinator.Stage.DISPATCHED -> update("Включаю доступ. HeadSwap сейчас перезапустится…", Phase.DISPATCHED)
                                }
                                if (stage != DesktopActivationCoordinator.Stage.DISPATCHED) showCode(null, optional = true)
                            }, {
                                synchronized(attemptLock) {
                                    checkActive()
                                    if (codeGate?.complete() != true) throw PairingInputSubmitted()
                                    waitingCode = false
                                    dispatched = true
                                }
                                postAttempt { notifications.cancel(CODE_ID) }
                            })
                        } finally { coordinator = null }
                    }
                    if (result == DesktopActivationCoordinator.Result.READY) {
                        val completed = synchronized(attemptLock) {
                            checkActive()
                            if (codeGate?.complete() != true) false else {
                                check(DesktopActivationCoordinator.isReady(this)) { "Готовность рабочего стола изменилась. Повторите активацию." }
                                completeCommittedActivation(this, completionReceiptStarted)
                                true
                            }
                        }
                        if (!completed) continue
                        waitingCode = false
                        update("HeadSwap готов к подключению.", Phase.READY)
                        return
                    }
                    val deadline = SystemClock.elapsedRealtime() + PhoneActivationPolicy.DEADLINE_MS
                    while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                        when (val state = PhoneActivationStatus.read(this)) {
                            is PhoneActivationStatus.Snapshot -> when (state.phase) {
                                PhoneActivationStatus.Phase.READY -> {
                                    if (DesktopActivationCoordinator.isReady(this)) {
                                        synchronized(attemptLock) {
                                            checkActive()
                                            completeCommittedActivation(this, completionReceiptStarted)
                                        }
                                        update("HeadSwap готов к подключению.", Phase.READY)
                                        return
                                    }
                                    throw DesktopPreparationException("Доступ получен, но рабочий стол ещё не готов. Повторите активацию.")
                                }
                                PhoneActivationStatus.Phase.FAILED -> throw DesktopPreparationException(state.message)
                                else -> Thread.sleep(250)
                            }
                        }
                    }
                    throw DesktopPreparationException("Результат запуска пока не подтверждён. Откройте HeadSwap.")
                } catch (error: Exception) {
                    checkActive()
                    // Closing the current request only interrupts its probe. The same worker then pairs.
                    if (!dispatched && codeGate?.hasCode() == true) continue
                    when {
                        error is DesktopActivationCoordinator.PairingRequired -> pairWithNotification()
                        error is DexAccessException && !dispatched && PhonePairingPromptPolicy.canRetryWithCode(error.reason) ->
                            if (error.reason in setOf(DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.PAIR_REJECTED))
                                pairWithNotification("Сохранённый ключ не принят. Откройте новое окно сопряжения и введите код.")
                            else waitForSavedConnectionOrCode()
                        error is DesktopPreparationException && error.canPair && !dispatched ->
                            waitForSavedConnectionOrCode()
                        else -> throw error
                    }
                }
            }
        } catch (_: InterruptedException) {
            synchronized(attemptLock) { cancelled = true }
        } catch (error: Exception) {
            AppLog.i("PHONE notification activation failed: ${DexAccessDiagnostics.safeTrace(error)}")
            failure = when (error) {
                is DexAccessException, is DesktopPreparationException -> error.message
                else -> "Не удалось подготовить HeadSwap. Проверьте Wi-Fi и беспроводную отладку, затем повторите активацию."
            }
        } finally {
            coordinator = null
            val attempt = replyToken
            main.post {
                if (!destroyed && replyToken == attempt) {
                    if (!cancelled || !dispatched) finish(failure) else stopSelf()
                }
            }
        }
    }

    private fun ensureDiscovery() {
        if (!discoveryAllowed()) return
        postAttempt { startDiscoveryOnMain() }
    }

    private fun discoveryAllowed(): Boolean = if (waitingCode || snapshot.phase == Phase.PAIRING)
        PhoneAdbEndpointRequest.pairingDiscoveryAllowed(this) else PhoneAdbEndpointRequest.discoveryAllowed(this)

    private fun startDiscoveryOnMain() {
        if (discovery != null || !discoveryAllowed()) return
        val generation = discoveryGeneration.get()
        discovery = DexDiscovery(this) { p, c, _ ->
            if (!destroyed && !finished && !cancelled && generation == discoveryGeneration.get()) {
                pairing = p
                connection = c
            }
        }.also { it.start() }
    }

    /** A pairing sheet owns a one-time port; never reuse a cached port from an earlier sheet. */
    private fun refreshPairingDiscovery() {
        val generation = discoveryGeneration.incrementAndGet()
        pairing = null
        connection = null
        postAttempt {
            if (generation != discoveryGeneration.get()) return@postAttempt
            discovery?.close()
            discovery = null
            pairing = null
            connection = null
            startDiscoveryOnMain()
        }
    }

    private fun knownConnectEndpoints(): List<DexEndpoint> {
        val prefs = DexPrefs(this)
        return listOfNotNull(connection, PhoneAdbEndpointRequest.tlsEndpoint(), IndependentRecoveryAccess.recoveryEndpoint(this)) +
            VdmRecoveryEndpoints.candidates(DexEndpoint(prefs.host, prefs.connectPort), true, DexAccess::acceptsHost)
    }

    /** An unavailable listener is not evidence that Android revoked the saved key. */
    private fun waitForSavedConnectionOrCode() {
        val deadline = SystemClock.elapsedRealtime() + CODE_TIMEOUT_MS
        showCode(null, savedRetryAllowed = true)
        refreshPairingDiscovery()
        while (SystemClock.elapsedRealtime() < deadline) {
            checkActive()
            if (codeGate?.hasCode() == true) return
            ensureDiscovery()
            val environment = PhoneAdbEndpointRequest.environment(this)
            if (PhoneAdbEndpointRequest.discoveryAllowed(this)) {
                if (savedRetry.fresh(connection, environment) || savedRetry.fresh(PhoneAdbEndpointRequest.tlsEndpoint(), environment)) {
                    AppLog.i("PHONE saved pairing retry: fresh debugging endpoint or changed wireless state", false)
                    return
                }
            } else savedRetry.markTried(emptyList(), environment)
            Thread.sleep(100)
        }
        throw DesktopPreparationException("Беспроводная отладка не появилась. Включите её и повторите активацию; сохранённое сопряжение не удалено.")
    }

    private fun pairWithNotification(initialProblem: String? = null, initialCode: String? = null) {
        val deadline = SystemClock.elapsedRealtime() + CODE_TIMEOUT_MS
        var problem: String? = initialProblem
        var suppliedCode = initialCode
        while (true) {
            checkActive()
            if (SystemClock.elapsedRealtime() >= deadline) throw DesktopPreparationException("Время ввода кода истекло. Повторите активацию.")
            if (suppliedCode == null) {
                showCode(problem)
                while (suppliedCode == null && SystemClock.elapsedRealtime() < deadline) {
                    checkActive()
                    ensureDiscovery()
                    suppliedCode = codeGate?.takeCode()
                    if (suppliedCode == null) Thread.sleep(100)
                }
            }
            val code = suppliedCode ?: continue
            suppliedCode = null
            checkActive()
            try {
                update("Код принят. Ищу открытое окно сопряжения Android…", Phase.PAIRING)
                refreshPairingDiscovery()
                val endpoint = awaitEndpoint(20_000) { pairing }
                if (endpoint == null) {
                    problem = "Окно сопряжения не найдено. Включите Wi-Fi и «Беспроводную отладку», откройте «Подключить устройство с помощью кода» и введите новый код, оставив это окно открытым."
                    continue
                }
                PairingRequest().use { request ->
                    pairingRequest = request
                    try {
                        checkActive()
                        runBlocking { DexAccess.pair(applicationContext, endpoint.port, code, endpoint.host, request = request) }
                        checkActive()
                    } finally { pairingRequest = null }
                }
                postAttempt { notifications.cancel(CODE_ID) }
                return
            } catch (e: DexAccessException) {
                if (!PhonePairingPromptPolicy.canRetryWithCode(e.reason)) throw e
                problem = "Сопряжение не принято. Откройте новое окно с кодом и повторите."
            } finally { codeGate?.pairingFinished() }
        }
    }

    private fun awaitEndpoint(timeoutMs: Long, value: () -> DexEndpoint?): DexEndpoint? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            checkActive()
            ensureDiscovery()
            value()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(100)
        }
    }

    private fun checkActive() { if (cancelled || destroyed || finished || currentInstance !== this || Thread.currentThread().isInterrupted) throw InterruptedException() }
    private fun postAttempt(action: () -> Unit) {
        val attempt = replyToken
        main.post {
            if (!destroyed && !finished && !cancelled && attempt != null && replyToken == attempt) action()
        }
    }
    private fun publish(value: Phase, message: String) {
        if (snapshot.phase != value) AppLog.i("PHONE activation phase=${value.name}", false)
        status = message
        snapshot = Snapshot(value, message, replyToken, inAppOnly)
    }
    private fun update(message: String, value: Phase = Phase.CHECKING) = postAttempt {
        publish(value, message)
        notifications.notify(PROGRESS_ID, progress(message))
    }
    private fun showCode(problem: String?, reopen: Boolean = true, optional: Boolean = false, savedRetryAllowed: Boolean = false) = postAttempt {
        val previous = codeGate?.token()
        synchronized(attemptLock) {
            if (reopen) codeGate?.open()
            waitingCode = codeGate?.token() != null
        }
        if (!waitingCode) return@postAttempt
        if (previous == null) AppLog.i("PHONE pairing input available: optional=$optional", false)
        // A saved key may be rejected after startup, when the code channel was not required.
        // Keep this attempt and its reply token; only switch the visible input surface.
        val blocked = notificationBlock(this, needsCode = true) != null
        if (blocked) inAppOnly = true
        val prompt = if (savedRetryAllowed)
            "Сохранённое сопряжение не удалено. Включите Wi-Fi и «Беспроводную отладку» — проверка продолжится автоматически. Новый код необязателен; введите его, только если открыли окно нового сопряжения Android."
        else if (inAppOnly)
            "Откройте в беспроводной отладке окно сопряжения. Введите 6 цифр в приложении HeadSwap."
        else "Откройте в беспроводной отладке окно сопряжения. Введите 6 цифр здесь или в уведомлении HeadSwap."
        val message = listOfNotNull(problem, if (blocked) "Уведомление с вводом кода отключено." else null, prompt).joinToString("\n")
        if (!optional) {
            publish(Phase.WAITING_CODE, message)
            notifications.notify(PROGRESS_ID, progress(status))
        }
        if (inAppOnly) notifications.cancel(CODE_ID) else notifications.notify(CODE_ID, codeNotification(
            if (optional) "Сохранённый доступ проверяется автоматически. Если вы уже открыли окно с новым кодом Android, его можно ввести сейчас." else message))
    }
    private fun acceptCode(token: String, value: String): Boolean {
        val interrupted = synchronized(attemptLock) {
            if (cancelled || finished || destroyed || dispatched || !waitingCode || codeGate?.offer(token, value) != true) return false
            waitingCode = false
            coordinator
        }
        // This request owns only verification/recovery. Never interrupt the committed bootstrap.
        interrupted?.close()
        AppLog.i("PHONE pairing code accepted for the current attempt", false)
        postAttempt { if (!inAppOnly) notifications.notify(CODE_ID, codeNotification("Код принят. Подготавливаю сопряжение…", allowReply = false)) }
        return true
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
        val promptToken = codeGate?.token()
        val settings = PendingIntent.getActivity(this, 8, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CODE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setLargeIcon(BrandArtwork.bitmap(resources))
            .setContentTitle("HeadSwap · введите код сопряжения")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply {
                if (allowReply && promptToken != null) {
                    val reply = RemoteInput.Builder(CODE).setLabel("6 цифр, например 123456")
                        .setAllowFreeFormInput(true).build()
                    addAction(Notification.Action.Builder(Icon.createWithResource(this@PhonePairingService, android.R.drawable.ic_menu_edit),
                        "Ввести 6 цифр", serviceAction(REPLY, 1, true, promptToken)).addRemoteInput(reply)
                        .setAllowGeneratedReplies(false).setAuthenticationRequired(true).build())
                }
            }.build()
    }
    private fun serviceAction(action: String, request: Int, mutable: Boolean, promptToken: String? = null) = PendingIntent.getService(this, request,
        Intent(this, PhonePairingService::class.java).setAction(action)
            // Extras are not part of PendingIntent identity. A new attempt must not update an old reply/cancel token.
            .setData(Uri.Builder().scheme("headswap-desk").authority("activation")
                .appendPath(checkNotNull(if (action == REPLY) promptToken else replyToken)).appendPath(action).build())
            .putExtra(TOKEN, if (action == REPLY) promptToken else replyToken),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    private fun finish(failure: String?) {
        if (destroyed || finished) return
        finished = true
        active = false
        val cancelledMessage = if (cancelled) when (IndependentRecoveryAccess.snapshot(this).kind) {
            IndependentRecoveryAccess.Kind.PENDING, IndependentRecoveryAccess.Kind.RESTORE_REQUIRED ->
                "Подготовка остановлена. Настройка отладки не завершена; в разделе «Активация» выберите «Исправить настройку активации»."
            IndependentRecoveryAccess.Kind.OWNED, IndependentRecoveryAccess.Kind.REUSED ->
                "Подготовка остановлена. Режим восстановления без Wi-Fi сохранён; активацию можно повторить."
            IndependentRecoveryAccess.Kind.DISABLED -> "Подготовка отменена."
        } else null
        publish(if (cancelled) Phase.CANCELLED else if (failure != null) Phase.FAILED else Phase.READY,
            cancelledMessage ?: failure ?: "HeadSwap готов к подключению.")
        codeGate?.cancel()
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
        coordinator?.close()
        pairingRequest?.close()
        codeGate?.cancel()
        worker?.interrupt()
        discovery?.close()
        discovery = null
        notifications.cancel(CODE_ID)
        super.onDestroy()
    }

    companion object {
        @Volatile private var currentInstance: PhonePairingService? = null

        /** Same one-use reply capability as SystemUI; never persisted or logged. */
        internal fun replyAttempt(): String? = currentInstance?.let {
            if (it.waitingCode && !it.cancelled && !it.finished && !it.destroyed) it.codeGate?.token() else null
        }

        internal fun submitCode(context: Context, attempt: String, value: String): Boolean {
            val code = PairingReplyPolicy.code(value) ?: return false
            return currentInstance?.acceptCode(attempt, code) == true
        }

        internal fun cancel(context: Context, attempt: String): Boolean =
            currentInstance?.cancelAttempt(attempt) == true

        private const val START = "com.headswap.desk.activation.START"
        private const val REPLY = "com.headswap.desk.activation.REPLY"
        private const val CANCEL = "com.headswap.desk.activation.CANCEL"
        private const val FORCE_PAIR = "force_pair"
        private const val IN_APP_ONLY = "in_app_only"
        private const val RESUME_COMMITTED = "resume_committed"
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
        @Volatile var snapshot = Snapshot(Phase.IDLE, "", null)
            private set
        @Volatile private var resumedReceiptStarted = Long.MIN_VALUE
        @Volatile private var completedReceiptStarted = Long.MIN_VALUE
        val phase: Phase get() = snapshot.phase

        /** A matching phone bootstrap is one transaction: grants do not yet imply projection readiness. */
        fun blocksProjectionForCommittedActivation(context: Context): Boolean {
            if (!DexPrefs(context).useDelegated) return false
            val receipt = PhoneActivationStatus.read(context)
            return PhoneCommittedActivationPolicy.blocks(true,
                receipt.phase == PhoneActivationStatus.Phase.PENDING,
                receipt.phase == PhoneActivationStatus.Phase.READY,
                receipt.activationBinding, receipt.startedElapsedMs, completedReceiptStarted)
        }

        /** Called only by this attempt's worker after a current, live coordinator READY check. */
        private fun completeCommittedActivation(context: Context, expectedStarted: Long) {
            val receipt = PhoneActivationStatus.read(context)
            if (PhoneCommittedActivationPolicy.canComplete(
                    receipt.phase == PhoneActivationStatus.Phase.READY, receipt.activationBinding,
                    receipt.startedElapsedMs, expectedStarted, DesktopActivationCoordinator.isReady(context))) {
                completedReceiptStarted = receipt.startedElapsedMs
            }
        }

        /** Continue the exact phone activation after instrumentation replaced its old app process. */
        @Synchronized internal fun resumeCommittedActivation(context: Context): Boolean {
            if (!DexPrefs(context).useDelegated || !DelegatedDesktopAccess.isReady(context)) return false
            val receipt = PhoneActivationStatus.read(context)
            if (receipt.phase != PhoneActivationStatus.Phase.READY ||
                receipt.activationBinding != PhoneCommittedActivationPolicy.Binding.CURRENT ||
                completedReceiptStarted == receipt.startedElapsedMs) return false
            if (active) return true
            if (resumedReceiptStarted == receipt.startedElapsedMs) return false
            resumedReceiptStarted = receipt.startedElapsedMs // Failure/cancel never causes an implicit retry loop.
            return try {
                context.startForegroundService(Intent(context, PhonePairingService::class.java).setAction(START)
                    .putExtra(IN_APP_ONLY, true).putExtra(RESUME_COMMITTED, true)) != null
            } catch (_: Exception) {
                status = "Не удалось продолжить проверку рабочего стола. Нажмите «Активировать»."
                snapshot = Snapshot(Phase.FAILED, status, null, true)
                false
            }
        }
        fun start(context: Context, forcePair: Boolean = false, allowWithoutNotification: Boolean = false): android.content.ComponentName? {
            if (!allowWithoutNotification && !DesktopActivationCoordinator.isReady(context) && notificationBlock(context, forcePair || !DexPrefs(context).hasPaired) != null) {
                status = "Уведомления активации отключены. Откройте HeadSwap и выберите ручной ввод или настройку уведомлений."
                return null
            }
            return context.startForegroundService(
                Intent(context, PhonePairingService::class.java).setAction(START).putExtra(FORCE_PAIR, forcePair)
                    .putExtra(IN_APP_ONLY, allowWithoutNotification))
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
