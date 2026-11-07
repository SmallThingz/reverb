package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class DurableSaveTerminalPresentationTest {
    private val output = RecordingEntity("output", "Recording", "audio/wav", 1L, 1000L, 100L, "WAV",
        RecordingStorageType.FILE, "/managed", "revision")
    private val catalog = mapOf(output.id to output.fileIdentity)
    private fun job(progress: Float = .94f) = PendingSave("job", "Recording", 1L, 1000L,
        SavePhase.SAVING, progress, publishedRecordingId = output.id,
        publishedRecordingIdentity = output.fileIdentity)

    @Test fun everyPublishedPreRetirementStageRetainsOnePendingRowAndProgressNotification() {
        val state = DurableSavePresentationState()
        for (progress in listOf(.94f, .96f, .98f, 1f)) {
            val pending = job(progress)
            val receipt = requireNotNull(state.publish(pending))
            state.acknowledgePublished(catalog)
            assertEquals(listOf(pending), state.state.value)
            assertEquals(listOf(LibraryListItem.Pending(pending)), buildPendingSaveItems(state.state.value, catalog))
            assertTrue(recordingOwnedByPendingSave(output, pendingSaveOutputIdentities(state.state.value)))
            assertTrue(durableSaveNeedsProgressNotification(pending))
            assertTrue(requireNotNull(renderedSaveNotification(pending).percent) <= 99)
            assertFalse(durableSaveCompletionNeedsCatalogRefresh(emptyList(), state.state.value, emptyMap()))
            // Observing the catalog must not revoke the worker's current failure receipt.
            assertNotNull(state.failIfCurrent(receipt, "late cleanup failure"))
        }
    }

    @Test fun retirementEmitsTerminalEvenAfterEarlierCatalogObservationAndConflation() {
        val state = DurableSavePresentationState()
        state.publish(job())
        state.acknowledgePublished(catalog)
        state.publish(job(.98f))
        state.remove("job")
        val terminal = state.state.value.single()
        assertTrue(terminal.terminallyRetired)
        assertFalse(durableSaveNeedsProgressNotification(terminal))
        assertTrue(buildPendingSaveItems(listOf(terminal), catalog).isEmpty())
        assertFalse(recordingOwnedByPendingSave(output, pendingSaveOutputIdentities(listOf(terminal))))
        assertTrue(durableSaveCompletionNeedsCatalogRefresh(emptyList(), listOf(terminal), emptyMap()))
        state.acknowledgePublished(catalog)
        assertTrue(state.state.value.isEmpty())
    }

    @Test fun failedCleanupAndRecoveryContinueToOwnOnlyTheirExactOutput() {
        val state = DurableSavePresentationState()
        state.publish(job(.98f))
        state.acknowledgePublished(catalog)
        for (phase in listOf(SavePhase.FAILED, SavePhase.RECOVERING)) {
            state.publish(job().copy(phase = phase, error = "cleanup pending"))
            state.acknowledgePublished(catalog)
            val pending = state.state.value.single()
            assertFalse(pending.terminallyRetired)
            assertTrue(durableSaveNeedsProgressNotification(pending))
            assertEquals(1, buildPendingSaveItems(state.state.value, catalog).size)
            val owned = pendingSaveOutputIdentities(state.state.value)
            assertTrue(recordingOwnedByPendingSave(output, owned))
            assertFalse(recordingOwnedByPendingSave(output.copy(fileIdentity = "replacement"), owned))
            assertFalse(recordingOwnedByPendingSave(output.copy(id = "unrelated"), owned))
        }
    }

    @Test fun publicationCannotForgeRetirementAndTerminalCacheCannotEvictRepublishedWork() {
        val state = DurableSavePresentationState()
        state.publish(job().copy(terminallyRetired = true))
        assertFalse(state.state.value.single().terminallyRetired)
        state.remove("job")
        state.publish(job().copy(phase = SavePhase.RECOVERING))
        repeat(140) { index ->
            val id = "other-$index"
            state.publish(job().copy(id = id, publishedRecordingId = id))
            state.remove(id)
        }
        assertTrue(state.state.value.any { it.id == "job" && !it.terminallyRetired })
    }
}
