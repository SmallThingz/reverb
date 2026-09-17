package app.smallthingz.reverb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import java.util.Date
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


internal fun isLibraryDismissEdge(x: Float, width: Float): Boolean {
    if (!x.isFinite() || !width.isFinite() || width <= 0f) return false
    val edgeWidth = width * 0.13f
    return x <= edgeWidth || x >= width - edgeWidth
}

private sealed class ListItem {
    data class Header(val dateLabel: String) : ListItem()
    data class Recording(val recording: RecordingEntity) : ListItem()
}

internal fun deletionBatchFailed(requestedCount: Int, deletedCount: Int, hadError: Boolean): Boolean =
    hadError || deletedCount < requestedCount

internal fun shouldHandoffPendingDeletionsToBackground(
    hasPending: Boolean,
    committedInBackground: Boolean,
    foregroundCommitInFlight: Boolean,
): Boolean = hasPending && !committedInBackground && !foregroundCommitInFlight

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
    var changed = false
    val sanitized = recordings.map { recording ->
        if (recording.waveformData.isEmpty() && recording.waveformRevision.isEmpty()) {
            recording
        } else {
            changed = true
            recording.copy(waveformData = "", waveformRevision = "")
        }
    }
    return if (changed) sanitized else recordings
}

internal fun libraryEmptyStateVisible(
    hasLoaded: Boolean,
    listEmpty: Boolean,
    isRefreshing: Boolean,
): Boolean = hasLoaded && listEmpty && !isRefreshing

private data class LibraryNotice(
    val message: String,
    val tone: FeedbackTone,
    val canUndo: Boolean = false,
)

