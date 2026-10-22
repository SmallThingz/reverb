package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.platform.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.LibraryNativeListState
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal data class NativeLibraryCallbacks(
    val onSelectionActiveChange: (Boolean) -> Unit = {},
    val onExpandedRecordingActiveChange: (Boolean) -> Unit = {},
    val onVisibleRecordingsChanged: (List<RecordingEntity>) -> Unit = {},
    val onParentRefreshRequested: () -> Unit = {},
    val onUndoVisibleChange: (Boolean) -> Unit = {},
)
internal data class NativeLibraryInput(
    val active: Boolean,
    val initialRecordings: List<RecordingEntity>,
    val callbacks: NativeLibraryCallbacks,
)
internal data class NativeLibraryUndoNotice(val message: String, val window: Long)
internal data class NativeLibraryPresentation(
    val list: LibraryNativeListState,
    val recordings: List<RecordingEntity>,
    val hasLoaded: Boolean,
    val loadFailed: Boolean,
    val isRefreshing: Boolean,
    val selectedCount: Int,
    val selectionHasBusyMutation: Boolean,
    val acceptsDeletions: Boolean,
    val undo: NativeLibraryUndoNotice?,
    val menuRecording: RecordingEntity?,
    val renameRecording: RecordingEntity?,
    val infoRecording: RecordingEntity?,
)

/**
 * FilesScreen's retained owner, separated from its rendering. Existing repository, refresh queue,
 * deletion phase/receipt and share-delivery implementations stay authoritative. One owner survives
 * hidden Library; close is actual source composition disposal, never temporary screen inactivity.
 */
