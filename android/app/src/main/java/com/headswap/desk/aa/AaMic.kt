package com.headswap.desk.aa

import java.util.ArrayDeque

/** Откуда брать звук для микрофона AA. */
enum class MicMode(val key: String, val label: String) {
    /** Только микрофон ГУ (запрос записи по CarLife); нет данных - ждём дальше. */
    CAR("car", "машина"),

    /** Только микрофон телефона. */
    PHONE("phone", "телефон"),

    /** Сначала ГУ; если за HU_TIMEOUT_MS нет данных - микрофон телефона. */
    AUTO("auto", "авто");

    companion object {
        fun parse(key: String?): MicMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** Формат микрофона AA (MicCaptureFormat в headunit-revived): PCM 16 кГц 16 бит моно, кадры по 2048 сэмплов. */
object AaMicFormat {
    const val SAMPLE_RATE = 16000
    const val BITS = 16
    const val CHANNELS = 1
    const val BYTES_PER_SEC = SAMPLE_RATE * 2
    const val CHUNK_FRAMES = 2048
    const val CHUNK_BYTES = CHUNK_FRAMES * 2

    /** Длительность n байт PCM в микросекундах. */
    fun usForBytes(n: Int): Long = n * 1_000_000L / BYTES_PER_SEC

    /** Тело MEDIA_DATA на канале MIC: [timestamp u64 BE, мкс][PCM]. */
    fun dataPayload(tsUs: Long, pcm: ByteArray): ByteArray {
        val out = ByteArray(8 + pcm.size)
        for (i in 0 until 8) out[i] = (tsUs ushr (56 - 8 * i)).toByte()
        System.arraycopy(pcm, 0, out, 8, pcm.size)
        return out
    }
}

/** Телефон просит у ГУ микрофон (MicrophoneRequest). Реализует ядро моста. */
interface AaMicSink {
    /** true = принято, PCM пойдёт через AaPeer.sendMicPcm. false = отказ (телефону уйдёт ошибка). */
    fun onMicOpen(): Boolean

    fun onMicClose(why: String)
}

/** Источник звука на телефоне (AudioRecord). */
interface PhoneMic {
    /** Начать запись; sink получает PCM16 mono 16 кГц. false = не запустилось (нет разрешения и т.п.). */
    fun start(sink: (ByteArray) -> Unit): Boolean

    fun stop()
}

/**
 * Поток микрофона к телефону: нарезка PCM на кадры по 4096 Б (2048 сэмплов, ровно 128 мс, как требует гайд
 * Google и делает headunit-revived), окно maxUnacked из MicrophoneRequest, подтверждения Ack.
 *
 * Отправка кадров не блокирует: emit только ставит сообщение в очередь AAP. До activate() кадры копятся
 * (ответ MicrophoneResponse должен уйти раньше первых DATA). Хвост < 4096 Б при закрытии отбрасывается
 * (не дополняется нулями). Лишние кадры сверх окна ждут в очереди до maxPending, дальше OVERFLOW:
 * зависшего телефона не копим, сессию закрывает вызывающий.
 */
class AaMicUplink(
    private val emit: (sessionId: Int, tsUs: Long, pcm: ByteArray) -> Unit,
    private val maxPending: Int = MAX_PENDING_CHUNKS,
) {
    enum class Feed { OK, CLOSED, OVERFLOW }

    class Summary(val sessionId: Int, val bytesIn: Long, val chunksSent: Long, val chunksAcked: Long, val discardedBytes: Long) {
        override fun toString() =
            "сессия $sessionId: принято ${bytesIn} Б, кадров отправлено $chunksSent, подтверждено $chunksAcked, отброшено ${discardedBytes} Б"
    }

    private class Chunk(val tsUs: Long, val pcm: ByteArray)

    private val lock = Any()
    private var open = false
    private var ready = false
    private var sid = 0
    private var window = 0
    private var inFlight = 0
    private val pending = ArrayDeque<Chunk>()
    private val partial = ByteArray(AaMicFormat.CHUNK_BYTES)
    private var fill = 0
    private var partialTs = 0L
    private var bytesIn = 0L
    private var sent = 0L
    private var acked = 0L
    private var discarded = 0L

    val isOpen: Boolean get() = synchronized(lock) { open }
    val sessionId: Int get() = synchronized(lock) { sid }

    fun open(sessionId: Int, window: Int) {
        require(window in 1..4096) { "maxUnacked $window вне 1..4096" }
        synchronized(lock) {
            check(!open) { "микрофонная сессия уже открыта" }
            open = true
            ready = false
            sid = sessionId
            this.window = window
            inFlight = 0
            pending.clear()
            fill = 0
            bytesIn = 0
            sent = 0
            acked = 0
            discarded = 0
        }
    }

    /** MicrophoneResponse(успех) поставлен в очередь: можно слать данные. */
    fun activate() {
        synchronized(lock) {
            if (!open) return
            ready = true
            pump()
        }
    }

    /** pcm = PCM16 LE mono; capturedAtUs = момент захвата первого байта (мкс, часовой механизм телефона). */
    fun feed(pcm: ByteArray, capturedAtUs: Long): Feed {
        synchronized(lock) {
            if (!open) return Feed.CLOSED
            bytesIn += pcm.size
            var i = 0
            while (i < pcm.size) {
                if (fill == 0) partialTs = capturedAtUs + AaMicFormat.usForBytes(i)
                val take = minOf(partial.size - fill, pcm.size - i)
                System.arraycopy(pcm, i, partial, fill, take)
                fill += take
                i += take
                if (fill == partial.size) {
                    fill = 0
                    if (pending.size >= maxPending) {
                        discarded += partial.size + (pcm.size - i)
                        return Feed.OVERFLOW
                    }
                    pending.addLast(Chunk(partialTs, partial.copyOf()))
                    pump()
                }
            }
            return Feed.OK
        }
    }

    /** Ack телефона: count принятых сообщений сессии sessionId. false = не наш/лишний ack. */
    fun ack(sessionId: Int, count: Int): Boolean {
        synchronized(lock) {
            if (!open || sessionId != sid || count <= 0 || count > inFlight) return false
            inFlight -= count
            acked += count
            pump()
            return true
        }
    }

    /** Закрыть сессию; null, если она не была открыта. Невышедшее отбрасывается. */
    fun close(): Summary? {
        synchronized(lock) {
            if (!open) return null
            open = false
            ready = false
            discarded += fill + pending.sumOf { it.pcm.size.toLong() }
            pending.clear()
            fill = 0
            return Summary(sid, bytesIn, sent, acked, discarded)
        }
    }

    private fun pump() {
        while (open && ready && inFlight < window && pending.isNotEmpty()) {
            val c = pending.removeFirst()
            inFlight++
            sent++
            emit(sid, c.tsUs, c.pcm)
        }
    }

    companion object {
        /** Четыре кадра по 128 мс: застрявший телефон прерывает сессию, а не растит задержку. */
        const val MAX_PENDING_CHUNKS = 4
    }
}

/** Отменяемая отложенная задача (для таймера fallback). */
fun interface MicTimerHandle {
    fun cancel()
}

/**
 * Маршрутизация микрофона AA: кто и откуда даёт звук.
 *  - open от AA: режим CAR/AUTO просят запись у ГУ (CarLife MIC_RECORD_RECOG_START), PHONE сразу пишет с телефона;
 *  - данные ГУ (VR 0x58001) идут в AA; в AUTO без данных за huTimeoutMs - fallback на микрофон телефона;
 *  - close от AA или потеря CarLife: остановить запись на ГУ (MIC_RECORD_END) / на телефоне.
 * Без Android: всё внешнее приходит лямбдами. Лог: префикс MIC.
 */
class AaMicRouter(
    private val log: (String, Boolean) -> Unit,
    private val mode: () -> MicMode,
    /** Есть живая CarLife-сессия (после START), у ГУ можно просить запись. */
    private val huLinkUp: () -> Boolean,
    /** Запрос записи у ГУ: true = старт, false = стоп. false в ответе = отправить не удалось. */
    private val huRecord: (start: Boolean) -> Boolean,
    private val phone: () -> PhoneMic?,
    /** PCM -> телефону (AapSession.sendMicPcm). */
    private val toAa: (pcm: ByteArray, capturedAtUs: Long) -> Unit,
    private val schedule: (delayMs: Long, task: Runnable) -> MicTimerHandle,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nowUs: () -> Long = { System.nanoTime() / 1_000 },
    private val huTimeoutMs: Long = HU_TIMEOUT_MS,
    /** Baidu MD (ReceiveDataThread.FILTER_PAKCAGE_NUM) выбрасывает первые 20 пакетов VR после старта записи. */
    private val huSkipPackets: Int = HU_SKIP_PACKETS,
) {
    enum class State { IDLE, HU_WAIT, HU, PHONE }

    private val lock = Any()
    private var state = State.IDLE
    private var openMode = MicMode.AUTO
    private var gen = 0
    private var timer: MicTimerHandle? = null
    private var openedAt = 0L
    private var huReqAt = 0L
    private var huBytes = 0L
    private var huPackets = 0L
    private var huSkipped = 0
    private var huIgnoredLogged = false
    private var huLastLogAt = 0L
    private var huLastLogBytes = 0L
    private var phoneBytes = 0L
    private var phoneStarted = false
    private var fellBack = false
    private var huSilentLogged = false

    val current: State get() = synchronized(lock) { state }
    val bytesFromHu: Long get() = synchronized(lock) { huBytes }
    val bytesFromPhone: Long get() = synchronized(lock) { phoneBytes }

    /** Телефон открыл микрофон. false = отказ (в режиме CAR нет CarLife, в PHONE не запустился AudioRecord). */
    fun open(): Boolean {
        synchronized(lock) {
            if (state != State.IDLE) {
                log("MIC open при уже открытом (${state}): закрываю прошлый", true)
                stopLocked("повторный open")
            }
            val m = mode()
            openMode = m
            gen++
            openedAt = nowMs()
            huBytes = 0
            huPackets = 0
            huSkipped = 0
            huIgnoredLogged = false
            huLastLogAt = 0
            huLastLogBytes = 0
            phoneBytes = 0
            phoneStarted = false
            fellBack = false
            huSilentLogged = false
            val link = huLinkUp()
            log("MIC open от AA: режим=${m.label}, CarLife ${if (link) "жив" else "не готов"}", true)
            when (m) {
                MicMode.PHONE -> return startPhoneLocked("режим «телефон»")
                MicMode.CAR -> {
                    if (!link) {
                        log("MIC отказ: режим «машина», а CarLife-сессии нет", true)
                        return false
                    }
                    if (!requestHuLocked()) {
                        log("MIC отказ: запрос записи к ГУ не ушёл", true)
                        return false
                    }
                    return true
                }
                MicMode.AUTO -> {
                    if (link && requestHuLocked()) return true
                    return startPhoneLocked("авто: ГУ недоступен")
                }
            }
        }
    }

    fun close(why: String) {
        synchronized(lock) {
            if (state == State.IDLE) return
            stopLocked(why)
        }
    }

    /** CarLife-сессия закрыта (USB оборван): запись на ГУ пропала, в AUTO переходим на телефон. */
    fun onCarlifeLost() {
        synchronized(lock) {
            if (state != State.HU_WAIT && state != State.HU) return
            log("MIC: CarLife закрыт во время записи с ГУ (получено $huBytes Б)", true)
            timer?.cancel()
            timer = null
            if (openMode == MicMode.AUTO) {
                fellBack = true
                state = State.IDLE
                startPhoneLocked("ГУ отключился")
            }
        }
    }

    /** PCM от ГУ (VR 0x58001, тело сообщения). */
    fun onHuData(pcm: ByteArray) {
        val captured: Long
        synchronized(lock) {
            when (state) {
                State.HU_WAIT -> {
                    state = State.HU
                    timer?.cancel()
                    timer = null
                    huLastLogAt = nowMs()
                    log("MIC: ГУ прислал первые данные: ${pcm.size} Б спустя ${nowMs() - huReqAt} мс после запроса", true)
                }
                State.HU -> {}
                else -> {
                    if (!huIgnoredLogged) {
                        huIgnoredLogged = true
                        log("MIC: данные ГУ (${pcm.size} Б) при состоянии $state игнорирую", false)
                    }
                    return
                }
            }
            huPackets++
            if (huSkipped < huSkipPackets) {
                huSkipped++
                return
            }
            huBytes += pcm.size
            val t = nowMs()
            if (t - huLastLogAt >= 1000) {
                val dt = (t - huLastLogAt) / 1000.0
                log("MIC: с ГУ ${huBytes} Б (+%.0f Б/с, ждём ${AaMicFormat.BYTES_PER_SEC}), пакетов $huPackets, последний ${pcm.size} Б".format((huBytes - huLastLogBytes) / dt), true)
                huLastLogAt = t
                huLastLogBytes = huBytes
            }
            captured = nowUs() - AaMicFormat.usForBytes(pcm.size)
        }
        toAa(pcm, captured)
    }

    private fun onPhoneData(pcm: ByteArray) {
        val captured: Long
        synchronized(lock) {
            if (state != State.PHONE) return
            phoneBytes += pcm.size
            captured = nowUs() - AaMicFormat.usForBytes(pcm.size)
        }
        toAa(pcm, captured)
    }

    private fun requestHuLocked(): Boolean {
        if (!huRecord(true)) return false
        state = State.HU_WAIT
        huReqAt = nowMs()
        val g = gen
        log("MIC: запросила запись у ГУ (MIC_RECORD_RECOG_START), жду данные ${huTimeoutMs} мс", true)
        timer = schedule(huTimeoutMs, Runnable { onHuTimeout(g) })
        return true
    }

    private fun onHuTimeout(g: Int) {
        synchronized(lock) {
            if (g != gen || state != State.HU_WAIT) return
            if (openMode == MicMode.AUTO) {
                log("MIC: ГУ молчит ${huTimeoutMs} мс (данных 0 Б, пакетов $huPackets): fallback на микрофон телефона", true)
                huRecord(false)
                fellBack = true
                state = State.IDLE
                startPhoneLocked("fallback: ГУ не прислал данных")
            } else if (!huSilentLogged) {
                huSilentLogged = true
                log("MIC: ГУ молчит ${huTimeoutMs} мс, режим «машина»: fallback выключен, жду дальше", true)
            }
        }
    }

    private fun startPhoneLocked(reason: String): Boolean {
        val p = phone()
        if (p == null) {
            log("MIC: микрофон телефона недоступен ($reason)", true)
            return false
        }
        state = State.PHONE
        phoneStarted = true
        val ok = try {
            p.start { onPhoneData(it) }
        } catch (e: Exception) {
            log("MIC: микрофон телефона не запустился: ${e.javaClass.simpleName}: ${e.message}", true)
            false
        }
        if (!ok) {
            state = State.IDLE
            phoneStarted = false
            log("MIC: микрофон телефона не запустился ($reason)", true)
            return false
        }
        log("MIC: источник = микрофон телефона ($reason)", true)
        return true
    }

    private fun stopLocked(why: String) {
        val was = state
        timer?.cancel()
        timer = null
        gen++
        if (was == State.HU_WAIT || was == State.HU) {
            log("MIC: останавливаю запись на ГУ (MIC_RECORD_END)", true)
            huRecord(false)
        }
        if (phoneStarted) {
            try {
                phone()?.stop()
            } catch (_: Exception) {
            }
            phoneStarted = false
        }
        state = State.IDLE
        log(
            "MIC close ($why): был $was, с ГУ ${huBytes} Б ($huPackets пакетов), с телефона ${phoneBytes} Б, " +
                "длительность ${nowMs() - openedAt} мс${if (fellBack) ", был fallback" else ""}", true
        )
    }

    companion object {
        const val HU_TIMEOUT_MS = 2000L

        /** 0: пакеты ГУ не режем. У Baidu 20, но размер пакета ГУ Honda неизвестен (при 4096 Б это 2,5 с речи). */
        const val HU_SKIP_PACKETS = 0
    }
}
