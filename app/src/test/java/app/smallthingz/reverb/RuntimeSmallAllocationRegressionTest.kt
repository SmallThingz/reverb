package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSmallAllocationRegressionTest {
    @Test
    fun errorGateDistinguishesShorterChainsAndIgnoresOnlyCausesBeyondEight() {
        fun chain(size: Int, changedAt: Int = -1): Throwable {
            var next: Throwable? = null
            for (index in size - 1 downTo 0) {
                next = IOException(if (index == changedAt) "changed" else "level-$index", next)
            }
            return requireNotNull(next)
        }
        val gate = RecorderStateReadErrorGate()
        assertTrue(gate.shouldReportFailure(chain(8)))
        assertFalse(gate.shouldReportFailure(chain(9)))
        assertFalse(gate.shouldReportFailure(chain(9, changedAt = 8)))
        assertTrue(gate.shouldReportFailure(chain(7)))
        assertFalse(gate.shouldReportFailure(chain(7)))
        assertTrue(gate.shouldReportFailure(chain(8)))
        assertTrue(gate.shouldReportFailure(chain(8, changedAt = 7)))
        assertFalse(gate.shouldReportFailure(chain(9, changedAt = 7)))
        assertTrue(gate.shouldReportFailure(chain(8)))
    }

    @Test
    fun errorGateReadsEachDescriptionOnceAndNeverTraversesBeyondItsBound() {
        class ObservedCause(val detail: String, val next: Throwable?) : Throwable() {
            var messageReads = 0
            var causeReads = 0
            override val message: String get() { messageReads++; return detail }
            override val cause: Throwable? get() { causeReads++; return next }
        }
        val causes = ArrayList<ObservedCause>()
        var next: Throwable? = null
        repeat(9) { index ->
            next = ObservedCause("level-$index", next).also(causes::add)
        }
        val gate = RecorderStateReadErrorGate()
        assertTrue(gate.shouldReportFailure(requireNotNull(next)))
        assertFalse(gate.shouldReportFailure(requireNotNull(next)))
        for (index in causes.indices) {
            assertEquals("message $index", if (index >= 1) 2 else 0, causes[index].messageReads)
            assertEquals("cause $index", if (index >= 2) 2 else 0, causes[index].causeReads)
        }
    }

    @Test
    fun incidentLocationPrefersAppFrameThenFirstForeignFrameAndHandlesEmptyTrace() {
        val foreign = StackTraceElement("external.Library", "run", "Library.java", 17)
        val app = StackTraceElement("app.smallthingz.reverb.Recorder", "read", "Recorder.kt", 42)
        val error = IOException("failure")
        error.stackTrace = arrayOf(foreign, app)
        assertEquals("Source: java.io.IOException: failure @ Recorder.read:42",
            unexpectedErrorIncidentDescription("Source", error))
        error.stackTrace = arrayOf(foreign, StackTraceElement("external.Other", "call", null, -1))
        assertEquals("Source: java.io.IOException: failure @ Library.run:17",
            unexpectedErrorIncidentDescription("Source", error))
        error.stackTrace = emptyArray()
        assertEquals("Source: java.io.IOException: failure", unexpectedErrorIncidentDescription("Source", error))
    }

}
