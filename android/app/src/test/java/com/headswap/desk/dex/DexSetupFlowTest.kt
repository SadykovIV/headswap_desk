package com.headswap.desk.dex

import org.junit.Assert.*
import org.junit.Test

class DexSetupFlowTest {
    private val pair = DexEndpoint("192.168.1.2", 33001)
    private val oldConnect = DexEndpoint("192.168.1.2", 33002)
    private val freshConnect = DexEndpoint("192.168.1.2", 33003)

    @Test fun successfulPairAutomaticallyChecksLatestConnectPortExactlyOnce() {
        val flow = DexSetupFlow()
        flow.beginPair("123456")
        val token = flow.token
        assertTrue(flow.discovered(token, pair, oldConnect) is DexSetupFlow.Command.Pair)
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, freshConnect))
        assertEquals(DexSetupFlow.Command.Probe(token, freshConnect), flow.paired(token))
        assertEquals(DexSetupFlow.Command.None, flow.paired(token))
        assertEquals(DexSetupFlow.Command.Ready(freshConnect), flow.verified(token))
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, freshConnect))
    }

    @Test fun lostConnectServiceDuringPairingCannotBeUsedAfterPairing() {
        val flow = DexSetupFlow()
        flow.beginPair("123456")
        val token = flow.token
        flow.discovered(token, pair, oldConnect)
        flow.discovered(token, pair, null)
        assertEquals(DexSetupFlow.Command.Discover(token, DexService.CONNECT), flow.paired(token))
        assertEquals(DexSetupFlow.Command.NotFound(DexService.CONNECT), flow.expired(token, DexService.CONNECT))
    }

    @Test fun stopRejectsDiscoveryTimeoutAndWorkerResultsFromTheCancelledAttempt() {
        val flow = DexSetupFlow()
        flow.beginPair("123456")
        val oldToken = flow.token
        flow.discovered(oldToken, pair, oldConnect)
        flow.cancel()
        flow.beginConnect()
        assertEquals(DexSetupFlow.Command.None, flow.paired(oldToken))
        assertEquals(DexSetupFlow.Command.None, flow.discovered(oldToken, pair, oldConnect))
        assertEquals(DexSetupFlow.Command.None, flow.expired(oldToken, DexService.CONNECT))
        assertEquals(DexSetupFlow.Phase.FIND_CONNECT, flow.phase)
    }

    @Test fun existingPairingUsesDiscoveredPortAndDoesNotRepeatProbeForEveryUpdate() {
        val flow = DexSetupFlow()
        flow.beginConnect(saved = oldConnect)
        val token = flow.token
        assertEquals(DexSetupFlow.Command.Probe(token, freshConnect), flow.discovered(token, pair, freshConnect))
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, freshConnect))
        assertEquals(DexSetupFlow.Command.None, flow.expired(token, DexService.CONNECT))
        flow.verified(token)
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, null, freshConnect))
    }

    @Test fun discoveryHasOneSavedEndpointFallbackAndNeverStartsPairingAutomatically() {
        val flow = DexSetupFlow()
        flow.beginConnect(saved = oldConnect)
        val token = flow.token
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, null))
        assertEquals(DexSetupFlow.Command.Probe(token, oldConnect), flow.expired(token, DexService.CONNECT))
        assertEquals(DexSetupFlow.Command.None, flow.expired(token, DexService.CONNECT))
        flow.failed(token)
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, freshConnect))
    }

    @Test fun phoneActivationConsumesOnlyACurrentVerifiedEndpointOnce() {
        val flow = DexSetupFlow()
        flow.beginConnect(manual = freshConnect)
        val token = flow.token
        assertFalse(flow.activating(token)) // A discovered endpoint alone is insufficient.
        flow.verified(token)
        assertTrue(flow.activating(token))
        assertFalse(flow.activating(token))
        assertEquals(DexSetupFlow.Phase.ACTIVATING, flow.phase)
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, oldConnect))
        assertEquals(DexSetupFlow.Command.None, flow.verified(token))
        assertTrue(flow.failed(token))
        assertEquals(DexSetupFlow.Phase.FAILED, flow.phase)
    }

    @Test fun cancelOrNewAttemptPreventsOldProbeFromActivatingPhone() {
        val flow = DexSetupFlow()
        flow.beginConnect(manual = freshConnect)
        val oldToken = flow.token
        flow.verified(oldToken)
        flow.cancel()
        assertFalse(flow.activating(oldToken))
        flow.beginConnect(manual = oldConnect)
        val currentToken = flow.token
        flow.verified(currentToken)
        assertFalse(flow.activating(oldToken))
        assertTrue(flow.activating(currentToken))
    }

    @Test fun replacedConnectPortCanRecoverWithoutPairingAgain() {
        val flow = DexSetupFlow()
        flow.beginConnect(manual = oldConnect)
        val token = flow.token
        flow.discovered(token, pair, freshConnect)
        val error = DexAccessException(DexAccessStage.PROBE_CONNECT, DexAccessReason.TLS_PROTOCOL)
        assertEquals(DexSetupFlow.Command.Probe(token, freshConnect), flow.probeFailed(token, error))
        assertEquals(DexSetupFlow.Command.Ready(freshConnect), flow.verified(token))
        assertTrue(flow.activating(token))
    }

    @Test fun transientFailureWaitsForNewEndpointButNeverReplaysTheFailedOne() {
        val flow = DexSetupFlow()
        flow.beginConnect(manual = oldConnect)
        val token = flow.token
        val error = DexAccessException(DexAccessStage.PROBE_CONNECT, DexAccessReason.REFUSED)
        assertEquals(DexSetupFlow.Command.Discover(token, DexService.CONNECT), flow.probeFailed(token, error))
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, oldConnect))
        assertEquals(DexSetupFlow.Command.Probe(token, freshConnect), flow.discovered(token, pair, freshConnect))
    }

    @Test fun recoveryTimeoutPreservesTheLastTransportError() {
        val flow = DexSetupFlow()
        flow.beginConnect(saved = oldConnect, manual = freshConnect)
        val token = flow.token
        val error = DexAccessException(DexAccessStage.PROBE_CONNECT, DexAccessReason.TLS_PROTOCOL)
        flow.probeFailed(token, error)
        assertEquals(DexSetupFlow.Command.ProbeFailed(error), flow.expired(token, DexService.CONNECT))
        assertEquals(DexSetupFlow.Phase.FAILED, flow.phase)
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, oldConnect))
    }

    @Test fun authorizationFailureDoesNotRetryOtherPortsOrStartPairing() {
        val flow = DexSetupFlow()
        flow.beginConnect(manual = oldConnect)
        val token = flow.token
        flow.discovered(token, pair, freshConnect)
        val error = DexAccessException(DexAccessStage.PROBE_CONNECT, DexAccessReason.AUTHORIZATION_REJECTED)
        assertEquals(DexSetupFlow.Command.ProbeFailed(error), flow.probeFailed(token, error))
        assertEquals(DexSetupFlow.Phase.FAILED, flow.phase)
        assertEquals(DexSetupFlow.Command.None, flow.discovered(token, pair, freshConnect))
    }

    @Test fun repeatedEndpointChangesAreBoundedAndCancellationRejectsOldFailure() {
        val flow = DexSetupFlow()
        flow.beginConnect(manual = oldConnect)
        val token = flow.token
        val error = DexAccessException(DexAccessStage.PROBE_CONNECT, DexAccessReason.REFUSED)
        flow.probeFailed(token, error)
        flow.discovered(token, pair, freshConnect)
        flow.probeFailed(token, error)
        flow.discovered(token, pair, DexEndpoint(freshConnect.host, 33999))
        assertEquals(DexSetupFlow.Command.ProbeFailed(error), flow.probeFailed(token, error))
        flow.cancel()
        flow.beginConnect()
        assertEquals(DexSetupFlow.Command.None, flow.probeFailed(token, error))
        assertEquals(DexSetupFlow.Phase.FIND_CONNECT, flow.phase)
    }
}
