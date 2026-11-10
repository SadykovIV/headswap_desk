package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.headswap.desk.MainActivity
import com.headswap.desk.dex.*
import com.headswap.desk.update.ComponentPermissionsActivity
import com.headswap.desk.update.DriveDeckPermissionPolicy
import com.headswap.desk.update.DriveDeckPermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable-emulator fixture only: restores every preference and never grants component access. */
internal object FirstRunProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var outcome = Activity.RESULT_CANCELED
        var saved: Map<String, *>? = null
        var store: SharedPreferences? = null
        var componentStore: SharedPreferences? = null
        var savedComponents: Map<String, *>? = null
        val activities = mutableListOf<Activity>()
        val monitors = mutableListOf<Instrumentation.ActivityMonitor>()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator only" }
            check(!PhonePairingService.active && !DelegatedDesktopAccess.ownsActivation) { "Stop the test activation first" }
            val context = test.targetContext
            store = context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE)
            saved = store.all
            componentStore = context.getSharedPreferences("desktop_component_trust_v1", Context.MODE_PRIVATE)
            savedComponents = componentStore.all
            check(store.edit().clear().commit())
            fun monitor(type: Class<out Activity>) = test.addMonitor(type.name, null, false).also(monitors::add)
            fun opened(monitor: Instrumentation.ActivityMonitor): Activity =
                checkNotNull(test.waitForMonitorWithTimeout(monitor, 6000)) { "Expected Activity did not open" }
                    .also { activities.add(it); awaitWindow(test, it.window.decorView) }

            val mainMonitor = monitor(DexSetupActivity::class.java)
            val wizardMonitor = monitor(DesktopOnboardingActivity::class.java)
            val componentMonitor = monitor(ComponentPermissionsActivity::class.java)
            // Use the actual launcher entry, not a direct wizard launch, to test the automatic gate.
            test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                ?.let(activities::add)
            val main = checkNotNull(test.waitForMonitorWithTimeout(mainMonitor, 6000)).also(activities::add)
            var wizard = opened(wizardMonitor)
            val prefs = DexPrefs(context)
            check(prefs.desktopMode == DesktopModePolicy.DRIVEDECK)
            check(prefs.initialSetupInProgress && !prefs.initialSetupAutoPending && !prefs.initialSetupCompleted)
            onUi(test) {
                check(wizard.window.decorView.hasText("1. Компоненты"))
                check(wizard.window.decorView.hasText("Рабочий стол: DriveDeck"))
            }
            result.putString("firstLaunch", "MainActivity automatically opens Components; clean default is DriveDeck")

            val listenersBefore = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            val component = DriveDeckPermissions.snapshot(context)
            val packagePresent = runCatching { context.packageManager.getPackageInfo(DriveDeckPermissionPolicy.PACKAGE, 0) }.isSuccess
            if (!packagePresent) check(component.availability == DriveDeckPermissions.Availability.MISSING)
            onUi(test) { DriveDeckPermissions.open(wizard) }
            val componentPage = opened(componentMonitor)
            onUi(test) {
                val root = componentPage.window.decorView
                check(root.hasText("Разрешения DriveDeck"))
                if (component.availability == DriveDeckPermissions.Availability.READY) {
                    check(root.hasText("Приблизительное местоположение") && root.hasText("Контакты"))
                    val row = root.nodes().first { it.contentDescription == "Доступ DriveDeck к музыкальным данным" }
                    check(row.stateDescription.toString().startsWith(if (component.musicAccess == true) "Доступ есть." else "Нет доступа."))
                } else check(root.button("Установить компоненты").isShown)
                root.button("Назад").performClick()
            }
            awaitWindow(test, wizard.window.decorView)
            check(Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") == listenersBefore) {
                "Opening the component page must not grant notification access"
            }
            result.putString("driveDeckPermissions", "${component.availability}; music=${component.musicAccess}; read-only exact listener state")

            // Instrumentation.waitForMonitorWithTimeout removes its monitor after the wait.
            // Each expected launch therefore owns a new monitor, registered before the click.
            val activationMonitor = monitor(DesktopWizardActivationActivity::class.java)
            // Choose the built-in profile through the real picker so this probe works without a DD APK.
            onUi(test) {
                wizard.window.decorView.button("Выбрать рабочий стол").performClick()
                val list = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .flatMap { it.nodes().toList() }.filterIsInstance<ListView>().last()
                val index = (0 until list.count).first { list.getItemAtPosition(it).toString() == "Android Desktop" }
                list.performItemClick(null, index, list.getItemIdAtPosition(index))
                check(prefs.desktopMode == DesktopModePolicy.ANDROID_DESKTOP)
                check(wizard.window.decorView.button("Далее").isEnabled)
                wizard.window.decorView.button("Далее").performClick()
                val page = wizard.window.decorView
                check(page.hasText("Разрешения HeadSwap Desktop") && page.hasText("Разрешения DriveDesk"))
                check(page.hasText("Отладка по USB") && page.hasText("Беспроводная отладка (Wi-Fi)"))
                check(!page.hasText("Доступ к рабочему столу") && !page.hasText("Открыть активацию"))
                if (component.availability == DriveDeckPermissions.Availability.READY) {
                    for (name in listOf("Доступ DriveDeck к музыкальным данным", "Контакты", "Точное местоположение",
                        "Приблизительное местоположение", "Состояние телефона", "Поиск устройств Bluetooth"))
                        check(page.hasText(name)) { name }
                }
                page.button("Далее").performClick()
            }
            var activation = opened(activationMonitor)
            val backMonitor = monitor(DesktopOnboardingActivity::class.java)
            onUi(test) {
                val page = activation.window.decorView
                check(page.hasText("Первоначальная настройка · 3 из 3") && page.hasText("Активация"))
                check(!page.button("Завершить настройку").isEnabled)
                check(!page.hasText("Завершить доступ и закрыть HeadSwap"))
                check(page.button("Активировать").isShown)
                page.button("Назад").performClick()
            }
            wizard = opened(backMonitor)
            check(prefs.initialSetupStep == 1 && !prefs.initialSetupCompleted)
            val again = monitor(DesktopWizardActivationActivity::class.java)
            onUi(test) { wizard.window.decorView.button("Далее").performClick() }
            activation = opened(again)
            check(prefs.initialSetupStep == 2 && prefs.initialSetupReviewed && prefs.initialSetupInProgress)
            result.putString("flow", "Inline package permissions, both debugging settings, activation methods and finish on step 3; Back returns to step 2")
            onUi(test) { activation.finish() }
            awaitWindow(test, main.window.decorView)
            val resumed = monitor(DesktopWizardActivationActivity::class.java)
            onUi(test) { main.startActivity(Intent(context, DesktopOnboardingActivity::class.java)) }
            activation = opened(resumed)
            onUi(test) {
                check(!activation.window.decorView.button("Завершить настройку").isEnabled)
                activation.window.decorView.button("Закрыть настройку").performClick()
            }
            check(!prefs.initialSetupInProgress && !prefs.initialSetupCompleted)
            result.putString("resume", "Activation step survives Activity replacement; explicit close does not claim completion")

            // An existing installation that never stored a profile keeps the original built-in one.
            onUi(test) { activities.forEach(Activity::finish) }
            test.waitForIdleSync()
            check(store.edit().clear().putInt("desktop_bitrate_mbps", 7).commit())
            check(DexPrefs(context).desktopMode == DesktopModePolicy.ANDROID_DESKTOP)
            check(!DexPrefs(context).initialSetupAutoPending)
            for (mode in listOf(DesktopModePolicy.SAMSUNG_DEX, DesktopModePolicy.DRIVEDECK, DesktopModePolicy.ANDROID_DESKTOP)) {
                check(store.edit().clear().putInt("desktop_mode", mode).commit())
                check(DexPrefs(context).desktopMode == mode && !DexPrefs(context).initialSetupAutoPending)
            }
            result.putString("migration", "Implicit old Android Desktop and all explicit profiles preserved")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(error))
        } finally {
            monitors.forEach(test::removeMonitor)
            onUi(test) { activities.forEach(Activity::finish) }
            test.waitForIdleSync()
            if (saved != null && store != null) restore(store, saved)
            if (savedComponents != null && componentStore != null) restore(componentStore, savedComponents)
        }
        test.finish(outcome, result)
    }

    private fun restore(store: SharedPreferences, values: Map<String, *>) {
        val editor = store.edit().clear()
        for ((key, value) in values) when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        check(editor.commit()) { "Unable to restore emulator preferences" }
    }
    private fun View.nodes(): Sequence<View> = sequence {
        yield(this@nodes)
        if (this@nodes is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).nodes())
    }
    private fun View.hasText(value: String) = nodes().filterIsInstance<TextView>().any { it.isShown && it.text == value }
    private fun View.button(value: String) = nodes().filterIsInstance<Button>().first { it.isShown && it.text == value }
    private fun onUi(test: Instrumentation, action: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { failure = runCatching(action).exceptionOrNull() }
        failure?.let { throw it }
    }
    private fun awaitWindow(test: Instrumentation, root: View) {
        val ready = CountDownLatch(1)
        fun inspect() { if (root.isAttachedToWindow && root.isShown && root.hasWindowFocus()) ready.countDown() }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { inspect() }
        val layout = ViewTreeObserver.OnGlobalLayoutListener { inspect() }
        onUi(test) {
            root.viewTreeObserver.addOnWindowFocusChangeListener(focus)
            root.viewTreeObserver.addOnGlobalLayoutListener(layout)
            inspect()
        }
        try { check(ready.await(6, TimeUnit.SECONDS)) { "Expected foreground Activity window" }; test.waitForIdleSync() }
        finally { onUi(test) {
            root.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            root.viewTreeObserver.removeOnGlobalLayoutListener(layout)
        } }
    }
}
