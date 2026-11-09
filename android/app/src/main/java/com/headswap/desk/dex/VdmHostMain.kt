package com.headswap.desk.dex

import android.content.AttributionSource
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.headswap.desk.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Shell-owned process launched only after explicit app activation. No TCP ADB changes. */
@Keep
class VdmHostMain private constructor(private val args: VdmHostPolicy.Args) {
    private var core: VdmHostCore? = null
    private var appUid = -1
    private var ownerLifetime: IBinder? = null
    private val exitArmed = AtomicBoolean()
    private val appDeath = IBinder.DeathRecipient { terminate() }
    private val host = object : IVdmDesktopHost.Stub() {
        private fun requireClient() {
            if (Binder.getCallingUid() != appUid && Binder.getCallingUid() != VdmHostPolicy.SHELL_UID) throw SecurityException("Foreign VDM host caller")
        }
        override fun protocol(): Int { requireClient(); return VdmHostPolicy.PROTOCOL }
        override fun hostPid(): Int { requireClient(); return Process.myPid() }
        override fun openSession(producerLifetime: IBinder, producerPid: Int): IVdmDesktopSession {
            requireClient(); return checkNotNull(core).openSession(producerLifetime, producerPid)
        }
        override fun ownsDisplay(displayId: Int): Boolean { requireClient(); return core?.ownsDisplay(displayId) == true }
        override fun shutdown() {
            requireClient()
            armExitWatchdog()
            try { core?.close() }
            finally { Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 100) }
        }
    }
    private fun run() {
        check(Process.myUid() == VdmHostPolicy.SHELL_UID && Build.VERSION.SDK_INT >= 37) { "VDM requires Android 17 shell" }
        if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
        check(appAlive()) { "Activation process has ended" }
        appUid = appUidFromProc()
        check(appUid % 100000 >= 10000)
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val context = at.getMethod("getSystemContext").invoke(thread) as Context
        try {
            core = VdmHostCore.create(context, appUid) { terminate() }
            check(appAlive() && appUidFromProc() == appUid)
            val reply = publish()
            check(reply?.getBoolean(VdmHostPolicy.EXTRA_READY) == true) { "VDM host handoff was rejected" }
            val client = checkNotNull(reply.getBinder(VdmHostPolicy.EXTRA_CLIENT))
            client.linkToDeath(appDeath, 0)
            ownerLifetime = client
            println("HEADSWAP_DESK_VDM_READY")
            Looper.loop()
        } finally { runCatching { core?.close() } }
    }
    private fun publish(): Bundle? {
        check(appAlive())
        val authority = args.packageName + ".vdmhost"
        val appUser = appUid / 100000
        val api = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, VdmHostCore.service("activity"))
        val externalToken = Binder()
        var acquired = false
        try {
            val holder = checkNotNull(api.javaClass.getMethod("getContentProviderExternal", String::class.java,
                Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(api, authority, appUser, externalToken, VdmHostPolicy.DISPLAY_NAME))
            acquired = true
            val info = holder.javaClass.getField("info").get(holder) as ProviderInfo
            check(info.packageName == args.packageName && info.applicationInfo.uid == appUid)
            val provider = checkNotNull(holder.javaClass.getField("provider").get(holder))
            val extras = Bundle().apply {
                putInt(VdmHostPolicy.EXTRA_APP_PID, args.appPid)
                putInt(VdmHostPolicy.EXTRA_HOST_PID, Process.myPid())
                putInt(VdmHostPolicy.EXTRA_BUILD, BuildConfig.VERSION_CODE)
                putBinder(VdmHostPolicy.EXTRA_HOST, host)
            }
            val source = AttributionSource.Builder(Process.myUid()).setPackageName(VdmHostPolicy.SHELL_PACKAGE).build()
            return Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java)
                .invoke(provider, source, authority, VdmHostPolicy.METHOD_PUBLISH, args.nonce, extras) as Bundle?
        } finally {
            if (acquired) runCatching { api.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java,
                IBinder::class.java, Int::class.javaPrimitiveType).invoke(api, authority, externalToken, appUser) }
        }
    }
    private fun appAlive(): Boolean = runCatching {
        File("/proc/${args.appPid}/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8) == args.packageName
    }.getOrDefault(false)
    private fun appUidFromProc(): Int = File("/proc/${args.appPid}/status").useLines { lines ->
        lines.first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+"))[0].toInt()
    }
    private fun terminate(): Nothing {
        armExitWatchdog()
        try { core?.close() } finally { Process.killProcess(Process.myPid()) }
        exitProcess(0)
    }
    private fun armExitWatchdog() {
        if (exitArmed.compareAndSet(false, true)) {
            Thread({ Thread.sleep(2_000); Process.killProcess(Process.myPid()) }, "headswap-vdm-exit").apply { isDaemon = true; start() }
        }
    }
    companion object {
        @Keep @JvmStatic fun main(argv: Array<String>) {
            try { VdmHostMain(VdmHostPolicy.Args.parse(argv)).run() }
            catch (error: Throwable) {
                // Never print argv, nonce, activation tokens or reflected exception messages.
                System.err.println("HEADSWAP_DESK_VDM_FAILED ${error.javaClass.simpleName}")
                var cause: Throwable? = error
                repeat(4) {
                    val failure = cause ?: return@repeat
                    val frames = failure.stackTrace.take(8).joinToString("\n") { frame ->
                        "  ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
                    }
                    Log.e("HeadSwapVDM", "${failure.javaClass.name}\n$frames")
                    cause = failure.cause?.takeUnless { it === failure }
                }
                exitProcess(1)
            }
        }
    }
}
