package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopMusicUsbLinkTest {
    private val service = Any()
    private val first = Any()
    private val retry = Any()
    private val gate = DesktopMusicUsbLink(8_000)

    @Test fun failedActiveOutputPausesBeforeSamsungDetachAndDoesNotRepeatAfterARetry() {
        gate.attach(service, first, 0)
        gate.markReady()
        assertTrue(gate.consumeTransportLoss(service, first, hadPicture = false))
        assertFalse(gate.consumeTransportLoss(service, first, hadPicture = true))
        gate.close(first, 130_000)
        gate.attach(service, retry, 130_521)
        assertFalse(gate.consumeTransportLoss(service, retry, hadPicture = false))
        assertFalse(gate.consumePhysicalDetach(service, 133_191))
        // The next physical connection can independently acquire its own proof.
        gate.attach(service, Any(), 140_000)
        gate.markReady()
        assertTrue(gate.consumePhysicalDetach(service, 140_250))
    }

    @Test fun aFirstCompletedPictureBeforeTheReadinessPollQualifiesOnlyItsOwnFd() {
        gate.attach(service, first, 0)
        assertFalse(gate.consumeTransportLoss(service, first, hadPicture = false))
        assertFalse(gate.consumePhysicalDetach(service, 1))
        assertFalse(gate.consumeTransportLoss(service, retry, hadPicture = true))
        assertFalse(gate.consumeTransportLoss(Any(), first, hadPicture = true))
        assertTrue(gate.consumeTransportLoss(service, first, hadPicture = true))
        assertFalse(gate.consumePhysicalDetach(service, 2_150))
    }

    @Test fun samsungReadErrorThenShortRetryStillPausesExactlyOnce() {
        gate.attach(service, first, 0)
        gate.markReady()
        gate.close(first, 130_000)
        gate.attach(service, retry, 130_521)
        assertFalse(gate.sawReady)
        assertTrue(gate.consumePhysicalDetach(service, 133_191))
        gate.markReady() // A late frame/close from the detached link cannot renew this proof.
        gate.close(retry, 133_192)
        assertFalse(gate.consumePhysicalDetach(service, 133_193))
        gate.attach(service, Any(), 133_194)
        assertFalse(gate.consumePhysicalDetach(service, 133_195))
    }

    @Test fun moreUnreadyRetriesCannotExtendTheOriginalDeadline() {
        gate.attach(service, first, 100)
        gate.markReady()
        gate.close(first, 200)
        gate.attach(service, retry, 2_000)
        gate.close(retry, 6_000)
        gate.attach(service, Any(), 8_100)
        assertFalse(gate.consumePhysicalDetach(service, 8_201))
    }

    @Test fun detachWithoutReplacementAcceptsTheInclusiveGraceBoundary() {
        gate.attach(service, first, 100)
        gate.markReady()
        gate.close(first, 200)
        assertTrue(gate.consumePhysicalDetach(service, 8_200))
    }

    @Test fun aNewServiceCannotUseTheOldServiceProofOrItsLateDetach() {
        gate.attach(service, first, 100)
        gate.markReady()
        gate.close(first, 200)
        val replacement = Any()
        gate.attach(replacement, retry, 201)
        assertFalse(gate.consumePhysicalDetach(service, 202))
        assertFalse(gate.consumePhysicalDetach(replacement, 203))
    }

    @Test fun explicitStopOrNeverReadyLinkCannotPauseUnrelatedHandsetPlayback() {
        gate.attach(service, first, 100)
        assertFalse(gate.consumePhysicalDetach(service, 120))
        gate.attach(service, retry, 130)
        gate.markReady()
        gate.close(retry, 200)
        gate.clear()
        gate.attach(service, Any(), 210)
        assertFalse(gate.consumePhysicalDetach(service, 211))
    }

    @Test fun overlappingReplacementDoesNotLoseProofOrAllowAStaleClose() {
        gate.attach(service, first, 100)
        gate.markReady()
        gate.attach(service, retry, 200)
        assertFalse(gate.close(first, 7_000))
        assertTrue(gate.consumePhysicalDetach(service, 7_001))
    }

    @Test fun aNewReadySessionQualifiesIndependentlyAfterLongIdle() {
        gate.attach(service, first, 100)
        gate.markReady()
        gate.close(first, 200)
        gate.attach(service, retry, 60_000)
        gate.markReady()
        // A connected, naturally static desktop stays eligible after its first real frame.
        assertTrue(gate.consumePhysicalDetach(service, 120_000))
    }
}
