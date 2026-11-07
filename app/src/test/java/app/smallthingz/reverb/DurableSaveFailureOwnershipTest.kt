package app.smallthingz.reverb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DurableSaveFailureOwnershipTest {
    private fun pending(phase: SavePhase = SavePhase.RECOVERING) =
        PendingSave("job", "Saved audio", 123, 1000, phase)

    @Test fun oldWorkerFailureCannotOverwriteNewerCompletedHandoff() {
        val state = DurableSavePresentationState()
        val initial = requireNotNull(state.publish(pending()))
        val failed = requireNotNull(state.publish(pending(SavePhase.FAILED).copy(error = "old writer")))
        val workerCaught = CountDownLatch(1)
        val resumeOldCatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldCatch = executor.submit<Boolean> {
                workerCaught.countDown()
                check(resumeOldCatch.await(5, TimeUnit.SECONDS))
                assertNull(state.failIfCurrent(initial, "old Service catch"))
                state.claimFailureDelivery(failed)
            }
            assertTrue(workerCaught.await(5, TimeUnit.SECONDS))
            val completed = pending(SavePhase.SAVING).copy(
                publishedRecordingId = "recording", publishedRecordingIdentity = "identity",
            )
            state.publish(completed)
            state.remove("job")
            resumeOldCatch.countDown()
            assertFalse(oldCatch.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(completed.copy(terminallyRetired = true)), state.state.value)
        } finally {
            resumeOldCatch.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun equalValuedRecoveryInvalidatesPreviousReceiptDespiteFlowConflation() {
        val state = DurableSavePresentationState()
        val original = pending()
        val old = requireNotNull(state.publish(original))
        val newer = requireNotNull(state.publish(original.copy()))
        assertEquals(listOf(original), state.state.value)
        assertNull(state.failIfCurrent(old, "stale rejection"))
        val failure = requireNotNull(state.failIfCurrent(newer, "foreground unavailable"))
        assertEquals(SavePhase.FAILED, state.state.value.single().phase)
        assertEquals("foreground unavailable", state.state.value.single().error)
        assertTrue(state.claimFailureDelivery(failure))
        assertFalse(state.claimFailureDelivery(failure))
    }

    @Test fun preWriterRejectionRemainsRetryableAndLaterProgressRejectsOldFailure() {
        val state = DurableSavePresentationState()
        val queued = requireNotNull(state.publish(pending(SavePhase.QUEUED)))
        val failure = requireNotNull(state.failIfCurrent(queued, "executor rejected"))
        assertTrue(retryDurableSavesForLibraryRefresh(DurableSaveJobs.Readiness(true), state.state.value, true))
        val recovered = requireNotNull(state.publish(pending()))
        state.publish(pending(SavePhase.SAVING).copy(progress = .25f))
        assertNull(state.failIfCurrent(recovered, "older pre-writer error"))
        assertFalse(state.claimFailureDelivery(failure))
        assertEquals(.25f, state.state.value.single().progress)
    }

    @Test fun realCleanupFailureStillRestoresRecoveryAuthorityAfterCatalogAcknowledgement() {
        val state = DurableSavePresentationState()
        val published = pending(SavePhase.SAVING).copy(
            publishedRecordingId = "recording", publishedRecordingIdentity = "identity",
        )
        val old = requireNotNull(state.publish(published))
        state.acknowledgePublished(mapOf("recording" to "identity"))
        assertEquals(listOf(published), state.state.value)
        val cleanup = published.copy(phase = SavePhase.FAILED, error = "source cleanup pending")
        val receipt = requireNotNull(state.publish(cleanup))
        assertNull(state.failIfCurrent(old, "stale Service failure"))
        assertEquals(listOf(cleanup), state.state.value)
        assertTrue(state.claimFailureDelivery(receipt))
        state.reconcileAuthoritativeJobs(setOf("job"))
        assertEquals(listOf(cleanup), state.state.value)
    }

    @Test fun delayedMainCallbackCannotClaimFailureAfterNewRecoveryAndClaimDoesNotHoldCallbackLock() {
        val state = DurableSavePresentationState()
        val initial = requireNotNull(state.publish(pending()))
        val failure = requireNotNull(state.failIfCurrent(initial, "read failed"))
        // This closure models the posted main-handler delivery, not its enqueue time.
        val delayedDelivery = { state.claimFailureDelivery(failure) }
        state.publish(pending())
        assertFalse(delayedDelivery())
        val current = requireNotNull(state.publish(pending(SavePhase.FAILED)))
        assertTrue(state.claimFailureDelivery(current))
        val executor = Executors.newSingleThreadExecutor()
        try {
            // Arbitrary receiver work runs after the claim; another publisher can proceed.
            executor.submit { state.publish(pending()) }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertFalse(state.claimFailureDelivery(current))
    }
}
