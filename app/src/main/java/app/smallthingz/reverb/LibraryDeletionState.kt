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

    val backgroundCommitRevision: Long?
        get() = undoRevision.takeIf { committedInBackground }

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

    fun handoffToBackground(hasPending: Boolean): Long? {
        if (!shouldHandoffPendingDeletionsToBackground(
                hasPending = hasPending,
                committedInBackground = committedInBackground,
                foregroundCommitInFlight = phase == Phase.FOREGROUND_COMMIT,
            )
        ) return null
        phase = Phase.BACKGROUND_COMMIT
        return undoRevision
    }

    fun completeBackgroundCommit(revision: Long, settle: () -> Unit): Boolean {
        if (phase != Phase.BACKGROUND_COMMIT || revision != undoRevision) return false
        settle()
        phase = Phase.IDLE
        return true
    }

    fun reconcileNoPendingTargets() {
        // Physical owners require their exact terminal revision, even after an empty scan.
        if (phase == Phase.UNDO) phase = Phase.IDLE
    }

    fun completeForegroundCommit() {
        if (phase == Phase.FOREGROUND_COMMIT) phase = Phase.IDLE
    }
}

/** A terminal receipt releases only its exact optimistic batch, never a newer row or window. */
internal fun settleCompletedBackgroundDeletion(
    state: LibraryDeletionState,
    revision: Long,
    attempted: List<RecordingEntity>,
    result: RecordingDeletionBatchResult,
    pending: MutableMap<String, RecordingEntity>,
    recordings: List<RecordingEntity>,
    publishRecordings: (List<RecordingEntity>) -> Unit,
): Boolean {
    // A missing outcome is unknown, not a failed or successful physical deletion.
    if (result.outcomes.size != attempted.size) return false
    return state.completeBackgroundCommit(revision) {
        val deleted = LinkedHashMap<String, RecordingEntity>()
        val targets = attempted.iterator()
        val outcomes = result.outcomes.iterator()
        while (targets.hasNext() && outcomes.hasNext()) {
            val recording = targets.next()
            if (outcomes.next()) deleted[recording.id] = recording
        }
        publishRecordings(recordings.filterNot { current ->
            deleted[current.id]?.let { requested -> sameRecordingActionTarget(requested, current) } == true
        })
        releaseCompletedForegroundDeletionTargets(pending, attempted)
    }
}
