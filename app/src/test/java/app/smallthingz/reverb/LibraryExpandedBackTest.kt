package app.smallthingz.reverb

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExpandedBackTest {
    // Exercise the real dispatcher registration/removal order used by FilesScreen:
    // Library root, persistent fallback, then lazily attached inline row callbacks.
    private class Host {
        val events = mutableListOf<String>()
        val mutations = recordingMutations
        val dispatcher = OnBackPressedDispatcher(Runnable { events += "system" })
        var active = true
        var selection = false
        var libraryOpen = true
        var expanded: InlinePlaybackRowOwner? = null
            private set
        private val rows = mutableListOf<Row>()
        private val library = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                libraryOpen = false
                events += "library"
            }
        }
        val fallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                finishLibraryExpandedBack(
                    owner = expanded,
                    isCurrentOwner = ::isCurrent,
                    isMutationActive = mutations::isActive,
                    collapse = {
                        expanded = null
                        events += "fallback"
                    },
                )
                syncEnabled()
            }
        }

        init {
            dispatcher.addCallback(library)
            dispatcher.addCallback(fallback)
        }

        fun isCurrent(owner: InlinePlaybackRowOwner): Boolean =
            active && !selection && expanded === owner

        fun expand(owner: InlinePlaybackRowOwner) {
            expanded = owner
            syncEnabled()
        }

        inner class Row(val owner: InlinePlaybackRowOwner, var trimMode: Boolean) {
            // Match the production row: disposal loses this local flag, while the
            // production completion policy must consult the process reservation.
            var trimSaving = false
            val callback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    finishInlineBackForCurrentRow(
                        owner = owner,
                        isCurrentOwner = ::isCurrent,
                        trimSaving = trimSaving,
                        trimMode = trimMode,
                        leaveTrim = {
                            trimMode = false
                            events += "trim"
                        },
                        collapse = {
                            expanded = null
                            events += "row"
                        },
                    )
                    syncEnabled()
                }
            }
        }

        fun attach(owner: InlinePlaybackRowOwner, trimMode: Boolean = false): Row =
            Row(owner, trimMode).also {
                rows += it
                dispatcher.addCallback(it.callback)
                syncEnabled()
            }

        fun detach(row: Row) {
            row.callback.remove()
            rows.remove(row)
            syncEnabled()
        }

        fun syncEnabled() {
            library.isEnabled = active && !selection && expanded == null
            fallback.isEnabled = active && !selection && expanded != null
            rows.forEach { it.callback.isEnabled = isCurrent(it.owner) }
        }
    }

    @Test
    fun currentVisiblePlayer_keepsChildPriorityAndNextBackClosesLibrary() {
        val host = Host()
        val owner = InlinePlaybackRowOwner("a")
        host.expand(owner)
        host.attach(owner)

        host.dispatcher.onBackPressed()

        assertEquals(listOf("row"), host.events)
        assertNull(host.expanded)
        assertTrue(host.libraryOpen)
        host.dispatcher.onBackPressed()
        assertEquals(listOf("row", "library"), host.events)
        assertFalse(host.libraryOpen)
    }

    @Test
    fun scrollDisposesExpandedRow_fallbackCollapsesOwnerBeforeLibraryBack() {
        val host = Host()
        val owner = InlinePlaybackRowOwner("a")
        host.expand(owner)
        val row = host.attach(owner)
        host.detach(row)
        assertSame(owner, host.expanded)
        assertTrue(host.dispatcher.hasEnabledCallbacks())

        host.dispatcher.onBackPressed()

        assertEquals(listOf("fallback"), host.events)
        assertNull(host.expanded)
        assertTrue(host.libraryOpen)
        host.dispatcher.onBackPressed()
        assertEquals(listOf("fallback", "library"), host.events)
        assertFalse(host.libraryOpen)
    }

    @Test
    fun visibleTrim_keepsChildActionThenOffscreenFallbackRetiresExpansion() {
        val host = Host()
        val owner = InlinePlaybackRowOwner("a")
        host.expand(owner)
        val row = host.attach(owner, trimMode = true)

        host.dispatcher.onBackPressed()

        assertEquals(listOf("trim"), host.events)
        assertFalse(row.trimMode)
        assertSame(owner, host.expanded)
        host.detach(row)
        host.dispatcher.onBackPressed()
        assertEquals(listOf("trim", "fallback"), host.events)
        assertNull(host.expanded)
    }

    @Test
    fun reattachedRow_restoresChildPriorityWithoutReregisteringFallback() {
        val host = Host()
        val owner = InlinePlaybackRowOwner("a")
        host.expand(owner)
        host.detach(host.attach(owner))
        host.attach(owner, trimMode = true)

        host.dispatcher.onBackPressed()

        assertEquals(listOf("trim"), host.events)
        assertSame(owner, host.expanded)
        host.dispatcher.onBackPressed()
        assertEquals(listOf("trim", "row"), host.events)
    }

    @Test
    fun aToBToA_staleRowDisposalCannotRetireReopenedOwnerOrItsFallback() {
        val host = Host()
        val firstA = InlinePlaybackRowOwner("a")
        host.expand(firstA)
        val oldA = host.attach(firstA)
        val b = InlinePlaybackRowOwner("b")
        host.expand(b)
        val oldB = host.attach(b)
        val reopenedA = InlinePlaybackRowOwner("a")
        host.expand(reopenedA)
        val freshA = host.attach(reopenedA, trimMode = true)

        oldA.callback.handleOnBackPressed()
        oldB.callback.handleOnBackPressed()
        host.detach(oldA)
        host.detach(oldB)
        assertSame(reopenedA, host.expanded)
        assertEquals(emptyList<String>(), host.events)

        host.dispatcher.onBackPressed()
        assertEquals(listOf("trim"), host.events)
        assertSame(reopenedA, host.expanded)
        host.detach(freshA)
        host.dispatcher.onBackPressed()
        assertEquals(listOf("trim", "fallback"), host.events)
        assertNull(host.expanded)
    }

    @Test
    fun committedTrimAfterRowDisposal_blocksBackUntilLiveReservationFinishes() {
        val host = Host()
        val owner = InlinePlaybackRowOwner("offscreen-trim-save")
        host.expand(owner)
        val row = host.attach(owner, trimMode = true)
        val save = checkNotNull(host.mutations.tryBegin(owner.recordingId))
        try {
            host.detach(row)

            // No collected-flow/Compose update is needed for the terminal-time lock check.
            host.dispatcher.onBackPressed()
            assertSame(owner, host.expanded)
            assertTrue(host.libraryOpen)
            assertEquals(emptyList<String>(), host.events)
            host.mutations.finish(save)
            host.dispatcher.onBackPressed()
            assertEquals(listOf("fallback"), host.events)
            assertNull(host.expanded)
        } finally {
            host.mutations.finish(save)
        }
    }

    @Test
    fun reattachedRow_localSavingResetCannotBypassPendingTrimReservation() {
        val host = Host()
        val owner = InlinePlaybackRowOwner("reattached-trim-save")
        host.expand(owner)
        val original = host.attach(owner, trimMode = true).apply { trimSaving = true }
        val save = checkNotNull(host.mutations.tryBegin(owner.recordingId))
        try {
            host.detach(original)
            val reattached = host.attach(owner)
            assertFalse(reattached.trimSaving)

            host.dispatcher.onBackPressed()

            assertSame(owner, host.expanded)
            assertTrue(host.libraryOpen)
            assertEquals(emptyList<String>(), host.events)
            host.mutations.finish(save)
            host.dispatcher.onBackPressed()
            assertNull(host.expanded)
            assertEquals(listOf("row"), host.events)
        } finally {
            host.mutations.finish(save)
        }
    }

    @Test
    fun reattachedRow_predictiveCommitRechecksPendingReservationAtSettlement() = runBlocking {
        val host = Host()
        val owner = InlinePlaybackRowOwner("reattached-predictive-save")
        host.expand(owner)
        val save = checkNotNull(host.mutations.tryBegin(owner.recordingId))
        host.detach(host.attach(owner, trimMode = true).apply { trimSaving = true })
        val reattached = host.attach(owner)
        val settled = CompletableDeferred<Unit>()
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0.4f,
                animateToEnd = { settled.await() },
                onBack = { reattached.callback.handleOnBackPressed() },
            )
        }
        try {
            settled.complete(Unit)
            back.join()

            assertFalse(reattached.trimSaving)
            assertSame(owner, host.expanded)
            assertEquals(emptyList<String>(), host.events)
            host.mutations.finish(save)
            host.dispatcher.onBackPressed()
            assertNull(host.expanded)
            assertEquals(listOf("row"), host.events)
        } finally {
            settled.complete(Unit)
            back.cancel()
            host.mutations.finish(save)
        }
    }

    @Test
    fun reattachedRow_oldPredictiveCommitCannotCollapseReopenedOwnerAfterSaveFinishes() = runBlocking {
        val host = Host()
        val owner = InlinePlaybackRowOwner("reattached-stale-save")
        host.expand(owner)
        host.detach(host.attach(owner))
        val reattached = host.attach(owner)
        val save = checkNotNull(host.mutations.tryBegin(owner.recordingId))
        val settled = CompletableDeferred<Unit>()
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0.5f,
                animateToEnd = { settled.await() },
                onBack = { reattached.callback.handleOnBackPressed() },
            )
        }
        try {
            val reopened = InlinePlaybackRowOwner(owner.recordingId)
            host.expand(reopened)
            host.attach(reopened)
            host.mutations.finish(save)
            settled.complete(Unit)
            back.join()

            assertSame(reopened, host.expanded)
            assertEquals(emptyList<String>(), host.events)
            host.dispatcher.onBackPressed()
            assertNull(host.expanded)
            assertEquals(listOf("row"), host.events)
        } finally {
            settled.complete(Unit)
            back.cancel()
            host.mutations.finish(save)
        }
    }

    @Test
    fun unrelatedReservation_preservesVisibleTrimThenPlayerBack() {
        val host = Host()
        val owner = InlinePlaybackRowOwner("visible-unreserved-row")
        host.expand(owner)
        val row = host.attach(owner, trimMode = true)
        val otherSave = checkNotNull(host.mutations.tryBegin("different-recording-save"))
        try {
            host.dispatcher.onBackPressed()
            assertFalse(row.trimMode)
            assertSame(owner, host.expanded)
            assertEquals(listOf("trim"), host.events)

            host.dispatcher.onBackPressed()
            assertNull(host.expanded)
            assertEquals(listOf("trim", "row"), host.events)
        } finally {
            host.mutations.finish(otherSave)
        }
    }

    @Test
    fun staleEnabledFallback_rechecksHiddenSelectionAndExactOwnerBeforeCollapsing() {
        val host = Host()
        val firstA = InlinePlaybackRowOwner("a")
        host.expand(firstA)
        host.active = false
        host.fallback.handleOnBackPressed()
        assertSame(firstA, host.expanded)
        host.active = true
        host.selection = true
        host.fallback.handleOnBackPressed()
        assertSame(firstA, host.expanded)
        host.selection = false
        val reopenedA = InlinePlaybackRowOwner("a")
        host.expand(reopenedA)

        assertFalse(finishLibraryExpandedBack(
            owner = firstA,
            isCurrentOwner = host::isCurrent,
            isMutationActive = host.mutations::isActive,
            collapse = { error("retired expansion must not collapse the reopened row") },
        ))
        assertFalse(finishLibraryExpandedBack(
            owner = null,
            isCurrentOwner = host::isCurrent,
            isMutationActive = host.mutations::isActive,
            collapse = { error("no expansion must not collapse") },
        ))
        assertSame(reopenedA, host.expanded)
        assertEquals(emptyList<String>(), host.events)
    }
}
