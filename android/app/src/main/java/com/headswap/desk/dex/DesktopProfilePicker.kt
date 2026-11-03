package com.headswap.desk.dex

import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox
import com.headswap.desk.component.DesktopComponentEntry
import com.headswap.desk.component.DesktopComponents

/** Profile names belong to the phone UI. Independent profiles run on the Android Desktop engine. */
internal object DesktopProfilePicker {
    fun name(entry: DesktopComponentEntry) = when {
        entry.modeId != "carbridge" -> entry.label
        entry.packageName == "com.example.headswap.panel.carbridgepreview" ->
            if (entry.dashboard) "CarBridge · панель HeadSwap" else "CarBridge · тест SDK"
        else -> "CarBridge"
    }

    fun show(activity: Activity, currentMode: Int, unlocked: () -> Boolean, apply: (Int, DesktopComponentEntry?, Boolean) -> Unit) {
        val installed = DesktopComponents.list(activity)
        val selected = DesktopComponents.selected(activity).takeIf { currentMode == DesktopModePolicy.ANDROID_DESKTOP }
        val carBridge = installed.filter { it.modeId == "carbridge" }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        var checked = -1

        fun row(label: String, active: Boolean, action: () -> Unit) {
            if (active) checked = labels.size
            labels += label
            actions += action
        }
        fun builtin(label: String, mode: Int) =
            row(label, selected == null && currentMode == mode) { if (unlocked()) apply(mode, null, false) }
        fun choose(entry: DesktopComponentEntry) {
            if (!unlocked()) return
            if (entry == selected && !entry.dashboard) return
            val media = if (entry.dashboard) CheckBox(activity).apply {
                text = "Разрешить показывать текущий трек и обложку и управлять воспроизведением"
                isChecked = DesktopComponents.mediaAllowed(activity, entry)
            } else null
            val dialog = AlertDialog.Builder(activity).setTitle("Использовать профиль ${name(entry)}?")
                .setMessage("Приложение: ${entry.appLabel}\nПакет: ${entry.packageName}\nActivity: ${entry.componentName.className}\nВерсия: ${entry.versionName} (${entry.versionCode})\nSHA-256 сертификата:\n${entry.signerSha256.sorted().joinToString("\n")}\n\nПрофиль сможет показывать свой интерфейс и запрашивать открытие обычных приложений в окне ГУ.")
                .setPositiveButton("Использовать") { _, _ -> if (unlocked()) apply(DesktopModePolicy.ANDROID_DESKTOP, entry, media?.isChecked == true) }
                .setNegativeButton("Отмена", null)
            if (media != null) dialog.setView(media)
            dialog.show()
        }
        builtin("Android Desktop", DesktopModePolicy.ANDROID_DESKTOP)
        val carBridgeTitle = when (carBridge.size) {
            0 -> "CarBridge · не установлен"
            1 -> name(carBridge.single())
            else -> "CarBridge"
        }
        row(carBridgeTitle, selected?.modeId == "carbridge") {
            when (carBridge.size) {
                0 -> AlertDialog.Builder(activity).setTitle("Профиль CarBridge")
                    .setMessage("Для этого профиля нужен совместимый APK панели от автора CarBridge. Самостоятельное приложение CarBridge Offline v0.43 не объявляет интерфейс панелей HeadSwap.")
                    .setPositiveButton("Понятно", null).show()
                1 -> choose(carBridge.single())
                else -> AlertDialog.Builder(activity).setTitle("Профиль CarBridge · выбрать компонент")
                    .setItems(carBridge.map { "${it.appLabel}\n${it.componentName.flattenToShortString()}" }.toTypedArray()) { _, index -> choose(carBridge[index]) }
                    .setNegativeButton("Отмена", null).show()
            }
        }
        builtin("DriveDeck", DesktopModePolicy.DRIVEDECK)
        if (SamsungDexCapability.isAvailable(activity)) builtin("Samsung DeX · Shizuku", DesktopModePolicy.SAMSUNG_DEX)
        for (entry in installed.filterNot { it.modeId == "carbridge" }) {
            row("${name(entry)} · ${entry.packageName}", selected?.componentName == entry.componentName) { choose(entry) }
        }
        AlertDialog.Builder(activity).setTitle("Профиль рабочего стола")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, index ->
                dialog.dismiss()
                if (unlocked()) actions[index]()
            }.setNegativeButton("Отмена", null).show()
    }
}
