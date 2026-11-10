package com.headswap.desk.dex

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.InterruptedByTimeoutException
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

enum class DexAccessStage {
    VALIDATE, IDENTITY, PAIR_CONNECT, PAIR_HANDSHAKE, PROBE_CONNECT, PROBE_SHELL, CONNECT
}

enum class DexAccessReason {
    INVALID_HOST, INVALID_PORT, INVALID_CODE, REFUSED, TIMEOUT, NETWORK_UNREACHABLE,
    PERMISSION_DENIED, TLS_HANDSHAKE, TLS_PROTOCOL, TLS_PROVIDER, PAIR_REJECTED,
    CONNECTION_CLOSED, IDENTITY_FAILURE, SHELL_REJECTED, IO_FAILURE, INTERNAL,
    AUTHORIZATION_REJECTED, TLS_VERSION_UNSUPPORTED, LOCAL_ADB_CONFIGURATION, LOCAL_ADB_RESTORE_REQUIRED
}

/** Deliberately omits the raw cause: library errors can carry pairing material. */
class DexAccessException(val stage: DexAccessStage, val reason: DexAccessReason) : IOException(
    "${DexAccessDiagnostics.stageLabel(stage)}: ${DexAccessDiagnostics.reasonLabel(reason, stage)} [$stage/$reason]"
)

internal object DexAccessDiagnostics {
    fun stageLabel(stage: DexAccessStage): String = when (stage) {
        DexAccessStage.VALIDATE -> "Проверка параметров"
        DexAccessStage.IDENTITY -> "Сохранённый ключ ADB"
        DexAccessStage.PAIR_CONNECT -> "Подключение к порту сопряжения"
        DexAccessStage.PAIR_HANDSHAKE -> "Защищённое сопряжение"
        DexAccessStage.PROBE_CONNECT, DexAccessStage.CONNECT -> "Подключение к основной отладке"
        DexAccessStage.PROBE_SHELL -> "Проверка доступа Android shell"
    }

    fun reasonLabel(reason: DexAccessReason, stage: DexAccessStage): String {
        val pairing = stage == DexAccessStage.PAIR_CONNECT || stage == DexAccessStage.PAIR_HANDSHAKE
        val endpointHint = if (pairing) "Откройте окно сопряжения с кодом заново и проверьте свежие IP и порт из этого окна; оставьте его открытым" else
            "Проверьте IP и основной порт на экране «Беспроводная отладка»; порт из окна с кодом здесь не подходит"
        return when (reason) {
        DexAccessReason.INVALID_HOST -> "укажите IP этого телефона из настроек отладки или 127.0.0.1; адреса других устройств запрещены"
        DexAccessReason.INVALID_PORT -> "порт должен быть числом от 1 до 65535"
        DexAccessReason.INVALID_CODE -> "нужен шестизначный код из открытого системного окна сопряжения"
        DexAccessReason.REFUSED -> "телефон не принимает соединение на этом порту. $endpointHint"
        DexAccessReason.TIMEOUT -> "истекло время ожидания. Проверьте актуальный порт, IP и включённую беспроводную отладку"
        DexAccessReason.NETWORK_UNREACHABLE -> "адрес телефона сейчас недоступен. Проверьте подключение к Wi-Fi и IP"
        DexAccessReason.PERMISSION_DENIED -> "Android не разрешил сетевое подключение. Разрешите доступ к локальной сети, если система его запрашивает"
        DexAccessReason.TLS_HANDSHAKE -> "телефон не завершил защищённое подключение. Повторите активацию; если ошибка сохраняется, отправьте журнал"
        DexAccessReason.TLS_PROTOCOL -> "сбой защищённого обмена с отладкой. Повторите активацию; если ошибка сохраняется, отправьте журнал из настроек"
        DexAccessReason.AUTHORIZATION_REJECTED -> if (pairing)
            "телефон отклонил сопряжение. Откройте новое окно с кодом и повторите"
            else "телефон отклонил сохранённое сопряжение. Выберите «Сопрячь заново» и введите новый код; удалять данные приложения не нужно"
        DexAccessReason.TLS_VERSION_UNSUPPORTED -> "отладка отклонила требуемую версию защищённого соединения. Отправьте журнал для проверки совместимости прошивки"
        DexAccessReason.TLS_PROVIDER -> "TLS-компонент Android не завершил обмен. Сохраните журнал для проверки совместимости"
        DexAccessReason.PAIR_REJECTED -> "код не принят или окно сопряжения закрыто. Откройте его заново и введите свежие код и порт"
        DexAccessReason.CONNECTION_CLOSED -> "телефон закрыл соединение. $endpointHint"
        DexAccessReason.IDENTITY_FAILURE -> "не удалось прочитать или создать ключ приложения. Сохраните журнал; не удаляйте данные приложения"
        DexAccessReason.SHELL_REJECTED -> "сопряжение не предоставило shell-доступ. Проверьте основной порт и список сопряжённых устройств"
        DexAccessReason.IO_FAILURE -> "ошибка обмена с телефоном. Сохраните журнал с этапом сбоя"
        DexAccessReason.LOCAL_ADB_CONFIGURATION -> "не удалось включить восстановление без Wi-Fi. Требуется собственное сопряжение HeadSwap и обычная конфигурация отладки; существующая настройка другого приложения не заменяется. Отправьте журнал или выключите эту опцию и повторите активацию"
        DexAccessReason.LOCAL_ADB_RESTORE_REQUIRED -> "обычный режим отладки не подтверждён. Включите Wi-Fi и выберите «Активация → Исправить настройку активации». Если отладка недоступна, выполните на компьютере adb usb"
        DexAccessReason.INTERNAL -> "внутренняя ошибка подготовки подключения. Сохраните журнал"
        }
    }

