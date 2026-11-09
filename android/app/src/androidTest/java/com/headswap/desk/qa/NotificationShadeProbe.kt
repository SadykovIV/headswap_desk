package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.TextView
import com.headswap.desk.CarLifeState
import com.headswap.desk.Ch
import com.headswap.desk.Msg
import com.headswap.desk.dex.*
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.notify.HuNotificationShade
import com.headswap.desk.notify.HuNotifications
import com.headswap.desk.notify.HuNotificationFeature
import com.headswap.desk.notify.HuNotificationGateway
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Disposable-emulator UI check with a harmless notification posted by Android's shell. */
internal object NotificationShadeProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        val ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = test.targetContext
        val prefs = DexPrefs(context)
        val previous = prefs.huNotificationsEnabled
        var shade: HuNotificationShade? = null
        val owner = Any()
        val calls = Executors.newSingleThreadExecutor()
        val delivery = DesktopVideoDelivery()
        val images = HandlerThread("qa-shade-images").apply { start() }
        val latest = AtomicReference<Bitmap?>()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            shell(ui, "appops set com.headswap.desk SYSTEM_ALERT_WINDOW allow")
            shell(ui, "cmd notification allow_listener com.headswap.desk/com.headswap.desk.cluster.ClusterNavigationListener")
            ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY)
            val manager = context.getSystemService(DisplayManager::class.java)
            ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3).use { reader ->
                reader.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val picture = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        if (picture !== padded) padded.recycle()
                        latest.getAndSet(picture)?.recycle()
                    }
                }, Handler(images.looper))
                val display = checkNotNull(manager.createVirtualDisplay("HeadSwap notification shade probe",1280,720,128, reader.surface,
                    DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, Build.VERSION.SDK_INT)))
                try {
                    val id = display.display.displayId
                    shell(ui, "am start -W --display $id -f 0x10000000 -n com.headswap.desk.test/com.headswap.desk.qa.HardwareInputFixture")
                    SystemClock.sleep(400)
                    val binder = Binder()
                    val remote = Proxy.newProxyInstance(IDexUserService::class.java.classLoader, arrayOf(IDexUserService::class.java)) { _, method, _ ->
                        when (method.name) { "asBinder" -> binder; else -> null }
                    } as IDexUserService
                    EmbeddedSessionRegistry.activate(owner, remote, id, calls, DesktopModePolicy.ANDROID_DESKTOP, 128, false)
                    DexState.displayId = id; DexState.running = true; CarLifeState.running = true
                    prefs.huNotificationsEnabled = true
                    delivery.start(SystemClock.elapsedRealtime())
                    delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_DATA, 1, SystemClock.elapsedRealtime())
                    DesktopConnectionState.attach(delivery)
                    test.runOnMainSync { shade = HuNotificationShade(context).also { it.start() } }
                    shell(ui, "cmd notification post -t HeadSwapTestMessage headswap-shade-test NotificationShadeVerification")
                    if (!HuNotificationFeature.AVAILABLE) {
                        verifyDisabled(test, ui, context, checkNotNull(shade), id, owner, delivery, result)
                    } else {
                    result.putString("notificationShadeMode", "enabled-interaction")
                    val deadline = SystemClock.elapsedRealtime() + 6000
                    while (SystemClock.elapsedRealtime() < deadline && HuNotifications.items.none { it.who.contains("HeadSwapTestMessage") }) {
                        delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_HEARTBEAT, 0, SystemClock.elapsedRealtime())
                        SystemClock.sleep(200)
                    }
                    val panelField = HuNotificationShade::class.java.getDeclaredField("panel").apply { isAccessible = true }
                    var view: ViewGroup? = null
                    // An already delivered NLS item does not imply the newly attached overlay has
                    // reached SurfaceFlinger/InputDispatcher. Await its real visible input window;
                    // then inject exactly one tap, without retries that could hide a lost gesture.
                    val visibleDeadline = SystemClock.elapsedRealtime() + 6000
                    var inputState = ""
                    var inputVisible = false
                    while (SystemClock.elapsedRealtime() < visibleDeadline) {
                        delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_HEARTBEAT, 0, SystemClock.elapsedRealtime())
                        inputState = shell(ui, "dumpsys input")
                        inputVisible = inputState.lineSequence().any { line ->
                            line.contains("name=") && line.contains("HeadSwapDesk notification shade") &&
                                line.contains("displayId=$id,") && line.contains("inputConfig=") && !line.contains("NOT_VISIBLE")
                        }
                        if (inputVisible) break
                        SystemClock.sleep(100)
                    }
                    context.getExternalFilesDir(null)!!.resolve("shade-input.txt").writeText(inputState)
                    context.getExternalFilesDir(null)!!.resolve("shade-windows.txt").writeText(shell(ui,"dumpsys window windows"))
                    check(inputVisible) { "Shade view exists but its input surface is not visible" }
                    test.runOnMainSync {
                        view = panelField.get(shade) as? ViewGroup
                        check(view?.display?.displayId == id) { "Shade did not attach to owned HU display" }
                    }
                    result.putBoolean("inputWindowVisible", true)
                    result.putString("inputResult", shell(ui, "input -d $id tap 640 20"))
                    delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_HEARTBEAT, 0, SystemClock.elapsedRealtime())
                    SystemClock.sleep(400)
                    test.runOnMainSync {
                        view = panelField.get(shade) as? ViewGroup
                        check(view!!.display.displayId == id)
                        result.putInt("shadeDisplay", view!!.display.displayId)
                        result.putBoolean("expandedByTouch", view!!.childCount > 1)
                        result.putBoolean("listenerConnected", HuNotifications.connected)
                        result.putInt("notifications", HuNotifications.items.size)
                        result.putString("visibleText", texts(view!!).joinToString(" | "))
                    }
                    check(result.getBoolean("expandedByTouch")) { "Physical tap did not expand shade: ${result.getString("inputResult")}" }
                    check(HuNotifications.items.any { it.who.contains("HeadSwapTestMessage") }) { "Actual NLS notification did not arrive" }
                    latest.getAndSet(null)?.let { picture ->
                        val file = context.getExternalFilesDir(null)!!.resolve("notification-shade-probe.png")
                        file.outputStream().use { out -> picture.compress(Bitmap.CompressFormat.PNG,100,out) }
                        result.putString("screenshot",file.absolutePath)
                        picture.recycle()
                    }
                    EmbeddedSessionRegistry.deactivate(owner)
                    SystemClock.sleep(500)
                    test.runOnMainSync { check(panelField.get(shade) == null) { "Shade remained after ownership ended" } }
                    check(HuNotifications.items.isEmpty()) { "Notification model survived session end" }
                    result.putBoolean("removedAfterSession", true)
                    }
                } finally { display.release() }
            }
        } catch (error: Throwable) { result.putString("error",android.util.Log.getStackTraceString(error)) }
        finally {
            test.runOnMainSync { shade?.close() }
            DesktopConnectionState.detach(delivery)
            EmbeddedSessionRegistry.deactivate(owner)
            calls.shutdownNow()
            images.quitSafely()
            latest.getAndSet(null)?.recycle()
            DexState.displayId = -1; DexState.running = false; CarLifeState.running = false
            prefs.huNotificationsEnabled = previous
            ui.dropShellPermissionIdentity()
            shell(ui, "cmd notification disallow_listener com.headswap.desk/com.headswap.desk.cluster.ClusterNavigationListener")
            shell(ui, "appops set com.headswap.desk SYSTEM_ALERT_WINDOW default")
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK,result)
    }
    /** Explicit suspension test, not a successful run of the normal shade interaction scenario. */
    private fun verifyDisabled(test: Instrumentation, ui: UiAutomation, context: Context,
        shade: HuNotificationShade, displayId: Int, owner: Any, delivery: DesktopVideoDelivery, result: Bundle) {
        result.putString("notificationShadeMode", "disabled")
        result.putString("scope", "Saved opt-in, owned display, gateway/model/capability suspension; actual NLS delivery and phone RemoteInput availability. No HUD manoeuvre, music or TLS pairing test.")
        check(DexPrefs(context).huNotificationsEnabled && EmbeddedSessionRegistry.ownsParentDisplay(displayId))
        result.putBoolean("savedOptInAndOwnedDisplay", true)
        test.runOnMainSync {
            HuNotifications.session(context, true) // A stale caller cannot bypass the build gate either.
            shade.expand(displayId)
            check(!shade.available(displayId))
            val panelField = HuNotificationShade::class.java.getDeclaredField("panel").apply { isAccessible = true }
            check(panelField.get(shade) == null)
        }
        val status = HuNotificationGateway.status(displayId, Binder(), true)
        check(!status.getBoolean("enabled") && !status.getBoolean("connected") && status.getInt("count") == 0)
        check(!HuNotificationGateway.hasNativeEntry(displayId))
        check(!HuNotificationGateway.show(displayId))
        check(!HuNotificationGateway.open(context, displayId, "qa-disabled", "com.android.shell"))
        result.putBoolean("gatewayRejectsShadeAndCardActions", true)
        val capabilities = HuNotificationFeature.capabilities(127)
        check(capabilities == 63) { "Notification suspension changed other gateway capabilities" }
        result.putInt("sharedGatewayCapabilityMask", capabilities)
        val input = shell(ui, "dumpsys input")
        check(input.lineSequence().none { it.contains("HeadSwapDesk notification shade") && it.contains("displayId=$displayId,") })
        result.putBoolean("noShadeInputWindow", true)

        await {
            com.headswap.desk.cluster.ClusterNavigationListener.connected?.activeNotifications?.any {
                it.tag == "headswap-shade-test"
            } == true
        }
        check(HuNotifications.items.isEmpty()) { "Suspended shade collected notification content" }
        result.putBoolean("notificationModelEmptyAfterRealPost", true)
        result.putBoolean("hudListenerStillReceivesSystemNotifications", true)

        // These are two sequential scenarios. Activation correctly refuses to run during
        // a desktop session, so retire this probe's synthetic ownership before testing it.
        test.runOnMainSync { shade.close() }
        DesktopConnectionState.detach(delivery)
        EmbeddedSessionRegistry.deactivate(owner)
        DexState.displayId = -1; DexState.running = false; CarLifeState.running = false
        result.putBoolean("fixtureDesktopRetiredBeforePairing", true)

        // Exercise the actual independent phone service without submitting any pairing code.
        check(!PhonePairingService.active) { "A pre-existing pairing attempt must not be interrupted" }
        val notifications = context.getSystemService(NotificationManager::class.java)
        shell(ui, "pm grant com.headswap.desk android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 37) shell(ui, "pm grant com.headswap.desk android.permission.ACCESS_LOCAL_NETWORK")
        try {
            test.runOnMainSync { PhonePairingService.start(context, true) }
            await { notifications.activeNotifications.any { it.id == 72 && !it.notification.actions.isNullOrEmpty() } }
            val action = notifications.activeNotifications.single { it.id == 72 }.notification.actions.single()
            check(action.remoteInputs.single().allowFreeFormInput)
            check(PhonePairingService.active)
            result.putBoolean("phonePairingInlineActionAvailable", true)
        } finally {
            context.stopService(Intent(context, PhonePairingService::class.java))
            await { !PhonePairingService.active && notifications.activeNotifications.none { it.id == 71 || it.id == 72 } }
        }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        check(predicate()) { "Disabled-shade probe prerequisite did not become ready" }
    }
    private fun texts(view: android.view.View): List<String> = when(view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { String(it.readBytes()) }
}
