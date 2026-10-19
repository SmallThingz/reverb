package app.smallthingz.reverb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread ownership of one cumulative Undo window and its eventual physical batch. */
internal class LibraryDeletionState {
    private enum class Phase { IDLE, UNDO, FOREGROUND_COMMIT, BACKGROUND_COMMIT }

    private var phase by mutableStateOf(Phase.IDLE)
    private var undoRevision = 0L

    val acceptsDeletions: Boolean
        get() = phase == Phase.IDLE || phase == Phase.UNDO

    val committedInBackground: Boolean
        get() = phase == Phase.BACKGROUND_COMMIT

    /** Adding another target extends the existing window; feedback is never a busy state. */
    fun extendUndoWindow(): Long? {
        if (!acceptsDeletions) return null
        phase = Phase.UNDO
        return ++undoRevision
    }

    fun beginForegroundCommit(revision: Long): Boolean {
        if (phase != Phase.UNDO || revision != undoRevision) return false
        phase = Phase.FOREGROUND_COMMIT
        return true
    }

    fun undo(revision: Long): Boolean {
        if (phase != Phase.UNDO || revision != undoRevision) return false
        phase = Phase.IDLE
        return true
    }

    fun handoffToBackground(hasPending: Boolean): Boolean {
        if (!shouldHandoffPendingDeletionsToBackground(
                hasPending = hasPending,
                committedInBackground = committedInBackground,
                foregroundCommitInFlight = phase == Phase.FOREGROUND_COMMIT,
            )
        ) return false
        phase = Phase.BACKGROUND_COMMIT
        return true
    }

    fun reconcileNoPendingTargets() {
        // A concurrent refresh cannot revoke the physical owner's terminal reconciliation.
        if (phase == Phase.UNDO || phase == Phase.BACKGROUND_COMMIT) phase = Phase.IDLE
    }

    fun completeForegroundCommit() {
        if (phase == Phase.FOREGROUND_COMMIT) phase = Phase.IDLE
    }
}
