package app.smallthingz.reverb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/** Retain active process work only; callers may keep the completed receipt for UI settlement. */
internal class RecordingBackgroundDeletionQueue(private val scope: CoroutineScope) {
    private var tail: Job? = null

    @Synchronized
    fun pending(): Job? = tail

    @Synchronized
    fun enqueue(
        recordings: List<RecordingEntity>,
        deleteBatch: suspend (List<RecordingEntity>) -> RecordingDeletionBatchResult,
        reportFailure: (RecordingDeletionBatchResult) -> Unit,
    ): Deferred<RecordingDeletionBatchResult> {
        val receipt = scope.launchRecordingBackgroundDeletion(tail, recordings, deleteBatch, reportFailure)
        tail = receipt
        receipt.invokeOnCompletion {
            synchronized(this) {
                // An older batch may finish after a newer batch has taken queue ownership.
                if (tail === receipt) tail = null
            }
        }
        return receipt
    }

    suspend fun awaitIdle() {
        while (true) {
            val job = pending() ?: return
            job.join()
            if (pending() === job) return
        }
    }
}

/** The process owner keeps the physical result after any particular UI waiter is cancelled. */
internal fun CoroutineScope.launchRecordingBackgroundDeletion(
    previous: Job?,
    recordings: List<RecordingEntity>,
    deleteBatch: suspend (List<RecordingEntity>) -> RecordingDeletionBatchResult,
    reportFailure: (RecordingDeletionBatchResult) -> Unit,
): Deferred<RecordingDeletionBatchResult> {
    val targets = recordings.toList()
    return async {
        val result = try {
            previous?.join()
            deleteBatch(targets)
        } catch (error: Exception) {
            try {
                reportFailure(RecordingDeletionBatchResult(List(targets.size) { false }, false,
                    failures = targets.indices.associateWith {
                        RecordingDeletionFailure(RecordingDeletionFailureReason.CATALOG_UNAVAILABLE, error)
                    }))
            } catch (reportingError: Exception) {
                if (reportingError !== error) error.addSuppressed(reportingError)
            }
            throw error
        }
        if (result.cleanupFailed || result.outcomes.size != targets.size ||
            result.outcomes.withIndex().any { (index, deleted) -> !deleted && index !in result.consentIndices }
        ) {
            reportFailure(result)
        }
        result
    }
}
