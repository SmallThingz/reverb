package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableUiRetryTest {
    @Test
    fun committedAttempt_finishesAcceptedWriteAfterOwnerCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()

        val owner = launch {
            runCommittedDurableUiBooleanAttempt {
                started.complete(Unit)
                release.await()
                completed.complete(Unit)
                true
            }
        }

        started.await()
        owner.cancel()
        release.complete(Unit)
        completed.await()
        owner.join()

        assertTrue(completed.isCompleted)
    }

    @Test
    fun committedAttempt_mapsOrdinaryFailureToFalse() = runBlocking {
        assertFalse(
            runCommittedDurableUiBooleanAttempt {
                throw IOException("storage unavailable")
            },
        )
    }

    @Test
    fun submittedCommittedAttempt_outlivesSubmittingCoroutine() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Boolean>()

        val submitter = launch {
            submitCommittedDurableUiBooleanAttempt(
                block = {
                    started.complete(Unit)
                    release.await()
                    true
                },
                onTerminal = terminal::complete,
            )
        }

        submitter.join()
        started.await()
        release.complete(Unit)

        assertTrue(terminal.await())
    }

    @Test fun typedAttemptPreservesOriginalCauseAndRollbackSuppression() = runBlocking {
        val primary = IOException("write failed")
        val rollback = SecurityException("rollback denied")
        val result = runDurableUiAttempt {
            persistRetentionTransaction(
                writeNewRecovery = { true },
                commitNewPreferences = { throw primary },
                restoreRecovery = { throw rollback },
                restorePreferences = { true },
            )
        }
        assertFalse(result.succeeded)
        org.junit.Assert.assertSame(primary, result.failure)
        org.junit.Assert.assertTrue(primary.suppressed.contains(rollback))
    }

    @Test fun falseOnlyAttemptDoesNotInventACause() = runBlocking {
        val result = runDurableUiAttempt { false }
        assertFalse(result.succeeded)
        org.junit.Assert.assertNull(result.failure)
    }

    @Test fun typedAttemptRethrowsCancellationWithoutReportingFailure() = runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("owner left")
        try {
            runDurableUiAttempt { throw cancelled }
            org.junit.Assert.fail("Cancellation was converted to failure")
        } catch (actual: kotlinx.coroutines.CancellationException) {
            org.junit.Assert.assertSame(cancelled, actual)
        }
    }
}
