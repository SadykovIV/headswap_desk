package com.headswap.desk.dex

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.headswap.desk.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** A pending request survives the deliberate main-process restart, never supplies privileges. */
object PhoneActivationStatus {
    enum class Phase { IDLE, PENDING, READY, FAILED }
    data class Snapshot(val phase: Phase, val message: String, val startedElapsedMs: Long = 0,
                        val deadlineElapsedMs: Long = 0,
                        internal val activationBinding: PhoneCommittedActivationPolicy.Binding = PhoneCommittedActivationPolicy.Binding.NO_ACTIVATION)
    internal data class Request(val token: String, val started: Long, val audio: Boolean, val screen: Boolean)

    fun read(context: Context): Snapshot {
        val request = readJson(context, PhoneActivationPolicy.REQUEST) ?: return Snapshot(Phase.IDLE, "")
        // An update restarts instrumentation. Preserve pairing, but never show the previous APK's result.
        if (!PhoneActivationPolicy.sameBuild(request.optInt("versionCode"), BuildConfig.VERSION_CODE)) return Snapshot(Phase.IDLE, "")
        val token = request.optString("token")
        val started = request.optLong("startedElapsedMs", -1)
        if (!PhoneActivationPolicy.validToken(token) || request.optInt("protocol") != PhoneActivationPolicy.PROTOCOL)
            return Snapshot(Phase.FAILED, "Сохранённая попытка запуска недействительна")
        val binding = PhoneCommittedActivationPolicy.binding(token, DelegatedDesktopInstrumentation.activationIdentity)
        val result = readJson(context, PhoneActivationPolicy.statusPath(token))?.takeIf { it.optString("token") == token }
        val phase = result?.optString("phase")
        val partial = phase == PhoneActivationPolicy.READY_WITHOUT_PANEL
        if (phase == "READY" || partial) {
            val ready = DelegatedDesktopAccess.isReady(context) &&
                (!request.optBoolean("audio") || DesktopAudioAccess.hasPermission(context)) &&
                (if (partial) request.optBoolean("screen") && DelegatedPanelAccess.unsupportedThisActivation
                 else !request.optBoolean("screen") || DelegatedPanelAccess.hasPermission(context))
            return if (ready) Snapshot(Phase.READY, if (partial) PhoneActivationPolicy.PANEL_MESSAGE else "Доступ включён на телефоне", started, activationBinding = binding)
            else Snapshot(Phase.FAILED, "Доступ завершён. Включите его снова на телефоне или с компьютера.", started, activationBinding = binding)
        }
        if (result?.optString("phase") == "FAILED")
            return Snapshot(Phase.FAILED, result.optString("message", "Активация не завершена").take(640), started, activationBinding = binding)
        if (!PhoneActivationPolicy.isPending(started, SystemClock.elapsedRealtime()))
            return Snapshot(Phase.FAILED, "Попытка запуска завершилась без подтверждения. Включите беспроводную отладку и повторите.", started, activationBinding = binding)
        return Snapshot(Phase.PENDING, "Включаю доступ на телефоне… HeadSwap может открыться заново.",
            started, started + PhoneActivationPolicy.DEADLINE_MS, binding)
    }

    @Synchronized fun clearFinished(context: Context) {
        if (read(context).phase != Phase.FAILED) return
        deletePriorResult(context)
        AtomicFile(file(context, PhoneActivationPolicy.REQUEST)).delete()
    }

    /** A successful new activation supersedes an already finished failure, never its own pending result. */
    @Synchronized internal fun clearPriorFailure(context: Context) {
        val request = readJson(context, PhoneActivationPolicy.REQUEST) ?: return
        val token = request.optString("token")
        if (!PhoneActivationPolicy.validToken(token)) return
        val result = readJson(context, PhoneActivationPolicy.statusPath(token)) ?: return
        if (result.optString("token") == token && result.optString("phase") == "FAILED") {
            deletePriorResult(context)
            AtomicFile(file(context, PhoneActivationPolicy.REQUEST)).delete()
        }
    }

    @Synchronized internal fun begin(context: Context, audio: Boolean, screen: Boolean): Request {
        check(read(context).phase != Phase.PENDING) { "Предыдущая активация ещё выполняется" }
        val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val started = SystemClock.elapsedRealtime()
        val request = JSONObject().put("protocol", PhoneActivationPolicy.PROTOCOL).put("versionCode", BuildConfig.VERSION_CODE).put("token", token)
            .put("uid", Process.myUid()).put("startedElapsedMs", started).put("audio", audio).put("screen", screen)
        deletePriorResult(context)
        write(context, PhoneActivationPolicy.REQUEST, request)
        return Request(token, started, audio, screen)
    }

    internal fun isCurrentPending(context: Context, request: Request): Boolean {
        val saved = readJson(context, PhoneActivationPolicy.REQUEST) ?: return false
        return saved.optString("token") == request.token && saved.optInt("uid") == Process.myUid() &&
            PhoneActivationPolicy.isPending(request.started, SystemClock.elapsedRealtime())
    }

    internal fun failed(context: Context, request: Request, message: String) {
        if (readJson(context, PhoneActivationPolicy.REQUEST)?.optString("token") != request.token) return
        write(context, PhoneActivationPolicy.statusPath(request.token), JSONObject().put("protocol", PhoneActivationPolicy.PROTOCOL)
            .put("token", request.token).put("phase", "FAILED").put("message", message.take(640)))
    }

    internal fun dispatched(context: Context, request: Request): Boolean =
        readJson(context, PhoneActivationPolicy.statusPath(request.token))?.let {
            it.optString("token") == request.token && it.optString("phase") in setOf("PENDING", "READY", PhoneActivationPolicy.READY_WITHOUT_PANEL)
        } ?: false

    private fun deletePriorResult(context: Context) {
        val token = readJson(context, PhoneActivationPolicy.REQUEST)?.optString("token") ?: return
        if (!PhoneActivationPolicy.validToken(token)) return
        AtomicFile(file(context, PhoneActivationPolicy.statusPath(token))).delete()
        file(context, PhoneActivationPolicy.statusPath(token) + ".tmp").delete()
    }

    private fun readJson(context: Context, relative: String): JSONObject? = runCatching {
        val file = AtomicFile(file(context, relative))
        file.openRead().use { input ->
            val bytes = ByteArray(4097); var size = 0
            while (size < bytes.size) { val n = input.read(bytes, size, bytes.size - size); if (n < 0) break; size += n }
            check(size <= 4096)
            JSONObject(String(bytes, 0, size, Charsets.UTF_8))
        }
    }.getOrNull()
    private fun file(context: Context, relative: String) = File(context.filesDir, relative.removePrefix("files/"))
    private fun write(context: Context, relative: String, json: JSONObject) {
        val file = AtomicFile(file(context, relative)); val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}

enum class PhoneActivationLaunch { ACCEPTED }
