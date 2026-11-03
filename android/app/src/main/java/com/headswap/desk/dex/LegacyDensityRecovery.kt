package com.headswap.desk.dex

import android.net.LocalServerSocket
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** One-way migration of the per-user journal written by 0.4.1. There is no API to create a
 * journal or apply a projection setting to the default display. A later user change wins.
 */
@Keep
internal object LegacyDensityRecovery {
    const val JOURNAL = "headswap_primary_density_lease_v1"

    internal data class Original(val forced: Int?, val initial: Int, val target: Int)
    internal interface Access {
        fun journal(): String?
        fun current(): Pair<Int, Int?> // Current logical density and explicit user override.
        fun restoreOriginal(forced: Int?)
        fun forget()
    }

    private fun original(raw: String): Original {
        val f = raw.split(':')
        check(f.size == 5 && f[0] == "v1") { "Unknown legacy density record" }
        val (initial, base, forced, target) = f.drop(1).map(String::toInt)
        check(initial > 0 && forced >= 0 && base == (forced.takeIf { it > 0 } ?: initial) &&
            target in 96..640 && base != target) { "Invalid legacy density record" }
        return Original(forced.takeIf { it > 0 }, initial, target)
    }

    internal fun recover(access: Access) {
        val raw = access.journal() ?: return
        val old = original(raw)
        val (current, forced) = access.current()
        if (current == old.target) {
            check(forced == old.target || forced == null && old.initial == old.target) {
                "Cannot establish ownership of the previous density setting"
            }
            // Recheck the record and current value just before the only authorized restoration.
            check(access.journal() == raw && access.current() == (current to forced))
            access.restoreOriginal(old.forced)
            check(access.current() == ((old.forced ?: old.initial) to old.forced)) {
                "Previous density was not restored; keeping the recovery record"
            }
        }
        if (access.journal() == raw) access.forget()
    }

    fun run(uid: Int) {
        check(Process.myUid() in setOf(0, 2000) && uid % 100000 >= 10000)
        val user = uid / 100000
        val store = AndroidAccess(user)
        if (store.journal() == null || !store.isCurrentUser()) return
        // Do not compete with a guard from an older process that still owns this user's lease.
        val lock = try { LocalServerSocket("headswap.primary.density.v1.$user") }
            catch (_: java.io.IOException) { return }
        lock.use { recover(store) }
    }

    private class AndroidAccess(private val user: Int) : Access {
        private val api by lazy { Class.forName("android.view.IWindowManager") }
        private val wm by lazy {
            val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "window")
            Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, service)
        }
        private val integer = Int::class.javaPrimitiveType
        fun isCurrentUser() = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) == user

        private fun setting(op: String, key: String, vararg values: String): String {
            val p = ProcessBuilder(listOf("/system/bin/settings", "--user", user.toString(), op, "secure", key) + values)
                .redirectErrorStream(true).start()
            check(p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); "Settings timed out" }
            val result = p.inputStream.bufferedReader().use { it.readText().trim() }
            check(p.exitValue() == 0 && !result.startsWith("Error") && !result.startsWith("Exception"))
            return result
        }

        override fun journal() = setting("get", JOURNAL).takeUnless { it.isBlank() || it == "null" }
        override fun forget() { setting("delete", JOURNAL) }
        override fun current(): Pair<Int, Int?> {
            check(isCurrentUser())
            val raw = setting("get", "display_density_forced")
            val forced = if (raw in setOf("null", "", "0")) null else raw.toInt().also { require(it > 0) }
            return (api.getMethod("getBaseDisplayDensity", integer).invoke(wm, 0) as Int) to forced
        }

        override fun restoreOriginal(forced: Int?) {
            check(isCurrentUser())
            if (forced == null) {
                api.getMethod("clearForcedDisplayDensityForUser", integer, integer).invoke(wm, 0, user)
            } else {
                api.getMethod("setForcedDisplayDensityForUser", integer, integer, integer).invoke(wm, 0, forced, user)
                // AOSP clears an override equal to physical density. Preserve an explicitly saved
                // same-value override only when WindowManager already reports its requested value.
                if (current() == (forced to null)) setting("put", "display_density_forced", forced.toString())
            }
        }
    }

    /** Legacy ADB transport invokes this installed class with its own app UID, never a DPI. */
    @JvmStatic fun main(args: Array<String>) {
        try { require(args.size == 1); run(args[0].toInt()) }
        catch (e: Exception) { Log.e("HeadSwapDensity", "Legacy recovery incomplete", e); exitProcess(1) }
    }
}
