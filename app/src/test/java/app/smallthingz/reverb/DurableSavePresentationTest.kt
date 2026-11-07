package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableSavePresentationTest {
    @Test
    fun failedSaveSectionIncludesOneExplicitRetryActionWithOrWithoutFinishedRecordings() {
        val failed = PendingSave("failed", "Recording", 1L, 1000L, SavePhase.FAILED)
        val anotherFailure = failed.copy(id = "another")
        val completed = failed.copy(id = "completed", phase = SavePhase.SAVING,
            publishedRecordingId = "finished", publishedRecordingIdentity = "revision", terminallyRetired = true)
        val pendingOnly = buildPendingSaveSection(listOf(failed, anotherFailure), emptyMap())
        assertEquals(LibraryListItem.RetryFailedSaves, pendingOnly.first())
        assertEquals(1, pendingOnly.count { it === LibraryListItem.RetryFailedSaves })
        assertEquals(listOf(failed, anotherFailure), pendingOnly.filterIsInstance<LibraryListItem.Pending>().map { it.save })
        val mixed = buildPendingSaveSection(listOf(completed, failed), mapOf("finished" to "revision"))
        assertEquals(listOf(LibraryListItem.RetryFailedSaves, LibraryListItem.Pending(failed)), mixed)
        assertEquals("save-jobs-retry", libraryListItemKey(mixed.first()))
        assertTrue(buildPendingSaveSection(listOf(completed), mapOf("finished" to "revision")).isEmpty())
    }

    @Test
    fun failedSaveRetryRequiresExplicitActivationAndCurrentInteractiveFailure() {
        val failed = PendingSave("failed", "Recording", 1L, 1000L, SavePhase.FAILED)
        var retries = 0
        val request = {
            if (retryDurableSavesForLibraryRefresh(DurableSaveJobs.Readiness(loaded = true),
                    listOf(failed), userInitiated = true)) retries++
        }
        repeat(3) { buildPendingSaveSection(listOf(failed), emptyMap()) }
        assertEquals(0, retries)
        assertFalse(performLibraryFailedSaveRetry(false, listOf(failed), retry = request))
        assertFalse(performLibraryFailedSaveRetry(true, listOf(failed.copy(phase = SavePhase.RECOVERING)), retry = request))
        assertFalse(performLibraryFailedSaveRetry(true, emptyList(), retry = request))
        assertEquals(0, retries)
        assertTrue(performLibraryFailedSaveRetry(true, listOf(failed), retry = request))
        assertEquals(1, retries)
        assertFalse(retryDurableSavesForLibraryRefresh(DurableSaveJobs.Readiness(loaded = true),
            listOf(failed), userInitiated = false))
    }

    @Test
    fun recoveryAndCatalogFailuresHaveOneAccessibleRetryAcrossVisibleRowCombinations() {
        val ready = DurableSaveJobs.Readiness(loaded = true)
        val rootFailure = ready.copy(recoveryFailure = "Root unavailable")
        val queued = PendingSave("job", "Recording", 1L, 1000L, SavePhase.QUEUED)
        val published = queued.copy(phase = SavePhase.SAVING,
            publishedRecordingId = "finished", publishedRecordingIdentity = "revision", terminallyRetired = true)
        val cases = listOf(emptyList(), listOf(queued), listOf(queued.copy(phase = SavePhase.FAILED)),
            listOf(published), listOf(queued.copy(phase = SavePhase.RECOVERING)))
        for (jobs in cases) for (finished in listOf(emptyMap(), mapOf("finished" to "revision"))) {
            val rootRows = buildPendingSaveSection(jobs, finished, rootFailure)
            assertEquals(1, rootRows.count { it === LibraryListItem.RetryFailedSaves })
            val both = listOf(LibraryListItem.SaveRecoveryStatus("Root unavailable")) +
                buildPendingSaveSection(jobs, finished, rootFailure, "Catalog unavailable")
            assertEquals(1, both.count { it === LibraryListItem.RetryFailedSaves })
            assertEquals(2, both.filterIsInstance<LibraryListItem.SaveRecoveryStatus>().size)
            assertEquals(both.size, both.map(::libraryListItemKey).toSet().size)
            assertEquals(buildPendingSaveItems(jobs, finished), both.filterIsInstance<LibraryListItem.Pending>())
        }
        val catalogRows = buildPendingSaveSection(listOf(queued), emptyMap(), ready, "Catalog unavailable")
        assertEquals(listOf(LibraryListItem.SaveRecoveryStatus("Catalog unavailable", catalog = true),
            LibraryListItem.RetryFailedSaves, LibraryListItem.Pending(queued)), catalogRows)
        assertEquals(1, buildPendingSaveSection(emptyList(), mapOf("finished" to "revision"), ready,
            "Catalog unavailable").count { it === LibraryListItem.RetryFailedSaves })
        // With no rows or recovery banner, the existing full-body catalog error owns Retry.
        assertTrue(buildPendingSaveSection(emptyList(), emptyMap(), ready, "Catalog unavailable").isEmpty())
        assertEquals(listOf(LibraryListItem.Pending(queued)), buildPendingSaveSection(listOf(queued), emptyMap(), ready))
        assertTrue(buildPendingSaveSection(listOf(published), mapOf("finished" to "revision"), ready).isEmpty())
        assertTrue(buildPendingSaveSection(emptyList(), emptyMap(), DurableSaveJobs.Readiness()).isEmpty())
    }

    @Test
    fun retryActivationUsesCurrentFailureReasonsAndRejectsRecoveredLoadingOrInactiveState() {
        val healthy = DurableSaveJobs.Readiness(loaded = true)
        val failedRoot = healthy.copy(recoveryFailure = "Unavailable")
        val queued = PendingSave("job", "Recording", 1L, 1000L, SavePhase.QUEUED)
        var refreshes = 0
        var recoveryRequests = 0
        fun activate(active: Boolean, readiness: DurableSaveJobs.Readiness, jobs: List<PendingSave>, catalog: Boolean): Boolean =
            performLibraryFailedSaveRetry(active, jobs, readiness, catalog) {
                refreshes++
                if (retryDurableSavesForLibraryRefresh(readiness, jobs, userInitiated = true)) recoveryRequests++
            }
        for (jobs in listOf(emptyList(), listOf(queued), listOf(queued.copy(phase = SavePhase.FAILED)))) {
            assertFalse(activate(false, failedRoot, jobs, true))
            assertTrue(activate(true, failedRoot, jobs, false))
        }
        assertEquals(3, refreshes)
        assertEquals(3, recoveryRequests)
        // Catalog-only retry refreshes the catalog without requesting durable recovery.
        assertTrue(activate(true, healthy, listOf(queued), true))
        assertEquals(4, refreshes)
        assertEquals(3, recoveryRequests)
        assertFalse(activate(true, healthy, emptyList(), false))
        assertFalse(activate(true, healthy, listOf(queued), false))
        assertFalse(activate(true, DurableSaveJobs.Readiness(), emptyList(), false))
        assertEquals(4, refreshes)
        // Clearing either error keeps the other action; clearing both removes it.
        assertEquals(1, buildPendingSaveSection(listOf(queued), emptyMap(), failedRoot).count { it === LibraryListItem.RetryFailedSaves })
        assertEquals(1, buildPendingSaveSection(listOf(queued), emptyMap(), healthy, "Catalog unavailable").count { it === LibraryListItem.RetryFailedSaves })
        assertFalse(buildPendingSaveSection(listOf(queued), emptyMap(), healthy).contains(LibraryListItem.RetryFailedSaves))
    }

    @Test
    fun conflatedFirstPublicationRefreshesCatalogWithoutRequiringAnEarlierPendingEmission() {
        val completed = PendingSave("job", "Recording", 1L, 1000L, SavePhase.SAVING, progress = 1f,
            publishedRecordingId = "output", publishedRecordingIdentity = "revision", terminallyRetired = true)
        assertTrue(durableSaveCompletionNeedsCatalogRefresh(emptyList(), listOf(completed), emptyMap()))
        val otherJob = completed.copy(id = "other", publishedRecordingId = null, publishedRecordingIdentity = null)
        assertTrue(durableSaveCompletionNeedsCatalogRefresh(listOf(otherJob), listOf(completed), emptyMap()))
        assertTrue(durableSaveCompletionNeedsCatalogRefresh(listOf(otherJob), listOf(otherJob, completed), emptyMap()))
        assertFalse(durableSaveCompletionNeedsCatalogRefresh(listOf(completed), listOf(completed.copy(progress = .9f)), emptyMap()))
        assertFalse(durableSaveCompletionNeedsCatalogRefresh(emptyList(), listOf(completed), mapOf("output" to "revision")))
        assertTrue(durableSaveCompletionNeedsCatalogRefresh(emptyList(), listOf(completed), mapOf("output" to "different-revision")))
        assertTrue(durableSaveCompletionNeedsCatalogRefresh(listOf(completed.copy(phase = SavePhase.FAILED)), listOf(completed), emptyMap()))
    }

    @Test
    fun failedPublishedSourceRemainsVisibleWithoutAutomaticCompletionRefreshLoop() {
        val failed = PendingSave("job", "Recording", 1L, 1000L, SavePhase.FAILED,
            publishedRecordingId = "output", publishedRecordingIdentity = "revision")
        assertFalse(durableSaveCompletionNeedsCatalogRefresh(emptyList(), listOf(failed), emptyMap()))
        assertFalse(durableSaveCompletionNeedsCatalogRefresh(listOf(failed), listOf(failed.copy(error = "Unavailable")), emptyMap()))
        assertEquals(listOf(LibraryListItem.Pending(failed)),
            buildPendingSaveItems(listOf(failed), mapOf("output" to "revision")))
    }

    @Test
    fun authoritativeMembershipRemovesOnlyAbsentFailuresAndRetainsCompletedHandoff() {
        val state = DurableSavePresentationState()
        val absent = PendingSave("absent", "Recording", 1L, 1000L, SavePhase.FAILED)
        val present = absent.copy(id = "present")
        val queued = absent.copy(id = "queued", phase = SavePhase.QUEUED)
        val saving = absent.copy(id = "saving", phase = SavePhase.SAVING)
        val completed = saving.copy(id = "completed", publishedRecordingId = "output",
            publishedRecordingIdentity = "revision")
        listOf(absent, present, queued, saving, completed).forEach(state::publish)
        state.remove(completed.id)
        state.reconcileAuthoritativeJobs(setOf(present.id))
        assertEquals(setOf(present, queued, saving, completed.copy(terminallyRetired = true)), state.state.value.toSet())
        state.acknowledgePublished(mapOf("output" to "revision"))
        assertEquals(setOf(present, queued, saving), state.state.value.toSet())
        state.publish(absent.copy(phase = SavePhase.QUEUED))
        assertTrue(state.state.value.any { it.id == absent.id })
    }

    @Test
    fun onlyExplicitRefreshRetriesFailedMaterializationAfterSuccessfulRecovery() {
        val ready = DurableSaveJobs.Readiness(loaded = true)
        val failed = PendingSave("job", "Recording", 1L, 1000L, SavePhase.FAILED)
        assertTrue(retryDurableSavesForLibraryRefresh(ready, listOf(failed), userInitiated = true))
        assertFalse(retryDurableSavesForLibraryRefresh(ready, listOf(failed), userInitiated = false))
        assertFalse(retryDurableSavesForLibraryRefresh(ready, listOf(failed.copy(phase = SavePhase.SAVING)), userInitiated = true))
        assertTrue(retryDurableSavesForLibraryRefresh(DurableSaveJobs.Readiness(), emptyList(), userInitiated = false))
        assertTrue(retryDurableSavesForLibraryRefresh(ready.copy(recoveryFailure = "Unavailable"), emptyList(), userInitiated = false))
    }

    @Test
    fun conflatedPublicationAndRetirementKeepTheFinalIdUntilCatalogObservation() {
        val state = DurableSavePresentationState()
        val queued = PendingSave("job", "Recording", 1L, 1000L, SavePhase.QUEUED)
        state.publish(queued)
        val finished = queued.copy(phase = SavePhase.SAVING, progress = 1f, publishedRecordingId = "output",
            publishedRecordingIdentity = "revision-one")
        state.publish(finished)
        state.remove("job")
        // A collector resuming only now must still receive the exact mapping.
        assertEquals(listOf(finished.copy(terminallyRetired = true)), state.state.value)
        state.acknowledgePublished(mapOf("unrelated" to "revision-one"))
        assertEquals(listOf(finished.copy(terminallyRetired = true)), state.state.value)
        state.acknowledgePublished(mapOf("output" to "revision-one"))
        assertTrue(state.state.value.isEmpty())
        // Removing the finished catalog row later cannot resurrect the pending card.
        state.acknowledgePublished(emptyMap())
        assertTrue(state.state.value.isEmpty())
    }

    @Test
    fun catalogObservationBeforeWorkerRetirementKeepsPendingUntilTerminalEmission() {
        val state = DurableSavePresentationState()
        val finished = PendingSave("job", "Recording", 1L, 1000L, SavePhase.SAVING,
            publishedRecordingId = "output", publishedRecordingIdentity = "revision-one")
        state.publish(finished)
        state.acknowledgePublished(mapOf("output" to "revision-one"))
        assertEquals(listOf(finished), state.state.value)
        state.publish(finished.copy(progress = 1f))
        state.remove("job")
        assertTrue(state.state.value.single().terminallyRetired)
        state.acknowledgePublished(mapOf("output" to "revision-one"))
        assertTrue(state.state.value.isEmpty())
    }

    @Test
    fun completedHandoffCacheIsBoundedWithoutEvictingUnfinishedOrFailedSources() {
        val state = DurableSavePresentationState()
        val unfinished = PendingSave("active", "Recording", 1L, 1000L, SavePhase.SAVING)
        val failed = unfinished.copy(id = "failed", phase = SavePhase.FAILED)
        state.publish(unfinished)
        state.publish(failed)
        repeat(140) { index ->
            state.publish(unfinished.copy(id = "job-$index", publishedRecordingId = "output-$index"))
            state.remove("job-$index")
        }
        assertEquals(130, state.state.value.size)
        assertTrue(unfinished in state.state.value)
        assertTrue(failed in state.state.value)
        assertEquals(128, state.state.value.count { it.publishedRecordingId != null })
    }

    @Test
    fun publishedPendingRowDisappearsOnlyWhenItsExactFinishedIdIsVisible() {
        val saving = PendingSave("job-one", "Recording", 1L, 1000L, SavePhase.SAVING)
        val published = saving.copy(publishedRecordingId = "output-one", publishedRecordingIdentity = "revision-one",
            terminallyRetired = true)
        assertEquals(listOf(LibraryListItem.Pending(saving)), buildPendingSaveItems(listOf(saving), mapOf("other" to "revision-one")))
        assertEquals(listOf(LibraryListItem.Pending(published)), buildPendingSaveItems(listOf(published), mapOf("other" to "revision-one")))
        assertTrue(buildPendingSaveItems(listOf(published), mapOf("output-one" to "revision-one")).isEmpty())
    }

    @Test
    fun overlappingJobsKeepIndependentStableKeysAndFailureDoesNotHideSources() {
        val first = PendingSave("one", "Same name", 1L, 1000L, SavePhase.FAILED, error = "Storage unavailable")
        val second = first.copy(id = "two", phase = SavePhase.QUEUED, error = null)
        val rows = buildPendingSaveItems(listOf(first, second), emptyMap())
        assertEquals(listOf("save-job:one", "save-job:two"), rows.map(::libraryListItemKey))
        assertEquals(first, rows.first().save)
    }

    @Test
    fun sameIdReplacementOrMissingIdentityCannotHideOrAcknowledgeRetainedSourceJob() {
        val state = DurableSavePresentationState()
        val pending = PendingSave("job", "Recording", 1L, 1000L, SavePhase.SAVING,
            publishedRecordingId = "output", publishedRecordingIdentity = "original-object-revision")
        state.publish(pending)
        for (catalog in listOf(mapOf("output" to "replacement-object-revision"), mapOf("output" to ""))) {
            assertEquals(listOf(LibraryListItem.Pending(pending)), buildPendingSaveItems(listOf(pending), catalog))
            state.acknowledgePublished(catalog)
            assertEquals(listOf(pending), state.state.value)
        }
        val uncertain = pending.copy(publishedRecordingIdentity = null)
        state.publish(uncertain)
        state.acknowledgePublished(mapOf("output" to "original-object-revision"))
        assertEquals(listOf(uncertain), state.state.value)
    }

    @Test
    fun latePublicationFailurePreservesRecoveryCardDespiteEarlierCatalogObservation() {
        val state = DurableSavePresentationState()
        val published = PendingSave("job", "Recording", 1L, 1000L, SavePhase.SAVING,
            publishedRecordingId = "output", publishedRecordingIdentity = "original-object-revision")
        state.publish(published)
        state.acknowledgePublished(mapOf("output" to "original-object-revision"))
        assertEquals(listOf(published), state.state.value)
        val failed = published.copy(phase = SavePhase.FAILED, error = "Output changed before source cleanup")
        state.publish(failed)
        state.acknowledgePublished(mapOf("output" to "replacement-object-revision"))
        assertEquals(listOf(failed), state.state.value)
        assertEquals(listOf(LibraryListItem.Pending(failed)),
            buildPendingSaveItems(listOf(failed), mapOf("output" to "original-object-revision")))
    }
}
