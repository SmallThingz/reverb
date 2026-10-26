package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NativeParityCleanupTest {
    @Test fun successfulCleanupPreservesAnExistingFailure() {
        val primary = IllegalStateException("operation")
        var releases = 0
        assertSame(primary, captureNativeParityCleanup(primary) { releases++ })
        assertNull(captureNativeParityCleanup(null) { releases++ })
        assertEquals(2, releases)
        assertEquals(0, primary.suppressed.size)
    }

    @Test fun everyIndependentReleaseRunsAfterEarlierFailures() {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("close")
        val detachFailure = IllegalArgumentException("detach")
        var failure = captureNativeParityCleanup(null) { events += "close"; throw closeFailure }
        failure = captureNativeParityCleanup(failure) { events += "detach"; throw detachFailure }
        failure = captureNativeParityCleanup(failure) { events += "finish" }
        assertSame(closeFailure, failure)
        assertEquals(listOf("close", "detach", "finish"), events)
        assertEquals(listOf(detachFailure), closeFailure.suppressed.toList())
    }

    @Test fun operationFailureRemainsPrimaryAndReceivesCleanupFailuresInOrder() {
        val primary = IllegalStateException("operation")
        val first = IllegalArgumentException("first release")
        val second = UnsupportedOperationException("second release")
        var failure = captureNativeParityCleanup(primary) { throw first }
        failure = captureNativeParityCleanup(failure) { throw second }
        assertSame(primary, failure)
        assertEquals(listOf(first, second), primary.suppressed.toList())
    }

    @Test fun rethrowingThePrimaryDoesNotCauseSelfSuppressionOrSkipTheNextRelease() {
        val shared = IllegalStateException("shared")
        var finished = false
        var failure = captureNativeParityCleanup(shared) { throw shared }
        failure = captureNativeParityCleanup(failure) { finished = true }
        assertSame(shared, failure)
        assertEquals(true, finished)
        assertEquals(0, shared.suppressed.size)
    }

    @Test fun fatalErrorsAreRetainedForPropagationAfterIndependentCleanup() {
        val fatal = LinkageError("fixture release")
        var finished = false
        var failure = captureNativeParityCleanup(null) { throw fatal }
        failure = captureNativeParityCleanup(failure) { finished = true }
        assertEquals(true, finished)
        try {
            failure?.let { throw it }
            throw AssertionError("Failure was swallowed")
        } catch (error: LinkageError) {
            assertSame(fatal, error)
        }
    }
}