    fun chain(error: Throwable): List<Throwable> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var cursor: Throwable? = error
        while (cursor != null && result.size < 8 && seen.add(cursor)) {
            result += cursor
            cursor = cursor.cause
        }
        return result
    }

    fun classify(error: Throwable): DexAccessReason {
        if (error is DexAccessException) return error.reason
        val causes = chain(error)
        // Inspect known platform tokens only; never return or log arbitrary exception messages.
        if (causes.any { it is SecurityException || it.message.orEmpty().contains("EACCES") || it.message.orEmpty().contains("EPERM") }) return DexAccessReason.PERMISSION_DENIED
        if (causes.any { it is SocketTimeoutException || it is InterruptedByTimeoutException || it is java.util.concurrent.TimeoutException || it.message.orEmpty().contains("ETIMEDOUT") }) return DexAccessReason.TIMEOUT
        if (causes.any { it is NoRouteToHostException || it.message.orEmpty().contains("ENETUNREACH") || it.message.orEmpty().contains("EHOSTUNREACH") }) return DexAccessReason.NETWORK_UNREACHABLE
        if (causes.any { it is ConnectException || it.message.orEmpty().contains("ECONNREFUSED") }) return DexAccessReason.REFUSED
        // Keep diagnostics loadable on the project's JDK 17 host tests. Kadb's Android
        // artifact contains Java 25 bytecode before D8 conversion; referencing its exception
        // types here would load them even while classifying an unrelated platform error.
        if (causes.any { isAdbAuthorizationFailure(it.javaClass.name) }) return DexAccessReason.AUTHORIZATION_REJECTED
        when (tlsSignal(error)) {
            TlsSignal.CERTIFICATE_REQUIRED, TlsSignal.UNKNOWN_CA, TlsSignal.CERTIFICATE_UNKNOWN,
            TlsSignal.BAD_CERTIFICATE, TlsSignal.ACCESS_DENIED -> return DexAccessReason.AUTHORIZATION_REJECTED
            TlsSignal.PROTOCOL_VERSION -> return DexAccessReason.TLS_VERSION_UNSUPPORTED
            else -> Unit
        }
        if (causes.any { it is SSLProtocolException }) return DexAccessReason.TLS_PROTOCOL
        if (causes.any { it is SSLHandshakeException }) return DexAccessReason.TLS_HANDSHAKE
        if (causes.any { it is SSLException }) return DexAccessReason.TLS_PROVIDER
        if (causes.any { it.message.orEmpty().contains("exportKeyingMaterial") || it.message.orEmpty().contains("Conscrypt") }) return DexAccessReason.TLS_PROVIDER
        if (causes.any {
                it.message.orEmpty().contains("Could not exchange peer info") ||
                    it.message.orEmpty().contains("Exchanging message wasn't successful")
            }) return DexAccessReason.PAIR_REJECTED
        if (causes.any { it is SocketException || it is EOFException }) return DexAccessReason.CONNECTION_CLOSED
        return if (causes.any { it is IOException }) DexAccessReason.IO_FAILURE else DexAccessReason.INTERNAL
    }

    internal fun isAdbAuthorizationFailure(typeName: String): Boolean = typeName in setOf(
        "com.flyfishxu.kadb.exception.AdbPairAuthException",
        "com.flyfishxu.kadb.exception.AdbAuthException",
    )

    /** Whitelisted TLS alerts only. Neither arbitrary exception messages nor credentials escape. */
    enum class TlsSignal {
        CERTIFICATE_REQUIRED, UNKNOWN_CA, CERTIFICATE_UNKNOWN, BAD_CERTIFICATE, ACCESS_DENIED,
        PROTOCOL_VERSION, WRONG_VERSION_NUMBER, BAD_RECORD_MAC, DECRYPTION_FAILED, UNEXPECTED_MESSAGE,
        HANDSHAKE_FAILURE, NONE
    }

    fun tlsSignal(error: Throwable): TlsSignal {
        val causes = chain(error)
        if (causes.none { it is SSLException }) return TlsSignal.NONE
        for (cause in causes) {
            val tokens = cause.message.orEmpty().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_")
            for (signal in TlsSignal.entries) {
                if (signal != TlsSignal.NONE && Regex("(^|_)${signal.name}(_|$)").containsMatchIn(tokens)) return signal
            }
        }
        return TlsSignal.NONE
    }

    /** Only exception class names and our own frame names/line numbers, never messages. */
    fun safeTrace(error: Throwable): String = chain(error).joinToString(" <- ") { cause ->
        val name = cause.javaClass.simpleName.filter { it.isLetterOrDigit() || it == '_' }.take(80)
        fun frameName(value: String) = value.filter { it.isLetterOrDigit() || it in "_$<>" }.take(80)
        val frames = cause.stackTrace.filter { it.className.startsWith("com.headswap.desk.") }
            .take(5).joinToString(",") { "${frameName(it.className.substringAfterLast('.'))}.${frameName(it.methodName)}:${it.lineNumber}" }
        if (frames.isEmpty()) name else "$name{$frames}"
    }
}
