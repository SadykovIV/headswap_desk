package com.headswap.desk.update

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import com.headswap.desk.dex.DesktopSetupViews

/** Phone-only component setup. It never grants access or starts the component on display zero. */
class ComponentPermissionsActivity : Activity() {
    private val ui by lazy { DesktopSetupViews(this) }
    override fun onCreate(state: Bundle?) { super.onCreate(state) }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val root = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.background)
            isFillViewport = true
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        window.setDecorFitsSystemWindows(false)
        setContentView(scroll)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this) }
        ui.button(header, "Назад") { finish() }
        ui.text(root, "Разрешения DriveDeck", 25f, true)
        val state = DriveDeckPermissions.snapshot(this)
        val card = ui.section(root, "Плеер и обложки")
        ui.text(card, state.description, 15f)
        if (state.availability == DriveDeckPermissions.Availability.READY) {
            ui.permission(card, "Доступ DriveDeck к музыкальным данным") { DriveDeckPermissions.openMusicAccess(this) }
                .update(state.musicAccess == true, when (state.musicAccess) {
                    true -> "Разрешён через Android"
                    false -> "Нужен доступ к уведомлениям"
                    null -> "Проверьте в настройках Android"
                })
            ui.text(card, "Android выдаёт доступ к уведомлениям приложений. DriveDeck использует его для подключения к медиасессиям и получения названия, исполнителя и доступной обложки. Доступ к фотографиям, файлам и специальным возможностям для этого не требуется.", 14f)
            ui.button(card, "Открыть доступ к уведомлениям") { DriveDeckPermissions.openMusicAccess(this) }
            val other = ui.section(root, "Другие разрешения")
            ui.text(other, "Геолокация, контакты и другие разрешения относятся к отдельным функциям DriveDeck. Выдавайте их только для нужных функций; для обложки трека они не нужны.", 14f)
            ui.button(other, "Открыть настройки DriveDeck") { DriveDeckPermissions.openAppSettings(this) }
        } else {
            ui.button(card, "Установить компоненты") { startActivity(android.content.Intent(this, ComponentUpdatesActivity::class.java)) }
        }
    }
}
