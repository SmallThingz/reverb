// Generated from released 3e16f8ddb78dca15df4b436d34d330801ce83ffd; identifier renames only.
package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


internal fun ReleasedLibraryisLibraryDismissEdge(x: Float, width: Float): Boolean {
    if (!x.isFinite() || !width.isFinite() || width <= 0f) return false
    val edgeWidth = width * 0.13f
    return x <= edgeWidth || x >= width - edgeWidth
}

internal fun ReleasedLibraryfinishLibraryExpandedBack(
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

private sealed class ReleasedLibraryListItem {
    data class Header(val dateLabel: String) : ReleasedLibraryListItem()
    data class Recording(val recording: RecordingEntity) : ReleasedLibraryListItem()
}

internal fun ReleasedLibrarydeletionBatchFailed(requestedCount: Int, deletedCount: Int, hadError: Boolean): Boolean =
    hadError || deletedCount < requestedCount

internal fun ReleasedLibraryshouldHandoffPendingDeletionsToBackground(
    hasPending: Boolean,
    committedInBackground: Boolean,
    foregroundCommitInFlight: Boolean,
): Boolean = hasPending && !committedInBackground && !foregroundCommitInFlight

internal suspend fun ReleasedLibraryrunCommittedRecordingRename(
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

internal class ReleasedLibraryRenameUiCallbackGate(
    private var onRenamed: ((RecordingEntity) -> Unit)?,
    private var onRejected: (() -> Unit)?,
    private var onUncertain: (() -> Unit)?,
    private var onHiddenTerminal: (() -> Unit)?,
    private val isVisible: () -> Boolean,
) {
    var attached: Boolean = true
        private set

    fun detach() {
        attached = false
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
        if (!isVisible()) {
            onHiddenTerminal?.invoke()
            return false
        }
        deliver()
        return true
    }
}

internal class ReleasedLibraryRenameResultReceiver(
    onRenamed: (RecordingEntity) -> Unit,
    onRejected: () -> Unit,
    onUncertain: () -> Unit,
    onHiddenTerminal: () -> Unit,
    isUiVisible: () -> Boolean,
    private val onDetachedSuccess: (RecordingEntity) -> Unit,
    private val onDetachedFailure: () -> Unit,
    private val onTerminal: (ReleasedLibraryRenameResultReceiver) -> Unit,
) {
    private val uiCallbacks = ReleasedLibraryRenameUiCallbackGate(
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
internal fun ReleasedLibraryrecordingsAfterForegroundDeletion(
    recordings: List<RecordingEntity>,
    successfullyDeleted: Collection<RecordingEntity>,
): List<RecordingEntity> {
    if (successfullyDeleted.isEmpty()) return recordings
    val deletedById = successfullyDeleted.associateBy { it.id }
    return recordings.filterNot { current ->
        deletedById[current.id]?.let { requested -> sameRecordingActionTarget(requested, current) } == true
    }
}

internal fun ReleasedLibraryreleaseCompletedForegroundDeletionTargets(
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

internal fun ReleasedLibrarylibrarySnapshotWithoutWaveformTrust(
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

internal fun ReleasedLibrarylibraryEmptyStateVisible(
    hasLoaded: Boolean,
    listEmpty: Boolean,
    isRefreshing: Boolean,
): Boolean = hasLoaded && listEmpty && !isRefreshing

private data class ReleasedLibraryLibraryUndoNotice(val message: String, val window: Long)

@Composable
fun ReleasedLibraryFilesScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    initialRecordings: List<RecordingEntity> = emptyList(),
    onSelectionActiveChange: (Boolean) -> Unit = {},
    onExpandedRecordingActiveChange: (Boolean) -> Unit = {},
    onVisibleRecordingsChanged: (List<RecordingEntity>) -> Unit = {},
    onParentRefreshRequested: () -> Unit = {},
    showNormalTopBar: Boolean = true,
    onBrandClick: () -> Unit = {},
    onIncidentsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    hasIncidents: Boolean = false,
    onDismissLibrary: () -> Unit = {},
    onUndoVisibleChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val accessibilityManager = LocalAccessibilityManager.current
    val edgeDismissDistancePx = with(density) { 64.dp.toPx() }
    val chrome = appChrome()
    val topBarNoiseBrush = rememberAppNoiseBrush(APP_NOISE_SEED_TOP_BAR)
    val activeMutationRecordingIds by recordingMutations.activeIds.collectAsState()

    var recordings by remember {
        mutableStateOf(ReleasedLibrarylibrarySnapshotWithoutWaveformTrust(initialRecordings))
    }
    var isRefreshing by remember { mutableStateOf(false) }
    // Catalog rows can paint immediately; only this visible session's storage reconciliation
    // can establish that there are no recordings.
    var hasLoaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    val refreshGeneration = remember { LibraryRefreshGeneration { isRefreshing = it } }

    val selectedIds = remember { mutableStateMapOf<String, RecordingEntity>() }
    val pendingDeletions = remember { mutableStateMapOf<String, RecordingEntity>() }
    val deletionState = remember { LibraryDeletionState() }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var infoRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var expandedRecordingOwner by remember { mutableStateOf<InlinePlaybackRowOwner?>(null) }
    var trimRequestRecordingId by remember { mutableStateOf<String?>(null) }
    var undoNotice by remember { mutableStateOf<ReleasedLibraryLibraryUndoNotice?>(null) }
    val deletionJob = remember { arrayOfNulls<Job>(1) }
    val backgroundDeletionReceipt = remember { arrayOfNulls<Deferred<RecordingDeletionBatchResult>>(1) }
    val shareJob = remember { arrayOfNulls<Job>(1) }
    val shareGeneration = remember { intArrayOf(0) }
    val shareDelivery = remember { arrayOfNulls<RecordingOpenResultDelivery<Intent>>(1) }
    val activeState = androidx.compose.runtime.rememberUpdatedState(active)
    val undoVisibilityCallback = androidx.compose.runtime.rememberUpdatedState(onUndoVisibleChange)
    val refreshQueue = remember(scope) { LibraryRefreshQueue(scope) { activeState.value } }
    val firstRefreshReady = remember { booleanArrayOf(false) }
    val deferredRefreshSpinner = remember { booleanArrayOf(false) }
    var contextMenuRecordingId by remember { mutableStateOf<String?>(null) }

    fun showPassiveNotice(message: String, tone: FeedbackTone) {
        AppFeedbackCenter.post(message, tone)
    }

    fun navigationAllowed(): Boolean = activeState.value &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        hostView.isAttachedToWindow && hostView.isShown && hostView.hasWindowFocus()

    fun retireShareRequest() {
        shareGeneration[0]++
        shareJob[0]?.cancel()
        shareJob[0] = null
        val retired = shareDelivery[0]
        shareDelivery[0] = null
        retired?.retire()
    }

    DisposableEffect(hostView, lifecycleOwner) {
        val observer = hostView.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) shareDelivery[0]?.foregroundChanged()
        }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            val currentObserver = if (observer.isAlive) observer else hostView.viewTreeObserver
            if (currentObserver.isAlive) currentObserver.removeOnWindowFocusChangeListener(listener)
            retireShareRequest()
        }
    }

    fun clearUndoNotice(window: Long? = null) {
        if (window != null && undoNotice?.window != window) return
        undoNotice = null
        undoVisibilityCallback.value(false)
    }

    DisposableEffect(active) {
        undoVisibilityCallback.value(active && undoNotice != null)
        onDispose { undoVisibilityCallback.value(false) }
    }

    fun setExpandedRecording(id: String?) {
        if (expandedRecordingOwner?.recordingId != id) {
            expandedRecordingOwner = id?.let(::InlinePlaybackRowOwner)
        }
        if (id == null) trimRequestRecordingId = null
        onExpandedRecordingActiveChange(id != null)
    }

    fun syncSelectionActive() {
        onSelectionActiveChange(selectedIds.isNotEmpty())
    }

    fun toggleSelection(recording: RecordingEntity) {
        if (selectedIds.containsKey(recording.id)) selectedIds.remove(recording.id)
        else selectedIds[recording.id] = recording
        syncSelectionActive()
    }

    fun reconcileSelection(currentById: Map<String, RecordingEntity>) {
        selectedIds.keys.toList().forEach { id ->
            val previous = selectedIds[id]
            val updated = currentById[id]
            if (previous == null || updated == null || !sameRecordingActionTarget(previous, updated)) {
                selectedIds.remove(id)
            } else if (previous != updated) {
                selectedIds[id] = updated
            }
        }
        syncSelectionActive()
    }

    fun reconcileTransientRecordings(
        previousById: Map<String, RecordingEntity>,
        storedById: Map<String, RecordingEntity>,
    ) {
        fun retainId(id: String?): String? {
            id ?: return null
            val previous = previousById[id] ?: return null
            val current = storedById[id] ?: return null
            return id.takeIf { sameRecordingActionTarget(previous, current) }
        }

        contextMenuRecordingId = retainId(contextMenuRecordingId)
        renameRecording = renameRecording?.let { previous ->
            storedById[previous.id]?.takeIf { current -> sameRecordingActionTarget(previous, current) }
        }
        if (renameRecording == null) showRenameDialog = false
        infoRecording = infoRecording?.let { previous ->
            storedById[previous.id]?.takeIf { current -> sameRecordingActionTarget(previous, current) }
        }
        if (infoRecording == null) showInfoDialog = false

        val retainedExpandedId = retainId(expandedRecordingOwner?.recordingId)
        setExpandedRecording(retainedExpandedId)
        trimRequestRecordingId = retainId(trimRequestRecordingId)?.takeIf { it == retainedExpandedId }
    }

    fun refresh(showSpinner: Boolean = true) {
        if (!activeState.value) return
        if (!firstRefreshReady[0]) {
            if (showSpinner) isRefreshing = true
            deferredRefreshSpinner[0] = deferredRefreshSpinner[0] || showSpinner
            return
        }
        val generation = refreshGeneration.request(showSpinner)
        loadFailed = false
        refreshQueue.request {
            if (!refreshGeneration.isCurrent(generation)) return@request
            try {
                // A refresh admitted before handoff may otherwise miss the new repository job.
                // Only this exact settled batch may use this scan to recover an unknown result.
                val backgroundRevision = deletionState.backgroundCommitRevision
                val backgroundReceipt = backgroundDeletionReceipt[0]
                backgroundReceipt?.join()
                val stored = RecordingRepository.refresh(context)
                if (!refreshGeneration.isCurrent(generation)) return@request
                val previousById = recordings.associateBy { it.id }
                recordings = stored
                hasLoaded = true
                val storedById = stored.associateBy { it.id }
                reconcileSelection(storedById)
                reconcileTransientRecordings(previousById, storedById)
                if (deletionState.committedInBackground) {
                    // deleteInBackground owns failure reporting through the process feedback
                    // queue before refresh can return. Refresh only reconciles the optimistic
                    // Library transaction; do not render a second local error card for it.
                    if (backgroundRevision != null && backgroundReceipt != null &&
                        backgroundDeletionReceipt[0] === backgroundReceipt
                    ) {
                        deletionState.completeBackgroundCommit(backgroundRevision) {
                            pendingDeletions.clear()
                            backgroundDeletionReceipt[0] = null
                        }
                    }
                } else {
                    var pendingTargetInvalidated = false
                    pendingDeletions.keys.toList().forEach { id ->
                        val requested = pendingDeletions[id]
                        val updated = storedById[id]
                        when {
                            updated == null || requested == null -> pendingDeletions.remove(id)
                            !sameRecordingActionTarget(requested, updated) -> {
                                pendingDeletions.remove(id)
                                pendingTargetInvalidated = true
                            }
                        }
                    }
                    if (pendingDeletions.isEmpty()) {
                        deletionJob[0]?.cancel()
                        deletionJob[0] = null
                        deletionState.reconcileNoPendingTargets()
                        clearUndoNotice()
                        if (pendingTargetInvalidated) {
                            showPassiveNotice(
                                resources.getString(R.string.recording_delete_failed),
                                FeedbackTone.ERROR,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (refreshGeneration.isCurrent(generation)) {
                    hasLoaded = false
                    loadFailed = true
                    showPassiveNotice(
                        resources.getString(R.string.recordings_refresh_failed),
                        FeedbackTone.ERROR,
                    )
                }
            } finally {
                refreshGeneration.complete(generation)
            }
        }
    }

    LaunchedEffect(initialRecordings) {
        val safeInitialRecordings = ReleasedLibrarylibrarySnapshotWithoutWaveformTrust(initialRecordings)
        if (pendingDeletions.isEmpty() &&
            safeInitialRecordings != ReleasedLibrarylibrarySnapshotWithoutWaveformTrust(recordings)
        ) {
            // Parent snapshots are catalog-only until this Library session itself has completed
            // storage reconciliation. Keep metadata for the fast first paint, but do not trust a
            // derived waveform cache merely because its stored revision matches stale metadata.
            val previousById = recordings.associateBy { it.id }
            // An empty catalog snapshot cannot erase rows while its storage is unavailable.
            if (safeInitialRecordings.isNotEmpty() || recordings.isEmpty()) {
                recordings = safeInitialRecordings
            }
            hasLoaded = false
            val currentById = recordings.associateBy { it.id }
            reconcileSelection(currentById)
            reconcileTransientRecordings(previousById, currentById)
            if (active) refresh(showSpinner = false)
        }
    }

    LaunchedEffect(active) {
        if (!active) {
            // A hidden retained Library no longer has current storage proof. Preserve cheap
            // catalog metadata, but revoke waveform-cache trust before the next visible session.
            recordings = ReleasedLibrarylibrarySnapshotWithoutWaveformTrust(recordings)
            hasLoaded = false
            loadFailed = false
            firstRefreshReady[0] = false
            deferredRefreshSpinner[0] = false
            refreshQueue.dropPending()
            // The Library stays composed behind the home screen. Invalidate any storage refresh
            // launched by the previous visible session so it cannot mutate retained UI state
            // after close or leave a spinner owned by an obsolete generation stuck on reopen.
            refreshGeneration.invalidate()
            retireShareRequest()
            contextMenuRecordingId = null
            selectedIds.clear()
            syncSelectionActive()
            showRenameDialog = false
            renameRecording = null
            showInfoDialog = false
            infoRecording = null
            setExpandedRecording(null)
            clearUndoNotice()
            return@LaunchedEffect
        }
        if (!hasLoaded) {
            try {
                val known = ReleasedLibrarylibrarySnapshotWithoutWaveformTrust(
                    RecordingRepository.listKnown(context),
                )
                if (known.isNotEmpty() || recordings.isEmpty()) recordings = known
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A local catalog read failure is not an authoritative empty Library. Keep the
                // last known rows and leave first-load pending; the scheduled reconciliation
                // below owns user-visible failure reporting if storage is still unavailable.
            }
        }

        // Guarantee a first frame from the local DB before any SAF/filesystem work.
        // Parent snapshots and pull-to-refresh requests wait here too, so they cannot start
        // an eager scan followed by a duplicate first-entry scan.
        withFrameNanos { }
        delay(250L)
        firstRefreshReady[0] = true
        val showSpinner = deferredRefreshSpinner[0]
        deferredRefreshSpinner[0] = false
        refresh(showSpinner = showSpinner)
    }

    suspend fun finalizeDeletions() {
        val pending = pendingDeletions.values.toList()
        if (pending.isEmpty()) return
        val generation = refreshGeneration.invalidate()
        var deleted = 0
        var failed = false
        val deletedTargets = mutableListOf<RecordingEntity>()
        // Once the undo window has closed, the user's delete is committed. Keep both the physical
        // identity-bound batch and its terminal optimistic-state reconciliation alive through
        // panel/lifecycle disposal. A cancelled owner must not leave a completed attempt hidden in
        // pendingDeletions after the irreversible delete phase has already run.
        withContext(NonCancellable) {
            val result = RecordingRepository.deleteBatch(context, pending)
            failed = result.cleanupFailed
            val targets = pending.iterator()
            val outcomes = result.outcomes.iterator()
            while (targets.hasNext() && outcomes.hasNext()) {
                val recording = targets.next()
                if (outcomes.next()) {
                    deleted++
                    deletedTargets += recording
                }
            }
            // Remove successful rows before releasing the optimistic suppression. Failed exact
            // targets then become visible again immediately, while successful deletes cannot
            // flicker back during a cancelled/unavailable authoritative refresh. Identity-check
            // pending entries so a newer reused ID could never be cleared by this older batch.
            recordings = ReleasedLibraryrecordingsAfterForegroundDeletion(recordings, deletedTargets)
            ReleasedLibraryreleaseCompletedForegroundDeletionTargets(pendingDeletions, pending)
        }
        val deletePhaseFailed = ReleasedLibrarydeletionBatchFailed(pending.size, deleted, failed)
        try {
            val refreshed = if (deletePhaseFailed) {
                RecordingRepository.refresh(context)
            } else {
                ReleasedLibrarylibrarySnapshotWithoutWaveformTrust(RecordingRepository.listKnown(context))
            }
            if (refreshGeneration.isCurrent(generation)) {
                recordings = refreshed
                if (deletePhaseFailed) {
                    hasLoaded = true
                    loadFailed = false
                } else if (!hasLoaded) {
                    refresh(showSpinner = false)
                }
            }
        } catch (cancelled: CancellationException) {
            // Local Library UI may already be gone. Preserve failure feedback at process scope.
            if (deletePhaseFailed) {
                AppFeedbackCenter.post(
                    resources.getString(R.string.recording_delete_failed),
                    FeedbackTone.ERROR,
                )
            }
            throw cancelled
        } catch (_: Exception) {
            if (refreshGeneration.isCurrent(generation)) {
                failed = true
                hasLoaded = false
                loadFailed = true
            }
        }
        if (ReleasedLibrarydeletionBatchFailed(pending.size, deleted, failed)) {
            if (activeState.value) {
                showPassiveNotice(
                    resources.getString(R.string.recording_delete_failed),
                    FeedbackTone.ERROR,
                )
            } else {
                AppFeedbackCenter.post(
                    resources.getString(R.string.recording_delete_failed),
                    FeedbackTone.ERROR,
                )
            }
        }
    }

    fun commitPendingDeletionsInBackground() {
        val revision = deletionState.handoffToBackground(pendingDeletions.isNotEmpty()) ?: return
        // A scan admitted before handoff cannot restore pre-delete rows after receipt settlement.
        refreshGeneration.invalidate()
        deletionJob[0]?.cancel()
        deletionJob[0] = null
        val pending = pendingDeletions.values.toList()
        val receipt = RecordingRepository.deleteInBackground(context, pending)
        backgroundDeletionReceipt[0] = receipt
        // The retained Library scope survives panel close. Actual disposal only cancels this
        // waiter: the process-owned receipt and its failure feedback continue independently.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                receipt.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No per-record outcomes were delivered. Keep optimistic protection until an
                // authoritative refresh after this receipt settles; repository owns feedback.
                return@launch
            }
            if (backgroundDeletionReceipt[0] !== receipt) return@launch
            if (settleCompletedBackgroundDeletion(
                    state = deletionState,
                    revision = revision,
                    attempted = pending,
                    result = result,
                    pending = pendingDeletions,
                    recordings = recordings,
                    publishRecordings = { recordings = it },
                )
            ) {
                backgroundDeletionReceipt[0] = null
            }
        }
    }

    DisposableEffect(lifecycleOwner, active) {
        // LifecycleRegistry synchronously catches a newly added observer up to the current
        // state. Ignore that synthetic/catch-up ON_RESUME: LaunchedEffect(active) owns the
        // first-open DB-first, frame-first, delayed reconciliation path. Real later resumes
        // happen after addObserver returns and still refresh immediately.
        var observerInstalled = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) retireShareRequest()
            if (event == Lifecycle.Event.ON_RESUME) shareDelivery[0]?.foregroundChanged()
            if (active && observerInstalled && event == Lifecycle.Event.ON_RESUME) {
                refresh(showSpinner = false)
            }
            if (event == Lifecycle.Event.ON_STOP && pendingDeletions.isNotEmpty()) {
                clearUndoNotice()
                commitPendingDeletionsInBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        observerInstalled = true
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (active) {
                clearUndoNotice()
                deletionJob[0]?.cancel()
                // Register any committed delete before asking the parent to refresh. This keeps
                // its fast DB snapshot from racing ahead of background deletion on sheet close.
                commitPendingDeletionsInBackground()
                onParentRefreshRequested()
            }
        }
    }

    fun clearSelection() {
        selectedIds.clear()
        syncSelectionActive()
    }

    val visibleRecordings by remember {
        derivedStateOf { recordings.filterNot { it.id in pendingDeletions } }
    }

    val listItems by remember {
        derivedStateOf { ReleasedLibrarybuildListItems(context, visibleRecordings) }
    }
    LaunchedEffect(visibleRecordings) {
        onVisibleRecordingsChanged(visibleRecordings)
    }

    fun deleteRecordings(targets: Collection<RecordingEntity>) {
        if (targets.isEmpty()) return
        val undoWindow = deletionState.extendUndoWindow() ?: return
        if (targets.any { it.id == expandedRecordingOwner?.recordingId }) {
            setExpandedRecording(null)
        }
        targets.forEach { pendingDeletions[it.id] = it }
        onVisibleRecordingsChanged(recordings.filterNot { it.id in pendingDeletions })
        clearSelection()
        contextMenuRecordingId = null
        val count = pendingDeletions.size
        val message = if (count == 1) resources.getString(R.string.recording_deleted)
        else resources.getQuantityString(R.plurals.recordings_deleted, count, count)
        undoVisibilityCallback.value(activeState.value)
        undoNotice = ReleasedLibraryLibraryUndoNotice(message, undoWindow)
        deletionJob[0]?.cancel()
        deletionJob[0] = scope.launch {
            runLibraryUndoDeadline(deletionState, undoWindow, accessibilityManager) {
                clearUndoNotice(undoWindow)
                // The deadline owns foreground commit before the notice/job is cleared, so
                // stale Undo and lifecycle handoff cannot take the same batch.
                deletionJob[0] = null
                finalizeDeletions()
            }
        }
    }

    fun deleteSelected() {
        val selected = selectedIds.values.toList()
        if (selected.isEmpty()) { clearSelection(); return }
        deleteRecordings(selected)
    }

    fun undoDelete(undoWindow: Long) {
        val undoJob = deletionJob[0] ?: return
        if (!deletionState.undo(undoWindow)) return
        undoJob.cancel()
        deletionJob[0] = null
        pendingDeletions.clear()
        onVisibleRecordingsChanged(recordings)
        clearUndoNotice(undoWindow)
        refresh(showSpinner = false)
    }

    fun shareRecordings(recordingsToShare: Collection<RecordingEntity>) {
        if (recordingsToShare.isEmpty()) return
        val targets = recordingsToShare.toList()
        val chooserTitle = resources.getString(
            if (targets.size == 1) R.string.share_recording_title
            else R.string.share_recordings_title,
        )
        retireShareRequest()
        val generation = shareGeneration[0]
        fun reportShareFailure() {
            showPassiveNotice(resources.getString(R.string.share_recording_failed), FeedbackTone.ERROR)
            if (navigationAllowed()) refresh(showSpinner = false)
        }
        lateinit var delivery: RecordingOpenResultDelivery<Intent>
        delivery = RecordingOpenResultDelivery(
            navigationAllowed = { generation == shareGeneration[0] && navigationAllowed() },
            deliver = { shareIntent ->
                if (shareIntent == null) {
                    reportShareFailure()
                } else {
                    try {
                        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
                    } catch (_: RuntimeException) {
                        reportShareFailure()
                    }
                }
            },
            reportUndeliveredFailure = ::reportShareFailure,
            finish = {
                if (shareDelivery[0] === delivery) shareDelivery[0] = null
            },
        )
        shareDelivery[0] = delivery
        shareJob[0] = scope.launch {
            try {
                val shareIntent = try {
                    withContext(Dispatchers.IO) {
                        buildShareRecordingsIntent(context, targets)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    null
                }
                delivery.complete(shareIntent)
            } finally {
                if (generation == shareGeneration[0]) shareJob[0] = null
            }
        }
    }

    val selectionActive by remember { derivedStateOf { selectedIds.isNotEmpty() } }
    val selectionHasBusyMutation by remember {
        derivedStateOf { selectedIds.keys.any { it in activeMutationRecordingIds } }
    }
    LaunchedEffect(selectionActive) { onSelectionActiveChange(selectionActive) }
    LaunchedEffect(expandedRecordingOwner?.recordingId) {
        onExpandedRecordingActiveChange(expandedRecordingOwner?.recordingId != null)
    }
    DisposableEffect(Unit) {
        onDispose {
            shareGeneration[0]++
            shareJob[0]?.cancel()
            shareJob[0] = null
            onSelectionActiveChange(false)
            onExpandedRecordingActiveChange(false)
        }
    }
    // Keep this unkeyed handler before the lazy rows. Their later registered inline
    // handlers own visible trim/player Back (including predictive motion); if scrolling
    // disposes that row, this screen-level fallback still owns the expanded recording.
    // It never re-registers on A -> B -> A, so it cannot jump ahead of a retained row.
    BackHandler(enabled = active && expandedRecordingOwner != null && !selectionActive) {
        val owner = expandedRecordingOwner
        ReleasedLibraryfinishLibraryExpandedBack(
            owner = owner,
            isCurrentOwner = { activeState.value && selectedIds.isEmpty() && expandedRecordingOwner === it },
            // A committed trim outlives its row; read the live reservation, not collected UI state.
            isMutationActive = recordingMutations::isActive,
            collapse = { setExpandedRecording(null) },
        )
    }
    val selectionBackMotion = rememberPredictiveBackMotion(
        enabled = active && selectionActive,
        onBack = ::clearSelection,
    )
    val showSelectionBackUnderlay by remember(selectionBackMotion) {
        derivedStateOf {
            selectionBackMotion.gestureActive &&
                selectionBackMotion.progress.value.coerceIn(0f, 1f) > 0f
        }
    }
    val selectionBackDirection = predictiveBackHorizontalDirection(selectionBackMotion.swipeEdge)


    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            if (selectionActive) {
                Box {
                    if (showSelectionBackUnderlay) {
                        AppTopBar(
                            onBrandClick = onBrandClick,
                            onIncidentsClick = onIncidentsClick,
                            onSettingsClick = onSettingsClick,
                            hasIncidents = hasIncidents,
                            onBackClick = onDismissLibrary,
                            applyStatusBarPadding = false,
                        )
                    }
                    Surface(
                        modifier = Modifier.graphicsLayer {
                            val selectionBackProgress = if (selectionBackMotion.gestureActive) {
                                selectionBackMotion.progress.value.coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            translationX = selectionBackDirection * size.width * selectionBackProgress
                            alpha = 1f - 0.18f * selectionBackProgress
                            val scale = 1f - 0.015f * selectionBackProgress
                            scaleX = scale
                            scaleY = scale
                        },
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .appNoise(topBarNoiseBrush)
                            .height(AppTopBarContentHeight)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReleasedLibraryLibrarySelectionAction(
                            icon = AppIcons.close,
                            contentDescription = stringResource(R.string.clear_selection),
                            onClick = { clearSelection() },
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.recordings_selected, selectedIds.size, selectedIds.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = chrome.ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selectedIds.isNotEmpty()) {
                            ReleasedLibraryLibrarySelectionAction(
                                icon = AppIcons.share,
                                contentDescription = stringResource(R.string.share_recording),
                                onClick = { shareRecordings(selectedIds.values.toList()) },
                                enabled = !selectionHasBusyMutation,
                            )
                        }
                        if (selectedIds.isNotEmpty()) {
                            ReleasedLibraryLibrarySelectionAction(
                                icon = AppIcons.delete,
                                contentDescription = stringResource(R.string.delete_recording),
                                onClick = { deleteSelected() },
                                enabled = deletionState.acceptsDeletions && !selectionHasBusyMutation,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        }
                    }
                }
            } else if (showNormalTopBar) {
                AppTopBar(
                    onBrandClick = onBrandClick,
                    onIncidentsClick = onIncidentsClick,
                    onSettingsClick = onSettingsClick,
                    hasIncidents = hasIncidents,
                    onBackClick = onDismissLibrary,
                    applyStatusBarPadding = false,
                )
            } else {
                Spacer(Modifier.height(AppTopBarContentHeight))
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(onDismissLibrary, edgeDismissDistancePx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        if (!ReleasedLibraryisLibraryDismissEdge(down.position.x, size.width.toFloat())) {
                            return@awaitEachGesture
                        }

                        var downwardDrag = 0f
                        var accepted = false
                        val dragStart = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            if (overSlop > 0f) {
                                accepted = true
                                downwardDrag += overSlop
                                change.consume()
                            }
                        } ?: return@awaitEachGesture
                        if (!accepted) return@awaitEachGesture

                        val completed = verticalDrag(dragStart.id) { change ->
                            val deltaY = change.positionChange().y
                            downwardDrag = if (deltaY >= 0f) {
                                downwardDrag + deltaY
                            } else {
                                (downwardDrag + deltaY).coerceAtLeast(0f)
                            }
                            change.consume()
                        }
                        if (completed && downwardDrag >= edgeDismissDistancePx) onDismissLibrary()
                    }
                },
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refresh(showSpinner = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (listItems.isEmpty() && !hasLoaded) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (loadFailed) {
                            Text(
                                text = stringResource(R.string.recordings_refresh_failed),
                                color = chrome.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { refresh(showSpinner = true) }) {
                                Text(stringResource(R.string.library_retry))
                            }
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                } else if (ReleasedLibrarylibraryEmptyStateVisible(
                        hasLoaded = hasLoaded,
                        listEmpty = listItems.isEmpty(),
                        isRefreshing = isRefreshing,
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ReleasedLibraryEmptyState()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    ) {
                        itemsIndexed(listItems, key = { _, item ->
                            when (item) {
                                is ReleasedLibraryListItem.Header -> "header:${item.dateLabel}"
                                is ReleasedLibraryListItem.Recording -> "recording:${item.recording.id}"
                            }
                        }) { index, item ->
                            when (item) {
                                is ReleasedLibraryListItem.Header -> ReleasedLibraryHeaderItem(item.dateLabel, compactTop = index == 0)
                                is ReleasedLibraryListItem.Recording -> {
                                    val recording = item.recording
                                    ReleasedLibraryRecordingItem(
                                        item = item,
                                        groupEdges = RecordingGroupEdges(
                                            first = listItems.getOrNull(index - 1) !is ReleasedLibraryListItem.Recording,
                                            last = listItems.getOrNull(index + 1) !is ReleasedLibraryListItem.Recording,
                                        ),
                                        isSelected = recording.id in selectedIds,
                                        selectionActive = selectionActive,
                                        screenActive = active,
                                        playbackOwner = expandedRecordingOwner?.takeIf { it.recordingId == recording.id },
                                        isCurrentPlaybackOwner = { owner ->
                                            activeState.value && expandedRecordingOwner === owner
                                        },
                                        menuExpanded = contextMenuRecordingId == recording.id,
                                        expanded = expandedRecordingOwner?.recordingId == recording.id,
                                        trimRequested = trimRequestRecordingId == recording.id,
                                        externallyBusy = recording.id in activeMutationRecordingIds,
                                        deleteEnabled = deletionState.acceptsDeletions,
                                        onClick = {
                                            contextMenuRecordingId = null
                                            if (selectionActive) {
                                                toggleSelection(recording)
                                            } else {
                                                setExpandedRecording(
                                                    if (expandedRecordingOwner?.recordingId == recording.id) null else recording.id,
                                                )
                                            }
                                        },
                                        onIconLongClick = {
                                            contextMenuRecordingId = null
                                            setExpandedRecording(null)
                                            toggleSelection(recording)
                                        },
                                        onLongClick = {
                                            if (selectionActive) {
                                                toggleSelection(recording)
                                            } else {
                                                contextMenuRecordingId = recording.id
                                            }
                                        },
                                        onDismissMenu = { contextMenuRecordingId = null },
                                        onRename = {
                                            contextMenuRecordingId = null
                                            setExpandedRecording(null)
                                            renameRecording = recording
                                            showRenameDialog = true
                                        },
                                        onInfo = {
                                            contextMenuRecordingId = null
                                            setExpandedRecording(null)
                                            infoRecording = recording
                                            showInfoDialog = true
                                        },
                                        onShare = {
                                            contextMenuRecordingId = null
                                            shareRecordings(listOf(recording))
                                        },
                                        onTrim = {
                                            contextMenuRecordingId = null
                                            if (!recordingHasStableTrimIdentity(recording)) {
                                                showPassiveNotice(
                                                    resources.getString(R.string.recording_unavailable),
                                                    FeedbackTone.ERROR,
                                                )
                                            } else {
                                                setExpandedRecording(recording.id)
                                                trimRequestRecordingId = recording.id
                                            }
                                        },
                                        onDelete = { deleteRecordings(listOf(recording)) },
                                        onMultiSelect = {
                                            contextMenuRecordingId = null
                                            setExpandedRecording(null)
                                            selectedIds[recording.id] = recording
                                            syncSelectionActive()
                                        },
                                        onCollapse = { setExpandedRecording(null) },
                                        onTrimRequestConsumed = {
                                            if (trimRequestRecordingId == recording.id) trimRequestRecordingId = null
                                        },
                                        onTrimSaved = { refresh(showSpinner = false) },
                                        onTrimStateUncertain = { refresh(showSpinner = false) },
                                        onWaveformCached = { cached ->
                                            recordings = recordings.map { current ->
                                                if (
                                                    current.id == cached.id &&
                                                    isValidRecordingWaveformCache(
                                                        current, cached.waveformData, cached.waveformRevision,
                                                    )
                                                ) {
                                                    current.copy(
                                                        waveformData = cached.waveformData,
                                                        waveformRevision = cached.waveformRevision,
                                                    )
                                                } else {
                                                    current
                                                }
                                            }
                                        },
                                        onPlaybackFailed = {
                                            finishInlinePlaybackFailure(
                                                retireFailedPlayer = {
                                                    val current = expandedRecordingOwner?.recordingId == recording.id
                                                    if (current) setExpandedRecording(null)
                                                    current
                                                },
                                                navigationAllowed = ::navigationAllowed,
                                                openRecording = {
                                                    context.startActivity(RecordingOpenActivity.intentFor(context, recording))
                                                },
                                                reportUnavailable = {
                                                    showPassiveNotice(
                                                        resources.getString(R.string.recording_unavailable),
                                                        FeedbackTone.ERROR,
                                                    )
                                                    if (navigationAllowed()) refresh(showSpinner = false)
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(84.dp)) }
                    }
                }
            }


            undoNotice?.let { current ->
                FeedbackCard(
                    message = current.message,
                    tone = FeedbackTone.INFO,
                    actionLabel = stringResource(R.string.undo),
                    onAction = { undoDelete(current.window) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (showRenameDialog) {
        if (renameRecording == null) {
            showRenameDialog = false
        } else {
            ReleasedLibraryRenameRecordingDialog(
                recording = renameRecording ?: return,
                active = active,
                onDismiss = { showRenameDialog = false; renameRecording = null },
                onRenamed = { renamed ->
                    val updatedRecordings = recordings.map { item ->
                        if (item.id == (renameRecording?.id ?: renamed.id)) renamed else item
                    }
                    recordings = updatedRecordings
                    onVisibleRecordingsChanged(updatedRecordings.filterNot { it.id in pendingDeletions })
                    showRenameDialog = false
                    renameRecording = null
                    clearSelection()
                    refresh(showSpinner = false)
                },
                onStateUncertain = {
                    showRenameDialog = false
                    renameRecording = null
                    clearSelection()
                    showPassiveNotice(resources.getString(R.string.rename_recording_failed), FeedbackTone.ERROR)
                    refresh(showSpinner = false)
                },
            )
        }
    }

    if (showInfoDialog) {
        if (infoRecording == null) {
            showInfoDialog = false
        } else {
            ReleasedLibraryRecordingInfoDialogContent(
                recording = infoRecording ?: return,
                onDismiss = { showInfoDialog = false; infoRecording = null },
            )
        }
    }


}

@Composable
private fun ReleasedLibraryLibrarySelectionAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = appChrome().ink,
) {
    val chrome = appChrome()
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(46.dp),
            shape = RoundedCornerShape(15.dp),
            color = chrome.field,
            border = BorderStroke(1.dp, chrome.border),
            tonalElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(23.dp),
                    tint = if (enabled) tint else tint.copy(alpha = 0.38f),
                )
            }
        }
    }
}

@Composable
private fun ReleasedLibraryEmptyState() {
    Surface(
        modifier = Modifier.size(84.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = AppIcons.library,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun ReleasedLibraryHeaderItem(dateLabel: String, compactTop: Boolean = false) {
    Text(
        text = dateLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = (0.04).sp,
        modifier = Modifier.padding(top = if (compactTop) 8.dp else 28.dp, bottom = 8.dp),
    )
}

@Composable
private fun ReleasedLibraryRecordingItem(
    item: ReleasedLibraryListItem.Recording,
    groupEdges: RecordingGroupEdges,
    isSelected: Boolean,
    selectionActive: Boolean,
    screenActive: Boolean,
    playbackOwner: InlinePlaybackRowOwner?,
    isCurrentPlaybackOwner: (InlinePlaybackRowOwner) -> Boolean,
    menuExpanded: Boolean,
    expanded: Boolean,
    trimRequested: Boolean,
    externallyBusy: Boolean,
    deleteEnabled: Boolean,
    onClick: () -> Unit,
    onIconLongClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit,
    onMultiSelect: () -> Unit,
    onCollapse: () -> Unit,
    onTrimRequestConsumed: () -> Unit,
    onTrimSaved: (RecordingEntity) -> Unit,
    onTrimStateUncertain: () -> Unit,
    onWaveformCached: (RecordingEntity) -> Unit,
    onPlaybackFailed: () -> Unit,
) {
    val chrome = appChrome()
    var localOperationBusy by remember(item.recording.id) { mutableStateOf(false) }
    val operationBusy = localOperationBusy || externallyBusy
    Box {
        RecordingEntityCard(
            recording = item.recording,
            modifier = Modifier.fillMaxWidth(),
            groupEdges = groupEdges,
            isSelected = isSelected,
            selectionActive = selectionActive,
            interactionsEnabled = !operationBusy,
            onClick = onClick,
            onLongClick = onLongClick,
            onIconLongClick = onIconLongClick,
            expanded = expanded,
            expandedContent = {
                RecordingInlinePlayer(
                    recording = item.recording,
                    trimRequested = trimRequested,
                    screenActive = screenActive && expanded,
                    playbackOwner = playbackOwner,
                    isCurrentPlaybackOwner = isCurrentPlaybackOwner,
                    onTrimRequestConsumed = onTrimRequestConsumed,
                    onTrimSaved = onTrimSaved,
                    onTrimStateUncertain = onTrimStateUncertain,
                    onWaveformCached = onWaveformCached,
                    onBusyChange = { localOperationBusy = it },
                    onCollapse = onCollapse,
                    onPlaybackFailed = onPlaybackFailed,
                )
            },
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.align(Alignment.TopEnd),
            shape = RoundedCornerShape(18.dp),
            containerColor = chrome.raised,
        ) {
            DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.rename_recording), color = chrome.ink) },
                onClick = onRename,
                leadingIcon = { Icon(AppIcons.edit, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.recording_info), color = chrome.ink) },
                onClick = onInfo,
                leadingIcon = { Icon(AppIcons.info, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.share_recording), color = chrome.ink) },
                onClick = onShare,
                leadingIcon = { Icon(AppIcons.share, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.trim_recording), color = chrome.ink) },
                onClick = onTrim,
                leadingIcon = { Icon(AppIcons.trim, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                enabled = !operationBusy && deleteEnabled,
                text = { Text(stringResource(R.string.delete_recording), color = MaterialTheme.colorScheme.error) },
                onClick = onDelete,
                leadingIcon = {
                    Icon(AppIcons.delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
            )
            DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.multi_select), color = chrome.ink) },
                onClick = onMultiSelect,
                leadingIcon = { Icon(AppIcons.multiSelect, contentDescription = null, tint = chrome.ink) },
            )
        }
    }
}

@Composable
private fun ReleasedLibraryRenameRecordingDialog(
    recording: RecordingEntity,
    active: Boolean,
    onDismiss: () -> Unit,
    onRenamed: (RecordingEntity) -> Unit,
    onStateUncertain: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeState = androidx.compose.runtime.rememberUpdatedState(active)
    val activeReceiver = remember(recording.id) { arrayOfNulls<ReleasedLibraryRenameResultReceiver>(1) }
    var name by remember(recording.id, recording.displayName) {
        val baseName = recording.displayName.substringBeforeLast('.', recording.displayName)
        mutableStateOf(if (baseName.isEmpty()) recording.displayName else baseName)
    }
    var error by remember(recording.id) { mutableStateOf<String?>(null) }
    var isRenaming by remember(recording.id) { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { activeReceiver[0]?.detachUi() }
    }

    fun validateAndRename(trimmed: String) {
        if (isRenaming) return
        if (trimmed.isBlank()) {
            error = resources.getString(R.string.rename_recording_invalid)
            return
        }
        if (hasIllegalRecordingNameCharacters(trimmed)) {
            error = resources.getString(R.string.rename_recording_illegal_chars)
            return
        }
        isRenaming = true
        lateinit var receiver: ReleasedLibraryRenameResultReceiver
        receiver = ReleasedLibraryRenameResultReceiver(
            onRenamed = { renamed ->
                isRenaming = false
                onRenamed(renamed)
            },
            onRejected = {
                isRenaming = false
                error = resources.getString(R.string.rename_recording_failed)
            },
            onUncertain = {
                isRenaming = false
                onStateUncertain()
            },
            onHiddenTerminal = {
                isRenaming = false
                onDismiss()
            },
            isUiVisible = {
                activeState.value && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            },
            onDetachedSuccess = { renamed ->
                AppFeedbackCenter.post(
                    "${appContext.getString(R.string.rename_recording)}: ${renamed.displayName}",
                    FeedbackTone.SUCCESS,
                )
            },
            onDetachedFailure = {
                AppFeedbackCenter.post(
                    appContext.getString(R.string.rename_recording_failed),
                    FeedbackTone.ERROR,
                )
            },
            onTerminal = { completed ->
                if (activeReceiver[0] === completed) activeReceiver[0] = null
            },
        )
        activeReceiver[0] = receiver
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ReleasedLibraryrunCommittedRecordingRename(
                rename = { RecordingRepository.rename(appContext, recording, trimmed) },
                onTerminal = receiver::terminal,
            )
        }
    }

    ReverbActionSheet(
        title = stringResource(R.string.rename_recording),
        onDismiss = { if (!isRenaming) onDismiss() },
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!isRenaming) validateAndRename(name.trim()) }),
            )
        },
        actions = {
            TextButton(onClick = onDismiss, enabled = !isRenaming) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { validateAndRename(name.trim()) },
                enabled = !isRenaming && name.isNotBlank(),
            ) {
                Text(stringResource(R.string.rename_recording))
            }
        },
    )
}

