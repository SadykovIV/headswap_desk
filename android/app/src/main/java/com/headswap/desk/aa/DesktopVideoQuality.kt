package com.headswap.desk.aa

/** Quality target for the native desktop Surface source, independent of legacy AA modes. */
object DesktopVideoQuality {
    // CarLife's VIDEO_ENCODER_INIT carries width, height and frameRate, not a 4 Mbit/s cap.
    // This remains a target: the existing HU, thermal and transport policies may lower it.
    const val BITRATE_CEILING_BPS = 8_000_000
    const val MAX_FPS = 60

    /** Desktop AUTO accepts an actual 60 Hz HU request; legacy Android Auto keeps its own policy. */
    fun chooseFps(setting: FpsSetting, requested: Int, huMaxFps: Int? = null): Int {
        val selected = setting.fixed ?: requested.takeIf { it in 1..MAX_FPS } ?: 30
        return huMaxFps?.let { minOf(selected, it) } ?: selected
    }

    fun frameRateChangeAllowed(fps: Int): Boolean = fps in 3..MAX_FPS

    /** A receiver-specific encoder target is another upper bound, never a user-setting override. */
    fun encoderBitrate(policy: BitratePolicy, receiverLimit: Int? = null): Int =
        minOf(policy.current, receiverLimit ?: Int.MAX_VALUE)

    fun bitratePolicy(profile: BitrateProfile, ceilingBps: Int = BITRATE_CEILING_BPS): BitratePolicy {
        // A first oversized native desktop IDR may block before any completed write provides
        // a drain sample. Its measured write age must still be able to reduce the producer.
        val nativeProfile = if (profile == BitrateProfile.USB)
            profile.copy(downStep = 0.8, downWithoutDrain = true) else profile
        return BitratePolicy(ceilingBps.coerceIn(1_000_000, 20_000_000), profile = nativeProfile)
    }
}
