package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Process
import android.os.UserHandle
import java.io.Closeable

/** Own one registered loopback route and its address-bound recorder on the capture worker. */
internal class RegisteredPlaybackMix private constructor(
    val record: AudioRecord,
    private val unregister: () -> Unit
) : Closeable {
    override fun close() {
        try {
            record.release()
        } finally {
            unregister()
        }
    }

    companion object {
        // These are system APIs. Only the temporary --audio instrumentation may use them.
        @SuppressLint("PrivateApi")
        fun open(context: Context): RegisteredPlaybackMix {
            val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mix = buildMix(mixClass)
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilder = Class.forName(policyClass.name + "\$Builder")
                .getConstructor(Context::class.java).newInstance(context)
            policyBuilder.javaClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            val policy = policyBuilder.javaClass.getMethod("build").invoke(policyBuilder)
            // Resolve the cleanup method before registering anything on this ROM.
            val unregisterMethod = AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass)
            val createSink = policyClass.getMethod("createAudioRecordSink", mixClass)
            val result = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(manager, policy)
            check(result == 0) { "Android отклонил регистрацию маршрута USB-звука (код $result)" }
            val unregister: () -> Unit = { unregisterMethod.invoke(manager, policy) }
            var record: AudioRecord? = null
            try {
                record = createSink.invoke(policy, mix) as? AudioRecord
                check(record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    "Android не создал источник звука для зарегистрированного маршрута"
                }
                return RegisteredPlaybackMix(record, unregister)
            } catch (error: Throwable) {
                runCatching { record?.release() }.onFailure(error::addSuppressed)
                runCatching(unregister).onFailure(error::addSuppressed)
                throw error
            }
        }

        @SuppressLint("PrivateApi")
        private fun buildMix(mixClass: Class<*>): Any {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilder = Class.forName(ruleClass.name + "\$Builder").getConstructor().newInstance()
            val addRule = ruleBuilder.javaClass.getMethod("addRule", AudioAttributes::class.java, Int::class.java)
            val usageRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.USAGE_ASSISTANT)) {
                addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), usageRule)
            }
            val userId = UserHandle::class.java.getMethod("getIdentifier").invoke(Process.myUserHandle())
            ruleBuilder.javaClass.getMethod("addMixRule", Int::class.java, Any::class.java)
                .invoke(ruleBuilder, ruleClass.getField("RULE_MATCH_USERID").getInt(null), userId)
            val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)

            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(DesktopAudioPolicy.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val mixBuilder = Class.forName(mixClass.name + "\$Builder").getConstructor(ruleClass).newInstance(rule)
            mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.java)
                .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
            return mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)
        }
    }
}
