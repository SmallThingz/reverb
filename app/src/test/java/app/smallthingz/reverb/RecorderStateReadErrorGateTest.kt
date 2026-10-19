package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderStateReadErrorGateTest {
    @Test
    fun unchangedPollingFailureReportsOnceEvenWhenEachReadThrowsANewException() {
        val gate = RecorderStateReadErrorGate()
        assertTrue(gate.shouldReportFailure(IOException("Store unavailable")))
        repeat(100) {
            assertFalse(gate.shouldReportFailure(IOException("Store unavailable")))
        }
    }

    @Test
    fun successfulReadEndsFailureEpisodeSoRecurrenceIsReported() {
        val gate = RecorderStateReadErrorGate()
        assertTrue(gate.shouldReportFailure(IOException("Store unavailable")))
        assertFalse(gate.shouldReportFailure(IOException("Store unavailable")))
        gate.readSucceeded()
        assertTrue(gate.shouldReportFailure(IOException("Store unavailable")))
        assertFalse(gate.shouldReportFailure(IOException("Store unavailable")))
    }

    @Test
    fun changedErrorTypeOrMessageIsReportedWithoutWaitingForRecovery() {
        val gate = RecorderStateReadErrorGate()
        assertTrue(gate.shouldReportFailure(IOException("Read failed")))
        assertTrue(gate.shouldReportFailure(SecurityException("Read failed")))
        assertTrue(gate.shouldReportFailure(SecurityException("Access revoked")))
        assertFalse(gate.shouldReportFailure(SecurityException("Access revoked")))
    }

    @Test
    fun changedUnderlyingCauseIsNotHiddenByTheSameWrapperMessage() {
        val gate = RecorderStateReadErrorGate()
        fun failure(cause: Throwable) = IOException("Read failed", cause)

        assertTrue(gate.shouldReportFailure(failure(IOException("No space"))))
        assertFalse(gate.shouldReportFailure(failure(IOException("No space"))))
        assertTrue(gate.shouldReportFailure(failure(SecurityException("Access revoked"))))
        assertFalse(gate.shouldReportFailure(failure(SecurityException("Access revoked"))))
    }

    @Test
    fun cyclicCauseChainDoesNotMakePollingOrReportingUnbounded() {
        val gate = RecorderStateReadErrorGate()
        val outer = IOException("Outer")
        val inner = IOException("Inner", outer)
        outer.initCause(inner)

        assertTrue(gate.shouldReportFailure(outer))
        assertFalse(gate.shouldReportFailure(outer))
        gate.readSucceeded()
        assertTrue(gate.shouldReportFailure(outer))
    }
}
