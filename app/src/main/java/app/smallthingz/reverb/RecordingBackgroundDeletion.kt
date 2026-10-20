package app.smallthingz.reverb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/** The process owner keeps the physical result after any particular UI waiter is cancelled. */
internal fun CoroutineScope.launchRecordingBackgroundDeletion(
    previous: Job?,
    recordings: List<RecordingEntity>,
    deleteBatch: suspend (List<RecordingEntity>) -> RecordingDeletionBatchResult,
    reportFailure: () -> Unit,
): Deferred<RecordingDeletionBatchResult> {
    val targets = recordings.toList()
    return async {
        val result = try {
            previous?.join()
            deleteBatch(targets)
        } catch (error: Exception) {
            try {
                reportFailure()
            } catch (reportingError: Exception) {
                if (reportingError !== error) error.addSuppressed(reportingError)
            }
            throw error
        }
        if (result.cleanupFailed || result.outcomes.size != targets.size || result.outcomes.any { !it }) {
            reportFailure()
        }
        result
    }
}
