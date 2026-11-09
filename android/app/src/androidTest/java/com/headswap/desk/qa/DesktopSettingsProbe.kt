package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.IBinder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.headswap.desk.R
import com.headswap.desk.aa.AaPrefs
import com.headswap.desk.dex.*
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.shared.DesktopRenderPolicy
import com.headswap.desk.update.ComponentUpdatesActivity
import java.util.concurrent.Executors

/** Read the actual view tree on a disposable emulator. No updater or system installer is invoked. */
internal object DesktopSettingsProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Emulator only" }
            val context = test.targetContext
            onUi(test) {
                for ((width, scale) in listOf(320 to 1.3f, 360 to 1.8f)) checkLayout(context, width, scale)
            }
            result.putString("layouts", "320dp/1.3 and 360dp/1.8")
            checkMapBuffers()
            result.putString("appBuffers", "screen 160 DPI; selected app buffer 320 DPI at 2x for create and resize")
            checkSettings(test)
            result.putString("settings", "phone controls, persisted choices, HUD switch and both component cards")
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(error))
            test.finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkMapBuffers() {
        val uid = android.os.Process.myUid()
        val worker = Executors.newSingleThreadExecutor()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val owner = Any()
        val sampled = mutableListOf<List<Int>>()
        val producer = object : IDexUserService.Default() {
            private val binder = Binder()
            override fun asBinder(): IBinder = binder
            override fun createChildDisplay(s: Surface, token: IBinder, w: Int, h: Int, dpi: Int): Int {
                sampled.add(listOf(w, h, dpi)); return 43
            }
            override fun createFullscreenChildDisplay(s: Surface, token: IBinder, w: Int, h: Int, dpi: Int): Int =
                createChildDisplay(s, token, w, h, dpi)
            override fun resizeChildDisplay(token: IBinder, w: Int, h: Int, dpi: Int) { sampled.add(listOf(w, h, dpi)) }
        }
        try {
            EmbeddedSessionRegistry.activate(owner, producer, 42, worker, DesktopModePolicy.DRIVEDECK,
                160, true, setOf("example.reader"))
            val policy = EmbeddedSessionRegistry.renderConfig(42, false, "example.reader")
            for (fullscreen in listOf(false, true)) {
                sampled.clear()
                fun buffer(w: Int, h: Int) = DesktopRenderPolicy.geometry(w, h, policy[0], policy[1] == 1)
                val start = buffer(576, 684)
                val handle = EmbeddedSessionRegistry.create(uid, surface, Binder(), 42, start[0], start[1], start[2],
                    fullscreen = fullscreen)
                try {
                    val resized = buffer(640, 480)
                    EmbeddedSessionRegistry.resize(uid, handle, resized[0], resized[1], resized[2])
                    check(sampled == listOf(listOf(1152, 1368, 320), listOf(1280, 960, 320)))
                } finally { EmbeddedSessionRegistry.release(uid, handle) }
            }
        } finally {
            EmbeddedSessionRegistry.deactivate(owner)
            texture.release()
            surface.release()
            worker.shutdownNow()
        }
    }

    private fun checkLayout(context: Context, widthDp: Int, fontScale: Float) {
        val config = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
        val ui = DesktopSetupViews(ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Headswap_Dex))
        val root = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val card = ui.section(root)
        val longLabel = "Подсказки навигации на приборной панели и HUD"
        val toggle = ui.toggle(card, longLabel, false) {}
        val first = ui.button(card, "Способ отключения: Затемнять экран ▾") {}
        val next = ui.button(card, "Настройка уведомлений приложений") {}
        root.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val label = toggle.row.getChildAt(0) as TextView
        check(label.lineCount >= 2 && label.right <= toggle.control.left)
        check(toggle.control.width >= ui.dp(48) && toggle.control.height >= ui.dp(48))
        check(toggle.control.right <= toggle.row.width && toggle.control.contentDescription == longLabel)
        check(first.top > toggle.row.bottom && next.top > first.bottom)
        for (text in listOf(label, first, next)) {
            val layout = checkNotNull(text.layout)
            check(layout.getLineEnd(layout.lineCount - 1) == text.text.length)
            check(layout.getEllipsisCount(layout.lineCount - 1) == 0)
        }
        toggle.row.performClick()
        check(toggle.control.isChecked)
    }

    private fun checkSettings(test: Instrumentation) {
        val context = test.targetContext
        val prefs = DexPrefs(context)
        val originalMode = prefs.desktopMode
        prefs.desktopMode = DesktopModePolicy.ANDROID_DESKTOP
        val originalAccess = prefs.accessMode
        val originalScreen = prefs.desktopPanelControl
        val originalTimer = prefs.phoneScreenMode
        val originalTimerEnabled = prefs.phoneTimerEnabled
        val originalTimeout = prefs.phoneIdleTimeout
        val originalNav = AaPrefs(context).metaNavi
        val originalSetupCompleted = prefs.initialSetupCompleted
        val originalCompact = prefs.compactMapSymbols
        val originalPackages = prefs.dpiCorrectionPackages
        var activity: Activity? = null
        var components: Activity? = null
        val monitor = test.addMonitor(ComponentUpdatesActivity::class.java.name, null, false)
        try {
            prefs.initialSetupCompleted = true
            prefs.useDelegated = true
            prefs.desktopPanelControl = true
            prefs.phoneTimerEnabled = true
            prefs.phoneScreenMode = PhoneIdleMode.CURTAIN
            prefs.phoneIdleTimeout = PhoneIdleTimeout.SYSTEM
            AaPrefs(context).metaNavi = true
            prefs.compactMapSymbols = true
            prefs.dpiCorrectionPackages = com.headswap.desk.shared.DesktopRenderPolicy.DEFAULT_PACKAGES
            activity = test.startActivitySync(Intent(Intent.ACTION_MAIN).setClass(context, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val root = activity.window.decorView
            onUi(test) { root.button("Настройки").performClick() }
            checkDpiMenu(test, root, prefs)
            onUi(test) {
                val screen = root.toggle("Управление экраном телефона")
                val timer = root.nodes().filterIsInstance<Button>().first { it.text.startsWith("Способ отключения:") }
                val timeout = root.nodes().filterIsInstance<Button>().first { it.text.startsWith("Гасить через:") }
                check(screen.isEnabled && screen.isChecked && timer.isShown && timeout.isShown)
                screen.performClick()
                check(!prefs.desktopPanelControl && !timer.isShown && !timeout.isShown)
                screen.performClick()
                check(timer.isShown && timeout.isShown && prefs.phoneIdleMode == PhoneIdleMode.CURTAIN &&
                    prefs.phoneIdleTimeout == PhoneIdleTimeout.SYSTEM)
                for (mode in listOf(PhoneIdleMode.PANEL, PhoneIdleMode.CURTAIN)) {
                    timer.performClick()
                    val options = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .flatMap { it.nodes().filterIsInstance<android.widget.ListView>().toList() }
                        .last { it.isShown }
                    val labels = (0 until options.count).map { options.getItemAtPosition(it).toString() }
                    check(labels == listOf("Выключать экран", "Затемнять экран"))
                    val index = labels.indexOf(mode.label)
                    options.performItemClick(null, index, options.getItemIdAtPosition(index))
                    check(prefs.phoneScreenMode == mode && prefs.phoneTimerEnabled)
                }
                root.toggle("Подсказки навигации на приборной панели и HUD").performClick()
                check(!AaPrefs(context).metaNavi)
                // Scope blocking monitors to the two Settings launches. A null-action
                // explicit Activity intent can otherwise also match an IntentFilter.
                val appNotifications = test.addMonitor(IntentFilter(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS), null, true)
                val listenerAccess = test.addMonitor(IntentFilter(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS), null, true)
                try {
                    root.button("Настройка уведомлений приложений").performClick()
                    root.button("Доступ HeadSwap к уведомлениям").performClick()
                    check(appNotifications.hits == 1 && listenerAccess.hits == 1)
                } finally {
                    test.removeMonitor(appNotifications)
                    test.removeMonitor(listenerAccess)
                }
                root.button("Компоненты HeadSwap Desktop").performClick()
            }
            components = checkNotNull(test.waitForMonitorWithTimeout(monitor, 5_000))
            val detail = components.window.decorView
            onUi(test) {
                check(detail.button("Проверить обновления HeadSwap Desktop").isShown)
                check(detail.button("Проверить версии компонента").isShown && detail.button("Установить из файла…").isShown)
                check(detail.nodes().filterIsInstance<TextView>().any { it.text == "DriveDeck · HeadSwap" })
            }
        } finally {
            test.removeMonitor(monitor)
            onUi(test) { components?.finish(); activity?.finish() }
            prefs.desktopPanelControl = originalScreen
            prefs.phoneScreenMode = originalTimer
            prefs.phoneTimerEnabled = originalTimerEnabled
            prefs.phoneIdleTimeout = originalTimeout
            prefs.accessMode = originalAccess
            prefs.desktopMode = originalMode
            AaPrefs(context).metaNavi = originalNav
            prefs.initialSetupCompleted = originalSetupCompleted
            prefs.compactMapSymbols = originalCompact
            prefs.dpiCorrectionPackages = originalPackages
        }
    }

    private fun checkDpiMenu(test: Instrumentation, root: View, prefs: DexPrefs) {
        val monitor = test.addMonitor(DpiCorrectionActivity::class.java.name, null, false)
        val page = try {
            onUi(test) { root.button("Исправить DPI приложения").performClick() }
            checkNotNull(test.waitForMonitorWithTimeout(monitor, 4000))
        } finally { test.removeMonitor(monitor) }
        try {
            val view = page.window.decorView
            test.waitForIdleSync()
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                onUi(test) { ready = view.button("Добавить приложение").isEnabled }
                if (!ready) android.os.SystemClock.sleep(40)
            }
            check(ready)
            onUi(test) {
                val toggle = view.toggle("Включить исправление DPI")
                toggle.performClick()
                check(!prefs.compactMapSymbols)
                toggle.performClick()
                check(prefs.compactMapSymbols)
                val nav = view.nodes().filterIsInstance<TextView>().first { it.text == "ru.yandex.yandexnavi" }
                ((nav.parent.parent as View).nodes().filterIsInstance<android.widget.ImageButton>().first()).performClick()
                check(prefs.dpiCorrectionPackages == setOf("ru.yandex.yandexmaps"))
                view.button("Добавить приложение").performClick()
                val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().last {
                    it.nodes().any { node -> node is android.widget.EditText && node.hint == "Поиск по названию или пакету" }
                }
                dialog.nodes().filterIsInstance<android.widget.EditText>().first().setText("com.android.settings")
                val list = dialog.nodes().filterIsInstance<android.widget.ListView>().first()
                val index = (0 until list.count).first { list.getItemAtPosition(it).toString().endsWith("\ncom.android.settings") }
                list.performItemClick(null, index, list.getItemIdAtPosition(index))
                check(DexPrefs(page).dpiCorrectionPackages == setOf("ru.yandex.yandexmaps", "com.android.settings"))
            }
        } finally { onUi(test) { page.finish() }; test.waitForIdleSync() }
    }

    private fun View.nodes(): Sequence<View> = sequence {
        yield(this@nodes)
        if (this@nodes is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).nodes())
    }
    private fun View.button(text: String) = nodes().filterIsInstance<Button>().first { it.text == text }
    private fun View.toggle(label: String) = nodes().filterIsInstance<Switch>().first { it.contentDescription == label }
    private fun onUi(test: Instrumentation, run: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { failure = runCatching(run).exceptionOrNull() }
        failure?.let { throw it }
    }
}