@Composable
private fun ReleasedLibraryRecordingInfoDialogContent(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = android.text.format.DateFormat.getMediumDateFormat(context)
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    val startedAt = remember(recording.startedAtMillis) { Date(recording.startedAtMillis) }
    val rows = listOf(
        stringResource(R.string.recording_details_started) to
            "${dateFormat.format(startedAt)} ${timeFormat.format(startedAt)}",
        stringResource(R.string.recording_details_duration) to
            formatSavedRecordingDuration(context, recording.durationMillis),
        stringResource(R.string.recording_details_size) to formatShortFileSize(recording.sizeBytes),
        stringResource(R.string.recording_details_codec) to recording.codecSummary,
        stringResource(R.string.recording_details_mime) to recording.mimeType,
        stringResource(R.string.recording_details_storage) to recording.storageType.name,
        stringResource(R.string.recording_details_location) to describeRecordingLocation(context, recording),
    )

    ReverbActionSheet(
        title = stringResource(R.string.recording_info),
        onDismiss = onDismiss,
        content = {
            Text(
                text = recording.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rows.forEach { (label, value) -> ReleasedLibraryRecordingInfoRow(label, value) }
            }
        },
        actions = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun ReleasedLibraryRecordingInfoRow(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                text = label.removeSuffix(":"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun ReleasedLibrarybuildListItems(context: Context, recordings: List<RecordingEntity>): List<ReleasedLibraryListItem> {
    if (recordings.isEmpty()) return emptyList()
    val dateFormat = android.text.format.DateFormat.getLongDateFormat(context)
    val items = mutableListOf<ReleasedLibraryListItem>()
    var currentDateHeader = ""
    recordings.forEach { recording ->
        val dateHeader = dateFormat.format(Date(recording.startedAtMillis))
        if (dateHeader != currentDateHeader) {
            currentDateHeader = dateHeader
            items.add(ReleasedLibraryListItem.Header(dateHeader))
        }
        items.add(ReleasedLibraryListItem.Recording(recording))
    }
    return items
}
