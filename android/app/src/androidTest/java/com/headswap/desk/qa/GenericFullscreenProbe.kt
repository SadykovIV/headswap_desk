package com.headswap.desk.qa

import android.app.Activity
import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.*
import com.headswap.desk.dex.*
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.embed.FullscreenSlotBusyException
import java.util.concurrent.Executors

/** Real caller UID denial plus owned fullscreen lease/pixels on the Android Desktop profile. */
internal object GenericFullscreenProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result=Bundle()
        var ui: UiAutomation?=null
        var scene: VdmFunctionalProbe.Scene?=null
        val owner=Any()
        val calls=Executors.newSingleThreadExecutor()
        try {
            check(Build.HARDWARE in listOf("ranchu","goldfish") && Build.VERSION.SDK_INT>=33)
            val automation=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui=automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY,DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS,DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL,
                ShellDisplayIme.PERMISSION,"android.permission.DUMP","android.permission.WAKE_LOCK")
            val active=VdmFunctionalProbe.Scene(test,automation).also { scene=it }
            active.start()
            EmbeddedSessionRegistry.activate(owner,active.remote,active.parent,calls,DesktopModePolicy.ANDROID_DESKTOP,
                128,false,nativeLifetime=active.gateway.nativeProducerLifetime ?: active.remote.asBinder())
            val (tileToken,tile)=active.child(640,360)
            val foreignUid=active.context.packageManager.getApplicationInfo(active.fixturePackage,0).uid
            check(foreignUid!=Process.myUid())
            shell(automation,"run-as ${active.fixturePackage} rm -f files/fullscreen-caller.txt")
            active.context.startActivity(Intent().setComponent(ComponentName(active.fixturePackage,FullscreenCallerFixture::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("qa-private-service",Bundle().apply { putBinder("service",active.remote.asBinder()) }),
                ActivityOptions.makeBasic().setLaunchDisplayId(tile).toBundle())
            active.remote.trackDesktopChildLaunch(tileToken,active.fixturePackage)
            await { shell(automation,"run-as ${active.fixturePackage} cat files/fullscreen-caller.txt").trim()=="DENIED uid=$foreignUid" }
            result.putBoolean("actualForeignUidDeniedBeforeAllocation",true)
            val output=VdmFunctionalProbe.Frames(640,360).also(active.frames::add)
            val token=Binder()
            val handle=EmbeddedSessionRegistry.create(Process.myUid(),output.reader.surface,token,active.parent,
                640,360,128,ownDesktop=true,fullscreen=true)
            try {
                EmbeddedSessionRegistry.launch(Process.myUid(),handle,active.fixturePackage,VirtualDeviceLockFixture::class.java.name)
                await { output.color(100,250)==Color.CYAN }
                result.putBoolean("ownUidAndroidDesktopFullscreenRendered",true)
                val denied=runCatching {
                    EmbeddedSessionRegistry.create(Process.myUid(),output.reader.surface,Binder(),active.parent,
                        640,360,128,ownDesktop=true,fullscreen=true)
                }.exceptionOrNull()
                check(denied is FullscreenSlotBusyException) { "Second fullscreen slot was accepted" }
                result.putBoolean("oneFullscreenSlotPreserved",true)
            } finally { EmbeddedSessionRegistry.release(Process.myUid(),handle) }
            check(active.displays.getDisplay(tile)?.isValid==true)
            check(active.remote.childTaskState(tileToken,active.fixturePackage)>0)
            result.putBoolean("tileSurvivesFullscreenRelease",true)
        } catch(error: Throwable) { result.putString("error",android.util.Log.getStackTraceString(error)) }
        finally {
            EmbeddedSessionRegistry.deactivate(owner)
            runCatching { scene?.close() }.onFailure { result.putString("cleanupError",it.toString()) }
            EmbeddedSessionRegistry.producerStopped(owner)
            calls.shutdownNow()
            ui?.dropShellPermissionIdentity()
        }
        test.finish(if(result.containsKey("error")||result.containsKey("cleanupError")) Activity.RESULT_CANCELED else Activity.RESULT_OK,result)
    }
    private fun shell(ui: UiAutomation,command:String):String=
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun await(condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+12_000
        while(!condition()) { check(SystemClock.elapsedRealtime()<end) { "Generic fullscreen condition timed out" };SystemClock.sleep(80) }
    }
}
