package com.headswap.desk.qa

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import com.headswap.desk.dex.VdmHostBootstrapPolicy
import com.headswap.desk.dex.VdmHostLink
import java.util.UUID

/** Same authenticated production bootstrap for each API37 runtime regression; no UI permission bypass. */
internal object VdmProbeHost {
    fun start(test: Instrumentation, ui: UiAutomation): AutoCloseable {
        if (Build.VERSION.SDK_INT < 37) return AutoCloseable { }
        check(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val apk = test.targetContext.applicationInfo.sourceDir
        check(VdmHostBootstrapPolicy.validApk(apk) && apk.none(Char::isWhitespace))
        val token = UUID.randomUUID().toString().replace("-", "")
        VdmHostLink.beginActivation(token)
        try {
            val request = checkNotNull(VdmHostLink.requestLaunch())
            // UiAutomation uses argv rather than a shell; env supplies CLASSPATH explicitly.
            val command = "/system/bin/env CLASSPATH=$apk /system/bin/app_process / " +
                "${VdmHostBootstrapPolicy.ENTRY} ${Process.myUid()} ${Process.myPid()} $token ${request.nonce}"
            val output = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
                .use { it.readBytes().toString(Charsets.UTF_8) }
            check(output.lineSequence().any { it.trim() == "HEADSWAP_VDM_READY" } && VdmHostLink.isReady) {
                "Production VDM bootstrap did not acknowledge readiness"
            }
            return AutoCloseable { VdmHostLink.endActivation() }
        } catch (error: Throwable) {
            VdmHostLink.endActivation()
            throw error
        }
    }
}
