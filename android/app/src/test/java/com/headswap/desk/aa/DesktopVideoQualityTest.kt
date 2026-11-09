package com.headswap.desk.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopVideoQualityTest {
    @Test fun reducedHuFrameRateIsAppliedWithoutTheLegacyAaTenFpsFloor() {
        for (requested in listOf(3, 5, 9, 10, 30, 60)) {
            assertEquals(requested, DesktopVideoQuality.frameRateChangeTarget(requested))
            assertEquals(minOf(requested, 20), DesktopVideoQuality.chooseFps(FpsSetting.AUTO, requested, 20))
            val dynamic = DesktopFrameRatePolicy().apply { reset(30) }
            val target = DesktopVideoQuality.frameRateChangeTarget(requested, 20)
            dynamic.update(0, 0.0, target)
            assertTrue(dynamic.current <= target)
        }
        assertEquals(20, DesktopVideoQuality.frameRateChangeTarget(60, 20))
    }
    @Test fun receiverEncoderTargetCannotOverrideSelectedOrThermalBitrate() {
        val selected = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB, 1_000_000)
        assertEquals(1_000_000, DesktopVideoQuality.encoderBitrate(selected, 3_840_000))
        val adaptive = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB, 8_000_000)
        adaptive.lowerCeiling(3_840_000)
        assertEquals(3_840_000, DesktopVideoQuality.encoderBitrate(adaptive, 3_840_000))
        adaptive.update(0, 750.0, 0.0)
        assertTrue(DesktopVideoQuality.encoderBitrate(adaptive, 3_840_000) < 3_840_000)
        adaptive.setThermalCap(1_500_000)
        assertEquals(1_500_000, DesktopVideoQuality.encoderBitrate(adaptive, 3_840_000))
        assertEquals(1_000_000, DesktopVideoQuality.encoderBitrate(adaptive, 1_000_000))
    }
    @Test fun blockedFirstWriteAdaptsBeforeFirstDrainSampleAndHonorsSelectedCeiling() {
        val policy = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB, 4_000_000)
        assertEquals(4_000_000, policy.current)
        for (now in 0L..4000L step 100) policy.update(now, 750.0, 0.0)
        assertTrue(policy.current < 2_000_000)
        for (now in 4100L..300000L step 100) policy.update(now, 0.0, 2_000_000.0)
        assertEquals(4_000_000, policy.current)
    }
    @Test
    fun fastUsbKeepsEightMegabitsWithoutChangingLegacy720() {
        val policy = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB)
        assertEquals(8_000_000, policy.startBps)
        for (now in 0L..60_000L step 100) policy.update(now, 0.0, 2_000_000.0)
        assertEquals(8_000_000, policy.current)
        assertEquals(4_000_000, VideoMode.R720.bitrate)
        assertEquals(1280, VideoMode.R720.outW)
        assertEquals(720, VideoMode.R720.outH)
    }

    @Test
    fun slowUsbStillReducesTheHigherTargetAndRecoversOnlyToItsCeiling() {
        val policy = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB)
        val drain = 210.0 * 1024
        var queued = 0.0
        for (now in 0L..30_000L step 100) {
            queued = maxOf(0.0, queued + policy.current / 8.0 * 0.1 - drain * 0.1)
            policy.update(now, queued / drain * 1000, drain)
        }
        assertTrue("slow USB must shed its initial 8 Mbit/s load", policy.current < 2_000_000)
        assertTrue(policy.downs > 0)
        assertTrue(policy.current >= BitratePolicy.FLOOR_BPS)
        for (now in 30_100L..300_000L step 100) {
            policy.update(now, 0.0, 2_000_000.0)
            assertTrue(policy.current <= 8_000_000)
        }
        assertEquals(8_000_000, policy.current)
    }

    @Test
    fun knownHuAndThermalCapsRemainAuthoritative() {
        val policy = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB)
        policy.lowerCeiling(1_500_000)
        policy.setThermalCap(1_000_000)
        for (now in 0L..60_000L step 100) policy.update(now, 0.0, 2_000_000.0)
        assertEquals(1_000_000, policy.current)
        policy.setThermalCap(Int.MAX_VALUE)
        for (now in 60_100L..180_000L step 100) policy.update(now, 0.0, 2_000_000.0)
        assertEquals(1_500_000, policy.current)
        assertEquals(1_500_000, policy.ceilingBps)
    }
    @Test fun sixtyFpsSurvivesSelectionWhileAutoAndKnownHuCapsRemainExplicit() {
        assertEquals(60, DesktopVideoQuality.chooseFps(FpsSetting.FPS60, 30))
        assertEquals(60, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 60))
        assertEquals(30, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 30))
        assertEquals(30, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 120))
        assertEquals(30, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 0))
        assertEquals(20, DesktopVideoQuality.chooseFps(FpsSetting.FPS60, 60, 20))
        assertTrue(DesktopVideoQuality.frameRateChangeAllowed(60))
        org.junit.Assert.assertFalse(DesktopVideoQuality.frameRateChangeAllowed(61))
    }
}
