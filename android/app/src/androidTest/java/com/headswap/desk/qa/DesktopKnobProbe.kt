package com.headswap.desk.qa

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.*
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import com.headswap.desk.CarLifeState
import com.headswap.desk.BridgeService
import com.headswap.desk.Ch
import com.headswap.desk.Msg
import com.headswap.desk.component.DesktopComponents
import com.headswap.desk.desktop.*
import com.headswap.desk.dex.*
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real catalog/layout/scroll/action test; never edits or inspects a foreign application's UI. */
internal object DesktopKnobProbe {
    @JvmStatic @JvmOverloads fun run(test: Instrumentation, wirelessDashboard: Boolean = false, rawBack: Boolean = false) {
        val result = Bundle()
        var ui: UiAutomation? = null
        var phone: Activity? = null
        var phoneController: PhoneScreenIdleController? = null
        val prefs = DexPrefs(test.targetContext)
        val oldSetup = prefs.initialSetupCompleted
        val oldPanel = prefs.desktopPanelControl
        val oldMode = prefs.phoneScreenMode
        val oldAlive = BridgeService.alive
        val delivery = DesktopVideoDelivery()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish"))
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.INJECT_EVENTS, DelegatedDesktopAccess.REAL_GET_TASKS,
                DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL, ShellDisplayIme.PERMISSION, "android.permission.DUMP")
            val context = test.targetContext
            DesktopComponents.clear(context)
            if (wirelessDashboard) {
                shell(automation, "run-as com.headswap.desk.test rm -f files/panel-ready.txt files/panel-command.txt")
                val provider = checkNotNull(DesktopComponents.installed(context,
                    ComponentName("com.headswap.desk.test", DesktopPanelFixture::class.java.name)))
                check(DesktopComponents.select(context, provider))
                prefs.initialSetupCompleted = true; prefs.desktopPanelControl = true; prefs.phoneScreenMode = PhoneIdleMode.CURTAIN
                phone = test.startActivitySync(Intent(context, DexSetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                shell(automation, "am force-stop com.android.settings")
                delivery.start(SystemClock.elapsedRealtime())
                DesktopConnectionState.attach(delivery)
            }
            val lease = DelegatedDesktopAccess.begin {}
            val gateway = DelegatedInputGateway(context)
            val token = Binder(); gateway.beginGeneration(1, token, true)
            val owner = Any()
            val calls = Executors.newSingleThreadExecutor()
            val ready = CountDownLatch(1)
            var remote: IDexUserService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = IDexUserService.Stub.asInterface(binder); ready.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { remote = null }
            }
            var bound = false
            val monitor = test.addMonitor(AndroidDesktopActivity::class.java.name, null, false)
            try {
                val intent = Intent(context, DelegatedDesktopService::class.java)
                    .setAction(DelegatedDesktopService.BIND_ACTION_PREFIX + "knob-probe-" + SystemClock.uptimeMillis())
                intent.putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, gateway) })
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound && ready.await(8, TimeUnit.SECONDS))
                val service = checkNotNull(remote)
                ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3).use { pixels ->
                    val drain = HandlerThread("qa-knob-images").apply { start() }
                    val greenPixels = AtomicInteger()
                    val childPixels = AtomicInteger()
                    pixels.setOnImageAvailableListener({ it.acquireLatestImage()?.use { image ->
                        if (wirelessDashboard) delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_DATA, 1, SystemClock.elapsedRealtime())
                        else {
                            val plane = image.planes[0]
                            var count = 0
                            var fixtureCount = 0
                            for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
                                val index = y * plane.rowStride + x * plane.pixelStride
                                val red = plane.buffer[index].toInt() and 255
                                val green = plane.buffer[index + 1].toInt() and 255
                                val blue = plane.buffer[index + 2].toInt() and 255
                                if (red in 81..87 && green in 212..218 && blue in 144..150) count++
                                if (red in 4..10 && green in 88..94 && blue in 45..51) fixtureCount++
                            }
                            greenPixels.set(count)
                            childPixels.set(fixtureCount)
                        }
                    } }, Handler(drain.looper))
                    try {
                        val parent = service.startDesktopSized(pixels.surface, token, 640, 480, 128, DesktopModePolicy.ANDROID_DESKTOP)
                        EmbeddedSessionRegistry.activate(owner, service, parent, calls, DesktopModePolicy.ANDROID_DESKTOP, 128, false)
                        DexState.displayId = parent; DexState.running = true; CarLifeState.running = true
                        CarLifeState.wirelessSession = wirelessDashboard
                        val activity = checkNotNull(monitor.waitForActivityWithTimeout(10000))
                        if (wirelessDashboard) {
                            setBridgeAlive(true)
                            onMain(test) {
                                phoneController = PhoneScreenIdleController(checkNotNull(phone), isEnabled = { false }, isConnected = { true })
                                    .also { it.onStart() }
                            }
                            verifyWirelessDashboard(test, activity, service, parent, automation, delivery, result)
                        } else {
                        var grid: GridView? = null
                        await {
                            onMain(test) { grid = findGrid(activity.window.decorView) }
                            grid?.let { it.isShown && it.count >= 12 && it.childCount > 0 } == true
                        }
                        val catalog = checkNotNull(grid)
                        fun selected(): String? = field(activity, "knobFocus").let { it as DesktopKnobFocus }.selected
                        fun rotate(delta: Int) { onMain(test) { check(DesktopHardwareUi.rotate(parent, delta)) } }
                        fun key(code: Int) { onMain(test) { check(DesktopHardwareUi.key(parent, code)) } }
                        rotate(1)
                        val first = selected()
                        rotate(1)
                        check(first != selected()) { "One detent did not move to next app" }
                        val count = catalog.count
                        repeat(count - 2) { rotate(1) }
                        val last = selected()
                        check(last?.contains("KnobTarget11") == true) { "Catalog traversal skipped last entry: $last" }
                        await { catalog.lastVisiblePosition == count - 1 && catalog.firstVisiblePosition > 0 }
                        onMain(test) { check(catalog.getChildAt(count - 1 - catalog.firstVisiblePosition).foreground != null) }
                        await { greenPixels.get() > 5 }
                        result.putInt("renderedFocusPixels", greenPixels.get())
                        result.putBoolean("semanticNextAndScroll", true)
                        service.injectTouchPoints(0, 0, intArrayOf(0), floatArrayOf(250f), floatArrayOf(10f))
                        service.injectTouchPoints(1, 0, intArrayOf(0), floatArrayOf(250f), floatArrayOf(10f))
                        await { !(field(activity, "knobFocus") as DesktopKnobFocus).visible }
                        await { greenPixels.get() == 0 }
                        rotate(-1); check(selected() == last) { "First detent after touch must reveal, not move" }
                        rotate(-1); check(selected() != last); rotate(1); check(selected() == last)
                        result.putBoolean("touchHidesAndFirstDetentReveals", true)
                        shell(automation, "run-as com.headswap.desk.test rm -f files/knob-back.txt")
                        key(23)
                        var fixtureDisplay = -1
                        await {
                            @Suppress("DEPRECATION")
                            context.getSystemService(ActivityManager::class.java).getRunningTasks(100).any {
                                val identity = FrameworkTaskIdentityReader.read(it)
                                (identity?.displayId?.let { id -> id > 0 && id != parent } == true &&
                                    it.topActivity?.packageName == "com.headswap.desk.test").also { matched ->
                                        if (matched) fixtureDisplay = checkNotNull(identity).displayId
                                    }
                            }
                        }
                        await { !catalog.isShown }
                        onMain(test) { check(!DesktopHardwareUi.rotate(parent, 1)) { "Foreign window must retain DPAD fallback" } }
                        result.putBoolean("okOpensSelectedOwnChild", true)
                        await { childPixels.get() > 1000 }
                        var backTarget: DesktopHardwareUi.BackTarget? = null
                        onMain(test) { backTarget = DesktopHardwareUi.captureBack(parent) }
                        val actualBack = checkNotNull(backTarget)
                        check(!actualBack.modal)
                        service.resetHardwareInput()
                        if (rawBack) {
                            // Diagnostic comparison only: one old parent-injected Back, then report
                            // both callback and task state. A missing marker is not labelled a product failure.
                            fun childAlive(): Boolean {
                                @Suppress("DEPRECATION")
                                return context.getSystemService(ActivityManager::class.java).getRunningTasks(100).any {
                                    FrameworkTaskIdentityReader.read(it)?.displayId == fixtureDisplay &&
                                        (it.topActivity?.packageName == "com.headswap.desk.test" ||
                                            it.baseActivity?.packageName == "com.headswap.desk.test")
                                }
                            }
                            service.injectKey(0, KeyEvent.KEYCODE_BACK); service.injectKey(1, KeyEvent.KEYCODE_BACK)
                            val until = SystemClock.elapsedRealtime() + 2500
                            var marker: String
                            do {
                                marker = shell(automation, "run-as com.headswap.desk.test cat files/knob-back.txt")
                                if (marker.startsWith("back display=") || !childAlive()) break
                                SystemClock.sleep(40)
                            } while (SystemClock.elapsedRealtime() < until)
                            result.putBoolean("rawBackCallbackObserved", marker.startsWith("back display="))
                            result.putBoolean("rawBackChildTaskAlive", childAlive())
                            result.putString("rawBackMarker", marker.take(240))
                            result.putString("rawBackScope", "One parent-injected Back; 2500ms observation; diagnostic only")
                        } else {
                            check(service.routeHostBack(parent, actualBack.modal))
                            onMain(test) { check(actualBack.dispatch()) }
                            await { shell(automation, "run-as com.headswap.desk.test cat files/knob-back.txt").startsWith("back display=") }
                            result.putBoolean("singleBackReachesDisplayedChildWithoutIme", true)
                        }
                        service.showDesktop()
                        await { catalog.isShown }
                        if (!rawBack) {
                            onMain(test) { check(!actualBack.dispatch()) { "Old Back was redirected after Home" } }
                            result.putBoolean("staleHostBackRejectedAfterHome", true)
                        }
                        if (Build.VERSION.SDK_INT == 33) {
                            await { activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0 }
                            result.putBoolean("catalogHomeReclaimsParentFocus", true)
                        }
                        rotate(1)
                        // Traverse from the retained last app to every rail/control, including Home.
                        repeat(64) {
                            if (selected() != "home") rotate(1)
                        }
                        check(selected() == "home")
                        key(23)
                        check(catalog.isShown)
                        result.putBoolean("sidebarHomeReachable", true)
                        }
                    } finally {
                        EmbeddedSessionRegistry.deactivate(owner)
                        service.stop(); EmbeddedSessionRegistry.producerStopped(owner)
                        pixels.setOnImageAvailableListener(null, null)
                        drain.quitSafely(); drain.join(2000)
                        check(!drain.isAlive) { "Image reader callback did not finish before close" }
                    }
                }
            } finally {
                test.removeMonitor(monitor)
                if (bound) context.unbindService(connection)
                calls.shutdownNow(); gateway.deactivate(); DelegatedDesktopAccess.end(lease)
                DexState.displayId = -1; DexState.running = false; CarLifeState.running = false
                CarLifeState.wirelessSession = false
            }
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        finally {
            onMain(test) { phoneController?.onStop(); phone?.finish() }
            DesktopConnectionState.detach(delivery); delivery.close(); setBridgeAlive(oldAlive)
            if (wirelessDashboard) {
                prefs.initialSetupCompleted = oldSetup; prefs.desktopPanelControl = oldPanel; prefs.phoneScreenMode = oldMode
                DesktopComponents.clear(test.targetContext)
            }
            ui?.dropShellPermissionIdentity()
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private fun verifyWirelessDashboard(test: Instrumentation, activity: Activity, service: IDexUserService,
        parent: Int, ui: UiAutomation, delivery: DesktopVideoDelivery, result: Bundle) {
        val context = test.targetContext
        await { field(activity, "dashboard") == true && field(activity, "panelPlaced") == true && field(activity, "profileExposed") == true }
        await { shell(ui, "run-as com.headswap.desk.test cat files/panel-ready.txt").startsWith("ready display=") }
        val settings = checkNotNull(context.packageManager.getLaunchIntentForPackage("com.android.settings")?.component)
        fun command(name: String) {
            context.sendBroadcast(Intent("com.headswap.desk.test.PANEL_COMMAND").setPackage("com.headswap.desk.test")
                .putExtra("command", name).putExtra("package", settings.packageName).putExtra("activity", settings.className))
            await { shell(ui, "run-as com.headswap.desk.test cat files/panel-command.txt") == "$name accepted=true" }
        }
        command("layout")
        await { (fieldOrNull(activity, "dockHeightPx") as? Int)?.let { it > 0 } == true }
        delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_HEARTBEAT, 0, SystemClock.elapsedRealtime())
        command("phone")
        await { fieldOrNull(activity, "phoneConfirmation")?.let { it as AlertDialog }?.isShowing == true }
        val dialog = field(activity, "phoneConfirmation") as AlertDialog
        var staleDialogBack: DesktopHardwareUi.BackTarget? = null
        onMain(test) {
            staleDialogBack = DesktopHardwareUi.captureBack(parent)
            check(DesktopHardwareUi.hasModal(parent))
            check(!DesktopHardwareUi.hasModal(parent + 1000))
            if (Build.VERSION.SDK_INT == 33) check(activity.window.attributes.flags and
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) { "Modal did not acquire parent focus" }
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            check(yes.requestFocusFromTouch())
            check(DesktopHardwareUi.rotate(parent, -1))
            check(cancel.hasFocus()) { "Reverse rotary did not reach the dialog Cancel button" }
            check(DesktopHardwareUi.key(parent, KeyEvent.KEYCODE_DPAD_RIGHT))
            check(yes.hasFocus()) { "DPAD did not move within the owned dialog" }
            check(DesktopHardwareUi.rotate(parent, -1))
            check(cancel.hasFocus())
            check(DesktopHardwareUi.key(parent, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        await { !dialog.isShowing }
        if (Build.VERSION.SDK_INT == 33) {
            await { activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 }
            result.putBoolean("modalFocusRestoredToChild", true)
        }
        check(PhoneScreenIdleController.phoneCurtainVisible() == false) { "Cancel changed the phone panel" }
        result.putBoolean("modalRotaryDpadOkCancel", true)
        // A fresh external Binder request opens another real modal. Back is routed by the
        // service into the parent, not locally consumed/reset and not sent to the provider child.
        shell(ui, "run-as com.headswap.desk.test rm -f files/panel-command.txt")
        delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_HEARTBEAT, 0, SystemClock.elapsedRealtime())
        command("phone")
        await { fieldOrNull(activity, "phoneConfirmation")?.let { it as AlertDialog }?.isShowing == true }
        val backDialog = field(activity, "phoneConfirmation") as AlertDialog
        // Global focus is not per-display readiness. Observe this exact owned View's draw,
        // then leave traversal before sending one Back; no input-channel title assumptions.
        val drawn = CountDownLatch(1)
        onMain(test) {
            val decor = checkNotNull(backDialog.window).decorView
            check(decor.display.displayId == parent)
            lateinit var listener: android.view.ViewTreeObserver.OnDrawListener
            listener = android.view.ViewTreeObserver.OnDrawListener {
                decor.post {
                    if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener)
                    drawn.countDown()
                }
            }
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.invalidate()
        }
        check(drawn.await(6, TimeUnit.SECONDS)) { "Owned dialog did not draw" }
        context.getExternalFilesDir(null)!!.resolve("modal-input.txt").writeText(shell(ui, "dumpsys input"))
        onMain(test) {
            val decor = checkNotNull(backDialog.window).decorView
            check(DesktopHardwareUi.hasModal(parent))
            check(decor.isShown && decor.isLaidOut && decor.width > 0 && decor.height > 0)
            result.putBoolean("modalOwnViewDrawn", true)
            result.putBoolean("modalHasGlobalWindowFocus", decor.hasWindowFocus())
        }
        service.resetHardwareInput() // Mirrors the preceding locally consumed rotary/OK command.
        check(!service.routeHostBack(parent + 1000, true))
        check(backDialog.isShowing) { "Stale parent unexpectedly received Back" }
        var modalBack: DesktopHardwareUi.BackTarget? = null
        onMain(test) {
            check(!checkNotNull(staleDialogBack).dispatch()) { "Old modal callback reached a replacement dialog" }
            modalBack = DesktopHardwareUi.captureBack(parent)
        }
        val actualModalBack = checkNotNull(modalBack)
        check(actualModalBack.modal && service.routeHostBack(parent, actualModalBack.modal))
        onMain(test) { check(actualModalBack.dispatch()) }
        await { !backDialog.isShowing }
        result.putBoolean("staleModalBackRejected", true)
        if (Build.VERSION.SDK_INT == 33) await { activity.window.attributes.flags and
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 }
        onMain(test) { check(!DesktopHardwareUi.hasModal(parent)) }
        result.putBoolean("modalBackClosesOwnedDialog", true)
        result.putBoolean("wirelessRealProviderPanelConfirmation", true)
        command("launch")
        await {
            @Suppress("DEPRECATION")
            context.getSystemService(ActivityManager::class.java).getRunningTasks(100).any {
                FrameworkTaskIdentityReader.read(it)?.displayId?.let { id -> id > 0 && id != parent } == true &&
                    it.topActivity?.packageName == settings.packageName
            } && field(activity, "targetVisible") == true && !(field(activity, "menu") as View).isShown
        }
        result.putBoolean("wirelessRealProviderLaunchesSettingsChild", true)
        service.showDesktop()
        await { field(activity, "targetVisible") == false && field(activity, "profileExposed") == true }
        result.putString("scope", "Real external UID/Binder, dashboard task and confirmation; transport readiness simulated, no RF or power operation")
    }

    private fun setBridgeAlive(value: Boolean) {
        BridgeService::class.java.getDeclaredField("alive").apply { isAccessible = true }.setBoolean(null, value)
    }
    private fun onMain(test: Instrumentation, action: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { failure = runCatching(action).exceptionOrNull() }
        failure?.let { throw it }
    }
    private fun field(owner: Any, name: String): Any = checkNotNull(fieldOrNull(owner, name))
    private fun fieldOrNull(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { String(it.readBytes()).trim() }
    private fun findGrid(view: View): GridView? {
        if (view is GridView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findGrid(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out waiting for catalog input" }; SystemClock.sleep(40) }
    }
}
