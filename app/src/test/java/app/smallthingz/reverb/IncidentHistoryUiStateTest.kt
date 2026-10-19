package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentHistoryUiStateTest {
    @Test
    fun initialReadFailureNeverClaimsEmptyHistoryOrEnablesMutations() {
        val initial = IncidentHistoryUiState()
        assertEquals(IncidentHistoryLoadStatus.LOADING, initial.status)
        assertFalse(initial.showEmptyHistory)
        assertFalse(initial.canMutate)

        val failed = initial.loadFailed()
        assertEquals(IncidentHistoryLoadStatus.UNAVAILABLE, failed.status)
        assertFalse(failed.showEmptyHistory)
        assertFalse(failed.canMutate)
        assertEquals(failed, failed.loadFailed())
    }

    @Test
    fun previouslyEmptyHistoryIsNotAnEmptyClaimWhileRefreshIsUncertain() {
        val ready = IncidentHistoryUiState().loadSucceeded(prepareIncidentHistory(emptyList()))
        assertTrue(ready.showEmptyHistory)
        assertTrue(ready.canMutate)

        val refreshing = ready.loadStarted()
        assertFalse(refreshing.showEmptyHistory)
        assertFalse(refreshing.canMutate)
        val failed = refreshing.loadFailed()
        assertFalse(failed.showEmptyHistory)
        assertFalse(failed.canMutate)
    }

    @Test
    fun failedRefreshRetainsLoadedRowsAndAlertWithoutPermittingAnotherMutation() {
        val loaded = prepareIncidentHistory(listOf(RecordingIncident(occurredAtMillis = 1_000L)))
        val ready = IncidentHistoryUiState().loadSucceeded(loaded)
        val refreshing = ready.loadStarted()
        assertSame(loaded, refreshing.history)
        assertFalse(refreshing.canMutate)

        val failed = refreshing.loadFailed()
        assertSame(loaded, failed.history)
        assertTrue(failed.history.hasAlert)
        assertFalse(failed.canMutate)
        assertFalse(failed.showEmptyHistory)
        assertEquals(failed, failed.loadFailed())
    }

    @Test
    fun successfulRecoveryReplacesStaleRowsAndRestoresAuthoritativeEmptyState() {
        val stale = IncidentHistoryUiState()
            .loadSucceeded(prepareIncidentHistory(listOf(RecordingIncident(occurredAtMillis = 1_000L))))
            .loadStarted()
            .loadFailed()
        val recovered = stale.loadSucceeded(prepareIncidentHistory(emptyList()))
        assertEquals(IncidentHistoryLoadStatus.READY, recovered.status)
        assertTrue(recovered.canMutate)
        assertTrue(recovered.showEmptyHistory)
        assertFalse(recovered.history.hasAlert)
        assertTrue(recovered.history.rows.isEmpty())
    }
}
