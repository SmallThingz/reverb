package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun isLibraryDismissEdge(x: Float, width: Float): Boolean {
    if (!x.isFinite() || !width.isFinite() || width <= 0f) return false
    val edgeWidth = width * 0.13f
    return x <= edgeWidth || x >= width - edgeWidth
}

internal fun finishLibraryExpandedBack(
    owner: InlinePlaybackRowOwner?,
    isCurrentOwner: (InlinePlaybackRowOwner) -> Boolean,
    isMutationActive: (String) -> Boolean,
    collapse: () -> Unit,
): Boolean {
    owner ?: return false
    return finishInlineBackForCurrentRow(
        owner = owner,
        isCurrentOwner = isCurrentOwner,
        trimSaving = isMutationActive(owner.recordingId),
        trimMode = false,
        leaveTrim = {},
        collapse = collapse,
    )
}


internal fun deletionBatchFailed(requestedCount: Int, deletedCount: Int, hadError: Boolean): Boolean =
    hadError || deletedCount < requestedCount

internal fun shouldHandoffPendingDeletionsToBackground(
    hasPending: Boolean,
    committedInBackground: Boolean,
    foregroundCommitInFlight: Boolean,
): Boolean = hasPending && !committedInBackground && !foregroundCommitInFlight

internal suspend fun runCommittedRecordingRename(
    rename: suspend () -> RecordingEntity?,
    onTerminal: (Result<RecordingEntity?>) -> Unit,
) = withContext(NonCancellable) {
    val result = try {
        Result.success(rename())
    } catch (error: Exception) {
        Result.failure(error)
    }
    onTerminal(result)
}

internal class RenameUiCallbackGate(
    private var onRenamed: ((RecordingEntity) -> Unit)?,
    private var onRejected: (() -> Unit)?,
    private var onUncertain: (() -> Unit)?,
    private var onHiddenTerminal: (() -> Unit)?,
    isVisible: () -> Boolean,
) {
    private var isVisibleCallback: (() -> Boolean)? = isVisible
    var attached: Boolean = true
        private set

    fun detach() {
        attached = false
        isVisibleCallback = null
        onRenamed = null
        onRejected = null
        onUncertain = null
        onHiddenTerminal = null
    }

    fun renamed(recording: RecordingEntity): Boolean = deliverVisible { onRenamed?.invoke(recording) }

    fun rejected(): Boolean = deliverVisible { onRejected?.invoke() }

    fun uncertain(): Boolean = deliverVisible { onUncertain?.invoke() }

    private inline fun deliverVisible(deliver: () -> Unit): Boolean {
        if (!attached) return false
        if (isVisibleCallback?.invoke() != true) {
            onHiddenTerminal?.invoke()
            return false
        }
        deliver()
        return true
    }
}

internal class RenameResultReceiver(
    onRenamed: (RecordingEntity) -> Unit,
    onRejected: () -> Unit,
    onUncertain: () -> Unit,
    onHiddenTerminal: () -> Unit,
    isUiVisible: () -> Boolean,
    private val onDetachedSuccess: (RecordingEntity) -> Unit,
    private val onDetachedFailure: () -> Unit,
    private val onTerminal: (RenameResultReceiver) -> Unit,
) {
    private val uiCallbacks = RenameUiCallbackGate(
        onRenamed = onRenamed,
        onRejected = onRejected,
        onUncertain = onUncertain,
        onHiddenTerminal = onHiddenTerminal,
        isVisible = isUiVisible,
    )
    private val terminalDelivered = AtomicBoolean(false)

    fun detachUi() = uiCallbacks.detach()

    fun terminal(result: Result<RecordingEntity?>) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        deliverTerminalResult(
            deliver = {
                result.fold(
                    onSuccess = { renamed ->
                        if (renamed != null) {
                            deliverVisibleTerminalOrFallback(
                                deliverVisible = { uiCallbacks.renamed(renamed) },
                                fallback = { onDetachedSuccess(renamed) },
                            )
                        } else {
                            deliverVisibleTerminalOrFallback(
                                deliverVisible = uiCallbacks::rejected,
                                fallback = onDetachedFailure,
                            )
                        }
                    },
                    onFailure = {
                        deliverVisibleTerminalOrFallback(
                            deliverVisible = uiCallbacks::uncertain,
                            fallback = onDetachedFailure,
                        )
                    },
                )
            },
            finish = {
                uiCallbacks.detach()
                onTerminal(this)
            },
        )
    }
}

/** A completed delete removes its selected object, never a freshly observed replacement. */
internal fun recordingsAfterForegroundDeletion(
    recordings: List<RecordingEntity>,
    successfullyDeleted: Collection<RecordingEntity>,
): List<RecordingEntity> {
    if (successfullyDeleted.isEmpty()) return recordings
    val deletedById = successfullyDeleted.associateBy { it.id }
    return recordings.filterNot { current ->
        deletedById[current.id]?.let { requested -> sameRecordingActionTarget(requested, current) } == true
    }
}

internal fun releaseCompletedForegroundDeletionTargets(
    pendingDeletions: MutableMap<String, RecordingEntity>,
    attempted: Collection<RecordingEntity>,
) {
    attempted.forEach { requested ->
        val current = pendingDeletions[requested.id] ?: return@forEach
        if (sameRecordingActionTarget(requested, current)) {
            pendingDeletions.remove(requested.id)
        }
    }
}

internal fun librarySnapshotWithoutWaveformTrust(
    recordings: List<RecordingEntity>,
): List<RecordingEntity> {
    var sanitized: MutableList<RecordingEntity>? = null
    recordings.forEachIndexed { index, recording ->
        if (recording.waveformData.isNotEmpty() || recording.waveformRevision.isNotEmpty()) {
            val result = sanitized ?: recordings.toMutableList().also { sanitized = it }
            result[index] = recording.copy(waveformData = "", waveformRevision = "")
        }
    }
    return sanitized ?: recordings
}

internal fun libraryEmptyStateVisible(
    hasLoaded: Boolean,
    listEmpty: Boolean,
    isRefreshing: Boolean,
): Boolean = hasLoaded && listEmpty && !isRefreshing
