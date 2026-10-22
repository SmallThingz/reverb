package app.smallthingz.reverb

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.tanh

internal class LegacyRangeExportEditorState(
    initialDurationSeconds: Float,
    private val rememberedRangeExport: RememberedRangeExport? = null,
    onPreviewReleaseFailure: (Throwable) -> Unit = {},
    private val onPreviewFailure: (Throwable) -> Unit = {},
    notifyNativeUiChanged: (() -> Unit)? = null,
) {
    private val previewController = TimelineAudioPreviewController(
        onTrackReleaseFailure = onPreviewReleaseFailure,
    )

    var snapshot by legacyRangeUiState(mutableStateOf<ReverbService.TimelineSnapshot?>(null), notifyNativeUiChanged)
        private set
    var durationSeconds by legacyRangeUiState(mutableFloatStateOf(rangeTimelineDurationSeconds(initialDurationSeconds)), notifyNativeUiChanged)
        private set
    private val initialRestoredRange = restoreRememberedRangeExport(durationSeconds, rememberedRangeExport)
    private var restoreRememberedRangeOnFirstSnapshot = rememberedRangeExport != null

    var startSeconds by legacyRangeUiState(mutableFloatStateOf(initialRestoredRange.startSeconds), notifyNativeUiChanged)
        private set
    var endSeconds by legacyRangeUiState(mutableFloatStateOf(initialRestoredRange.endSeconds), notifyNativeUiChanged)
        private set
    var lastTarget by legacyRangeUiState(mutableStateOf(RangeEditTarget.START), notifyNativeUiChanged)
        private set
    var coarseWaveform by legacyRangeUiState(mutableStateOf(FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)), notifyNativeUiChanged)
        private set
    var coarseWaveformBuiltCount by legacyRangeUiState(mutableIntStateOf(0), notifyNativeUiChanged)
        private set
    var detailWaveform by legacyRangeUiState(mutableStateOf(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)), notifyNativeUiChanged)
        private set
    var detailWaveformBuiltCount by legacyRangeUiState(mutableIntStateOf(0), notifyNativeUiChanged)
        private set
    var waveformPass by legacyRangeUiState(mutableStateOf(RangeWaveformPass.COARSE), notifyNativeUiChanged)
        private set
    var waveformLoading by legacyRangeUiState(mutableStateOf(true), notifyNativeUiChanged)
        private set
    var isPlaying by legacyRangeUiState(mutableStateOf(false), notifyNativeUiChanged)
        private set
    var isScrubbing by legacyRangeUiState(mutableStateOf(false), notifyNativeUiChanged)
        private set
    var selectionDurationInteraction by legacyRangeUiState(mutableStateOf(RangeDurationWheelInteraction()), notifyNativeUiChanged)
        private set

    val selectionDurationEditing: Boolean
        get() = selectionDurationInteraction.active

    private var playbackAllowed = true
    private var previewStarting = false
    private var resumeAfterScrub = false
    private var fineAdjustShuttleActive = false
    private val fineSeekPosition = FineSeekPosition<RangeEditTarget>()
    private var lastAuditionAtMillis = 0L

    val selectionDurationExactSeconds: Double
        get() = rangeSelectionDurationExactSeconds(startSeconds, endSeconds)

    fun selectionDurationWheelLimitExactSeconds(exportLimitSeconds: Double): Double =
        rangeSelectionDurationWheelLimitSeconds(
            values = currentEditValues(),
            target = selectionDurationInteraction.target ?: lastTarget,
            timelineDurationSeconds = durationSeconds,
            exportLimitSeconds = exportLimitSeconds,
        )

    val durationActionAvailable: Boolean
        get() = playbackAllowed && snapshotReady && !selectionDurationEditing &&
            !isScrubbing && !fineAdjustShuttleActive

    val snapshotReady: Boolean
        get() = snapshot != null

    fun inputGesture(
        interactionAllowed: () -> Boolean,
        onCancel: (Boolean) -> Unit,
    ): RangeInputGesture = RangeInputGesture(
        interactionAllowed = { playbackAllowed && interactionAllowed() },
        durationInteraction = { selectionDurationInteraction },
        onCancel = onCancel,
    )

    fun attachSnapshot(value: ReverbService.TimelineSnapshot) {
        fineSeekPosition.reset()
        invalidateSelectionDurationCommit()
        val previousDuration = durationSeconds
        val nextDuration = rangeTimelineDurationSeconds(value.durationSeconds.toFloat())
        val endWasAtLiveEdge = kotlin.math.abs(endSeconds - previousDuration) <= 0.15f
        snapshot = value
        durationSeconds = nextDuration
        if (restoreRememberedRangeOnFirstSnapshot) {
            val restored = restoreRememberedRangeExport(nextDuration, rememberedRangeExport)
            restoreRememberedRangeOnFirstSnapshot = false
            startSeconds = restored.startSeconds
            endSeconds = restored.endSeconds
        } else {
            startSeconds = startSeconds.coerceIn(0f, (nextDuration - 0.05f).coerceAtLeast(0f))
            endSeconds = if (endWasAtLiveEdge) {
                nextDuration
            } else {
                endSeconds.coerceIn((startSeconds + 0.05f).coerceAtMost(nextDuration), nextDuration)
            }
        }
    }

    fun resetWaveformConstruction() {
        coarseWaveform = FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)
        coarseWaveformBuiltCount = 0
        detailWaveform = FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)
        detailWaveformBuiltCount = 0
        waveformPass = RangeWaveformPass.COARSE
        waveformLoading = true
    }

    fun beginDetailedWaveformPass() {
        waveformPass = RangeWaveformPass.DETAIL
        detailWaveformBuiltCount = 0
    }

    fun publishWaveformSnapshot(pass: RangeWaveformPass, snapshot: ProgressiveWaveformSnapshot) {
        when (pass) {
            RangeWaveformPass.COARSE -> {
                if (snapshot.values.size != coarseWaveform.size) return
                coarseWaveform = snapshot.values
                coarseWaveformBuiltCount = snapshot.builtCount.coerceIn(0, coarseWaveform.size)
            }
            RangeWaveformPass.DETAIL -> {
                if (snapshot.values.size != detailWaveform.size) return
                detailWaveform = snapshot.values
                detailWaveformBuiltCount = snapshot.builtCount.coerceIn(0, detailWaveform.size)
            }
        }
    }

    fun finishWaveformConstruction() {
        waveformLoading = false
    }

    fun selectTarget(target: RangeEditTarget) {
        fineSeekPosition.reset()
        invalidateSelectionDurationCommit()
        lastTarget = target
    }

    fun editEndpointFromAction(
        target: RangeEditTarget,
        requestedSeconds: Double?,
        gesture: RangeInputGesture,
    ): Boolean = runRangeEndpointAction(
        values = currentEditValues(),
        durationSeconds = durationSeconds,
        target = target,
        requestedSeconds = requestedSeconds,
        gesture = gesture,
        pause = ::pausePreview,
        apply = { update ->
            selectTarget(target)
            applyEditUpdate(update)
        },
    )

    fun setTarget(target: RangeEditTarget, requestedSeconds: Float) {
        selectTarget(target)
        applyEditUpdate(
            adjustRangeEditTarget(
                values = currentEditValues(),
                target = target,
                requestedSeconds = requestedSeconds,
                durationSeconds = durationSeconds,
            ),
        )
    }

    fun beginSelectionDurationEdit() {
        // Native wheel state settles after the pointer is released. Pin the edit owner now so
        // the delayed terminal commit and reachable cap belong to this exact interaction.
        selectionDurationInteraction = selectionDurationInteraction.begin(lastTarget)
        pausePreview()
    }

    fun setSelectionDuration(requestedDurationSeconds: Float) {
        val target = selectionDurationInteraction.commitTarget() ?: return
        fineSeekPosition.reset()
        applyEditUpdate(
            resizeRangeSelectionDuration(
                values = currentEditValues(),
                target = target,
                requestedDurationSeconds = requestedDurationSeconds,
                durationSeconds = durationSeconds,
            ),
        )
    }

    fun setSelectionDurationFromAction(requestedDurationSeconds: Float, maximumSeconds: Double) {
        val target = selectionDurationInteraction.commitTarget() ?: return
        setSelectionDuration(requestedDurationSeconds)
        applyEditUpdate(RangeEditUpdate(boundRangeDurationActionValues(currentEditValues(), target, maximumSeconds)))
    }

    fun endSelectionDurationEdit() {
        selectionDurationInteraction = selectionDurationInteraction.end()
    }

    private fun invalidateSelectionDurationCommit() {
        selectionDurationInteraction = selectionDurationInteraction.invalidateCommit()
    }

    private fun currentEditValues() = RangeEditValues(startSeconds, endSeconds)

    fun targetSeconds(target: RangeEditTarget): Float = targetValue(target)

    private fun applyEditUpdate(update: RangeEditUpdate) {
        startSeconds = update.values.startSeconds
        endSeconds = update.values.endSeconds
    }

    fun beginBoundaryEdit(target: RangeEditTarget) {
        selectTarget(target)
        pausePreview()
        previewController.stop()
        auditionSelectedBoundary(force = true)
    }

    private fun beginScrubPlaybackHandoff() {
        resumeAfterScrub = isPlaying || previewStarting
        if (isPlaying || previewStarting) {
            previewStarting = false
            previewController.stop()
            isPlaying = false
        }
        isScrubbing = true
    }

    fun beginBoundaryScrub(target: RangeEditTarget) {
        selectTarget(target)
        beginScrubPlaybackHandoff()
        auditionSelectedBoundary(force = true)
    }

    fun updateBoundaryScrub(target: RangeEditTarget, requestedSeconds: Float) {
        setTarget(target, requestedSeconds)
        auditionSelectedBoundary()
    }

    fun endBoundaryScrub() {
        isScrubbing = false
        previewController.stop()
        if (resumeAfterScrub) {
            resumeAfterScrub = false
            startPreview()
        }
    }

    fun beginFineAdjust(shuttleRate: Float) {
        if (!playbackAllowed) return
        fineSeekPosition.reset()
        invalidateSelectionDurationCommit()
        beginScrubPlaybackHandoff()
        fineAdjustShuttleActive = true
        snapshot?.let { readySnapshot ->
            previewController.startShuttle(
                snapshot = readySnapshot,
                atSeconds = targetValue(lastTarget).toDouble(),
                rate = shuttleRate,
            )
        }
    }

    fun updateFineAdjustShuttle(shuttleRate: Float, pendingDeltaSeconds: Float = 0f) {
        if (!fineAdjustShuttleActive) return
        previewController.updateShuttle(
            atSeconds = projectRangeFineSeekPosition(
                position = fineSeekPosition,
                values = currentEditValues(),
                target = lastTarget,
                pendingDeltaSeconds = pendingDeltaSeconds,
                durationSeconds = durationSeconds,
            ),
            rate = shuttleRate,
        )
    }

    fun fineAdjust(deltaSeconds: Float) {
        applyEditUpdate(adjustRangeFineSeekPosition(
            position = fineSeekPosition,
            values = currentEditValues(),
            target = lastTarget,
            deltaSeconds = deltaSeconds,
            durationSeconds = durationSeconds,
        ))
    }

    fun endFineAdjust() {
        fineSeekPosition.reset()
        if (fineAdjustShuttleActive) {
            fineAdjustShuttleActive = false
            previewController.stopShuttle()
        }
        isScrubbing = false
        if (resumeAfterScrub) {
            resumeAfterScrub = false
            startPreview()
        }
    }

    fun togglePreview() {
        if (isPlaying || previewStarting) pausePreview() else startPreview()
    }

    fun pausePreview() {
        fineSeekPosition.reset()
        previewStarting = false
        resumeAfterScrub = false
        isScrubbing = false
        if (fineAdjustShuttleActive) {
            fineAdjustShuttleActive = false
            previewController.stopShuttle()
            isScrubbing = false
        }
        previewController.stop()
        isPlaying = false
    }

    fun setPlaybackAllowed(allowed: Boolean) {
        playbackAllowed = allowed
        if (!allowed) pausePreview()
    }

    fun close() {
        playbackAllowed = false
        pausePreview()
        previewController.close()
    }

    private fun startPreview() {
        if (!playbackAllowed) return
        val readySnapshot = snapshot ?: return
        if (durationSeconds <= 0f) return
        val window = boundaryCursorPreviewWindow(
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            endBoundaryActive = lastTarget == RangeEditTarget.END,
        )
        previewStarting = false
        isPlaying = false
        isScrubbing = false
        previewStarting = previewController.play(
            snapshot = readySnapshot,
            fromSeconds = window.startSeconds.toDouble(),
            untilSeconds = window.endSeconds.toDouble(),
            onProgress = {},
            onStarted = {
                previewStarting = false
                isPlaying = true
            },
            onFinished = {
                previewStarting = false
                isPlaying = false
            },
            onError = { error ->
                previewStarting = false
                isPlaying = false
                onPreviewFailure(error)
            },
        )
    }

    private fun auditionSelectedBoundary(force: Boolean = false) {
        if (!playbackAllowed || fineAdjustShuttleActive) return
        val readySnapshot = snapshot ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAuditionAtMillis < 55L) return
        lastAuditionAtMillis = now
        previewController.audition(readySnapshot, targetValue(lastTarget).toDouble())
    }

    private fun targetValue(target: RangeEditTarget): Float = editTargetValue(currentEditValues(), target)
}

internal fun legacyRangeMorphSourceGeometry(
    rootBoundsInRoot: Rect?,
    blobBoundsInRoot: Rect?,
    active: Boolean,
    enabled: Boolean,
    activity: Float,
    renderedBaseRadiusFraction: Float? = null,
): RangeMorphSourceGeometry? {
    val root = rootBoundsInRoot ?: return null
    val blob = blobBoundsInRoot ?: return null
    val viewSize = minOf(blob.width, blob.height)
    if (viewSize <= 0f || root.width <= 0f || root.height <= 0f) return null
    val baseRadius = renderedBaseRadiusFraction
        ?.takeIf { it.isFinite() && it > 0f }
        ?: rangeBlobBaseRadiusFraction(active, enabled, activity)
    val bodyDiameter = viewSize * 2f * baseRadius
    return RangeMorphSourceGeometry(
        rootLeftInRootPx = root.left,
        rootTopInRootPx = root.top,
        centerXInRootPx = blob.center.x,
        centerYInRootPx = blob.center.y,
        bodyDiameterPx = bodyDiameter,
    )
}
