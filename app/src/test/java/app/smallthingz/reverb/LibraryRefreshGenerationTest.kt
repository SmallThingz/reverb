package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRefreshGenerationTest {
    @Test
    fun deleteInvalidationRetiresPullSpinnerBeforeCatalogOnlyCompletion() {
        var refreshing = false
        val generations = LibraryRefreshGeneration { refreshing = it }
        val scan = generations.request(showSpinner = true)
        assertTrue(refreshing)

        // Undo expires while the scan is running. Successful deletion only reads listKnown;
        // no later refresh exists to take ownership of the old spinner.
        val deletion = generations.invalidate()
        assertFalse(refreshing)
        generations.complete(scan)
        assertTrue(generations.isCurrent(deletion))
        assertFalse(refreshing)
        assertTrue(libraryEmptyStateVisible(hasLoaded = true, listEmpty = true, isRefreshing = refreshing))
    }

    @Test
    fun hiddenSessionRetiresDeferredSpinnerBeforeAnyScanStarts() {
        // A pull gesture can request a spinner during the frame-first entry delay.
        var refreshing = true
        val generations = LibraryRefreshGeneration { refreshing = it }
        generations.invalidate()
        assertFalse(refreshing)

        val reopenedScan = generations.request(showSpinner = false)
        assertFalse(refreshing)
        generations.complete(reopenedScan)
        assertFalse(refreshing)
    }

    @Test
    fun hiddenScanCannotClearReopenedSessionsSpinner() {
        var refreshing = false
        val generations = LibraryRefreshGeneration { refreshing = it }
        val hiddenScan = generations.request(showSpinner = true)
        generations.invalidate()
        val reopenedScan = generations.request(showSpinner = true)

        generations.complete(hiddenScan)
        assertTrue(refreshing)
        assertFalse(generations.isCurrent(hiddenScan))
        generations.complete(reopenedScan)
        assertFalse(refreshing)
    }

    @Test
    fun latestSilentRefreshInheritsSpinnerUntilItsOwnTerminal() {
        var refreshing = false
        val generations = LibraryRefreshGeneration { refreshing = it }
        val pullScan = generations.request(showSpinner = true)
        val latestScan = generations.request(showSpinner = false)

        generations.complete(pullScan)
        assertTrue(refreshing)
        assertTrue(generations.isCurrent(latestScan))
        generations.complete(latestScan)
        assertFalse(refreshing)
    }

    @Test
    fun cancelledRefreshOwnerRunsTerminalSpinnerCleanup() = runBlocking {
        var refreshing = false
        val generations = LibraryRefreshGeneration { refreshing = it }
        val owner = CoroutineScope(coroutineContext + Job())
        val queue = LibraryRefreshQueue(owner) { true }
        val storageRead = CompletableDeferred<Unit>()
        val scan = generations.request(showSpinner = true)
        queue.request {
            try {
                storageRead.await()
            } finally {
                generations.complete(scan)
            }
        }
        assertTrue(refreshing)
        owner.cancel()
        owner.coroutineContext.job.join()
        assertFalse(refreshing)
    }
}
