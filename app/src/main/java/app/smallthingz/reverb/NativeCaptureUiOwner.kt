package app.smallthingz.reverb

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewTreeObserver
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.runtime.withFrameNanos
import app.smallthingz.nativeui.platform.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.nativeui.ReverbHomeActions
import app.smallthingz.reverb.nativeui.ReverbHomeBodyState
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Immutable presentation snapshot. TimelineSnapshot ownership stays in NativeCaptureUiOwner:
 * consumers may render it, but cannot independently close or transfer it.
 */
internal data class NativeCaptureUiState(
    val body: ReverbHomeBodyState,
    val selectedBuffer: BufferSlot,
    val pendingClearBuffer: BufferSlot?,
    val clearStatus: BufferClearStatus?,
    val showExportClamp: Boolean,
    val clampWarningSeconds: Float,
    val rangeSnapshot: ReverbService.TimelineSnapshot?,
    val rangeSnapshotBuffer: BufferSlot?,
    val saveStatus: CaptureSaveStatus?,
    val errorMessage: String?,
)

/**
 * Presentation owner only. Existing Service/process owners retain accepted recording, clear and
 * export work. No new backend queue, recorder engine, permission policy or persistence path.
 *
 * The host supplies its actual coroutine context, view and lifecycle. Construction is inert;
 * attach() acquires the same released STARTED binding and RESUMED foreground/visualizer leases.
 * Only selectedBuffer is saveable. Temporary snapshots and export/clear drafts are not serialized.
 */
