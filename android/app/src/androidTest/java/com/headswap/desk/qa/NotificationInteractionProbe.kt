package com.headswap.desk.qa

import android.app.ActivityManager
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.headswap.desk.Ch
import com.headswap.desk.Msg
import com.headswap.desk.desktop.NotificationFullscreenActivity
import com.headswap.desk.desktop.DesktopHardwareUi
import com.headswap.desk.dex.*
import com.headswap.desk.notify.*

/** Uses a real native DriveDeck status tap, NLS notification, Activity PendingIntent and managed child. */
internal object NotificationInteractionProbe {
    fun runWithin(test: Instrumentation, ui: UiAutomation, parent: Int, remote: IDexUserService, result: Bundle, snapshot: (String) -> Unit) {
        val context = test.targetContext
        val prefs = DexPrefs(context)
        val before = prefs.huNotificationsEnabled
        val delivery = DesktopVideoDelivery()
        var shade: HuNotificationShade? = null
        val heartbeat = Handler(Looper.getMainLooper())
        val pulse = object : Runnable {
            override fun run() { delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_HEARTBEAT, 0, SystemClock.elapsedRealtime()); heartbeat.postDelayed(this, 300) }
        }
        val panelField = HuNotificationShade::class.java.getDeclaredField("panel").apply { isAccessible = true }
        val monitor = test.addMonitor(NotificationFullscreenActivity::class.java.name, null, false)
        val fixture = "com.headswap.desk.test/com.headswap.desk.qa.NotificationTargetFixture"
        try {
            shell(ui, "appops set com.headswap.desk SYSTEM_ALERT_WINDOW allow")
            shell(ui, "pm grant com.headswap.desk.test android.permission.POST_NOTIFICATIONS")
            shell(ui, "cmd notification allow_listener com.headswap.desk/com.headswap.desk.cluster.ClusterNavigationListener")
            delivery.start(SystemClock.elapsedRealtime())
            delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_DATA, 1, SystemClock.elapsedRealtime())
            DesktopConnectionState.attach(delivery)
            heartbeat.post(pulse)
            prefs.huNotificationsEnabled = true
            test.runOnMainSync { shade = HuNotificationShade(context).also { it.start() } }
            val info = ui.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            shell(ui, "am start -W --activity-single-top --display 0 -n $fixture --ez post true")
            @Suppress("DEPRECATION")
            check(context.getSystemService(ActivityManager::class.java).getRunningTasks(100).any {
                it.topActivity?.className == NotificationTargetFixture::class.java.name &&
                    FrameworkTaskIdentityReader.read(it)?.displayId == 0
            }) { "Existing notification Activity did not start on phone" }
            result.putBoolean("existingActivityOnPhoneBeforeClick", true)
            await(8_000) { HuNotifications.items.any { it.who.contains("HeadSwapNotificationTarget") } }
            val item = HuNotifications.items.first { it.who.contains("HeadSwapNotificationTarget") }
            var nativeBounds: Rect? = null
            await(8_000) {
                nativeBounds = ui.windowsOnAllDisplays[parent].orEmpty().asSequence().mapNotNull { findNative(it.root) }.firstOrNull()
                nativeBounds?.isEmpty == false
            }
            await(3_000) { var absent = false; test.runOnMainSync { absent = panelField.get(shade) == null }; absent }
            result.putBoolean("nativeEntryReplacesFloatingHandle", true)
            val bounds = checkNotNull(nativeBounds)
            result.putString("nativeEntryBounds", bounds.toShortString())
            tap(remote, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            var card: View? = null
            await(6_000) {
                test.runOnMainSync { card = findCard(panelField.get(shade) as? View, "HeadSwapNotificationTarget") }
                card?.let { it.isShown && it.isLaidOut && it.width > 0 && it.height > 0 } == true
            }
            result.putBoolean("nativeStatusTapOpenedShade", true)
            val point = IntArray(2)
            test.runOnMainSync {
                val target = checkNotNull(card)
                target.getLocationOnScreen(point)
                point[0] += target.width / 2; point[1] += target.height / 2
            }
            awaitInputPoint(ui, parent, "HeadSwapDesk notification shade", point)
            result.putBoolean("notificationCardInputReady", true)
            snapshot("shade")
            tap(remote, point[0].toFloat(), point[1].toFloat())
            val activity = checkNotNull(test.waitForMonitorWithTimeout(monitor, 6_000))
            var child = -1
            await(10_000) {
                @Suppress("DEPRECATION")
                val task = context.getSystemService(ActivityManager::class.java).getRunningTasks(100).firstOrNull {
                    it.topActivity?.className == NotificationTargetFixture::class.java.name &&
                        FrameworkTaskIdentityReader.read(it)?.displayId?.let { id -> id > 0 && id != parent } == true
                }
                child = task?.let { FrameworkTaskIdentityReader.read(it)?.displayId } ?: -1
                child > 0
            }
            result.putInt("notificationChildDisplay", child)
            result.putBoolean("activityPendingIntentOpenedManagedChild", true)
            snapshot("activity")
            result.putBoolean("notificationActivityPixelsRendered", true)
            shell(ui, "run-as com.headswap.desk.test rm -f files/notification-back.txt")
            var capturedBack: DesktopHardwareUi.BackTarget? = null
            test.runOnMainSync { capturedBack = DesktopHardwareUi.captureBack(parent) }
            val actualBack = checkNotNull(capturedBack) { "Fullscreen has no hardware Back owner" }
            remote.resetHardwareInput()
            check(!actualBack.modal && remote.routeHostBack(parent, actualBack.modal))
            test.runOnMainSync { check(actualBack.dispatch()) }
            await(6_000) { shell(ui, "run-as com.headswap.desk.test cat files/notification-back.txt").trim().startsWith("back display=$child via=") }
            result.putString("notificationHardwareBackRoute", shell(ui, "run-as com.headswap.desk.test cat files/notification-back.txt").trim())
            result.putBoolean("notificationHardwareBackReachesChildWithoutIme", true)
            test.runOnMainSync { capturedBack = DesktopHardwareUi.captureBack(parent) }
            val beforeTouch = checkNotNull(capturedBack)
            shell(ui, "run-as com.headswap.desk.test rm -f files/notification-touch.txt")
            tap(remote, 1000f, 300f)
            await(6_000) { shell(ui, "run-as com.headswap.desk.test cat files/notification-touch.txt").trim() == "touch display=$child" }
            test.runOnMainSync { check(!beforeTouch.dispatch()) { "Touch did not invalidate pending Back" } }
            result.putBoolean("notificationPendingBackInvalidatedByTouch", true)
            test.runOnMainSync { capturedBack = DesktopHardwareUi.captureBack(parent) }
            val beforeHome = checkNotNull(capturedBack)
            test.runOnMainSync {
                val home = checkNotNull(findDescription(activity.window.decorView, "Домой"))
                home.getLocationOnScreen(point); point[0] += home.width / 2; point[1] += home.height / 2
            }
            awaitInputPoint(ui, parent, "NotificationFullscreenActivity", point)
            tap(remote, point[0].toFloat(), point[1].toFloat())
            await(6_000) { activity.isDestroyed || activity.isFinishing }
            result.putBoolean("notificationHomeReturnsToDashboard", true)
            test.runOnMainSync { check(!beforeHome.dispatch()) { "Stale Back redirected after Home" } }
            result.putBoolean("notificationPendingBackInvalidatedAfterHome", true)
            await(6_000) {
                ui.windowsOnAllDisplays[parent].orEmpty().any { findNative(it.root)?.isEmpty == false }
            }
            snapshot("home")
            shell(ui, "am start -W --activity-single-top --display 0 -n $fixture --ez cancel true")
            await(5_000) { HuNotifications.items.none { it.key == item.key } }
            test.runOnMainSync { check(!HuNotificationLaunch.open(context, parent, item.key, item.pkg)) }
            result.putBoolean("removedNotificationCannotLaunch", true)
        } finally {
            heartbeat.removeCallbacks(pulse)
            test.runOnMainSync { shade?.close() }
            prefs.huNotificationsEnabled = before
            DesktopConnectionState.detach(delivery)
            test.removeMonitor(monitor)
        }
    }
    @Suppress("DEPRECATION") private fun findNative(node: AccessibilityNodeInfo?): Rect? {
        node ?: return null
        node.extras.getParcelable<Rect>("com.headswap.desk.notificationEntryBounds")?.let { return it }
        for (i in 0 until node.childCount) findNative(node.getChild(i))?.let { return it }
        return null
    }
    private fun findCard(view: View?, title: String): View? {
        if (view?.contentDescription?.toString()?.contains(title) == true && view.isClickable) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCard(view.getChildAt(i), title)?.let { return it }
        return null
    }
    private fun findDescription(view: View, title: String): View? {
        if (view.contentDescription == title) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findDescription(view.getChildAt(i), title)?.let { return it }
        return null
    }
    // View visibility precedes SurfaceFlinger/InputDispatcher commit. A single remote tap
    // must wait for the actual expanded window region, not leak into the previous tile.
    private fun awaitInputPoint(ui: UiAutomation, parent: Int, title: String, point: IntArray) {
        val frame = Regex("frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
        await(6_000) {
            shell(ui, "dumpsys input").lineSequence().any { line ->
                if (!line.contains("name=") || !line.contains(title) || !line.contains("displayId=$parent,") ||
                    !line.contains("inputConfig=") || line.contains("NOT_VISIBLE")) return@any false
                val match = frame.find(line) ?: return@any false
                val (left, top, right, bottom) = match.destructured
                point[0] >= left.toInt() && point[0] < right.toInt() &&
                    point[1] >= top.toInt() && point[1] < bottom.toInt()
            }
        }
    }
    private fun tap(remote: IDexUserService, x: Float, y: Float) {
        remote.injectTouchPoints(MotionEvent.ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
        SystemClock.sleep(40)
        remote.injectTouchPoints(MotionEvent.ACTION_UP, 0, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
    }
    private fun await(timeout: Long, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Notification UI condition timed out" }; SystemClock.sleep(100) }
    }
    private fun shell(ui: UiAutomation, command: String): String = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
