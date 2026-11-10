package com.headswap.desk.dex

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.headswap.desk.BuildConfig
import org.json.JSONObject
import java.io.File

/** Private, bounded receipt. It contains no pairing code, ADB key, endpoint or shell command. */
internal object PairingContinuationStore {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "pairing-input.json"))
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun selection(context: Context): String = DexPrefs(context).let {
        "${it.accessMode}:${it.activationMethod}:${it.desktopMode}:${it.usbPlaybackAudio}:${it.desktopPanelControl}:${it.independentRecoveryWithoutWifi}"
    }

    @Synchronized fun save(context: Context, attempt: String, prompt: String, started: Long): Boolean = runCatching {
        check(PairingContinuationPolicy.round(attempt, prompt) != null)
        val record = JSONObject().put("attempt", attempt).put("prompt", prompt).put("started", started)
            .put("boot", boot(context)).put("build", BuildConfig.VERSION_CODE).put("selection", selection(context))
        val target = file(context)
        val output = target.startWrite()
        try { output.write(record.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(output) }
        catch (error: Throwable) { target.failWrite(output); throw error }
    }.isSuccess

    @Synchronized fun consume(context: Context, prompt: String): PairingContinuationPolicy.Request? {
        val request = runCatching {
            val bytes = file(context).openRead().use { it.readNBytes(2049) }
            check(bytes.size <= 2048)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            PairingContinuationPolicy.Request(json.getString("attempt"), json.getString("prompt"),
                json.getLong("started"), json.getInt("boot"), json.getInt("build"), json.getString("selection"))
        }.getOrNull() ?: return null
        // A stale action must not destroy a newer notification's receipt.
        if (request.prompt != prompt) return null
        clear(context)
        return request.takeIf { PairingContinuationPolicy.valid(it, prompt, SystemClock.elapsedRealtime(),
            boot(context), BuildConfig.VERSION_CODE, selection(context)) }
    }

    @Synchronized fun clear(context: Context) { file(context).delete() }
}
