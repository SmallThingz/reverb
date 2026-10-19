package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDeletionStateTest {
    @Test
    fun rapidDeletesExtendUndoWithoutBlockingTheNextDelete() {
        val state = LibraryDeletionState()
        val first = state.extendUndoWindow()!!
        assertTrue(state.acceptsDeletions)
        val second = state.extendUndoWindow()!!
        assertTrue(state.acceptsDeletions)
        val third = state.extendUndoWindow()!!

        // Expired callbacks from either replaced timer must not commit the cumulative batch.
        assertFalse(state.beginForegroundCommit(first))
        assertFalse(state.beginForegroundCommit(second))
        assertTrue(state.acceptsDeletions)
        assertTrue(state.beginForegroundCommit(third))
        assertFalse(state.acceptsDeletions)
    }

    @Test
    fun undoAfterRapidDeletesClosesOnlyTheDisplayedWindow() {
        val state = LibraryDeletionState()
        val first = state.extendUndoWindow()!!
        val displayed = state.extendUndoWindow()!!
        assertFalse(state.undo(first))
        assertTrue(state.undo(displayed))
        assertFalse(state.beginForegroundCommit(displayed))
        assertFalse(state.undo(displayed))
        assertTrue(state.acceptsDeletions)
    }

    @Test
    fun delayedUndoCannotRestoreANewerDeletionBatch() {
        val state = LibraryDeletionState()
        val previous = state.extendUndoWindow()!!
        assertTrue(state.beginForegroundCommit(previous))
        state.completeForegroundCommit()
        val current = state.extendUndoWindow()!!

        assertFalse(state.undo(previous))
        assertFalse(state.beginForegroundCommit(previous))
        assertTrue(state.undo(current))
    }

    @Test
    fun physicalCommitKeepsOwnershipThroughRefreshAndPanelTeardown() {
        val state = LibraryDeletionState()
        val window = state.extendUndoWindow()!!
        assertTrue(state.beginForegroundCommit(window))
        state.reconcileNoPendingTargets()

        assertFalse(state.acceptsDeletions)
        assertNull(state.extendUndoWindow())
        assertFalse(state.undo(window))
        assertFalse(state.handoffToBackground(hasPending = true))
        assertFalse(state.beginForegroundCommit(window))

        state.completeForegroundCommit()
        assertNotNull(state.extendUndoWindow())
    }

    @Test
    fun pendingUndoTransfersOnceToBackgroundAndRevokesStaleCallbacks() {
        val state = LibraryDeletionState()
        val window = state.extendUndoWindow()!!
        assertTrue(state.handoffToBackground(hasPending = true))
        assertTrue(state.committedInBackground)
        assertFalse(state.handoffToBackground(hasPending = true))
        assertFalse(state.undo(window))
        assertFalse(state.beginForegroundCommit(window))
        assertNull(state.extendUndoWindow())
        // Only authoritative reconciliation releases background ownership.
        state.completeForegroundCommit()
        assertTrue(state.committedInBackground)
        state.reconcileNoPendingTargets()
        assertFalse(state.committedInBackground)
        assertNotNull(state.extendUndoWindow())
    }

    @Test
    fun externallyRemovedPendingTargetsCancelTheirOldUndoWindow() {
        val state = LibraryDeletionState()
        assertFalse(state.handoffToBackground(hasPending = false))
        val window = state.extendUndoWindow()!!
        state.reconcileNoPendingTargets()
        assertFalse(state.beginForegroundCommit(window))
        assertFalse(state.undo(window))
        assertNotNull(state.extendUndoWindow())
    }
}
