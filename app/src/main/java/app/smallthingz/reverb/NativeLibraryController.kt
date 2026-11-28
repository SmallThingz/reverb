package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.platform.AccessibilityManager
import smallthingz.ui.util.closePreservingPrimaryFailure
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
    private val submitBackgroundDeletion: (Context, List<RecordingEntity>) -> Deferred<RecordingDeletionBatchResult> =
        RecordingRepository::deleteInBackground,
    private val playbackState: StateFlow<RecordingPlaybackState> = RecordingPlayback.state,
    private val refreshRecordings: suspend (Context) -> List<RecordingEntity> = RecordingRepository::refresh,
    private val planDeletion: suspend (Context, List<RecordingEntity>) -> RecordingDeletionPlan =
        RecordingDeletionConsent::planDeletion,
    private val enqueueDeletionPrompt: (List<RecordingDeletionConsentTarget>) -> Deferred<Unit> =
        RecordingDeletionConsent::enqueueImmediate,
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
    private var loadFailed by observed(false, ::pendingSaveSectionChanged)
    private val refreshGeneration = LibraryRefreshGeneration { isRefreshing = it }
    private val selectedIds = ObservedRecordingMap(::invalidate)
    private val pendingDeletions = ObservedRecordingMap(::visibleChanged)
    private val localTrimBusyOwners = mutableMapOf<Any, String>()
    private val deletionState = LibraryDeletionState()
    private var checkingDeletionConsent by observed(false)
    private var leftDuringDeletionCheck = false
    private var awaitingDeletionPrompt by observed(false)
    private var deletionPlaybackTargets = emptyList<RecordingEntity>()
    private var renameRecording by observed<RecordingEntity?>(null)
    private var infoRecording by observed<RecordingEntity?>(null)
    private var expandedRecordingOwner by observed<InlinePlaybackRowOwner?>(null)
    private var openingPlayback = playbackState.value.takeIf { initial.active && it.playing }
    private var playbackRestoreReady = false
    private var playbackRestoreRefreshFinished = false
    private var playbackRestoreCatalog: List<RecordingEntity>? = null
    private var playbackRestoreReveal: RecordingEntity? = null
    private var automaticPlaybackOwner: InlinePlaybackRowOwner? = null
    private var waveformCatalogReady = false
    private var playingRecording = playbackState.value.let { state -> state.recording.takeIf { state.playing } }
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
    private val playbackRoute = RecordingPlaybackRoute()
    private var beforeRequestedPlayback: (() -> Unit)? = null
    private var playbackRequestFinished: ((RecordingEntity?) -> Unit)? = null
    private val focusObserver = hostView.viewTreeObserver
    private val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) {
            shareDelivery[0]?.foregroundChanged()
            if (playbackRoute.request != null) invalidate()
        }
    }
    private var lastVisible: List<RecordingEntity>? = null
    private var lastListItems: List<LibraryListItem> = emptyList()
    private var finishedListItems: List<LibraryListItem> = emptyList()
    private var dateHeadersDirty = true
    private var visibleRecordingIdentities = emptyMap<String, String>()
    private var pendingSaves = DurableSaveJobs.state.value
    private var pendingOutputIdentities = pendingSaveOutputIdentities(pendingSaves)
    private var pendingItemsDirty = true
    private var saveJobsReadiness = DurableSaveJobs.readiness.value
    private var visibleEffect: Job? = null
    private var lastSelectionActive: Boolean? = null
    private var selectionEffect: Job? = null
    private var lastExpandedId: String? = null
    private var expandedEffectStarted = false
    private var expandedEffect: Job? = null

    init {
        focusObserver.addOnWindowFocusChangeListener(focusListener)
        installLifecycle(initial.active)
        scope.launch {
            RecordingDeletionConsent.completions.collect { revision ->
                if (revision > 0 && input.active) refresh(showSpinner = false)
            }
        }
        // collectAsState is whole-owner collection in the released implementation, not STARTED-only.
        scope.launch {
            recordingMutations.activeIds.collect { activeMutationRecordingIds = it; invalidate() }
        }
        scope.launch {
            DurableSaveJobs.state.collect { jobs ->
                val completed = durableSaveCompletionNeedsCatalogRefresh(pendingSaves, jobs, visibleRecordingIdentities)
                pendingSaves = jobs
                val outputs = pendingSaveOutputIdentities(jobs)
                if (outputs != pendingOutputIdentities) {
                    pendingOutputIdentities = outputs
                    visibleDirty = true
                }
                pendingItemsDirty = true
                invalidate()
                if (completed && input.active) refresh()
            }
        }
        scope.launch {
            DurableSaveJobs.readiness.collect {
                saveJobsReadiness = it
                pendingItemsDirty = true
                invalidate()
            }
        }
        scope.launch {
            playbackState.collect { state ->
                val next = state.recording.takeIf { state.playing }
                val previous = playingRecording
                // Position/anchor ticks never publish row state or rebuild the catalog.
                if (previous !== next && (previous == null || next == null ||
                        !samePlaybackRecording(previous, next))) {
                    playingRecording = next
                    if (input.active) invalidate()
                }
            }
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
    fun dateFormattingChanged() {
        if (closed) return
        dateHeadersDirty = true
        invalidate()
    }
    private fun invalidate() {
        if (closed || deliveryPosted) return
        deliveryPosted = true
        main.post(delivery)
    }

    /** Publish retained rows or the actual loading state before the first attached draw. */
    fun publishInitialPresentation() {
        if (closed || lastVisible != null || !deliveryPosted) return
        main.removeCallbacks(delivery)
        delivery.run()
    }

    fun bind(next: NativeLibraryInput) {
        check(!closed)
        val previous = input
        // rememberUpdatedState already exposes the new active/callback values during effect cleanup.
        input = next
        if (!previous.active && next.active) {
            val playback = playbackState.value
            openingPlayback = playback.takeIf { it.playing }
            playingRecording = playback.recording.takeIf { playback.playing }
            playbackRestoreCatalog = null
            playbackRestoreRefreshFinished = false
        }
        if (previous.active != next.active) {
            playbackRestoreReady = false
            waveformCatalogReady = false
            var failure = if (!next.active) closePreservingPrimaryFailure(null) { finishPlaybackRequest(null) } else null
            // Source active-keyed DisposableEffect is disposed before its successor effects.
            // Finish old accepted handoffs even if a callback closes the new UI lifetime.
            failure = closePreservingPrimaryFailure(failure) { removeLifecycle(previous.active, previous.callbacks) }
            failure?.let { throw it }
            if (closed) return
            input.callbacks.onUndoVisibleChange(false)
            if (closed) return
        }
        if (previous.initialRecordings != next.initialRecordings) launchInitialEffect(next.initialRecordings)
        if (closed) return
        if (previous.active != next.active) {
            installLifecycle(next.active)
            if (closed) return
            launchActiveEffect(next.active)
            if (closed) return
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
            renameRecording = null
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

        playbackRestoreReady = true
        invalidate()

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
        if (closed) return
        var observerInstalled = false
        val observer = LifecycleEventObserver { _, event ->
            if (closed) return@LifecycleEventObserver
            if (event == Lifecycle.Event.ON_PAUSE) {
                retireShareRequest()
                playbackRoute.invalidateProof()
            }
            if (event == Lifecycle.Event.ON_RESUME) shareDelivery[0]?.foregroundChanged()
            if (active && observerInstalled && event == Lifecycle.Event.ON_RESUME) refresh(showSpinner = false)
            if (event == Lifecycle.Event.ON_STOP && !awaitingDeletionPrompt && (checkingDeletionConsent || pendingDeletions.isNotEmpty())) {
                val failure = closePreservingPrimaryFailure(null) { clearUndoNotice() }
                closePreservingPrimaryFailure(failure, ::commitPendingDeletionsInBackground)?.let { throw it }
            }
        }
        installedLifecycle = observer
        lifecycleOwner.lifecycle.addObserver(observer)
        observerInstalled = true
    }
    private fun removeLifecycle(wasActive: Boolean, callbacks: NativeLibraryCallbacks) {
        val observer = installedLifecycle
        installedLifecycle = null
        var failure = closePreservingPrimaryFailure(null) { observer?.let(lifecycleOwner.lifecycle::removeObserver) }
        if (wasActive) {
            // A renderer failure cannot strand an already accepted deletion at UI teardown.
            failure = closePreservingPrimaryFailure(failure, ::clearUndoNotice)
            failure = closePreservingPrimaryFailure(failure) { deletionJob[0]?.cancel() }
            failure = closePreservingPrimaryFailure(failure, ::commitPendingDeletionsInBackground)
            failure = closePreservingPrimaryFailure(failure, callbacks.onParentRefreshRequested)
        }
        failure?.let { throw it }
    }

    private fun deliver() {
        val visible = if (visibleDirty || lastVisible == null) {
            visibleDirty = false
            val catalogRows = recordings.filterNot { it.id in pendingDeletions }
            // Acknowledgement observes the raw catalog, including rows still hidden by active jobs.
            visibleRecordingIdentities = catalogRows.associate { it.id to it.fileIdentity }
            catalogRows.filterNot { recordingOwnedByPendingSave(it, pendingOutputIdentities) }
        } else checkNotNull(lastVisible)
        if (visible != lastVisible) {
            val previous = lastVisible
            lastVisible = visible
            dateHeadersDirty = true
            val visibleById = visible.associateBy { it.id }
            reconcileSelection(visibleById)
            if (previous != null) reconcileTransientRecordings(previous.associateBy { it.id }, visibleById)
            pendingItemsDirty = true
            visibleEffect?.cancel()
            val callback = input.callbacks.onVisibleRecordingsChanged
            visibleEffect = scope.launch { callback(visible) }
        }
        // Reconcile the previous visible owners before installing a newly discovered requested row.
        deliverPlaybackRequest()
        if (closed) return
        if (playbackRestoreReady) restorePlayingRecordingOnOpen(visible)
        if (dateHeadersDirty) {
            dateHeadersDirty = false
            finishedListItems = buildLibraryListItems(context, visible)
            pendingItemsDirty = true
        }
        if (pendingItemsDirty) {
            pendingItemsDirty = false
            val recoveryStatus = when {
                saveJobsReadiness.recoveryFailure != null -> resources.getString(R.string.save_jobs_recovery_failed)
                !saveJobsReadiness.loaded -> resources.getString(R.string.save_jobs_recovering)
                else -> null
            }
            val pendingItems = listOfNotNull(recoveryStatus?.let { LibraryListItem.SaveRecoveryStatus(it) }) +
                buildPendingSaveSection(pendingSaves, visibleRecordingIdentities, saveJobsReadiness,
                    if (loadFailed) resources.getString(R.string.recordings_refresh_failed) else null)
            lastListItems = LibraryListSnapshot.publish(lastListItems, pendingItems, finishedListItems)
            DurableSaveJobs.acknowledgePublished(visibleRecordingIdentities)
        }
        val selected = selectedIds.isNotEmpty()
        if (lastSelectionActive != selected) {
            lastSelectionActive = selected
            selectionEffect?.cancel()
            val callback = input.callbacks.onSelectionActiveChange
            selectionEffect = scope.launch { callback(selected) }
        }
        val expandedId = renameRecording?.id ?: infoRecording?.id ?: expandedRecordingOwner?.recordingId
        if (!expandedEffectStarted || expandedId != lastExpandedId) {
            expandedEffectStarted = true
            lastExpandedId = expandedId
            expandedEffect?.cancel()
            val callback = input.callbacks.onExpandedRecordingActiveChange
            expandedEffect = scope.launch { callback(expandedId != null) }
        }
        publish(NativeLibraryPresentation(
            list = LibraryNativeListState(lastListItems, selectedIds.keys.toSet(), selected,
                activeMutationRecordingIds, localTrimBusyOwners.values.toSet(), expandedRecordingOwner,
                trimRequestRecordingId, input.active, infoRecording?.id, renameRecording?.id, playingRecording,
                waveformReadAllowed = automaticPlaybackOwner == null ||
                    expandedRecordingOwner !== automaticPlaybackOwner || waveformCatalogReady),
            recordings = visible,
            hasLoaded = hasLoaded && saveJobsReadiness.loaded,
            loadFailed = loadFailed || saveJobsReadiness.recoveryFailure != null,
            isRefreshing = isRefreshing,
            selectedCount = selectedIds.size,
            selectionHasBusyMutation = selectedIds.keys.any { it in activeMutationRecordingIds },
            acceptsDeletions = deletionState.acceptsDeletions && !checkingDeletionConsent && !awaitingDeletionPrompt,
            undo = undoNotice,
            menuRecording = contextMenuRecordingId?.let { id -> visible.firstOrNull { it.id == id } },
            renameRecording = renameRecording,
            infoRecording = infoRecording,
        ))
    }

    /** A retired row lease can only clear its own lock. The process reservation is independent. */
    fun localBusy(recordingId: String, busy: Boolean, lease: Any = recordingId) {
        val changed = if (busy) localTrimBusyOwners.put(lease, recordingId) != recordingId
            else localTrimBusyOwners.remove(lease) != null
        if (changed) invalidate()
    }
    private fun isLocallyBusy(recordingId: String): Boolean = recordingId in localTrimBusyOwners.values
    private fun expandedTrimSaveInFlight(): Boolean =
        expandedRecordingOwner?.recordingId?.let(::isLocallyBusy) == true

    fun isCurrentPlaybackOwner(owner: InlinePlaybackRowOwner) = !closed && input.active && expandedRecordingOwner === owner
    private fun pendingSaveOwns(recording: RecordingEntity): Boolean =
        recordingOwnedByPendingSave(recording, pendingSaveOutputIdentities(DurableSaveJobs.state.value))
    private fun actionableCatalogRows(rows: List<RecordingEntity>): List<RecordingEntity> {
        val outputs = pendingSaveOutputIdentities(DurableSaveJobs.state.value)
        return rows.filterNot { it.id in pendingDeletions || recordingOwnedByPendingSave(it, outputs) }
    }
    fun fallbackExpandedBack() = finishLibraryExpandedBack(expandedRecordingOwner,
        { input.active && selectedIds.isEmpty() && expandedRecordingOwner === it },
        { isLocallyBusy(it) || recordingMutations.isActive(it) }, { setExpandedRecording(null) })

    fun activate(recording: RecordingEntity) {
        if (expandedTrimSaveInFlight() || renameRecording != null) return
        if (infoRecording != null) { dismissInfo(); return }
        if (pendingSaveOwns(recording)) return
        contextMenuRecordingId = null
        if (selectedIds.isNotEmpty()) toggleSelection(recording)
        else setExpandedRecording(if (expandedRecordingOwner?.recordingId == recording.id) null else recording.id)
    }
    fun leadingLongClick(recording: RecordingEntity) {
        if (expandedTrimSaveInFlight() || renameRecording != null || infoRecording != null) return
        if (pendingSaveOwns(recording)) return
        contextMenuRecordingId = null
        setExpandedRecording(null)
        toggleSelection(recording)
    }
    fun longClick(recording: RecordingEntity) {
        if (renameRecording != null || infoRecording != null || isLocallyBusy(recording.id)) return
        if (pendingSaveOwns(recording)) return
        if (selectedIds.isNotEmpty()) toggleSelection(recording) else contextMenuRecordingId = recording.id
    }
    fun dismissMenu() { contextMenuRecordingId = null }
    fun rename(recording: RecordingEntity) {
        if (expandedTrimSaveInFlight() || pendingSaveOwns(recording)) return
        retireShareRequest(reportUndeliveredFailure = false)
        contextMenuRecordingId = null
        infoRecording = null
        renameRecording = recording
        setExpandedRecording(null)
    }
    fun info(recording: RecordingEntity) {
        // A menu callback may outlive its published enabled state. Check live ownership before
        // collapsing any player/trim presentation or installing Info.
        if (expandedTrimSaveInFlight() || renameRecording != null || pendingSaveOwns(recording) ||
            recordingMutations.isActive(recording.id) || isLocallyBusy(recording.id)) return
        contextMenuRecordingId = null
        infoRecording = recording
        setExpandedRecording(null)
    }
    fun share(recording: RecordingEntity) { contextMenuRecordingId = null; shareRecordings(listOf(recording)) }
    fun shareSelection() = shareRecordings(selectedIds.values.toList())
    fun trim(recording: RecordingEntity) {
        if (expandedTrimSaveInFlight() || pendingSaveOwns(recording)) return
        contextMenuRecordingId = null
        if (!recordingHasStableTrimIdentity(recording)) {
            showPassiveNotice(resources.getString(R.string.recording_unavailable), FeedbackTone.ERROR)
        } else {
            setExpandedRecording(recording.id)
            trimRequestRecordingId = recording.id
        }
    }
    fun multiSelect(recording: RecordingEntity) {
        if (expandedTrimSaveInFlight() || pendingSaveOwns(recording)) return
        contextMenuRecordingId = null; setExpandedRecording(null)
        selectedIds[recording.id] = recording; syncSelectionActive()
    }
    fun collapse() {
        if (!expandedTrimSaveInFlight()) setExpandedRecording(null)
    }
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
    fun playbackFailed(recording: RecordingEntity, failure: Throwable? = null) {
        if (isLocallyBusy(recording.id)) return
        finishInlinePlaybackFailure(
            retireFailedPlayer = {
                val current = expandedRecordingOwner?.recordingId == recording.id
                if (current) setExpandedRecording(null)
                current
            },
            navigationAllowed = ::navigationAllowed,
            openRecording = { context.startActivity(RecordingOpenActivity.intentFor(context, recording)) },
            reportUnavailable = {
                showPassiveNotice(actionableErrorMessage(context, resources.getString(R.string.recording_unavailable), failure), FeedbackTone.ERROR)
                if (navigationAllowed()) refresh(showSpinner = false)
            })
    }
    fun dismissRename() { renameRecording = null }
    fun renamed(original: RecordingEntity, renamed: RecordingEntity) {
        // Completion belongs to the accepted target, never whichever editor is currently open.
        playbackRoute.invalidateProof()
        val updated = libraryRecordingsAfterRename(recordings, original, renamed)
        recordings = updated
        input.callbacks.onVisibleRecordingsChanged(actionableCatalogRows(updated))
        if (renameRecording?.let { sameRecordingActionTarget(it, original) } == true) dismissRename()
        clearSelection(); refresh(showSpinner = false)
    }
    fun renameUncertain(error: Throwable) {
        playbackRoute.invalidateProof()
        dismissRename(); clearSelection()
        showPassiveNotice(actionableErrorMessage(context, resources.getString(R.string.rename_recording_uncertain), error), FeedbackTone.ERROR)
        refresh(showSpinner = false)
    }
    fun dismissInfo() { infoRecording = null }

    fun showPassiveNotice(message: String, tone: FeedbackTone) {
        AppFeedbackCenter.post(message, tone)
    }

    fun navigationAllowed(): Boolean = input.active &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        hostView.isAttachedToWindow && hostView.isShown && hostView.hasWindowFocus()

    fun retireShareRequest(reportUndeliveredFailure: Boolean = true) {
        shareGeneration[0]++
        shareJob[0]?.cancel()
        shareJob[0] = null
        val retired = shareDelivery[0]
        shareDelivery[0] = null
        retired?.retire(reportUndeliveredFailure)
    }

    fun clearUndoNotice(window: Long? = null) {
        if (window != null && undoNotice?.window != window) return
        undoNotice = null
        input.callbacks.onUndoVisibleChange(false)
    }

    private fun restorePlayingRecordingOnOpen(visible: List<RecordingEntity>) {
        val opening = openingPlayback ?: return
        if (!input.active) return
        val playback = playbackState.value
        val source = opening.recording
        if (source == null || !playback.playing || !playback.matches(source) ||
            playback.generation != opening.generation ||
            playback.transportIntentRevision != opening.transportIntentRevision ||
            expandedRecordingOwner != null || renameRecording != null || infoRecording != null ||
            contextMenuRecordingId != null || selectedIds.isNotEmpty() || trimRequestRecordingId != null) {
            cancelPlaybackRestore()
            return
        }
        // A cheap catalog miss may recover on the first authoritative refresh. Other UI
        // publications reuse this same list and must not scan it again while waiting.
        if (playbackRestoreCatalog === visible && !playbackRestoreRefreshFinished) return
        playbackRestoreCatalog = visible
        val recording = visible.firstOrNull(playback::matches)
        if (recording == null) {
            if (playbackRestoreRefreshFinished) cancelPlaybackRestore()
            return
        }
        cancelPlaybackRestore()
        if (recording.id in pendingDeletions || pendingSaveOwns(recording) ||
            recordingMutations.isActive(recording.id) || isLocallyBusy(recording.id)) return
        // Restoring presentation cannot grant an initial seek or restart service playback.
        val owner = InlinePlaybackRowOwner(recording.id, automaticRestore = true).apply { consumeInitialAutoStart() }
        playbackRestoreReveal = recording
        automaticPlaybackOwner = owner
        expandedRecordingOwner = owner
    }

    /** Existing list input cancellation also retires the deferred reopen intent. */
    fun cancelPlaybackRestore() {
        openingPlayback = null
        playbackRestoreCatalog = null
        playbackRestoreReveal = null
    }

    /** One keyed viewport request after the restored row has entered the adapter. */
    fun takePlaybackRestoreReveal(): String? {
        val recording = playbackRestoreReveal ?: return null
        playbackRestoreReveal = null
        val playback = playbackState.value
        return recording.id.takeIf { input.active && expandedRecordingOwner?.recordingId == it &&
            playback.playing && playback.matches(recording) }
    }

    private fun setExpandedRecording(id: String?, cancelRestore: Boolean = true) {
        if (cancelRestore) {
            cancelPlaybackRestore()
            automaticPlaybackOwner = null
        }
        if (expandedRecordingOwner?.recordingId != id) {
            expandedRecordingOwner = id?.let(::InlinePlaybackRowOwner)
        }
        if (id == null) trimRequestRecordingId = null
        input.callbacks.onExpandedRecordingActiveChange(id != null || renameRecording != null || infoRecording != null)
    }

    fun syncSelectionActive() {
        input.callbacks.onSelectionActiveChange(selectedIds.isNotEmpty())
    }

    fun toggleSelection(recording: RecordingEntity) {
        if (expandedTrimSaveInFlight() || pendingSaveOwns(recording)) return
        cancelPlaybackRestore()
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
        infoRecording = infoRecording?.let { previous ->
            storedById[previous.id]?.takeIf { current -> sameRecordingActionTarget(previous, current) }
        }

        val retainedExpandedId = retainId(expandedRecordingOwner?.recordingId)
        setExpandedRecording(retainedExpandedId, cancelRestore = false)
        trimRequestRecordingId = retainId(trimRequestRecordingId)?.takeIf { it == retainedExpandedId }
    }

    private fun pendingSaveSectionChanged() {
        pendingItemsDirty = true
        invalidate()
    }

    fun retryFailedSaves() {
        performLibraryFailedSaveRetry(!closed && navigationAllowed(), DurableSaveJobs.state.value,
            DurableSaveJobs.readiness.value, catalogFailed = loadFailed) {
            refresh(showSpinner = true, userInitiated = true)
        }
    }

    fun requestPlayback(request: RecordingPlaybackRequest, beforePlayback: () -> Unit,
                        finished: (RecordingEntity?) -> Unit) {
        if (closed || !playbackRoute.submit(request)) return
        RecordingDeletionConsent.watchPlayback(playbackRoute)
        cancelPlaybackRestore()
        beforeRequestedPlayback = beforePlayback
        playbackRequestFinished = finished
        refresh(showSpinner = false)
    }

    private fun deliverPlaybackRequest() {
        val pendingRequest = playbackRoute.request ?: return
        if (deletionPlaybackTargets.any(pendingRequest::matches)) return
        // Rename owns its draft and terminal presentation until its own dismissal.
        val request = RecordingDeletionConsent.takePlaybackIfReady(playbackRoute,
            renameRecording == null && !expandedTrimSaveInFlight() && navigationAllowed(),
        ) ?: return
        val recording = recordings.firstOrNull(request::matches)?.takeUnless {
            it.id in pendingDeletions || pendingSaveOwns(it) || recordingMutations.isActive(it.id)
        }
        if (recording == null) {
            showPassiveNotice(resources.getString(R.string.recording_unavailable), FeedbackTone.ERROR)
            finishPlaybackRequest(null)
            return
        }
        // Consume row auto-start before installing it: this explicit request owns the only start.
        clearSelection()
        contextMenuRecordingId = null
        dismissInfo()
        trimRequestRecordingId = null
        setExpandedRecording(recording.id)
        expandedRecordingOwner?.consumeInitialAutoStart()
        beforeRequestedPlayback?.invoke()
        RecordingPlayback.play(context, recording)
        finishPlaybackRequest(recording)
    }

    private fun finishPlaybackRequest(recording: RecordingEntity?) {
        RecordingDeletionConsent.retirePlayback(playbackRoute)
        playbackRoute.clear()
        beforeRequestedPlayback = null
        val finished = playbackRequestFinished
        playbackRequestFinished = null
        finished?.invoke(recording)
    }

    fun refresh(showSpinner: Boolean = true, userInitiated: Boolean = false) {
        if (!input.active) return
        if (retryDurableSavesForLibraryRefresh(DurableSaveJobs.readiness.value, DurableSaveJobs.state.value, userInitiated)) {
            DurableSaveJobs.retryRecovery()
        }
        if (!firstRefreshReady[0]) {
            if (showSpinner) isRefreshing = true
            deferredRefreshSpinner[0] = deferredRefreshSpinner[0] || showSpinner
            return
        }
        val generation = refreshGeneration.request(showSpinner)
        // A recreated host may scan during a process-owned prompt. Only a scan admitted
        // after settlement can verify that notification. The completion collector requests it.
        val playbackProofGeneration = playbackRoute.proofGeneration
        val playbackRequestToken = playbackRoute.request?.takeUnless(RecordingDeletionConsent::holdsPlayback)?.token
        loadFailed = false
        refreshQueue.request {
            if (!refreshGeneration.isCurrent(generation)) return@request
            try {
                // A refresh admitted before handoff may otherwise miss the new repository job.
                // Only this exact settled batch may use this scan to recover an unknown result.
                val backgroundRevision = deletionState.backgroundCommitRevision
                val backgroundReceipt = backgroundDeletionReceipt[0]
                backgroundReceipt?.join()
                val stored = refreshRecordings(context)
                if (!refreshGeneration.isCurrent(generation)) return@request
                val previousById = recordings.associateBy { it.id }
                recordings = stored
                playbackRestoreRefreshFinished = true
                waveformCatalogReady = true
                if (openingPlayback != null || automaticPlaybackOwner != null) invalidate()
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
                } else if (!(checkingDeletionConsent && leftDuringDeletionCheck)) {
                    // The accepted stop handoff is waiting for its direct subset. Preserve its exact snapshot.
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
                                resources.getString(R.string.delete_error_changed),
                                FeedbackTone.ERROR,
                            )
                        }
                    }
                }
                playbackRoute.verified(playbackRequestToken, playbackProofGeneration)
                if (playbackRequestToken != null) invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (refreshGeneration.isCurrent(generation)) {
                    playbackRestoreRefreshFinished = true
                    if (openingPlayback != null) invalidate()
                    hasLoaded = false
                    loadFailed = true
                    showPassiveNotice(
                        actionableErrorMessage(context, resources.getString(R.string.recordings_refresh_failed), error),
                        FeedbackTone.ERROR,
                    )
                    finishPlaybackRequest(null)
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
        var awaitingConsent = 0
        var failed = false
        var failureMessage = ""
        val deletedTargets = mutableListOf<RecordingEntity>()
        // Once the undo window has closed, the user's delete is committed. Keep both the physical
        // identity-bound batch and its terminal optimistic-state reconciliation alive through
        // panel/lifecycle disposal. A cancelled owner must not leave a completed attempt hidden in
        // pendingDeletions after the irreversible delete phase has already run.
        withContext(NonCancellable) {
            val result = try {
                RecordingRepository.deleteBatch(context, pending)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RecordingDeletionBatchResult(List(pending.size) { false }, false,
                    failures = pending.indices.associateWith {
                        RecordingDeletionFailure(RecordingDeletionFailureReason.CATALOG_UNAVAILABLE, error)
                    })
            }
            failed = result.cleanupFailed
            awaitingConsent = result.consentIndices.size
            failureMessage = recordingDeletionBatchFailureMessage(context, pending, result)
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
        val deletePhaseFailed = deletionBatchFailed(pending.size, deleted + awaitingConsent, failed)
        try {
            val refreshed = if (deletePhaseFailed) {
                RecordingRepository.refresh(context)
            } else {
                librarySnapshotWithoutWaveformTrust(RecordingRepository.listKnown(context))
            }
            if (refreshGeneration.isCurrent(generation)) {
                recordings = if (!deletePhaseFailed && input.active) {
                    // The cheap read supplies metadata. Only this active session's current rows
                    // can supply an already-trusted waveform at the same content revision.
                    val currentById = recordings.associateBy { it.id }
                    refreshed.map { row ->
                        val current = currentById[row.id]
                        if (current != null && current.waveformRevision.isNotEmpty() && row.missingSinceMillis == null &&
                            current.waveformRevision == recordingWaveformRevision(row) &&
                            isValidRecordingWaveformCache(current, current.waveformData, current.waveformRevision)
                        ) {
                            row.copy(waveformData = current.waveformData, waveformRevision = current.waveformRevision)
                        } else {
                            row
                        }
                    }
                } else {
                    refreshed
                }
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
                    failureMessage,
                    FeedbackTone.ERROR,
                )
            }
            throw cancelled
        } catch (error: Exception) {
            if (refreshGeneration.isCurrent(generation)) {
                failed = true
                val refreshFailure = actionableErrorMessage(context,
                    resources.getString(R.string.delete_error_catalog), error)
                failureMessage = if (deletePhaseFailed) "$failureMessage\n$refreshFailure" else refreshFailure
                hasLoaded = false
                loadFailed = true
            }
        }
        if (deletionBatchFailed(pending.size, deleted + awaitingConsent, failed)) {
            if (input.active) {
                showPassiveNotice(
                    failureMessage,
                    FeedbackTone.ERROR,
                )
            } else {
                AppFeedbackCenter.post(
                    failureMessage,
                    FeedbackTone.ERROR,
                )
            }
        }
    }

    fun commitPendingDeletionsInBackground() {
        if (checkingDeletionConsent) {
            leftDuringDeletionCheck = true
            return
        }
        handOffPendingDeletions()
    }

    private fun handOffPendingDeletions() {
        val revision = deletionState.handoffToBackground(pendingDeletions.isNotEmpty()) ?: return
        // A scan admitted before handoff cannot restore pre-delete rows after receipt settlement.
        refreshGeneration.invalidate()
        deletionJob[0]?.cancel()
        deletionJob[0] = null
        val pending = pendingDeletions.values.toList()
        val receipt = submitBackgroundDeletion(context, pending)
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
        if (closed || checkingDeletionConsent || awaitingDeletionPrompt || !deletionState.acceptsDeletions) return
        if (targets.any { isLocallyBusy(it.id) || recordingMutations.isActive(it.id) || pendingSaveOwns(it) }) return
        val batch = targets.filterNot { it.id in pendingDeletions }
        if (batch.isEmpty()) return
        val checkConsent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && batch.any { it.storageType == RecordingStorageType.MEDIASTORE }
        cancelPlaybackRestore()
        batch.forEach(RecordingPlayback::stop)
        if (!checkConsent) {
            offerDeletionUndo(batch)
        } else {
            checkDeletionConsent(batch)
        }
        if (closed) return
        if (batch.any { it.id == expandedRecordingOwner?.recordingId }) setExpandedRecording(null)
        clearSelection()
        contextMenuRecordingId = null
    }

    private fun checkDeletionConsent(batch: List<RecordingEntity>) {
        deletionPlaybackTargets = batch
        checkingDeletionConsent = true
        leftDuringDeletionCheck = false
        // Keep the prior Undo token usable while permission checks run. Its deadline must not race
        // an arriving direct subset or expire behind the platform dialog.
        deletionJob[0]?.cancel()
        deletionJob[0] = null
        val app = context.applicationContext
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    val plan = try {
                        planDeletion(app, batch)
                    } catch (error: Exception) {
                        // A failed advisory check has no authority over the new targets. Settle the
                        // earlier accepted Undo according to its own lifetime, even on cancellation.
                        if (closed || leftDuringDeletionCheck) handOffPendingDeletions()
                        else undoNotice?.let { startUndoDeadline(it.window) }
                        if (error is CancellationException) throw error
                        showPassiveNotice(actionableErrorMessage(app, app.getString(R.string.delete_error_catalog), error),
                            FeedbackTone.ERROR)
                        return@withContext
                    }
                    val completion = if (plan.prompt.isEmpty()) null else {
                        if (!closed) awaitingDeletionPrompt = true
                        enqueueDeletionPrompt(plan.prompt)
                    }
                    try {
                        if (closed || leftDuringDeletionCheck) {
                            plan.undo.forEach { pendingDeletions[it.id] = it }
                        } else if (plan.undo.isNotEmpty()) {
                            offerDeletionUndo(plan.undo)
                        } else if (completion == null) {
                            undoNotice?.let { startUndoDeadline(it.window) }
                        }
                    } finally {
                        // Publishing Undo can synchronously retire this owner after the check above.
                        if (closed || leftDuringDeletionCheck) handOffPendingDeletions()
                        deletionPlaybackTargets = plan.prompt.map { it.recording }
                        if (!closed && completion != null) resumeUndoAfterPrompt(completion)
                    }
                }
            } finally {
                if (!awaitingDeletionPrompt) deletionPlaybackTargets = emptyList()
                checkingDeletionConsent = false
            }
        }
    }

    private fun resumeUndoAfterPrompt(completion: Deferred<Unit>) {
        // Only the UI waiter is cancelled on disposal. The process queue still owns the exact consent batch.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            completion.await()
            lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            val request = playbackRoute.request
            val needsFreshScan = request != null && deletionPlaybackTargets.any(request::matches)
            deletionPlaybackTargets = emptyList()
            if (needsFreshScan) playbackRoute.invalidateProof()
            awaitingDeletionPrompt = false
            if (needsFreshScan) refresh(showSpinner = false)
            undoNotice?.let { startUndoDeadline(it.window) }
        }
    }

    private fun offerDeletionUndo(batch: List<RecordingEntity>) {
        val undoWindow = deletionState.extendUndoWindow() ?: return
        batch.forEach { pendingDeletions[it.id] = it }
        val count = pendingDeletions.size
        val message = if (count == 1) resources.getString(R.string.recording_deleted)
        else resources.getQuantityString(R.plurals.recordings_deleted, count, count)
        undoNotice = NativeLibraryUndoNotice(message, undoWindow)
        if (!awaitingDeletionPrompt) startUndoDeadline(undoWindow)
        try {
            input.callbacks.onVisibleRecordingsChanged(actionableCatalogRows(recordings))
            if (!closed && undoNotice?.window == undoWindow) input.callbacks.onUndoVisibleChange(input.active)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the accepted transaction and its receipt waiter alive if the renderer fails.
            if (!checkingDeletionConsent) throw error
            RecordingIncidentStore.recordUnexpectedErrorInBackground(context, "Library Undo publication", error)
            showPassiveNotice(actionableErrorMessage(context, resources.getString(R.string.delete_error_catalog), error),
                FeedbackTone.ERROR)
        }
    }

    private fun startUndoDeadline(undoWindow: Long) {
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
        if (checkingDeletionConsent && leftDuringDeletionCheck) return
        if (!deletionState.undo(undoWindow)) return
        deletionJob[0]?.cancel()
        deletionJob[0] = null
        pendingDeletions.clear()
        input.callbacks.onVisibleRecordingsChanged(actionableCatalogRows(recordings))
        clearUndoNotice(undoWindow)
        refresh(showSpinner = false)
    }

    fun shareRecordings(recordingsToShare: Collection<RecordingEntity>) {
        if (recordingsToShare.any(::pendingSaveOwns)) return
        if (recordingsToShare.isEmpty()) return
        val targets = recordingsToShare.toList()
        val chooserTitle = resources.getString(
            if (targets.size == 1) R.string.share_recording_title
            else R.string.share_recordings_title,
        )
        retireShareRequest()
        val generation = shareGeneration[0]
        fun reportShareFailure(error: Throwable? = null) {
            showPassiveNotice(actionableErrorMessage(context, resources.getString(R.string.share_recording_failed), error), FeedbackTone.ERROR)
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
                    } catch (error: RuntimeException) {
                        reportShareFailure(error)
                    }
                }
            },
            reportUndeliveredFailure = { reportShareFailure() },
            deliverFailure = { reportShareFailure(it) },
            reportUndeliveredFailureWithCause = { reportShareFailure(it) },
            finish = {
                if (shareDelivery[0] === delivery) shareDelivery[0] = null
            },
        )
        shareDelivery[0] = delivery
        shareJob[0] = scope.launch {
            try {
                var shareFailure: Throwable? = null
                val shareIntent = try {
                    withContext(Dispatchers.IO) {
                        buildShareRecordingsIntent(context, targets)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RuntimeException) {
                    shareFailure = error
                    null
                }
                delivery.complete(shareIntent, shareFailure)
            } finally {
                if (generation == shareGeneration[0]) shareJob[0] = null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        RecordingDeletionConsent.retirePlayback(playbackRoute)
        playbackRoute.clear()
        beforeRequestedPlayback = null
        playbackRequestFinished = null
        // Accepted background/foreground deletion owners survive actual UI disposal.
        var failure = closePreservingPrimaryFailure(null) { removeLifecycle(input.active, input.callbacks) }
        failure = closePreservingPrimaryFailure(failure) { input.callbacks.onUndoVisibleChange(false) }
        failure = closePreservingPrimaryFailure(failure) {
            val currentObserver = if (focusObserver.isAlive) focusObserver else hostView.viewTreeObserver
            if (currentObserver.isAlive) currentObserver.removeOnWindowFocusChangeListener(focusListener)
        }
        failure = closePreservingPrimaryFailure(failure) { retireShareRequest() }
        failure = closePreservingPrimaryFailure(failure) { input.callbacks.onSelectionActiveChange(false) }
        failure = closePreservingPrimaryFailure(failure) { input.callbacks.onExpandedRecordingActiveChange(false) }
        failure = closePreservingPrimaryFailure(failure) { scope.cancel() }
        failure = closePreservingPrimaryFailure(failure) { main.removeCallbacks(delivery) }
        deliveryPosted = false
        failure?.let { throw it }
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

/** A late accepted result can only replace its original target, including same-ID reuse checks. */
internal fun libraryRecordingsAfterRename(
    recordings: List<RecordingEntity>,
    original: RecordingEntity,
    renamed: RecordingEntity,
): List<RecordingEntity> = recordings.map { if (sameRecordingActionTarget(it, original)) renamed else it }