internal class NativeLibraryController(
    private val context: Context,
    private val hostView: View,
    private val lifecycleOwner: LifecycleOwner,
    parentScope: CoroutineScope,
    private val frames: MonotonicFrameClock,
    private val accessibilityManager: AccessibilityManager?,
    initial: NativeLibraryInput,
    private val publish: (NativeLibraryPresentation) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val main = Handler(Looper.getMainLooper())
    private val resources get() = context.resources
    private var input = initial
    private var closed = false
    private var deliveryPosted = false
    private val delivery = Runnable { deliveryPosted = false; if (!closed) deliver() }
    private var visibleDirty = true
    private var recordings by observed(librarySnapshotWithoutWaveformTrust(initial.initialRecordings), ::visibleChanged)
    private var isRefreshing by observed(false)
    private var hasLoaded by observed(false)
    private var loadFailed by observed(false)
    private val refreshGeneration = LibraryRefreshGeneration { isRefreshing = it }
    private val selectedIds = ObservedRecordingMap(::invalidate)
    private val pendingDeletions = ObservedRecordingMap(::visibleChanged)
    private val localOperationBusyIds = LinkedHashSet<String>()
    private val deletionState = LibraryDeletionState()
    private var showRenameDialog by observed(false)
    private var renameRecording by observed<RecordingEntity?>(null)
    private var showInfoDialog by observed(false)
    private var infoRecording by observed<RecordingEntity?>(null)
    private var expandedRecordingOwner by observed<InlinePlaybackRowOwner?>(null)
    private var trimRequestRecordingId by observed<String?>(null)
    private var undoNotice by observed<NativeLibraryUndoNotice?>(null)
    private val deletionJob = arrayOfNulls<Job>(1)
    private val backgroundDeletionReceipt = arrayOfNulls<Deferred<RecordingDeletionBatchResult>>(1)
    private val shareJob = arrayOfNulls<Job>(1)
    private val shareGeneration = intArrayOf(0)
    private val shareDelivery = arrayOfNulls<RecordingOpenResultDelivery<Intent>>(1)
    private val refreshQueue = LibraryRefreshQueue(scope) { input.active }
    private val firstRefreshReady = booleanArrayOf(false)
    private val deferredRefreshSpinner = booleanArrayOf(false)
    private var contextMenuRecordingId by observed<String?>(null)
    private var activeMutationRecordingIds = emptySet<String>()
    private var activeEffect: Job? = null
    private var initialEffect: Job? = null
    private var installedLifecycle: LifecycleEventObserver? = null
    private val focusObserver = hostView.viewTreeObserver
    private val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) shareDelivery[0]?.foregroundChanged()
    }
    private var lastVisible: List<RecordingEntity>? = null
    private var lastListItems: List<LibraryListItem> = emptyList()
    private var visibleEffect: Job? = null
    private var lastSelectionActive: Boolean? = null
    private var selectionEffect: Job? = null
    private var lastExpandedId: String? = null
    private var expandedEffectStarted = false
    private var expandedEffect: Job? = null

    init {
        focusObserver.addOnWindowFocusChangeListener(focusListener)
        installLifecycle(initial.active)
        // collectAsState is whole-owner collection in the released implementation, not STARTED-only.
        scope.launch {
            recordingMutations.activeIds.collect { activeMutationRecordingIds = it; invalidate() }
        }
        launchInitialEffect(initial.initialRecordings)
        launchActiveEffect(initial.active)
        input.callbacks.onUndoVisibleChange(initial.active && undoNotice != null)
        invalidate()
    }

    private fun <T> observed(initial: T, changed: () -> Unit = ::invalidate): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private var current = initial
        override fun getValue(thisRef: Any?, property: KProperty<*>) = current
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (current == value) return
            current = value
            changed()
        }
    }
    private fun visibleChanged() { visibleDirty = true; invalidate() }
    private fun invalidate() {
        if (closed || deliveryPosted) return
        deliveryPosted = true
        main.post(delivery)
    }

    fun bind(next: NativeLibraryInput) {
        check(!closed)
        val previous = input
        // rememberUpdatedState already exposes the new active/callback values during effect cleanup.
        input = next
        if (previous.active != next.active) {
            // Source active-keyed DisposableEffect is disposed before its successor effects.
            removeLifecycle(previous.active, previous.callbacks)
            input.callbacks.onUndoVisibleChange(false)
        }
        if (previous.initialRecordings != next.initialRecordings) launchInitialEffect(next.initialRecordings)
        if (previous.active != next.active) {
            installLifecycle(next.active)
            launchActiveEffect(next.active)
            next.callbacks.onUndoVisibleChange(next.active && undoNotice != null)
        }
        invalidate()
    }

    private fun launchInitialEffect(initialRecordings: List<RecordingEntity>) {
        initialEffect?.cancel()
        initialEffect = scope.launch {
        val safeInitialRecordings = librarySnapshotWithoutWaveformTrust(initialRecordings)
        if (pendingDeletions.isEmpty() &&
            safeInitialRecordings != librarySnapshotWithoutWaveformTrust(recordings)
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
            if (input.active) refresh(showSpinner = false)
        }
    
        }
    }
    private fun launchActiveEffect(active: Boolean) {
        activeEffect?.cancel()
        activeEffect = scope.launch {
        if (!active) {
            // A hidden retained Library no longer has current storage proof. Preserve cheap
            // catalog metadata, but revoke waveform-cache trust before the next visible session.
            recordings = librarySnapshotWithoutWaveformTrust(recordings)
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
            return@launch
        }
        if (!hasLoaded) {
            try {
                val known = librarySnapshotWithoutWaveformTrust(
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
        frames.withFrameNanos { }
        delay(250L)
        firstRefreshReady[0] = true
        val showSpinner = deferredRefreshSpinner[0]
        deferredRefreshSpinner[0] = false
        refresh(showSpinner = showSpinner)
    
        }
    }
    private fun installLifecycle(active: Boolean) {
        var observerInstalled = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) retireShareRequest()
            if (event == Lifecycle.Event.ON_RESUME) shareDelivery[0]?.foregroundChanged()
            if (active && observerInstalled && event == Lifecycle.Event.ON_RESUME) refresh(showSpinner = false)
            if (event == Lifecycle.Event.ON_STOP && pendingDeletions.isNotEmpty()) {
                clearUndoNotice()
                commitPendingDeletionsInBackground()
            }
        }
        installedLifecycle = observer
        lifecycleOwner.lifecycle.addObserver(observer)
        observerInstalled = true
    }
    private fun removeLifecycle(wasActive: Boolean, callbacks: NativeLibraryCallbacks) {
        installedLifecycle?.let(lifecycleOwner.lifecycle::removeObserver)
        installedLifecycle = null
        if (wasActive) {
            clearUndoNotice()
            deletionJob[0]?.cancel()
            commitPendingDeletionsInBackground()
            callbacks.onParentRefreshRequested()
        }
    }

    private fun deliver() {
        val visible = if (visibleDirty || lastVisible == null) {
            visibleDirty = false
            recordings.filterNot { it.id in pendingDeletions }
        } else checkNotNull(lastVisible)
        if (visible != lastVisible) {
            lastVisible = visible
            lastListItems = buildLibraryListItems(context, visible)
            visibleEffect?.cancel()
            val callback = input.callbacks.onVisibleRecordingsChanged
            visibleEffect = scope.launch { callback(visible) }
        }
        val selected = selectedIds.isNotEmpty()
        if (lastSelectionActive != selected) {
            lastSelectionActive = selected
            selectionEffect?.cancel()
            val callback = input.callbacks.onSelectionActiveChange
            selectionEffect = scope.launch { callback(selected) }
        }
        val expandedId = expandedRecordingOwner?.recordingId
        if (!expandedEffectStarted || expandedId != lastExpandedId) {
            expandedEffectStarted = true
            lastExpandedId = expandedId
            expandedEffect?.cancel()
            val callback = input.callbacks.onExpandedRecordingActiveChange
            expandedEffect = scope.launch { callback(expandedId != null) }
        }
        publish(NativeLibraryPresentation(
            list = LibraryNativeListState(lastListItems, selectedIds.keys.toSet(), selected,
                activeMutationRecordingIds, localOperationBusyIds.toSet(), expandedRecordingOwner,
                trimRequestRecordingId, input.active),
            recordings = visible,
            hasLoaded = hasLoaded, loadFailed = loadFailed, isRefreshing = isRefreshing,
            selectedCount = selectedIds.size,
            selectionHasBusyMutation = selectedIds.keys.any { it in activeMutationRecordingIds },
            acceptsDeletions = deletionState.acceptsDeletions,
            undo = undoNotice,
            menuRecording = contextMenuRecordingId?.let { id -> visible.firstOrNull { it.id == id } },
            renameRecording = renameRecording.takeIf { showRenameDialog },
            infoRecording = infoRecording.takeIf { showInfoDialog },
        ))
    }

    /** Source row locals disappear with their keyed row, not with the process mutation reservation. */
    fun localBusy(recordingId: String, busy: Boolean) {
        val changed = if (busy) localOperationBusyIds.add(recordingId) else localOperationBusyIds.remove(recordingId)
        if (changed) invalidate()
    }
    fun isCurrentPlaybackOwner(owner: InlinePlaybackRowOwner) = input.active && expandedRecordingOwner === owner
    fun fallbackExpandedBack() = finishLibraryExpandedBack(expandedRecordingOwner,
        { input.active && selectedIds.isEmpty() && expandedRecordingOwner === it },
        recordingMutations::isActive, { setExpandedRecording(null) })

    fun activate(recording: RecordingEntity) {
        contextMenuRecordingId = null
        if (selectedIds.isNotEmpty()) toggleSelection(recording)
        else setExpandedRecording(if (expandedRecordingOwner?.recordingId == recording.id) null else recording.id)
    }
    fun leadingLongClick(recording: RecordingEntity) {
        contextMenuRecordingId = null
        setExpandedRecording(null)
        toggleSelection(recording)
    }
    fun longClick(recording: RecordingEntity) {
        if (selectedIds.isNotEmpty()) toggleSelection(recording) else contextMenuRecordingId = recording.id
    }
    fun dismissMenu() { contextMenuRecordingId = null }
    fun rename(recording: RecordingEntity) {
        contextMenuRecordingId = null; setExpandedRecording(null)
        renameRecording = recording; showRenameDialog = true
    }
    fun info(recording: RecordingEntity) {
        contextMenuRecordingId = null; setExpandedRecording(null)
        infoRecording = recording; showInfoDialog = true
    }
    fun share(recording: RecordingEntity) { contextMenuRecordingId = null; shareRecordings(listOf(recording)) }
    fun shareSelection() = shareRecordings(selectedIds.values.toList())
    fun trim(recording: RecordingEntity) {
        contextMenuRecordingId = null
        if (!recordingHasStableTrimIdentity(recording)) {
            showPassiveNotice(resources.getString(R.string.recording_unavailable), FeedbackTone.ERROR)
        } else {
            setExpandedRecording(recording.id)
            trimRequestRecordingId = recording.id
        }
    }
    fun multiSelect(recording: RecordingEntity) {
        contextMenuRecordingId = null; setExpandedRecording(null)
        selectedIds[recording.id] = recording; syncSelectionActive()
    }
    fun collapse() = setExpandedRecording(null)
    fun trimRequestConsumed(recordingId: String) {
        if (trimRequestRecordingId == recordingId) trimRequestRecordingId = null
    }
    fun waveformCached(cached: RecordingEntity) {
        recordings = recordings.map { current ->
            if (current.id == cached.id && isValidRecordingWaveformCache(current, cached.waveformData, cached.waveformRevision))
                current.copy(waveformData = cached.waveformData, waveformRevision = cached.waveformRevision)
            else current
        }
    }
    fun playbackFailed(recording: RecordingEntity) {
        finishInlinePlaybackFailure(
            retireFailedPlayer = {
                val current = expandedRecordingOwner?.recordingId == recording.id
                if (current) setExpandedRecording(null)
                current
            },
            navigationAllowed = ::navigationAllowed,
            openRecording = { context.startActivity(RecordingOpenActivity.intentFor(context, recording)) },
            reportUnavailable = {
                showPassiveNotice(resources.getString(R.string.recording_unavailable), FeedbackTone.ERROR)
                if (navigationAllowed()) refresh(showSpinner = false)
            })
    }
    fun dismissRename() { showRenameDialog = false; renameRecording = null }
    fun renamed(renamed: RecordingEntity) {
        val updated = recordings.map { if (it.id == (renameRecording?.id ?: renamed.id)) renamed else it }
        recordings = updated
        input.callbacks.onVisibleRecordingsChanged(updated.filterNot { it.id in pendingDeletions })
        dismissRename(); clearSelection(); refresh(showSpinner = false)
    }
    fun renameUncertain() {
        dismissRename(); clearSelection()
        showPassiveNotice(resources.getString(R.string.rename_recording_failed), FeedbackTone.ERROR)
        refresh(showSpinner = false)
    }
    fun dismissInfo() { showInfoDialog = false; infoRecording = null }

    fun showPassiveNotice(message: String, tone: FeedbackTone) {
        AppFeedbackCenter.post(message, tone)
    }

    fun navigationAllowed(): Boolean = input.active &&
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

    fun clearUndoNotice(window: Long? = null) {
        if (window != null && undoNotice?.window != window) return
        undoNotice = null
        input.callbacks.onUndoVisibleChange(false)
    }

    fun setExpandedRecording(id: String?) {
        if (expandedRecordingOwner?.recordingId != id) {
            expandedRecordingOwner = id?.let(::InlinePlaybackRowOwner)
        }
        if (id == null) trimRequestRecordingId = null
        input.callbacks.onExpandedRecordingActiveChange(id != null)
    }

    fun syncSelectionActive() {
        input.callbacks.onSelectionActiveChange(selectedIds.isNotEmpty())
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
        if (!input.active) return
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
            recordings = recordingsAfterForegroundDeletion(recordings, deletedTargets)
            releaseCompletedForegroundDeletionTargets(pendingDeletions, pending)
        }
        val deletePhaseFailed = deletionBatchFailed(pending.size, deleted, failed)
        try {
            val refreshed = if (deletePhaseFailed) {
                RecordingRepository.refresh(context)
            } else {
                librarySnapshotWithoutWaveformTrust(RecordingRepository.listKnown(context))
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
        if (deletionBatchFailed(pending.size, deleted, failed)) {
            if (input.active) {
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
                invalidate()
            }
        }
        invalidate()
    }

    fun clearSelection() {
        selectedIds.clear()
        syncSelectionActive()
    }

    fun deleteRecordings(targets: Collection<RecordingEntity>) {
        if (targets.isEmpty()) return
        val undoWindow = deletionState.extendUndoWindow() ?: return
        if (targets.any { it.id == expandedRecordingOwner?.recordingId }) {
            setExpandedRecording(null)
        }
        targets.forEach { pendingDeletions[it.id] = it }
        input.callbacks.onVisibleRecordingsChanged(recordings.filterNot { it.id in pendingDeletions })
        clearSelection()
        contextMenuRecordingId = null
        val count = pendingDeletions.size
        val message = if (count == 1) resources.getString(R.string.recording_deleted)
        else resources.getQuantityString(R.plurals.recordings_deleted, count, count)
        input.callbacks.onUndoVisibleChange(input.active)
        undoNotice = NativeLibraryUndoNotice(message, undoWindow)
        deletionJob[0]?.cancel()
        deletionJob[0] = scope.launch {
            try {
                runLibraryUndoDeadline(deletionState, undoWindow, accessibilityManager) {
                    clearUndoNotice(undoWindow)
                    // Deadline owns the foreground phase before the notice/job is cleared.
                    deletionJob[0] = null
                    invalidate()
                    finalizeDeletions()
                }
            } finally {
                // runLibraryUndoDeadline completes the phase in its own finally first.
                invalidate()
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
        input.callbacks.onVisibleRecordingsChanged(recordings)
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

    override fun close() {
        if (closed) return
        closed = true
        // Accepted background/foreground deletion owners survive actual UI disposal.
        removeLifecycle(input.active, input.callbacks)
        input.callbacks.onUndoVisibleChange(false)
        val currentObserver = if (focusObserver.isAlive) focusObserver else hostView.viewTreeObserver
        if (currentObserver.isAlive) currentObserver.removeOnWindowFocusChangeListener(focusListener)
        retireShareRequest()
        input.callbacks.onSelectionActiveChange(false)
        input.callbacks.onExpandedRecordingActiveChange(false)
        scope.cancel()
        main.removeCallbacks(delivery)
        deliveryPosted = false
    }

    private class ObservedRecordingMap(private val changed: () -> Unit) : LinkedHashMap<String, RecordingEntity>() {
        override fun put(key: String, value: RecordingEntity): RecordingEntity? {
            val previous = super.put(key, value)
            if (previous != value) changed()
            return previous
        }
        override fun remove(key: String): RecordingEntity? = super.remove(key).also { if (it != null) changed() }
        override fun clear() { if (isNotEmpty()) { super.clear(); changed() } }
        override fun putAll(from: Map<out String, RecordingEntity>) { from.forEach { (key, value) -> put(key, value) } }
    }
}
