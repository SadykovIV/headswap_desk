package com.headswap.desk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Диагностика звука телефона в логе (префикс AUDIO): выходные устройства (A2DP/SCO/USB...) и активные плееры.
 * Без разрешений: BLUETOOTH_CONNECT не нужен, видны только аудио-маршруты. Нужна, чтобы понять, кто и куда играет
 * при автоплее по BT (Mazda CX-5).
 */
class AudioDiag {
    private var am: AudioManager? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastPlayers: String? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            for (d in added) if (d.isSink) AppLog.i("AUDIO выход добавлен: ${describe(d)}", ui = false)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            for (d in removed) if (d.isSink) AppLog.i("AUDIO выход убран: ${describe(d)}", ui = false)
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = logPlayers(configs)
    }

    fun start(context: Context) {
        if (am != null) return
        val m = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        am = m
        lastPlayers = null
        AppLog.i("AUDIO выходы: ${m.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { describe(it) }.ifEmpty { "нет" }}", ui = false)
        m.registerAudioDeviceCallback(deviceCallback, main)
        m.registerAudioPlaybackCallback(playbackCallback, main)
        logPlayers(m.activePlaybackConfigurations)
    }

    fun stop() {
        val m = am ?: return
        am = null
        m.unregisterAudioDeviceCallback(deviceCallback)
        m.unregisterAudioPlaybackCallback(playbackCallback)
    }

    @Suppress("DEPRECATION")
    private fun logPlayers(configs: List<AudioPlaybackConfiguration>) {
        val summary = if (configs.isEmpty()) "нет" else configs.joinToString("; ") { c ->
            val a = c.audioAttributes
            val dev = if (Build.VERSION.SDK_INT >= 34) c.audioDeviceInfo?.let { ", выход ${typeName(it.type)}" } ?: ", выход ?" else ""
            "usage=${usageName(a.usage)} content=${contentName(a.contentType)}$dev"
        }
        if (summary == lastPlayers) return
        lastPlayers = summary
        AppLog.i("AUDIO плееры (${configs.size}): $summary", ui = false)
    }

    private fun describe(d: AudioDeviceInfo): String = "${typeName(d.type)} '${d.productName}'"

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "встроенный динамик"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "разговорный динамик"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "проводные наушники"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE broadcast"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "телефония"
        else -> "тип $type"
    }

    private fun usageName(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION_GUIDANCE"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        else -> usage.toString()
    }

    private fun contentName(type: Int): String = when (type) {
        AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
        AudioAttributes.CONTENT_TYPE_MOVIE -> "MOVIE"
        AudioAttributes.CONTENT_TYPE_SONIFICATION -> "SONIFICATION"
        else -> type.toString()
    }
}
