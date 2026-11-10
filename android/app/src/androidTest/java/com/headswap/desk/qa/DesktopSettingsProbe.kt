package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
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
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.headswap.desk.R
import com.headswap.desk.aa.AaPrefs
import com.headswap.desk.dex.*
import com.headswap.desk.embed.EmbeddedSessionRegistry
import com.headswap.desk.shared.DesktopRenderPolicy
import com.headswap.desk.update.ComponentUpdatesActivity
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            result.putString("settings", "flat settings, activation and permission subpages/Back/recreation, persisted phone/audio/HUD/DPI choices, disabled shade and both component cards")
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
        val navigation = ui.navigation(card, "Приборная панель и HUD", "Повороты, расстояние и данные воспроизводимой музыки") {}
        root.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val label = toggle.row.getChildAt(0) as TextView
        check(label.lineCount >= 2 && label.right <= toggle.control.left)
        check(toggle.control.width >= ui.dp(48) && toggle.control.height >= ui.dp(48))
        check(toggle.control.right <= toggle.row.width && toggle.control.contentDescription == longLabel)
        check(first.top > toggle.row.bottom && next.top > first.bottom)
        check(navigation.view.top > next.bottom && navigation.view.height >= ui.dp(64))
        check(navigation.view.isClickable && navigation.view.isFocusable)
        for (text in listOf(label, first, next) + navigation.view.nodes().filterIsInstance<TextView>().toList()) {
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
        val originalAccess = prefs.accessMode
        val originalScreen = prefs.desktopPanelControl
        val originalTimer = prefs.phoneScreenMode
        val originalTimerEnabled = prefs.phoneTimerEnabled
        val originalTimeout = prefs.phoneIdleTimeout
        val originalNav = AaPrefs(context).metaNavi
        val originalSetupCompleted = prefs.initialSetupCompleted
        val originalSetupProgress = prefs.initialSetupInProgress
        val originalSetupStep = prefs.initialSetupStep
        val originalSetupReviewed = prefs.initialSetupReviewed
        val originalSetupAuto = prefs.initialSetupAutoPending
        val originalCompact = prefs.compactMapSymbols
        val originalPackages = prefs.dpiCorrectionPackages
        val originalAutoplay = prefs.autoMusicPlayback
        val originalAudio = prefs.usbPlaybackAudio
        val originalIndependentRecovery = prefs.independentRecoveryWithoutWifi
        var activity: Activity? = null
        var components: Activity? = null
        val monitor = test.addMonitor(ComponentUpdatesActivity::class.java.name, null, false)
        try {
            prefs.desktopMode = DesktopModePolicy.ANDROID_DESKTOP
            prefs.initialSetupCompleted = true
            prefs.activationMethod = ActivationMethod.WIFI
            prefs.independentRecoveryWithoutWifi = false
            prefs.desktopPanelControl = true
            prefs.phoneTimerEnabled = true
            prefs.phoneScreenMode = PhoneIdleMode.CURTAIN
            prefs.phoneIdleTimeout = PhoneIdleTimeout.SYSTEM
            AaPrefs(context).metaNavi = true
            prefs.compactMapSymbols = true
            prefs.dpiCorrectionPackages = com.headswap.desk.shared.DesktopRenderPolicy.DEFAULT_PACKAGES
            activity = test.startActivitySync(Intent(Intent.ACTION_MAIN).setClass(context, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var root = activity.window.decorView
            onUi(test) { root.button("Настройки").performClick() }
            test.waitForIdleSync()
            onUi(test) {
                checkFlatSettings(root)
                val shade = root.toggle("Показывать шторку уведомлений")
                check(!shade.isChecked && !shade.isEnabled)
                val shadeRow = shade.parent as View
                check(!shadeRow.isEnabled)
                // Disabled controls cannot change state through normal user activation.
                check(!shadeRow.performClick() || !shade.isChecked)
                check(!shade.isChecked && !shade.isEnabled)
            }
            checkDpiMenu(test, root, prefs)
            onUi(test) {
                for ((label, read) in listOf<Pair<String, () -> Boolean>>(
                    "Автоматически продолжать музыку при подключении" to { prefs.autoMusicPlayback },
                    "Передавать звук в CarLife" to { prefs.usbPlaybackAudio })) {
                    val toggle = root.toggle(label)
                    check(toggle.isEnabled)
                    val before = read()
                    toggle.performClick(); check(read() != before)
                    toggle.performClick(); check(read() == before)
                }
                val screen = root.toggle("Управление дисплеем телефона")
                val timer = root.buttonStarting("Способ отключения:")
                val timeout = root.buttonStarting("Гасить через:")
                check(screen.isEnabled && screen.isChecked && timer.isEnabled && timeout.isEnabled)
                screen.performClick()
                check(!prefs.desktopPanelControl && timer.isShown && timeout.isShown && !timer.isEnabled && !timeout.isEnabled)
                screen.performClick()
                check(timer.isEnabled && timeout.isEnabled && prefs.phoneIdleMode == PhoneIdleMode.CURTAIN && prefs.phoneIdleTimeout == PhoneIdleTimeout.SYSTEM)
                for (mode in listOf(PhoneIdleMode.PANEL, PhoneIdleMode.CURTAIN)) {
                    timer.performClick()
                    val options = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .flatMap { it.nodes().filterIsInstance<android.widget.ListView>().toList() }.last { it.isShown }
                    val labels = (0 until options.count).map { options.getItemAtPosition(it).toString() }
                    check(labels == listOf("Выключать экран", "Затемнять экран"))
                    val index = labels.indexOf(mode.label)
                    options.performItemClick(null, index, options.getItemIdAtPosition(index))
                    check(prefs.phoneScreenMode == mode && prefs.phoneTimerEnabled)
                }
                root.toggle("Подсказки навигации на приборной панели и HUD").performClick()
                check(!AaPrefs(context).metaNavi)
                val appNotifications = test.addMonitor(IntentFilter(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS), null, true)
                try {
                    root.button("Настройка уведомлений приложений").performClick()
                    check(appNotifications.hits == 1)
                } finally { test.removeMonitor(appNotifications) }
            }
            // A permission is a live Android state, not a mutable in-app switch.
            reveal(test, root.button("Разрешения приложения"))
            val flatScroll = root.nodes().filterIsInstance<ScrollView>().single { it.isShown }
            val savedScroll = flatScroll.scrollY
            check(savedScroll > 0)
            onUi(test) { root.button("Разрешения приложения").performClick() }
            test.waitForIdleSync()
            onUi(test) { checkPermissions(root) }
            val recreated = test.addMonitor(DexSetupActivity::class.java.name, null, false)
            try {
                val previous = checkNotNull(activity)
                onUi(test) { previous.recreate() }
                activity = checkNotNull(test.waitForMonitorWithTimeout(recreated, 6_000))
                check(activity !== previous)
                root = activity.window.decorView
                test.waitForIdleSync()
            } finally { test.removeMonitor(recreated) }
            onUi(test) {
                checkPermissions(root)
                check(!AaPrefs(context).metaNavi && prefs.phoneScreenMode == PhoneIdleMode.CURTAIN && prefs.phoneTimerEnabled)
                check(prefs.phoneIdleTimeout == PhoneIdleTimeout.SYSTEM && prefs.desktopPanelControl)
                check(prefs.dpiCorrectionPackages == setOf("ru.yandex.yandexmaps", "com.android.settings"))
                val listenerAccess = test.addMonitor(IntentFilter(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS), null, true)
                try {
                    root.navigation("Доступ HeadSwap к уведомлениям").performClick()
                    check(listenerAccess.hits == 1)
                } finally { test.removeMonitor(listenerAccess) }
                root.button("Назад").performClick()
            }
            test.waitForIdleSync()
            onUi(test) {
                checkFlatSettings(root)
                val restoredScroll = root.nodes().filterIsInstance<ScrollView>().single { it.isShown }.scrollY
                check(restoredScroll == savedScroll) { "Settings scroll lost: $savedScroll -> $restoredScroll" }
                root.button("Назад").performClick()
                check(root.button("Подключить").isShown && root.button("Настройки").isShown)
                // Completed setup opens activation alone; initial onboarding must not return.
                root.button("Активировать").performClick()
                check(root.nodes().filterIsInstance<TextView>().any { it.isShown && it.text == "Активация" })
                check(root.nodes().filterIsInstance<TextView>().none { it.isShown && it.text == "Первоначальная настройка" })
                check(!root.button("Настроить приложение").isShown)
                check(root.button("На главный экран").isShown)
                checkActivationMethods(root, prefs)
                root.button("На главный экран").performClick()
                root.button("Настройки").performClick()
            }
            test.waitForIdleSync()
            // The HUD shortcut reaches the same permission page, not a second settings menu.
            onUi(test) {
                root.navigation("Доступ HeadSwap к уведомлениям").performClick()
                checkPermissions(root)
                root.navigation("Доступ к рабочему столу").performClick()
                check(root.button("На главный экран").isShown)
                check(!root.button("Настроить приложение").isShown)
                root.button("На главный экран").performClick()
                root.button("Настройки").performClick()
                root.button("Установить и обновить компоненты").performClick()
            }
            components = checkNotNull(test.waitForMonitorWithTimeout(monitor, 5_000))
            test.waitForIdleSync()
            val detail = components.window.decorView
            val cards = mutableListOf<View>()
            onUi(test) {
                check(detail.button("Проверить обновления HeadSwap Desktop").isShown)
                check(detail.nodes().filterIsInstance<Button>().none { it.text == "Выбрать компонент" })
                for (title in listOf("DriveDeck · HeadSwap", "CarBridge · HeadSwap")) {
                    val heading = detail.nodes().filterIsInstance<TextView>().single { it.text == title && it.isShown }
                    val card = heading.parent as View
                    check(card.button("Проверить версии компонента").isEnabled)
                    check(card.button("Установить из файла…").isEnabled)
                    cards.add(card)
                }
                check(cards[0] !== cards[1] && cards[0].parent === cards[1].parent)
                check(cards[1].top > cards[0].bottom)
            }
            for (card in cards) {
                val pick = card.button("Установить из файла…")
                reveal(test, pick)
                val picker = test.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addDataType("application/vnd.android.package-archive")
                }, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
                try { onUi(test) { pick.performClick(); check(picker.hits == 1) } }
                finally { test.removeMonitor(picker) }
            }
            // System pickers were intercepted; no network, download, or installer was started.
            onUi(test) { detail.button("Назад").performClick() }
            awaitVisibleWindow(test, root)
            onUi(test) { checkFlatSettings(root) }
            checkOnboardingPermissions(test, root)

        } finally {
            test.removeMonitor(monitor)
            onUi(test) { components?.finish(); activity?.finish() }
            prefs.desktopPanelControl = originalScreen
            prefs.phoneScreenMode = originalTimer
            prefs.phoneTimerEnabled = originalTimerEnabled
            prefs.phoneIdleTimeout = originalTimeout
            prefs.accessMode = originalAccess
            prefs.desktopMode = originalMode
            prefs.autoMusicPlayback = originalAutoplay
            prefs.usbPlaybackAudio = originalAudio
            prefs.independentRecoveryWithoutWifi = originalIndependentRecovery
            AaPrefs(context).metaNavi = originalNav
            prefs.initialSetupCompleted = originalSetupCompleted
            prefs.initialSetupInProgress = originalSetupProgress
            prefs.initialSetupStep = originalSetupStep
            prefs.initialSetupReviewed = originalSetupReviewed
            prefs.initialSetupAutoPending = originalSetupAuto
            prefs.compactMapSymbols = originalCompact
            prefs.dpiCorrectionPackages = originalPackages
        }
    }

    /** Method changes are local choices. The test never starts network ADB or changes its mode. */
    private fun checkActivationMethods(root: View, prefs: DexPrefs) {
        fun shownButton(label: String) = root.nodes().filterIsInstance<Button>().single { it.isShown && it.text == label }
        fun choose(method: ActivationMethod) {
            root.buttonStarting("Способ:").performClick()
            val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().last { view ->
                view.nodes().filterIsInstance<android.widget.ListView>().any()
            }
            val list = dialog.nodes().filterIsInstance<android.widget.ListView>().single()
            check((0 until list.count).map { list.getItemAtPosition(it).toString() } == listOf("Wi-Fi", "Shizuku", "С компьютера"))
            list.performItemClick(null, method.ordinal, list.getItemIdAtPosition(method.ordinal))
            check(prefs.activationMethod == method)
            check(!PhonePairingService.active && !prefs.independentRecoveryWithoutWifi)
        }
        check(prefs.activationMethod == ActivationMethod.WIFI)
        check(shownButton("Активировать").isEnabled)
        check(root.nodes().filterIsInstance<Button>().none { it.isShown && it.text in listOf(
            "Открыть настройки отладки", "Открыть отладку и ввод в уведомлении", "Сопрячь заново", "С компьютера", "Восстановить рабочий стол") })
        check(root.nodes().filterIsInstance<Switch>().none { it.isShown && it.contentDescription == "Восстанавливать рабочий стол без Wi-Fi" })
        check(!root.button("Скачать Android Platform Tools").isShown)
        if (Build.VERSION.SDK_INT >= 37) {
            shownButton("Активировать").performClick()
            val consent = android.view.inspector.WindowInspector.getGlobalWindowViews().last { view ->
                view.nodes().filterIsInstance<TextView>().any { it.text == "Автоматическое восстановление" }
            }
            check(consent.nodes().filterIsInstance<TextView>().any {
                it.text.contains("сетевые интерфейсы") && it.text.contains("без TLS")
            })
            check(!PhonePairingService.active && !prefs.independentRecoveryWithoutWifi)
            consent.button("Отмена").performClick()
            check(!PhonePairingService.active && !prefs.independentRecoveryWithoutWifi)
        }
        choose(ActivationMethod.COMPUTER)
        check(shownButton("Скачать Android Platform Tools").isEnabled)
        check(root.nodes().filterIsInstance<TextView>().any {
            it.isShown && it.text.contains("./adb -d shell") &&
                it.text.contains(".\\adb.exe -d shell") && it.text.contains("HEADSWAP_READY")
        })
        check(root.nodes().filterIsInstance<Button>().none { it.isShown && it.text == "Активировать" })
        choose(ActivationMethod.SHIZUKU)
        check(shownButton("Разрешить доступ Shizuku").isEnabled)
        check(!root.button("Скачать Android Platform Tools").isShown)
        choose(ActivationMethod.WIFI)
        check(shownButton("Активировать").isEnabled)
        check(!root.button("Разрешить доступ Shizuku").isShown)
    }

    private fun checkOnboardingPermissions(test: Instrumentation, settings: View) {
        val wizardMonitor = test.addMonitor(DesktopOnboardingActivity::class.java.name, null, false)
        var wizard: Activity? = null
        var permissions: Activity? = null
        val permissionMonitor = test.addMonitor(DexSetupActivity::class.java.name, null, false)
        try {
            onUi(test) { settings.button("Повторить заново первоначальную настройку").performClick() }
            val openedWizard = checkNotNull(test.waitForMonitorWithTimeout(wizardMonitor, 5000))
            wizard = openedWizard
            test.waitForIdleSync()
            onUi(test) {
                check(openedWizard.window.decorView.nodes().filterIsInstance<TextView>().any { it.isShown && it.text == "1. Компоненты" })
                openedWizard.window.decorView.button("Далее").performClick()
                check(openedWizard.window.decorView.nodes().filterIsInstance<TextView>().any { it.isShown && it.text == "2. Разрешения" })
                openedWizard.window.decorView.button("Разрешения приложения").performClick()
            }
            val openedPermissions = checkNotNull(test.waitForMonitorWithTimeout(permissionMonitor, 5000))
            permissions = openedPermissions
            test.waitForIdleSync()
            onUi(test) {
                val root = openedPermissions.window.decorView
                checkPermissions(root)
                root.button("Назад").performClick()
                check(openedPermissions.isFinishing) { "One Back must return directly to setup" }
            }
            awaitVisibleWindow(test, openedWizard.window.decorView)
            onUi(test) {
                val root = openedWizard.window.decorView
                check(root.button("Разрешения приложения").isShown)
                root.button("Далее").performClick()
                check(root.button("Открыть активацию").isShown)
                check(root.button("Завершить настройку").isShown)
                root.button("Закрыть настройку").performClick()
            }
            awaitVisibleWindow(test, settings)
        } finally {
            test.removeMonitor(wizardMonitor)
            test.removeMonitor(permissionMonitor)
            onUi(test) { permissions?.finish(); wizard?.finish() }
        }
    }

    private fun checkFlatSettings(root: View) {
        for (heading in listOf("Параметры изображения", "Звук", "Уведомления на магнитоле",
            "Приборная панель и HUD", "Компоненты HeadSwap Desktop", "Обслуживание")) {
            check(root.nodes().filterIsInstance<TextView>().any { it.isShown && it.text == heading }) { "Missing flat block: $heading" }
        }
        for (prefix in listOf("Разрешение:", "Размер интерфейса:", "Скорость видеопотока:", "Способ отключения:", "Гасить через:"))
            check(root.buttonStarting(prefix).isShown)
        for (label in listOf("Передавать звук в CarLife", "Автоматически продолжать музыку при подключении",
            "Управление дисплеем телефона", "Подсказки навигации на приборной панели и HUD")) check(root.toggle(label).isShown)
        for (label in listOf("Активация", "Исправить DPI приложения", "Разрешения приложения", "Установить и обновить компоненты",
            "Повторить заново первоначальную настройку", "Отправить журнал")) check(root.button(label).isShown)
        check(root.nodes().none { it.isShown && it.contentDescription?.startsWith("Открыть настройки:") == true })
        check(root.nodes().filterIsInstance<TextView>().none { it.isShown && it.text == "Приложение" })
    }

    private fun checkPermissions(root: View) {
        check(root.nodes().filterIsInstance<TextView>().any { it.isShown && it.text == "Разрешения приложения" })
        check(!root.toggle("Управление дисплеем телефона").isShown)
        for (label in listOf("Доступ к рабочему столу", "Доступ HeadSwap к уведомлениям", "Показ поверх приложений",
            "Уведомления HeadSwap", "Работа без ограничений батареи")) {
            val row = root.navigation(label)
            val info = row.createAccessibilityNodeInfo()
            try {
                check(info.className.toString() == Button::class.java.name && info.isClickable && !info.isCheckable)
                check(!info.stateDescription.isNullOrBlank())
            } finally { info.recycle() }
            check(row.nodes().none { it is Switch })
            val mark = row.nodes().filterIsInstance<TextView>().single { it.text == "✓" || it.text == "✕" }
            val granted = mark.text == "✓"
            val color = mark.currentTextColor
            check(if (granted) Color.green(color) > Color.red(color) else Color.red(color) > Color.green(color))
            check(row.stateDescription.toString().startsWith(if (granted) "Доступ есть." else "Нет доступа."))
        }
    }

    private fun reveal(test: Instrumentation, target: View) {
        onUi(test) {
            var ancestor = target.parent
            while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
            val scroll = checkNotNull(ancestor as? ScrollView) { "No scroll ancestor for $target" }
            val rect = Rect(0, 0, target.width, target.height)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.scrollTo(0, rect.top)
        }
        test.waitForIdleSync()
        onUi(test) {
            val visible = Rect()
            check(target.getGlobalVisibleRect(visible) && visible.height() > 0) { "Control is off screen: $target" }
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

    /** Main-thread idle does not imply that WindowManager finished a cross-Activity transition. */
    private fun awaitVisibleWindow(test: Instrumentation, root: View) {
        val ready = CountDownLatch(1)
        fun checkReady() {
            if (root.isAttachedToWindow && root.isShown && root.hasWindowFocus()) ready.countDown()
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { checkReady() }
        val layout = ViewTreeObserver.OnGlobalLayoutListener { checkReady() }
        onUi(test) {
            root.viewTreeObserver.addOnWindowFocusChangeListener(focus)
            root.viewTreeObserver.addOnGlobalLayoutListener(layout)
            checkReady()
        }
        try {
            check(ready.await(5, TimeUnit.SECONDS)) { "Expected Activity window did not become visible and focused" }
            test.waitForIdleSync()
        } finally {
            onUi(test) {
                root.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
                root.viewTreeObserver.removeOnGlobalLayoutListener(layout)
            }
        }
    }

    private fun View.nodes(): Sequence<View> = sequence {
        yield(this@nodes)
        if (this@nodes is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).nodes())
    }
    private fun View.button(text: String) = nodes().filterIsInstance<Button>().first { it.text == text }
    private fun View.buttonStarting(prefix: String) = nodes().filterIsInstance<Button>().first { it.isShown && it.text.startsWith(prefix) }
    private fun View.navigation(label: String) = nodes().first { it.isShown && it.isClickable && it.contentDescription == label }
    private fun View.toggle(label: String) = nodes().filterIsInstance<Switch>().first { it.contentDescription == label }
    private fun onUi(test: Instrumentation, run: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { failure = runCatching(run).exceptionOrNull() }
        failure?.let { throw it }
    }
}
