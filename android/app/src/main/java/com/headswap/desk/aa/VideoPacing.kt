package com.headswap.desk.aa

/** Срез видео-очереди CarLife для статистики моста (CarlifeLink.videoStats). */
class LinkVideoStats(
    /** Скорость слива очереди в USB, байт/с (0 = ещё не измерена). */
    val drainBytesPerSec: Double,
    /** Байты видео в очереди / скорость слива, мс. */
    val backlogMs: Long,
    val queueFrames: Int,
    val queueBytes: Long,
)

/**
 * Скорость слива видео-очереди в USB: байты видеокадров за время записи (write+flush) в окнах ~1 с, сглаживание EWMA.
 * Время берём по записи ВСЕХ пакетов (звук и команды делят канал с видео), байты - только видео: получается
 * реальная пропускная способность канала для видео при текущем миксе. Пишет одна нить (writer), читают любые.
 */
class DrainMeter(private val windowMs: Long = WINDOW_MS) {
    /** Байт/с, 0 = пока не измерено. */
    @Volatile var bytesPerSec = 0.0
        private set

    private var winStartMs = NEVER
    private var winVideoBytes = 0L
    private var winBusyNs = 0L

    fun onWrite(nowMs: Long, video: Boolean, bytes: Int, durNs: Long) {
        if (winStartMs == NEVER) winStartMs = nowMs
        if (video) winVideoBytes += bytes
        winBusyNs += durNs
        if (nowMs - winStartMs < windowMs) return
        if (winVideoBytes > 0 && winBusyNs >= MIN_BUSY_NS) {
            val sample = winVideoBytes * 1e9 / winBusyNs
            val old = bytesPerSec
            bytesPerSec = if (old <= 0) sample else ALPHA * sample + (1 - ALPHA) * old
        }
        winStartMs = nowMs
        winVideoBytes = 0
        winBusyNs = 0
    }

    private companion object {
        const val WINDOW_MS = 1000L
        const val ALPHA = 0.5
        const val MIN_BUSY_NS = 50_000_000L     // в окне почти не писали: оценка шумная, прежнюю не трогаем
        const val NEVER = Long.MIN_VALUE
    }
}

/** Предел видео-очереди CarLife и пересчёт её длины во время. Чистые функции. */
object VideoQueueLimit {
    const val MAX_FRAMES = 30
    const val MAX_BACKLOG_MS = 500L

    /** Пока слив не измерен. */
    const val UNMEASURED_MAX_BYTES = 512 * 1024L

    /** Нижняя граница лимита по байтам: при очень медленном сливе один IDR не должен считаться переполнением сам по себе. */
    const val MIN_BYTES = 64 * 1024L

    /** Сколько после переполнения оно учитывается как давление на битрейт и каким бэклогом (см. [policyBacklogMs]). */
    const val OVERFLOW_HOLD_MS = 1500L
    const val OVERFLOW_BACKLOG_MS = 1000.0

    /** Бэклог очереди в мс; 0, пока слив не измерен. */
    fun backlogMs(queueBytes: Long, drainBytesPerSec: Double): Long =
        if (drainBytesPerSec > 0) (queueBytes * 1000 / drainBytesPerSec).toLong() else 0L

    /**
     * Очередь переполнена: уже стоящих в ней видеокадров больше 30 или их байт больше ~500 мс слива. Новый кадр в счёт
     * не идёт: в пустую очередь всегда принимается любой кадр (IDR на 160 КБ), иначе ГУ не получил бы ни одного.
     */
    fun overflow(queueFrames: Int, queueBytes: Long, drainBytesPerSec: Double): Boolean {
        if (queueFrames > MAX_FRAMES) return true
        val limit = if (drainBytesPerSec > 0) maxOf((drainBytesPerSec * MAX_BACKLOG_MS / 1000).toLong(), MIN_BYTES) else UNMEASURED_MAX_BYTES
        return queueBytes > limit
    }

    /**
     * Бэклог для политики битрейта. Сам предел очереди срезает бэклог (кадры при переполнении не попадают в очередь,
     * бэклог пилит от нуля до IDR), и EWMA такой пилы не дотягивает до 300 мс. Поэтому недавнее переполнение
     * ([sinceOverflowMs] < [OVERFLOW_HOLD_MS]) считается бэклогом не меньше [OVERFLOW_BACKLOG_MS].
     */
    fun policyBacklogMs(realMs: Long, sinceOverflowMs: Long): Double =
        if (sinceOverflowMs in 0 until OVERFLOW_HOLD_MS) maxOf(realMs.toDouble(), OVERFLOW_BACKLOG_MS) else realMs.toDouble()
}

/** Ограничитель запросов IDR у энкодера при переполнении очереди: не чаще раза в [minIntervalMs]. */
class IdrThrottle(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var lastMs = NEVER

    /** true = запрос можно слать (время запоминается); false = ждём уже запрошенный или ближайший естественный IDR. */
    fun tryRequest(nowMs: Long): Boolean {
        if (lastMs != NEVER && nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        return true
    }

    fun reset() {
        lastMs = NEVER
    }

    companion object {
        const val MIN_INTERVAL_MS = 1500L
        private const val NEVER = Long.MIN_VALUE
    }
}