@Composable
fun FilesScreen(
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
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgeDismissDistancePx = with(density) { 64.dp.toPx() }
    val chrome = appChrome()
    val activeMutationRecordingIds by recordingMutations.activeIds.collectAsState()

    var recordings by remember {
        mutableStateOf(librarySnapshotWithoutWaveformTrust(initialRecordings))
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var hasLoaded by remember { mutableStateOf(initialRecordings.isNotEmpty()) }
    val refreshGeneration = remember { intArrayOf(0) }

    val selectedIds = remember { mutableStateMapOf<String, RecordingEntity>() }
    val pendingDeletions = remember { mutableStateMapOf<String, RecordingEntity>() }
    var isDeleting by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var infoRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var expandedRecordingId by remember { mutableStateOf<String?>(null) }
    var trimRequestRecordingId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<LibraryNotice?>(null) }
    val deletionJob = remember { arrayOfNulls<Job>(1) }
    val shareJob = remember { arrayOfNulls<Job>(1) }
    val shareGeneration = remember { intArrayOf(0) }
    val activeState = androidx.compose.runtime.rememberUpdatedState(active)
    val deletionsCommittedInBackground = remember { booleanArrayOf(false) }
    val deletionCommitInFlight = remember { booleanArrayOf(false) }
    var contextMenuRecordingId by remember { mutableStateOf<String?>(null) }

    fun showPassiveNotice(message: String, tone: FeedbackTone) {
        if (notice?.canUndo != true) notice = LibraryNotice(message, tone)
    }

    fun setExpandedRecording(id: String?) {
        expandedRecordingId = id
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

        val retainedExpandedId = retainId(expandedRecordingId)
        setExpandedRecording(retainedExpandedId)
        trimRequestRecordingId = retainId(trimRequestRecordingId)?.takeIf { it == retainedExpandedId }
    }

    fun refresh(showSpinner: Boolean = true) {
        val generation = ++refreshGeneration[0]
        if (showSpinner) isRefreshing = true
        scope.launch {
            try {
                val stored = RecordingRepository.refresh(context)
                if (generation != refreshGeneration[0]) return@launch
                val previousById = recordings.associateBy { it.id }
                recordings = stored
                hasLoaded = true
                val storedById = stored.associateBy { it.id }
                selectedIds.keys.toList().forEach { id ->
                    val previous = selectedIds[id]
                    val updated = storedById[id]
                    if (previous == null || updated == null || !sameRecordingActionTarget(previous, updated)) {
                        selectedIds.remove(id)
                    } else if (previous != updated) {
                        selectedIds[id] = updated
                    }
                }
                syncSelectionActive()
                reconcileTransientRecordings(previousById, storedById)
                if (deletionsCommittedInBackground[0]) {
                    // deleteInBackground owns failure reporting through the process feedback
                    // queue before refresh can return. Refresh only reconciles the optimistic
                    // Library transaction; do not render a second local error card for it.
                    pendingDeletions.clear()
                    deletionsCommittedInBackground[0] = false
                    isDeleting = false
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
                        isDeleting = false
                        if (notice?.canUndo == true) notice = null
                        if (pendingTargetInvalidated) {
                            notice = LibraryNotice(
                                resources.getString(R.string.recording_delete_failed),
                                FeedbackTone.ERROR,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == refreshGeneration[0]) {
                    showPassiveNotice(
                        resources.getString(R.string.recordings_refresh_failed),
                        FeedbackTone.ERROR,
                    )
                }
            } finally {
                if (generation == refreshGeneration[0]) isRefreshing = false
            }
        }
    }

    LaunchedEffect(initialRecordings) {
        if (pendingDeletions.isEmpty() && initialRecordings != recordings) {
            // Parent snapshots are catalog-only until this Library session itself has completed
            // storage reconciliation. Keep metadata for the fast first paint, but do not trust a
            // derived waveform cache merely because its stored revision matches stale metadata.
            val safeInitialRecordings = librarySnapshotWithoutWaveformTrust(initialRecordings)
            val previousById = recordings.associateBy { it.id }
            recordings = safeInitialRecordings
            hasLoaded = true
            val currentById = safeInitialRecordings.associateBy { it.id }
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
            reconcileTransientRecordings(previousById, currentById)
        }
    }

    LaunchedEffect(active) {
        if (!active) {
            // A hidden retained Library no longer has current storage proof. Preserve cheap
            // catalog metadata, but revoke waveform-cache trust before the next visible session.
            recordings = librarySnapshotWithoutWaveformTrust(recordings)
            // The Library stays composed behind the home screen. Invalidate any storage refresh
            // launched by the previous visible session so it cannot mutate retained UI state
            // after close or leave a spinner owned by an obsolete generation stuck on reopen.
            refreshGeneration[0]++
            isRefreshing = false
            shareGeneration[0]++
            shareJob[0]?.cancel()
            shareJob[0] = null
            contextMenuRecordingId = null
            selectedIds.clear()
            syncSelectionActive()
            showRenameDialog = false
            renameRecording = null
            showInfoDialog = false
            infoRecording = null
            setExpandedRecording(null)
            notice = null
            return@LaunchedEffect
        }
        if (!hasLoaded) {
            try {
                recordings = librarySnapshotWithoutWaveformTrust(
                    RecordingRepository.listKnown(context),
                )
                hasLoaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A local catalog read failure is not an authoritative empty Library. Keep the
                // last known rows and leave first-load pending; the scheduled reconciliation
                // below owns user-visible failure reporting if storage is still unavailable.
            }
        }

        // Guarantee a first frame from the local DB before any SAF/filesystem work.
        withFrameNanos { }
        delay(250L)
        refresh(showSpinner = false)
    }

    suspend fun finalizeDeletions() {
        val pending = pendingDeletions.values.toList()
        if (pending.isEmpty()) return
        val generation = ++refreshGeneration[0]
        var deleted = 0
        var failed = false
        val deletedIds = mutableSetOf<String>()
        // Once the undo window has closed, the user's delete is committed. Keep both the physical
        // identity-bound batch and its terminal optimistic-state reconciliation alive through
        // panel/lifecycle disposal. A cancelled owner must not leave a completed attempt hidden in
        // pendingDeletions after the irreversible delete phase has already run.
        withContext(NonCancellable) {
            pending.forEach { recording ->
                val didDelete = try {
                    RecordingRepository.delete(context, recording)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    false
                }
                if (didDelete) {
                    deleted++
                    deletedIds += recording.id
                }
            }
            // Remove successful rows before releasing the optimistic suppression. Failed exact
            // targets then become visible again immediately, while successful deletes cannot
            // flicker back during a cancelled/unavailable authoritative refresh. Identity-check
            // pending entries so a newer reused ID could never be cleared by this older batch.
            recordings = recordings.filterNot { it.id in deletedIds }
            releaseCompletedForegroundDeletionTargets(pendingDeletions, pending)
            deletionsCommittedInBackground[0] = false
        }
        val deletePhaseFailed = deletionBatchFailed(pending.size, deleted, failed)
        try {
            val refreshed = RecordingRepository.refresh(context)
            if (generation == refreshGeneration[0]) recordings = refreshed
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
            if (generation == refreshGeneration[0]) failed = true
        }
        if (deletionBatchFailed(pending.size, deleted, failed)) {
            if (activeState.value) {
                notice = LibraryNotice(
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
        if (!shouldHandoffPendingDeletionsToBackground(
                hasPending = pendingDeletions.isNotEmpty(),
                committedInBackground = deletionsCommittedInBackground[0],
                foregroundCommitInFlight = deletionCommitInFlight[0],
            )
        ) return
        deletionJob[0]?.cancel()
        deletionJob[0] = null
        val pending = pendingDeletions.values.toList()
        deletionsCommittedInBackground[0] = true
        isDeleting = true
        RecordingRepository.deleteInBackground(context, pending)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, active) {
        // LifecycleRegistry synchronously catches a newly added observer up to the current
        // state. Ignore that synthetic/catch-up ON_RESUME: LaunchedEffect(active) owns the
        // first-open DB-first, frame-first, delayed reconciliation path. Real later resumes
        // happen after addObserver returns and still refresh immediately.
        var observerInstalled = false
        val observer = LifecycleEventObserver { _, event ->
            if (active && observerInstalled && event == Lifecycle.Event.ON_RESUME) {
                refresh(showSpinner = false)
            }
            if (event == Lifecycle.Event.ON_STOP && pendingDeletions.isNotEmpty()) {
                notice = null
                commitPendingDeletionsInBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        observerInstalled = true
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (active) {
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
        derivedStateOf { buildListItems(context, visibleRecordings) }
    }
    LaunchedEffect(visibleRecordings) {
        onVisibleRecordingsChanged(visibleRecordings)
    }

    fun deleteRecordings(targets: Collection<RecordingEntity>) {
        if (isDeleting || targets.isEmpty()) return
        if (targets.any { it.id == expandedRecordingId }) {
            setExpandedRecording(null)
        }
        isDeleting = true
        deletionsCommittedInBackground[0] = false
        targets.forEach { pendingDeletions[it.id] = it }
        onVisibleRecordingsChanged(recordings.filterNot { it.id in pendingDeletions })
        clearSelection()
        contextMenuRecordingId = null
        val count = pendingDeletions.size
        val message = if (count == 1) resources.getString(R.string.recording_deleted)
        else resources.getQuantityString(R.plurals.recordings_deleted, count, count)
        notice = LibraryNotice(message, FeedbackTone.INFO, canUndo = true)
        deletionJob[0]?.cancel()
        deletionJob[0] = scope.launch {
            delay(4_500L)
            notice = null
            // Close the undo transaction before any physical delete begins. A stale
            // FeedbackCard callback may still be dispatched for one frame after notice clears.
            // Mark foreground ownership before dropping the undo Job reference so ON_STOP/panel
            // disposal cannot enqueue the same identity-bound delete concurrently.
            deletionCommitInFlight[0] = true
            deletionJob[0] = null
            try {
                finalizeDeletions()
            } finally {
                deletionCommitInFlight[0] = false
                isDeleting = false
            }
        }
    }

    fun deleteSelected() {
        val selected = selectedIds.values.toList()
        if (selected.isEmpty()) { clearSelection(); return }
        deleteRecordings(selected)
    }

    fun undoDelete() {
        val undoJob = deletionJob[0] ?: return
        if (notice?.canUndo != true || deletionsCommittedInBackground[0]) return
        undoJob.cancel()
        deletionJob[0] = null
        pendingDeletions.clear()
        deletionsCommittedInBackground[0] = false
        onVisibleRecordingsChanged(recordings)
        notice = null
        isDeleting = false
        refresh(showSpinner = false)
    }

    fun shareRecordings(recordingsToShare: Collection<RecordingEntity>) {
        if (recordingsToShare.isEmpty()) return
        val targets = recordingsToShare.toList()
        val chooserTitle = resources.getString(
            if (targets.size == 1) R.string.share_recording_title
            else R.string.share_recordings_title,
        )
        val generation = ++shareGeneration[0]
        shareJob[0]?.cancel()
        shareJob[0] = scope.launch {
            try {
                val shareIntent = withContext(Dispatchers.IO) {
                    buildShareRecordingsIntent(context, targets)
                }
                if (generation != shareGeneration[0] || !activeState.value) return@launch
                context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ActivityNotFoundException) {
                showPassiveNotice(resources.getString(R.string.share_recording_failed), FeedbackTone.ERROR)
            } catch (_: RuntimeException) {
                showPassiveNotice(resources.getString(R.string.share_recording_failed), FeedbackTone.ERROR)
                refresh(showSpinner = false)
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
    LaunchedEffect(expandedRecordingId) {
        onExpandedRecordingActiveChange(expandedRecordingId != null)
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
    val selectionBackMotion = rememberPredictiveBackMotion(
        enabled = active && selectionActive,
        onBack = ::clearSelection,
    )
    val selectionBackProgress = if (selectionBackMotion.gestureActive) {
        selectionBackMotion.progress.value.coerceIn(0f, 1f)
    } else {
        0f
    }
    val selectionBackDirection = predictiveBackHorizontalDirection(selectionBackMotion.swipeEdge)


    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            if (selectionActive) {
                Box {
                    if (selectionBackProgress > 0f) {
                        AppTopBar(
                            onBrandClick = onBrandClick,
                            onIncidentsClick = onIncidentsClick,
                            onSettingsClick = onSettingsClick,
                            hasIncidents = hasIncidents,
                            applyStatusBarPadding = false,
                        )
                    }
                    Surface(
                        modifier = Modifier.graphicsLayer {
                            translationX = selectionBackDirection * size.width * selectionBackProgress
                            alpha = 1f - 0.18f * selectionBackProgress
                            val scale = 1f - 0.015f * selectionBackProgress
                            scaleX = scale
                            scaleY = scale
                        },
                        color = chrome.field,
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AppTopBarContentHeight)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(
                                AppIcons.close,
                                contentDescription = stringResource(R.string.clear_selection),
                                tint = chrome.ink,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.recordings_selected, selectedIds.size, selectedIds.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = chrome.ink,
                        )
                        Spacer(Modifier.weight(1f))
                        if (selectedIds.isNotEmpty()) {
                            IconButton(
                                onClick = { shareRecordings(selectedIds.values.toList()) },
                                enabled = !selectionHasBusyMutation,
                            ) {
                                Icon(
                                    imageVector = AppIcons.share,
                                    contentDescription = stringResource(R.string.share_recording),
                                    tint = chrome.ink,
                                )
                            }
                        }
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = { deleteSelected() }, enabled = !isDeleting && !selectionHasBusyMutation) {
                                Icon(
                                    AppIcons.delete,
                                    contentDescription = stringResource(R.string.delete_recording),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
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
                        if (!isLibraryDismissEdge(down.position.x, size.width.toFloat())) {
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

                        verticalDrag(dragStart.id) { change ->
                            val deltaY = change.positionChange().y
                            downwardDrag = if (deltaY >= 0f) {
                                downwardDrag + deltaY
                            } else {
                                (downwardDrag + deltaY).coerceAtLeast(0f)
                            }
                            change.consume()
                        }
                        if (downwardDrag >= edgeDismissDistancePx) onDismissLibrary()
                    }
                },
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refresh(showSpinner = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!hasLoaded) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (libraryEmptyStateVisible(
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
                        EmptyState()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    ) {
                        itemsIndexed(listItems, key = { _, item ->
                            when (item) {
                                is ListItem.Header -> "header:${item.dateLabel}"
                                is ListItem.Recording -> "recording:${item.recording.id}"
                            }
                        }) { index, item ->
                            when (item) {
                                is ListItem.Header -> HeaderItem(item.dateLabel, compactTop = index == 0)
                                is ListItem.Recording -> {
                                    val recording = item.recording
                                    RecordingItem(
                                        item = item,
                                        isSelected = recording.id in selectedIds,
                                        selectionActive = selectionActive,
                                        screenActive = active,
                                        menuExpanded = contextMenuRecordingId == recording.id,
                                        expanded = expandedRecordingId == recording.id,
                                        trimRequested = trimRequestRecordingId == recording.id,
                                        externallyBusy = recording.id in activeMutationRecordingIds,
                                        onClick = {
                                            contextMenuRecordingId = null
                                            if (selectionActive) {
                                                toggleSelection(recording)
                                            } else {
                                                setExpandedRecording(
                                                    if (expandedRecordingId == recording.id) null else recording.id,
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
                                            setExpandedRecording(null)
                                            try {
                                                context.startActivity(RecordingOpenActivity.intentFor(context, recording))
                                            } catch (_: RuntimeException) {
                                                showPassiveNotice(
                                                    resources.getString(R.string.recording_unavailable),
                                                    FeedbackTone.ERROR,
                                                )
                                                refresh(showSpinner = false)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(84.dp)) }
                    }
                }
            }


            notice?.let { current ->
                FeedbackCard(
                    message = current.message,
                    tone = current.tone,
                    actionLabel = if (current.canUndo) stringResource(R.string.undo) else null,
                    onAction = if (current.canUndo) ::undoDelete else null,
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
            RenameRecordingDialog(
                recording = renameRecording ?: return,
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
            RecordingInfoDialogContent(
                recording = infoRecording ?: return,
                onDismiss = { showInfoDialog = false; infoRecording = null },
            )
        }
    }


}

@Composable
private fun EmptyState() {
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
private fun HeaderItem(dateLabel: String, compactTop: Boolean = false) {
    Text(
        text = dateLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = (0.04).sp,
        modifier = Modifier.padding(top = if (compactTop) 8.dp else 28.dp, bottom = 8.dp),
    )
}

@Composable
private fun RecordingItem(
    item: ListItem.Recording,
    isSelected: Boolean,
    selectionActive: Boolean,
    screenActive: Boolean,
    menuExpanded: Boolean,
    expanded: Boolean,
    trimRequested: Boolean,
    externallyBusy: Boolean,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
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
                    screenActive = screenActive,
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
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.rename_recording), color = chrome.ink) },
                onClick = onRename,
                leadingIcon = { Icon(AppIcons.edit, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.recording_info), color = chrome.ink) },
                onClick = onInfo,
                leadingIcon = { Icon(AppIcons.info, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.share_recording), color = chrome.ink) },
                onClick = onShare,
                leadingIcon = { Icon(AppIcons.share, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.trim_recording), color = chrome.ink) },
                onClick = onTrim,
                leadingIcon = { Icon(AppIcons.trim, contentDescription = null, tint = chrome.ink) },
            )
            DropdownMenuItem(
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.delete_recording), color = MaterialTheme.colorScheme.error) },
                onClick = onDelete,
                leadingIcon = {
                    Icon(AppIcons.delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
            )
            DropdownMenuItem(
                enabled = !operationBusy,
                text = { Text(stringResource(R.string.multi_select), color = chrome.ink) },
                onClick = onMultiSelect,
                leadingIcon = { Icon(AppIcons.multiSelect, contentDescription = null, tint = chrome.ink) },
            )
        }
    }
}

@Composable
private fun RenameRecordingDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onRenamed: (RecordingEntity) -> Unit,
    onStateUncertain: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var name by remember(recording.id, recording.displayName) {
        val baseName = recording.displayName.substringBeforeLast('.', recording.displayName)
        mutableStateOf(if (baseName.isEmpty()) recording.displayName else baseName)
    }
    var error by remember(recording.id) { mutableStateOf<String?>(null) }
    var isRenaming by remember(recording.id) { mutableStateOf(false) }

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
        scope.launch {
            try {
                val renamed = RecordingRepository.rename(context, recording, trimmed)
                if (renamed == null) error = resources.getString(R.string.rename_recording_failed)
                else onRenamed(renamed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onStateUncertain()
            } finally {
                isRenaming = false
            }
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
private fun RecordingInfoDialogContent(
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
                rows.forEach { (label, value) -> RecordingInfoRow(label, value) }
            }
        },
        actions = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun RecordingInfoRow(label: String, value: String) {
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

private fun buildListItems(context: Context, recordings: List<RecordingEntity>): List<ListItem> {
    val items = mutableListOf<ListItem>()
    var currentDateHeader = ""
    recordings.forEach { recording ->
        val dateHeader = formatRecordingDateHeader(context, recording.startedAtMillis)
        if (dateHeader != currentDateHeader) {
            currentDateHeader = dateHeader
            items.add(ListItem.Header(dateHeader))
        }
        items.add(ListItem.Recording(recording))
    }
    return items
}
