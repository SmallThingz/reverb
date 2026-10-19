package app.smallthingz.reverb

import kotlinx.coroutines.CancellationException

internal data class RecordingDeletionBatchResult(
    val outcomes: List<Boolean>,
    val cleanupFailed: Boolean,
)

internal class RecordingDeletionJournalBatch(
    private val chunkSize: Int = 16,
    private val onCleanupFailure: (Exception?) -> Unit = {},
    private val retire: (Set<String>) -> Boolean,
) {
    private val pending = linkedSetOf<String>()
    private var metadataCleanupFailed = false
    val failedEntries = mutableSetOf<String>()

    init { require(chunkSize > 0) }

    fun prepareForAddition(hasCapacity: () -> Boolean) {
        if (!hasCapacity()) flush()
    }

    fun defer(entry: String) {
        pending += entry
        if (pending.size >= chunkSize) flush()
    }

    fun markCleanupFailed() {
        metadataCleanupFailed = true
    }

    fun flush() {
        if (pending.isEmpty()) return
        val entries = pending.toSet()
        pending.clear()
        var failure: Exception? = null
        val retired = try {
            retire(entries)
        } catch (cancelled: CancellationException) {
            failedEntries += entries
            throw cancelled
        } catch (error: Exception) {
            failure = error
            false
        }
        if (!retired) {
            failedEntries += entries
            reportDeletionCleanupFailure(failure, onCleanupFailure)
        }
    }

    fun result(outcomes: List<Boolean>): RecordingDeletionBatchResult =
        RecordingDeletionBatchResult(outcomes, cleanupFailed = metadataCleanupFailed || failedEntries.isNotEmpty())
}

private fun reportDeletionCleanupFailure(error: Exception?, report: (Exception?) -> Unit) {
    try {
        report(error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (reportingError: Exception) {
        if (error != null && reportingError !== error) error.addSuppressed(reportingError)
    }
}

/** Called only after physical deletion; metadata failure cannot undo the physical outcome. */
internal suspend fun completeRecordingDeletionMetadata(
    deletedEntry: String,
    persistDeleted: () -> Boolean,
    retireCatalog: suspend () -> Unit,
    retireJournal: () -> Boolean,
    journalBatch: RecordingDeletionJournalBatch?,
    onCleanupFailure: (Exception?) -> Unit = {},
): Boolean {
    var failure: Exception? = null
    val completed = try {
        if (persistDeleted()) {
            retireCatalog()
            if (journalBatch == null) {
                retireJournal()
            } else {
                journalBatch.defer(deletedEntry)
                true
            }
        } else {
            false
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failure = error
        false
    }
    if (!completed) {
        journalBatch?.markCleanupFailed()
        reportDeletionCleanupFailure(failure, onCleanupFailure)
    }
    return true
}

internal suspend fun <T> runRecordingDeletionBatch(
    recordings: List<T>,
    replayPending: suspend () -> Unit,
    delete: suspend (T) -> Boolean,
): List<Boolean> {
    if (recordings.isEmpty()) return emptyList()
    try {
        // Replay pre-existing crash recovery once. Newly failed attempts remain journaled for
        // the next reconciliation instead of repeatedly retrying them between selected rows.
        replayPending()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return List(recordings.size) { false }
    }
    return recordings.map { recording ->
        try {
            delete(recording)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
