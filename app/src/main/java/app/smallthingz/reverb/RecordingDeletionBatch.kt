package app.smallthingz.reverb

import kotlinx.coroutines.CancellationException

internal data class RecordingDeletionBatchResult(
    val outcomes: List<Boolean>,
    val cleanupFailed: Boolean,
    val consentIndices: Set<Int> = emptySet(),
    val failures: Map<Int, RecordingDeletionFailure> = emptyMap(),
    val cleanupFailure: Throwable? = null,
)

/** Keep the journal stage attached to its original exception without changing transaction control flow. */
internal fun <T> recordingDeletionJournalStage(
    reportFailure: (RecordingDeletionFailure) -> Unit,
    block: () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    reportFailure(RecordingDeletionFailure(RecordingDeletionFailureReason.JOURNAL_FAILED, error))
    throw error
}

/** A surviving asset must be visible and have no durable replay intent before a prompt owns it. */
internal fun handOffRecordingDeletionConsent(
    stillSelectedAsset: () -> Boolean,
    retireUncommittedIntent: () -> Boolean,
    handOff: () -> Unit,
): Boolean {
    if (!stillSelectedAsset() || !retireUncommittedIntent()) return false
    handOff()
    return true
}

internal class RecordingDeletionJournalBatch(
    private val chunkSize: Int = 16,
    private val onCleanupFailure: (Exception?) -> Unit = {},
    private val retire: (Set<String>) -> Boolean,
) {
    private val pending = linkedSetOf<String>()
    private var metadataCleanupFailed = false
    private var metadataFailure: Exception? = null
    val failedEntries = mutableSetOf<String>()

    init { require(chunkSize > 0) }

    fun prepareForAddition(hasCapacity: () -> Boolean) {
        if (!hasCapacity()) flush()
    }

    fun defer(entry: String) {
        pending += entry
        if (pending.size >= chunkSize) flush()
    }

    fun markCleanupFailed(error: Exception? = null) {
        metadataCleanupFailed = true
        if (metadataFailure == null) metadataFailure = error
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
            if (metadataFailure == null) metadataFailure = failure
            failedEntries += entries
            reportDeletionCleanupFailure(failure, onCleanupFailure)
        }
    }

    fun result(outcomes: List<Boolean>): RecordingDeletionBatchResult =
        RecordingDeletionBatchResult(outcomes, cleanupFailed = metadataCleanupFailed || failedEntries.isNotEmpty(),
            cleanupFailure = metadataFailure)
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
        journalBatch?.markCleanupFailed(failure)
        reportDeletionCleanupFailure(failure, onCleanupFailure)
    }
    return true
}

internal suspend fun <T> runRecordingDeletionBatch(
    recordings: List<T>,
    replayPending: suspend () -> Unit,
    onReplayFailure: (Exception) -> Unit = {},
    delete: suspend (T) -> Boolean,
): List<Boolean> {
    if (recordings.isEmpty()) return emptyList()
    try {
        // Replay pre-existing crash recovery once. Newly failed attempts remain journaled for
        // the next reconciliation instead of repeatedly retrying them between selected rows.
        replayPending()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onReplayFailure(error)
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
