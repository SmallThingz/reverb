package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingRuntimeCommitTest {
    @Test
    fun successfulCommitCompletesBeforeOneRuntimeReload() {
        val events = mutableListOf<String>()
        var durable = false
        var reloads = 0

        val result = commitOnboardingAndReloadRuntime(
            commit = {
                events += "commit"
                durable = true
                true
            },
            reloadRuntime = {
                assertTrue(durable)
                events += "runtime"
                reloads++
            },
            onRuntimeFailure = { events += "feedback" },
        )

        assertTrue(result)
        assertEquals(1, reloads)
        assertEquals(listOf("commit", "runtime"), events)
    }

    @Test
    fun rejectedCommitDoesNotReloadOrReportARuntimeFailure() {
        val events = mutableListOf<String>()

        val result = commitOnboardingAndReloadRuntime(
            commit = { events += "commit"; false },
            reloadRuntime = { events += "runtime" },
            onRuntimeFailure = { events += "feedback" },
        )

        assertFalse(result)
        assertEquals(listOf("commit"), events)
    }

    @Test
    fun commitExceptionPropagatesWithoutDispatchingOrMisclassifyingTheFailure() {
        val expected = IOException("Preferences could not commit")
        val events = mutableListOf<String>()

        val actual = assertThrows(IOException::class.java) {
            commitOnboardingAndReloadRuntime(
                commit = { events += "commit"; throw expected },
                reloadRuntime = { events += "runtime" },
                onRuntimeFailure = { events += "feedback" },
            )
        }

        assertSame(expected, actual)
        assertEquals(listOf("commit"), events)
    }

    @Test
    fun rejectedServiceStartReportsOnceWithoutRelabelingDurableSuccess() {
        val events = mutableListOf<String>()
        var durable = false
        var failures = 0

        val result = commitOnboardingAndReloadRuntime(
            commit = { events += "commit"; durable = true; true },
            reloadRuntime = {
                events += "runtime"
                requireServiceStarted<String> { null }
            },
            onRuntimeFailure = {
                assertTrue(durable)
                events += "feedback"
                failures++
            },
        )

        assertTrue(result)
        assertTrue(durable)
        assertEquals(1, failures)
        assertEquals(listOf("commit", "runtime", "feedback"), events)
    }

    @Test
    fun throwingRuntimeAndFailureFeedbackStillLeaveTheAcceptedCommitSuccessful() {
        val events = mutableListOf<String>()
        var commits = 0
        var failures = 0

        val result = commitOnboardingAndReloadRuntime(
            commit = { events += "commit"; commits++; true },
            reloadRuntime = { events += "runtime"; throw SecurityException("Service start denied") },
            onRuntimeFailure = {
                events += "feedback"
                failures++
                throw IOException("Feedback unavailable")
            },
        )

        assertTrue(result)
        assertEquals(1, commits)
        assertEquals(1, failures)
        assertEquals(listOf("commit", "runtime", "feedback"), events)
    }
}
