package com.headswap.desk.dex

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.*
import org.junit.Test

class DexAccessDiagnosticsTest {
    @Test fun classifiesNestedTransportFailuresWithoutCallingThemTlsFailures() {
        val refusal = SSLHandshakeException("hidden message").apply { initCause(ConnectException("ECONNREFUSED")) }
        assertEquals(DexAccessReason.REFUSED, DexAccessDiagnostics.classify(refusal))
        assertEquals(DexAccessReason.TIMEOUT, DexAccessDiagnostics.classify(IOException(SocketTimeoutException())))
        assertEquals(DexAccessReason.NETWORK_UNREACHABLE, DexAccessDiagnostics.classify(ConnectException("ENETUNREACH")))
        assertEquals(DexAccessReason.PERMISSION_DENIED, DexAccessDiagnostics.classify(IOException("EPERM")))
        assertEquals(DexAccessReason.TLS_PROTOCOL, DexAccessDiagnostics.classify(SSLProtocolException("TLS failed")))
        assertEquals(DexAccessReason.PAIR_REJECTED, DexAccessDiagnostics.classify(IOException("Could not exchange peer info")))
    }

    @Test fun safeTraceCannotRevealMessagesPemOrPairingCode() {
        val secret = "654321\n-----BEGIN PRIVATE KEY-----\nprivate-key-material\n-----END PRIVATE KEY-----"
        val error = IOException(secret, ConnectException(secret)).apply {
            stackTrace = arrayOf(
                StackTraceElement("com.headswap.desk.dex.DexAccess", "pair", "DexAccess.kt", 99),
                StackTraceElement("untrusted.library", "exchange", secret, 1),
            )
        }
        val trace = DexAccessDiagnostics.safeTrace(error)
        assertTrue(trace.contains("IOException{DexAccess.pair:99}"))
        assertTrue(trace.contains("ConnectException"))
        assertFalse(trace.contains("654321"))
        assertFalse(trace.contains("PRIVATE"))
        assertFalse(trace.contains("private-key-material"))
        assertFalse(trace.contains("untrusted.library"))
        assertFalse(trace.contains('\n'))
        val reported = DexAccessException(DexAccessStage.PAIR_CONNECT, DexAccessDiagnostics.classify(error))
        assertNull(reported.cause)
        assertFalse(reported.toString().contains("654321"))
    }

    @Test fun probeAdviceDoesNotTellUserToReopenThePairingSheet() {
        val pair = DexAccessException(DexAccessStage.PAIR_CONNECT, DexAccessReason.REFUSED).message!!
        val probe = DexAccessException(DexAccessStage.PROBE_CONNECT, DexAccessReason.REFUSED).message!!
        assertTrue(pair.contains("Откройте окно сопряжения"))
        assertTrue(probe.contains("основной порт"))
        assertFalse(probe.contains("Откройте окно сопряжения"))
        assertFalse(DexAccessException(DexAccessStage.PAIR_HANDSHAKE, DexAccessReason.TLS_PROTOCOL).message!!.contains("не соответствует"))
    }
}
