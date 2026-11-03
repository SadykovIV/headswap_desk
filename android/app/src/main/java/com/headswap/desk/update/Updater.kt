package com.headswap.desk.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.headswap.desk.AppLocale
import com.headswap.desk.AppLog
import com.headswap.desk.BuildConfig
import com.headswap.desk.R
import com.headswap.desk.ui.UiText

class UpdatePrefs(ctx: Context) {
    // Never migrate the old "update" store: its versions, URLs and dismissals belong to upstream AA.
    private val sp = ctx.getSharedPreferences("desktop_updates", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
    var lastCheck: Long
        get() = sp.getLong("lastCheck", 0)
        set(v) = sp.edit().putLong("lastCheck", v).apply()
    var dismissed: String
        get() = sp.getString("dismissed", "") ?: ""
        set(v) = sp.edit().putString("dismissed", v).apply()

    fun save(r: UpdateLogic.Release?) = sp.edit().putString("tag", r?.tag).putString("apkName", r?.apkName).apply()
    fun release(): UpdateLogic.Release? {
        val saved = sp.all
        return UpdateLogic.Release.fromTag(saved["tag"] as? String, saved["apkName"] as? String)
    }
}

object Updater {
    enum class Step { IDLE, CHECKING }
    @Volatile var step = Step.IDLE
        private set
    @Volatile var error: UiText? = null
        private set

    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun available(ctx: Context): UpdateLogic.Release? =
        UpdatePrefs(ctx).release()?.takeIf { UpdateLogic.isNewer(it.version, BuildConfig.VERSION_NAME) }

    fun banner(ctx: Context): UpdateLogic.Release? {
        val p = UpdatePrefs(ctx)
        return if (p.enabled) available(ctx)?.takeIf { it.version != p.dismissed } else null
    }

    fun dismiss(ctx: Context, version: String) { UpdatePrefs(ctx).dismissed = version }

    fun maybeCheck(ctx: Context) {
        val p = UpdatePrefs(ctx)
        if (UpdateLogic.shouldCheck(p.enabled, p.lastCheck, System.currentTimeMillis())) check(ctx, manual = false)
    }

    fun check(ctx: Context, manual: Boolean) {
        synchronized(this) {
            if (step != Step.IDLE) return
            step = Step.CHECKING
            error = null
        }
        val app = ctx.applicationContext
        val p = UpdatePrefs(app)
        if (manual) toast(app, UiText.res(R.string.upd_status_checking))
        Thread({
            try {
                val rel = latest()
                p.save(rel)
                p.lastCheck = System.currentTimeMillis()
                AppLog.i("обновления HeadSwap Desktop: ${rel?.tag ?: "нет подходящего выпуска"}, установлена ${BuildConfig.VERSION_NAME}")
                if (manual) main.post {
                    if (rel == null) toast(app, UiText.res(R.string.upd_err_no_release))
                    else if (!UpdateLogic.isNewer(rel.version, BuildConfig.VERSION_NAME)) toast(app, UiText.res(R.string.upd_status_latest))
                    else if (ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed) start(ctx)
                }
            } catch (e: Exception) {
                AppLog.e("обновления HeadSwap Desktop: проверка не удалась", e)
                p.lastCheck = System.currentTimeMillis() - UpdateLogic.CHECK_INTERVAL_MS + UpdateLogic.RETRY_MS
                val text = UiText.res(R.string.upd_err_check, UiText.raw(e.message ?: e.javaClass.simpleName))
                error = text
                if (manual) toast(app, text)
            } finally {
                step = Step.IDLE
            }
        }, "desktop-update-check").start()
    }

    private fun latest(): UpdateLogic.Release? {
        // /releases/latest omits prereleases. Follow pagination using only the fixed repository host.
        var best: UpdateLogic.Release? = null
        ReleaseFeed.pages { best = UpdateLogic.newestInPage(it, best) }
        return best
    }

    /** The browser downloads only this exact release asset; Android handles installation and signature checks. */
    fun start(act: Activity) {
        val rel = available(act) ?: return
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.upd_status_available, rel.version))
            .setMessage(act.getString(R.string.upd_open_browser, rel.apkName))
            .setPositiveButton(R.string.upd_download) { _, _ ->
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: android.content.ActivityNotFoundException) {
                    toast(act, UiText.res(R.string.upd_no_browser))
                }
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    private fun toast(ctx: Context, text: UiText) {
        main.post { Toast.makeText(ctx, text.get(AppLocale.wrap(ctx)), Toast.LENGTH_SHORT).show() }
    }
}