internal class NativeCaptureUiOwner(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val view: View,
    hostScope: CoroutineScope,
    restoredSelection: BufferSlot = BufferSlot.ONE_SHOT,
    visualizerVisible: Boolean = true,
    private val accessibilityManager: AccessibilityManager?,
    isSaveFeedbackVisible: () -> Boolean,
    onRecordingSaved: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBufferSettings: (BufferSlot) -> Unit,
) : AutoCloseable {
    private data class Callbacks(
        val isSaveFeedbackVisible: () -> Boolean,
        val onRecordingSaved: () -> Unit,
        val onOpenLibrary: () -> Unit,
        val onOpenBufferSettings: (BufferSlot) -> Unit,
    )
    private var callbacks = Callbacks(isSaveFeedbackVisible, onRecordingSaved,
        onOpenLibrary, onOpenBufferSettings)

    /** Host delegates are current; already accepted Service/process work keeps its original owner. */
    fun bindCallbacks(
        isSaveFeedbackVisible: () -> Boolean,
        onRecordingSaved: () -> Unit,
        onOpenLibrary: () -> Unit,
        onOpenBufferSettings: (BufferSlot) -> Unit,
    ) {
        callbacks = Callbacks(isSaveFeedbackVisible, onRecordingSaved, onOpenLibrary, onOpenBufferSettings)
        refreshPresentationVisibility()
    }

    private val resources get() = context.resources
    private val scope = CoroutineScope(hostScope.coroutineContext +
        SupervisorJob(hostScope.coroutineContext[Job]))
    private val screenAlive = AtomicBoolean(true)
    private var attached = false
    private val serviceUiOwner = CaptureServiceUiOwner()
    private val uiForegroundOwner = Any()
    private var binding: Closeable? = null
    private var foreground: Closeable? = null
    private var visualization: Closeable? = null
    private var captureCommandOwner = CaptureCommandUiOwner<ReverbService.UiCaptureCommandResult>()
    private var service: ReverbService? = null
    private var serviceStateHydrated = false
    private var isListening = false
    private var activeBuffer: BufferSlot? = null
    private var isSaving = false
    private var oneShotDurationSeconds = 0f
    private var oneShotPayloadBytes = 0L
    private var loopingDurationSeconds = 0f
    private var loopingPayloadBytes = 0L
    private var oneShotEnabled = true
    private var oneShotFull = false
    private var loopingEnabled = true
    private var resolvedRetentionMode: RetentionMode? = null
    private var selectedBuffer = restoredSelection
    private var visualizerVisible = visualizerVisible
    private val oneShotBlobController = AudioBlobController()
    private val loopingBlobController = AudioBlobController()
    private val latestBlobActivity = FloatArray(2)
    private var pendingClearBuffer: BufferSlot? = null
    private var activeClearOperationId: Long? = null
    private var bufferClearStatus: BufferClearStatus? = null
    private var showExportClampDialog = false
    private var clampWarningSeconds = 0f
    private var pendingExportRange: ExportRange? = null
    private var rangeSnapshot: ReverbService.TimelineSnapshot? = null
    private var rangeSnapshotBuffer: BufferSlot? = null
    private var pendingExportSnapshot: ReverbService.TimelineSnapshot? = null
    private var isPreparingRange = false
    private var errorMessage: String? = null
    private var saveStatus: CaptureSaveStatus? = null
    private class Bookkeeping {
        var startupBufferChosen = false
        var latestListeningCommandGeneration = Long.MIN_VALUE
        var timelineSnapshotRequestGeneration = 0L
        var pendingCustomRangeBuffer: BufferSlot? = null
        var activeSaveReceiver: SaveResultReceiver? = null
    }
    private val bookkeeping = Bookkeeping()
    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<NativeCaptureUiState> = mutableState.asStateFlow()
    private var clearPoll: Job? = null
    private var clearPollKey: Pair<ReverbService?, Long?> = null to null
    private var feedbackJob: Job? = null
    private var feedbackStatus: CaptureSaveStatus? = null
    private val feedbackVisible = MutableStateFlow(false)
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { refreshPresentationVisibility() }

    val actions = ReverbHomeActions(
        selectBuffer = { selectedBuffer = it; publish() },
        activateBuffer = ::activateBuffer,
        openBufferSettings = { slot ->
            if (service?.isCaptureConfigurationUnavailable() == true) activateBuffer(slot)
            else callbacks.onOpenBufferSettings(slot)
        },
        exportFull = ::exportFull,
        exportCustom = ::exportCustom,
        clearBuffer = ::requestClearBuffer,
        openLibrary = { callbacks.onOpenLibrary() },
    )

    init {
        requireNotNull(scope.coroutineContext[MonotonicFrameClock]) {
            "Capture UI jobs must inherit their owning window's frame clock"
        }
    }

    fun attach() {
        check(screenAlive.get() && !attached)
        attached = true
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        binding = serviceUiOwner.bind(context, lifecycleOwner, view, screenAlive::get,
            object : CaptureServiceUiOwner.BindingCallbacks {
                override fun connected(connectedService: ReverbService) {
                    if (service != null && service !== connectedService) clearConnectedServiceState(false)
                    bookkeeping.latestListeningCommandGeneration = Long.MIN_VALUE
                    serviceStateHydrated = false
                    replaceService(connectedService)
                    connectedService.currentBufferClearStatus()?.takeIf { it.phase.isActive }?.let {
                        activeClearOperationId = it.operationId
                        bufferClearStatus = it
                    }
                    requestRecorderState(connectedService)
                    publish()
                }
                override fun unavailable(markSavingAsCancelRequested: Boolean) {
                    clearConnectedServiceState(markSavingAsCancelRequested)
                    publish()
                }
                override fun stopped() {
                    detachActiveSaveUi()
                    clearConnectedServiceState(false)
                    publish()
                }
            })
        replaceLeases()
        refreshPresentationVisibility()
        scope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    service?.let { recorder ->
                        requestRecorderState(recorder)
                        recorder.consumePendingError()?.let { errorMessage = it; publish() }
                    }
                    delay(500L)
                }
            }
        }
        publish()
    }

    /** The navigation host calls this after visibility changes; it never starts/stops capture. */
    fun setVisualizerVisible(value: Boolean) {
        if (visualizerVisible == value) return
        visualizerVisible = value
        if (attached) replaceVisualizationLease()
        refreshPresentationVisibility()
        publish()
    }
    fun refreshPresentationVisibility() {
        feedbackVisible.value = callbacks.isSaveFeedbackVisible() && view.hasWindowFocus()
    }
    fun savedSelection(): BufferSlot = selectedBuffer
    fun sampledBlobActivity(slot: BufferSlot): Float = latestBlobActivity[if (slot == BufferSlot.ONE_SHOT) 0 else 1]

    private fun snapshot() = NativeCaptureUiState(
        ReverbHomeBodyState(activeBuffer, BufferMetrics(oneShotDurationSeconds, oneShotPayloadBytes),
            BufferMetrics(loopingDurationSeconds, loopingPayloadBytes), oneShotEnabled, oneShotFull,
            loopingEnabled, resolvedRetentionMode, isListening, isSaving, service != null,
            serviceStateHydrated, isPreparingRange, oneShotBlobController, loopingBlobController,
            visualizerVisible),
        selectedBuffer, pendingClearBuffer,
        bufferClearStatus?.takeIf { activeClearOperationId == it.operationId },
        showExportClampDialog, clampWarningSeconds, rangeSnapshot, rangeSnapshotBuffer, saveStatus, errorMessage,
    )

    private fun publish() {
        val previous = mutableState.value
        if (previous.selectedBuffer != selectedBuffer) {
            val pending = bookkeeping.pendingCustomRangeBuffer
            if (pending != null && pending != selectedBuffer) invalidateTimelineSnapshotPreparation()
        }
        if (previous.body.activeBuffer != activeBuffer) {
            when (activeBuffer) {
                BufferSlot.ONE_SHOT -> { latestBlobActivity[1] = 0f; loopingBlobController.clear() }
                BufferSlot.LOOPING -> { latestBlobActivity[0] = 0f; oneShotBlobController.clear() }
                null -> { latestBlobActivity.fill(0f); oneShotBlobController.clear(); loopingBlobController.clear() }
            }
        }
        mutableState.value = snapshot()
        restartClearPoll()
        restartFeedbackDeadline()
    }

    private fun replaceService(next: ReverbService?) {
        if (service === next) return
        foreground?.close(); foreground = null
        visualization?.close(); visualization = null
        captureCommandOwner.retire()
        captureCommandOwner = CaptureCommandUiOwner()
        service = next
        if (attached && screenAlive.get()) replaceLeases()
    }
    private fun replaceLeases() {
        foreground?.close()
        foreground = serviceUiOwner.foregroundLease(service, lifecycleOwner, view, uiForegroundOwner)
        replaceVisualizationLease()
    }
    private fun replaceVisualizationLease() {
        visualization?.close()
        visualization = serviceUiOwner.visualizationLease(service, lifecycleOwner, view,
            visualizationCallback, visualizerVisible) {
            oneShotBlobController.clear(); loopingBlobController.clear()
        }
    }
    private val visualizationCallback = ReverbService.VisualizationCallback { frame ->
        when (activeBuffer) {
            BufferSlot.ONE_SHOT -> {
                latestBlobActivity[0] = frame.activity.coerceIn(0f, 1f)
                oneShotBlobController.submit(frame)
            }
            BufferSlot.LOOPING -> {
                latestBlobActivity[1] = frame.activity.coerceIn(0f, 1f)
                loopingBlobController.submit(frame)
            }
            null -> Unit
        }
    }

    private fun invalidateTimelineSnapshotPreparation() {
        bookkeeping.timelineSnapshotRequestGeneration++
        bookkeeping.pendingCustomRangeBuffer = null
        rangeSnapshotBuffer = null
        isPreparingRange = false
    }
    private fun canDisplaySaveFeedback(): Boolean = screenAlive.get() && callbacks.isSaveFeedbackVisible() &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        view.isAttachedToWindow && view.isShown && view.hasWindowFocus()
    private fun detachActiveSaveUi() {
        bookkeeping.activeSaveReceiver?.detachUi()
        bookkeeping.activeSaveReceiver = null
        if (isSaving) { isSaving = false; saveStatus = null }
    }
    private fun clearConnectedServiceState(markSavingAsCancelRequested: Boolean) {
        closeCaptureSnapshotsBestEffort(rangeSnapshot, pendingExportSnapshot)
        rangeSnapshot = null; rangeSnapshotBuffer = null
        pendingExportSnapshot = null; pendingExportRange = null
        showExportClampDialog = false; pendingClearBuffer = null
        activeClearOperationId = null; bufferClearStatus = null
        invalidateTimelineSnapshotPreparation()
        if (markSavingAsCancelRequested && isSaving) saveStatus = markExportCancelRequested(saveStatus)
        serviceStateHydrated = false
        replaceService(null)
    }

    private fun requestRecorderState(recorder: ReverbService) {
        val requestConnectionGeneration = serviceUiOwner.connectionGeneration
        recorder.getState(object : ReverbService.StateCallback {
            override fun state(commandGeneration: Long, listeningEnabled: Boolean, activeBufferSlot: BufferSlot?,
                oneShotSeconds: Float, oneShotBytes: Long, loopingSeconds: Float, loopingBytes: Long,
                oneShotIsEnabled: Boolean, oneShotIsFull: Boolean, loopingIsEnabled: Boolean,
                retentionMode: RetentionMode?, exporting: Boolean) {
                if (!shouldApplyRecorderStateSnapshot(requestConnectionGeneration,
                        serviceUiOwner.connectionGeneration, commandGeneration,
                        bookkeeping.latestListeningCommandGeneration)) return
                bookkeeping.latestListeningCommandGeneration = commandGeneration
                val previousListening = isListening
                val previousActiveBuffer = activeBuffer
                isListening = listeningEnabled
                activeBuffer = activeBufferSlot
                oneShotDurationSeconds = oneShotSeconds; oneShotPayloadBytes = oneShotBytes
                loopingDurationSeconds = loopingSeconds; loopingPayloadBytes = loopingBytes
                val resolved = captureResolvedBufferState(retentionMode, oneShotIsEnabled, oneShotIsFull, loopingIsEnabled)
                oneShotEnabled = resolved.oneShotEnabled; oneShotFull = resolved.oneShotFull
                loopingEnabled = resolved.loopingEnabled; resolvedRetentionMode = retentionMode
                val receiverAttached = bookkeeping.activeSaveReceiver != null
                saveStatus = reconcileCaptureExportStatus(exporting, receiverAttached, saveStatus)
                isSaving = captureExportUiBusy(exporting, receiverAttached, saveStatus)
                serviceStateHydrated = true
                if (!bookkeeping.startupBufferChosen) {
                    selectedBuffer = activeBufferSlot ?: defaultStartupBufferSlot(
                        resolved.oneShotEnabled, resolved.oneShotFull, resolved.loopingEnabled)
                    bookkeeping.startupBufferChosen = true
                } else selectedBuffer = captureSelectedBufferAfterRecorderState(selectedBuffer,
                    previousListening, previousActiveBuffer, listeningEnabled, activeBufferSlot)
                publish()
            }
        })
    }

    private fun restartClearPoll() {
        val key = service to activeClearOperationId
        if (key == clearPollKey) return
        clearPollKey = key
        clearPoll?.cancel(); clearPoll = null
        val recorder = key.first ?: return
        val operationId = key.second ?: return
        clearPoll = scope.launch {
            while (service === recorder && activeClearOperationId == operationId) {
                val status = recorder.currentBufferClearStatus()
                if (status?.operationId != operationId) {
                    activeClearOperationId = null; bufferClearStatus = null
                    requestRecorderState(recorder); publish()
                    return@launch
                }
                bufferClearStatus = status
                if (status.phase.isTerminal) {
                    requestRecorderState(recorder)
                    when (status.phase) {
                        BufferClearPhase.CANCELLED -> AppFeedbackCenter.post(resources.getString(R.string.clear_buffer_cancelled), FeedbackTone.INFO)
                        BufferClearPhase.FAILED -> {
                            val error = recorder.consumePendingError()
                            if (error != null) errorMessage = error
                            else if (errorMessage == null) errorMessage = resources.getString(R.string.clear_buffer_failed)
                        }
                        else -> Unit
                    }
                    activeClearOperationId = null; bufferClearStatus = null
                    publish()
                    return@launch
                }
                publish()
                delay(100L)
            }
        }
    }

    private fun restartFeedbackDeadline() {
        if (feedbackStatus == saveStatus) return
        feedbackStatus = saveStatus
        feedbackJob?.cancel(); feedbackJob = null
        val current = saveStatus as? CaptureSaveStatus.Saved ?: return
        feedbackJob = scope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                feedbackVisible.collectLatest { visible ->
                    if (!visible) return@collectLatest
                    dismissSavedFeedbackAfterTimeout(current, accessibilityManager, { saveStatus },
                        { saveStatus = null; publish() }, canDisplay = ::canDisplaySaveFeedback,
                        awaitVisibleFrame = { withFrameNanos { }; withFrameNanos { } })
                }
            }
        }
    }

    private fun activateBuffer(bufferSlot: BufferSlot) {
        val recorder = service
        if (recorder != null && serviceStateHydrated && !isSaving) {
            val action = captureBlobTapAction(bufferSlot, isListening, activeBuffer,
                oneShotEnabled, oneShotFull, loopingEnabled)
            if (captureBlobCommandMaySubmit(action, recorder.canRetryCaptureConfiguration())) {
                val connectionGeneration = serviceUiOwner.connectionGeneration
                val accepted = captureCommandOwner.submit(
                    transfer = { recorder.requestCaptureBlobTap(bufferSlot, it) },
                    onResult = { completion ->
                        val current = screenAlive.get() && service === recorder &&
                            connectionGeneration == serviceUiOwner.connectionGeneration
                        if (current) {
                            val result = completion.command
                            if (result.accepted && result.generation >= bookkeeping.latestListeningCommandGeneration) {
                                bookkeeping.latestListeningCommandGeneration = result.generation
                                isListening = completion.action != CaptureBlobTapAction.STOP
                                activeBuffer = completion.bufferSlot
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                            completion.errorMessage?.let { errorMessage = it }
                            requestRecorderState(recorder)
                            publish()
                        }
                        current
                    })
                if (!accepted) recorder.consumePendingError()?.let { errorMessage = it; publish() }
            }
        }
    }

    private fun requestClearBuffer(slot: BufferSlot) {
        if (captureServiceInteractionReady(service != null, serviceStateHydrated) &&
            !isSaving && activeClearOperationId == null) {
            pendingClearBuffer = slot; publish()
        }
    }
    fun dismissClearBuffer() { pendingClearBuffer = null; publish() }
    fun confirmClearBuffer() {
        val slot = pendingClearBuffer ?: return
        val recorder = service
        val id = recorder?.startClearBuffer(slot)
        pendingClearBuffer = null
        if (recorder == null || id == null) errorMessage = resources.getString(R.string.clear_buffer_failed)
        else {
            activeClearOperationId = id
            bufferClearStatus = recorder.currentBufferClearStatus()
                ?: BufferClearStatus(operationId = id, bufferSlot = slot, phase = BufferClearPhase.STARTING)
        }
        publish()
    }
    fun cancelClearBuffer() {
        val status = bufferClearStatus ?: return
        val id = activeClearOperationId ?: return
        if (status.operationId != id) return
        if (service?.cancelBufferClear(id) == true) {
            bufferClearStatus = status.copy(phase = BufferClearPhase.CANCELLING); publish()
        }
    }
    fun dismissError() { errorMessage = null; publish() }
    fun cancelExport() {
        if (service?.cancelCurrentExport() == true) { saveStatus = markExportCancelRequested(saveStatus); publish() }
    }

    private fun startOwnedExport(recorder: ReverbService?, range: ExportRange, snapshot: ReverbService.TimelineSnapshot?) {
        startExport(context, recorder, range, snapshot,
            setSaving = { isSaving = it; publish() },
            onStatus = { saveStatus = it; publish() },
            onError = { errorMessage = it; publish() },
            onSaved = { callbacks.onRecordingSaved() }, canDisplay = ::canDisplaySaveFeedback,
            onReceiverCreated = { bookkeeping.activeSaveReceiver = it },
            onReceiverTerminal = {
                if (bookkeeping.activeSaveReceiver === it) bookkeeping.activeSaveReceiver = null
            })
    }
    private fun queueOrStartExport(recorder: ReverbService?, range: ExportRange, snapshot: ReverbService.TimelineSnapshot) {
        if (range.warningDurationSeconds != null) {
            clampWarningSeconds = range.warningDurationSeconds
            pendingExportRange = range
            closeCaptureSnapshotsBestEffort(pendingExportSnapshot)
            pendingExportSnapshot = snapshot; showExportClampDialog = true
        } else startOwnedExport(recorder, range, snapshot)
        publish()
    }
    fun proceedExportClamp() {
        showExportClampDialog = false
        val range = pendingExportRange
        if (range != null) {
            val snapshot = pendingExportSnapshot
            pendingExportRange = null; pendingExportSnapshot = null
            startOwnedExport(service, range, snapshot)
        }
        publish()
    }
    fun dismissExportClamp() {
        showExportClampDialog = false; pendingExportRange = null
        closeCaptureSnapshotsBestEffort(pendingExportSnapshot); pendingExportSnapshot = null
        publish()
    }
    fun dismissRangeExport() {
        closeCaptureSnapshotsBestEffort(rangeSnapshot)
        rangeSnapshot = null; rangeSnapshotBuffer = null
        invalidateTimelineSnapshotPreparation()
        publish()
    }
    /** Capture once when binding the range view; submit uses that same rendered configuration. */
    fun rangeExportConfig(): ExportUiConfig = currentExportConfig(context, service)
    fun rangeExportLimitSeconds(config: ExportUiConfig): Double {
        return exportDurationLimitExactSeconds(config.format, config.codec, config.sampleRate,
            config.channelCount, config.sampleFormat)
    }
    fun submitRangeExport(startSeconds: Float, endSeconds: Float, renderedConfig: ExportUiConfig) {
        val snapshot = rangeSnapshot ?: return
        val slot = rangeSnapshotBuffer ?: return
        val range = buildCustomExportRange(snapshot.durationSeconds, startSeconds, endSeconds,
            renderedConfig).copy(rememberOnSave = ExportRangeMemory(slot, snapshot.durationSeconds))
        rangeSnapshot = null; rangeSnapshotBuffer = null
        invalidateTimelineSnapshotPreparation()
        queueOrStartExport(service, range, snapshot)
    }

    private fun exportFull(slot: BufferSlot) {
        val recorder = service
        if (recorder == null || !serviceStateHydrated || isSaving || isPreparingRange) return
        val request = ++bookkeeping.timelineSnapshotRequestGeneration
        isPreparingRange = true; publish()
        recorder.acquireTimelineSnapshot(slot) { snapshot ->
            if (!timelineSnapshotRequestIsCurrent(request, bookkeeping.timelineSnapshotRequestGeneration)) {
                closeCaptureSnapshotsBestEffort(snapshot); return@acquireTimelineSnapshot
            }
            isPreparingRange = false
            if (!screenAlive.get() || service !== recorder) {
                closeCaptureSnapshotsBestEffort(snapshot); publish(); return@acquireTimelineSnapshot
            }
            if (snapshot == null || snapshot.durationSeconds <= 0.0) {
                closeCaptureSnapshotsBestEffort(snapshot)
                AppFeedbackCenter.post(resources.getString(R.string.nothing_to_export), FeedbackTone.INFO)
                publish(); return@acquireTimelineSnapshot
            }
            val range = buildCustomExportRange(snapshot.durationSeconds, 0f, snapshot.durationSeconds.toFloat(),
                currentExportConfig(context, recorder)).copy(rememberOnSave = ExportRangeMemory(slot, snapshot.durationSeconds))
            queueOrStartExport(recorder, range, snapshot)
        }
    }
    private fun exportCustom(slot: BufferSlot) {
        if (!serviceStateHydrated || isSaving || isPreparingRange) return
        val recorder = service ?: return
        val seconds = (if (slot == BufferSlot.ONE_SHOT) oneShotDurationSeconds else loopingDurationSeconds).coerceAtLeast(0f)
        if (seconds <= 0f) {
            AppFeedbackCenter.post(resources.getString(R.string.nothing_to_export), FeedbackTone.INFO); return
        }
        val request = ++bookkeeping.timelineSnapshotRequestGeneration
        bookkeeping.pendingCustomRangeBuffer = slot
        rangeSnapshotBuffer = slot; isPreparingRange = true; publish()
        recorder.acquireTimelineSnapshot(slot) { snapshot ->
            val current = shouldApplyCustomRangeSnapshot(request, bookkeeping.timelineSnapshotRequestGeneration,
                slot, bookkeeping.pendingCustomRangeBuffer, selectedBuffer)
            if (!current) {
                closeCaptureSnapshotsBestEffort(snapshot)
                if (request == bookkeeping.timelineSnapshotRequestGeneration) {
                    bookkeeping.pendingCustomRangeBuffer = null; isPreparingRange = false; publish()
                }
                return@acquireTimelineSnapshot
            }
            bookkeeping.pendingCustomRangeBuffer = null; isPreparingRange = false
            if (!screenAlive.get() || service !== recorder) closeCaptureSnapshotsBestEffort(snapshot)
            else if (snapshot != null && snapshot.durationSeconds > 0.0) {
                closeCaptureSnapshotsBestEffort(rangeSnapshot)
                rangeSnapshot = snapshot; rangeSnapshotBuffer = slot
            } else {
                closeCaptureSnapshotsBestEffort(snapshot); rangeSnapshotBuffer = null
                AppFeedbackCenter.post(resources.getString(R.string.nothing_to_export), FeedbackTone.INFO)
            }
            publish()
        }
    }

    override fun close() {
        if (!screenAlive.compareAndSet(true, false)) return
        captureCommandOwner.retire()
        scope.cancel()
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
        // binding.close delivers stopped, which detaches receiver UI and releases draft snapshots.
        try { binding?.close() } finally {
            binding = null
            foreground?.close(); foreground = null
            visualization?.close(); visualization = null
            if (!attached) {
                detachActiveSaveUi()
                closeCaptureSnapshotsBestEffort(rangeSnapshot, pendingExportSnapshot)
                rangeSnapshot = null; pendingExportSnapshot = null
            }
            attached = false
        }
    }
}
