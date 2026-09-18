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
}
