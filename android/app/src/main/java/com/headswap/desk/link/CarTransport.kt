package com.headswap.desk.link

/**
 * Свойства транспорта CarLife. Для USB значения ровно те, что были до появления абстракции.
 * kind идёт в лог строкой «транспорт=…» и в WL-лог.
 */
class TransportCaps(
    /** usb-aoa, tcp-debug (один поток с внешним заголовком), tcp6 (6 сокетов Baidu без внешнего заголовка). */
    val kind: String,
    /** Поток с внешним заголовком [chan u32][len u32] (USB AOA, debug-TCP). */
    val outerHeader: Boolean,
    /** Каждый канал свой сокет, писатель на канал. */
    val separateSockets: Boolean,
    /** Обрыв приходит системным событием (USB_ACCESSORY_DETACHED), а не только EOF. */
    val nativeDetach: Boolean,
) {
    companion object {
        val USB_AOA = TransportCaps("usb-aoa", outerHeader = true, separateSockets = false, nativeDetach = true)
        val TCP_DEBUG = TransportCaps("tcp-debug", outerHeader = true, separateSockets = false, nativeDetach = false)
        val TCP6 = TransportCaps("tcp6", outerHeader = false, separateSockets = true, nativeDetach = false)
    }
}

/**
 * Транспорт CarLife на уровне «канал + inner-сообщение» (inner = внутренний заголовок 8/12 байт + тело).
 * Протокол (CarLifeSession) не знает, как каналы лежат на проводе: один поток с внешним заголовком или свой сокет на канал.
 */
interface CarTransport {
    val caps: TransportCaps
    /** True only after the USB fd itself returned EOF or a terminal read/write error. */
    val usbEndpointLost: Boolean get() = false
    /** A physical USB endpoint or a wireless channel was lost; explicit Stop is excluded. */
    val outputEndpointLost: Boolean get() = usbEndpointLost

    /** Для лога и экрана: описание аксессуара или адреса ГУ. */
    val label: String

    /**
     * Запуск чтения и записи. Вызовы sink сериализованы (одна нить за раз), onWritten тоже.
     * onClosed вызывается не больше одного раза и только при обрыве со стороны транспорта, не после close().
     */
    fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    )

    /**
     * Запись как у Baidu: 8 байт внешнего заголовка одним write, весь inner одним write (h242, Mobis CCNC).
     * Нужна только потоку с внешним заголовком; транспорты без него игнорируют.
     */
    fun setWholeWrites(on: Boolean) {}

    /** Неблокирующая постановка inner в очередь канала. */
    fun send(chan: Int, inner: ByteArray)

    /** Desktop frame: decide freshness immediately before writing its first fragment. */
    fun sendVideoFragment(inner: ByteArray, frame: VideoWrite, last: Boolean)

    /** Audio data may expire while waiting; framing/control messages must never be dropped. */
    fun sendTimed(chan: Int, inner: ByteArray, maxAgeMs: Long) = send(chan, inner)
    val audioExpiredPackets: Long get() = 0L
    /** Timed PCM evicted to enforce the waiting queue byte/packet budget. */
    val audioOverflowPackets: Long get() = 0L

    /** Закрыть всё, разбудить нити. Идемпотентно, onClosed не зовёт. */
    fun close()

    /** Принято байт от ГУ (все каналы). */
    val rxBytes: Long

    /** Сообщений в очереди, где стоит канал chan (у потока одна общая очередь на все каналы). */
    fun queueSize(chan: Int): Int

    /**
     * Выбросить из очереди ещё не записанные inner канала chan, для которых [pred] истинен; сколько выброшено.
     * Видео-счётчики не трогает (звук туда не входит). По умолчанию ничего не чистит.
     */
    fun dropQueued(chan: Int, pred: (ByteArray) -> Boolean): Int = 0

    /** Pending video includes the frame currently blocked in the transport write. */
    val queuedVideoBytes: Long
    val queuedVideoFrames: Int
    /** Age of the video write currently in progress, or zero when no video is being written. */
    val videoWriteAgeMs: Long get() = 0L

    /** Скорость слива видео, байт/с (0 = ещё не измерена). */
    val drainBytesPerSec: Double
}

/** Only PCM DATA packets are allowed to expire, independent of a caller's chosen channel. */
internal object AudioPacketDeadline {
    fun deadline(chan: Int, inner: ByteArray, nowMs: Long, maxAgeMs: Long): Long {
        if (maxAgeMs <= 0 || inner.size < 12) return Long.MAX_VALUE
        val expected = when (chan) {
            3 -> 0x00030006
            4 -> 0x00040003
            5 -> 0x00050003
            else -> return Long.MAX_VALUE
        }
        if (com.headswap.desk.Wire.u32(inner, 8) != expected) return Long.MAX_VALUE
        return if (nowMs > Long.MAX_VALUE - maxAgeMs) Long.MAX_VALUE else nowMs + maxAgeMs
    }
}
