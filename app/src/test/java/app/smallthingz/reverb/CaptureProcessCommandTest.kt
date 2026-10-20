package app.smallthingz.reverb

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProcessCommandTest {
    @Test
    fun acceptedProcessFailureReachesFeedbackBeforeLastTicketSettles() {
        val worker = ArrayDeque<Runnable>()
        val admission = CaptureCommandAdmission()
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        var settled = false
        errors.report("Unrelated existing failure")

        assertTrue(admission.submit(
            retainLifetime = { true },
            enqueue = { worker.add(it) },
            onIdle = {
                assertEquals(listOf("Start persistence failed"), processErrors)
                settled = true
            },
        ) {
            val accepted = errors.captureProcessCommand(processErrors::add) {
                // A rejected durable commit is an actual failure of accepted work.
                errors.report("Start persistence failed")
                false
            }
            assertFalse(accepted)
        })
        admission.closeAdmission()
        assertTrue(processErrors.isEmpty())
        worker.removeFirst().run()
        assertTrue(admission.isActive())
        assertFalse(settled)
        assertEquals(listOf("Start persistence failed"), processErrors)
        worker.removeFirst().run()
        assertFalse(admission.isActive())
        assertTrue(settled)
        assertEquals(listOf("Start persistence failed"), processErrors)
        assertEquals("Unrelated existing failure", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun synchronousTerminalFailureAndDeferredSealEachRetainTheirOwnReportOnce() {
        for (failure in listOf("Foreground start failed", "Intent rollback failed")) {
            val worker = ArrayDeque<Runnable>()
            val errors = RecorderErrorState()
            val processErrors = mutableListOf<String>()
            errors.report("Unrelated existing failure")
            val terminalReport: (String) -> Unit = {
                errors.reportTerminalCaptureFailure(it, processErrors::add)
            }

            assertTrue(errors.captureProcessCommand(terminalReport) {
                terminalReport(failure)
                assertTrue(processErrors.isEmpty())
                worker.add(Runnable { terminalReport("Deferred seal failed") })
                true
            })
            // The process handoff must run after the lexical command scope has ended.
            assertEquals(listOf(failure), processErrors)
            worker.removeFirst().run()
            assertEquals(listOf(failure, "Deferred seal failed"), processErrors)
            assertEquals("Unrelated existing failure", errors.consumePending())
            assertNull(errors.consumePending())
        }
    }

    @Test
    fun successfulAndModeledRejectedCommandsDoNotCreateFeedbackOrDrainPendingErrors() {
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        errors.report("Existing read error")
        for (result in listOf(true, false)) {
            assertEquals(result, errors.captureProcessCommand(processErrors::add) { result })
        }
        assertTrue(processErrors.isEmpty())
        assertEquals("Existing read error", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun refusedStartedLifetimeNeverEntersTheExecutionErrorScope() {
        val admission = CaptureCommandAdmission()
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        assertFalse(admission.submit(
            retainLifetime = { false },
            enqueue = { throw AssertionError("Rejected admission cannot queue") },
            onIdle = { throw AssertionError("No started lifetime was acquired") },
        ) {
            errors.captureProcessCommand(processErrors::add) {
                throw AssertionError("Rejected admission cannot execute")
            }
        })
        assertFalse(admission.isActive())
        assertTrue(processErrors.isEmpty())
        assertNull(errors.consumePending())
    }

    @Test
    fun throwingProcessFallbackKeepsItsFailureAndRestoresTheOrdinaryErrorChannel() {
        val errors = RecorderErrorState()
        val expected = IllegalStateException("Feedback failed")
        assertSame(expected, assertThrows(IllegalStateException::class.java) {
            errors.captureProcessCommand({ throw expected }) {
                errors.report("Actual command failure")
            }
        })
        errors.report("Later ordinary failure")
        assertEquals("Later ordinary failure", errors.consumePending())
        assertNull(errors.consumePending())
    }
}
