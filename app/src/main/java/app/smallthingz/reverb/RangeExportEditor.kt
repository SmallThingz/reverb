package app.smallthingz.reverb

import android.os.SystemClock
import smallthingz.ui.runtime.getValue
import smallthingz.ui.runtime.mutableFloatStateOf
import smallthingz.ui.runtime.mutableIntStateOf
import smallthingz.ui.runtime.mutableStateOf
import smallthingz.ui.runtime.setValue
import smallthingz.ui.geometry.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.tanh

internal enum class RangeEditTarget { START, END }

internal data class RangeDurationWheelInteraction(
    val target: RangeEditTarget? = null,
    val commitAllowed: Boolean = false,
    // Retain completed interactions so older pointer/frame callbacks cannot mistake idle for the same owner.
    val generation: Long = 0L,
) {
    val active: Boolean get() = target != null

    fun begin(target: RangeEditTarget): RangeDurationWheelInteraction =
        RangeDurationWheelInteraction(target = target, commitAllowed = true, generation = generation + 1L)

    fun invalidateCommit(): RangeDurationWheelInteraction =
        if (active && commitAllowed) copy(commitAllowed = false) else this

    fun end(): RangeDurationWheelInteraction = RangeDurationWheelInteraction(generation = generation)

    fun commitTarget(): RangeEditTarget? = target?.takeIf { commitAllowed }
}

/** Every continuous range input loses its whole gesture when the duration wheel takes admission. */
internal class RangeInputGesture(
    private val interactionAllowed: () -> Boolean,
    private val durationInteraction: () -> RangeDurationWheelInteraction,
    // Local cleanup always runs; editor/audio cleanup belongs only to the still-current owner.
    private val onCancel: (stillCurrent: Boolean) -> Unit,
) {
    private val durationGeneration = durationInteraction().generation
    private var terminal = false

    fun perform(action: () -> Unit): Boolean {
        if (!admit()) return false
        action()
        return true
    }

    fun finish(action: () -> Unit): Boolean {
        if (!admit()) return false
        terminal = true
        action()
        return true
    }

    fun cancel() {
        if (terminal) return
        terminal = true
        val durationOwner = durationInteraction()
        onCancel(!durationOwner.active && durationOwner.generation == durationGeneration)
    }

    private fun admit(): Boolean {
        if (terminal) return false
        val durationOwner = durationInteraction()
        // A generic-scroll step can begin and end entirely between pointer events or frames.
        if (!interactionAllowed() || durationOwner.active || durationOwner.generation != durationGeneration) {
            cancel()
            return false
        }
        return true
    }
}

private const val BOUNDARY_CURSOR_END_LEAD_IN_SECONDS = 3f

internal data class BoundaryCursorPreviewWindow(
    val startSeconds: Float,
    val endSeconds: Float,
)

internal fun boundaryCursorPreviewWindow(
    startSeconds: Float,
    endSeconds: Float,
    endBoundaryActive: Boolean,
): BoundaryCursorPreviewWindow {
    val start = startSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val end = endSeconds.takeIf { it.isFinite() }?.coerceAtLeast(start) ?: start
    val previewStart = if (endBoundaryActive) {
        (end - BOUNDARY_CURSOR_END_LEAD_IN_SECONDS).coerceAtLeast(start)
    } else {
        start
    }
    return BoundaryCursorPreviewWindow(previewStart, end)
}

internal data class RangeEditValues(
    val startSeconds: Float,
    val endSeconds: Float,
)

internal data class RememberedRangeExport(
    val selectionLengthMillis: Long,
    val endOffsetMillis: Long,
)

internal data class RestoredRangeExport(
    val startSeconds: Float,
    val endSeconds: Float,
)

internal fun restoreRememberedRangeExport(
    availableSeconds: Float,
    remembered: RememberedRangeExport?,
): RestoredRangeExport {
    val available = availableSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (available <= 0f || remembered == null ||
        remembered.selectionLengthMillis <= 0L || remembered.endOffsetMillis < 0L
    ) {
        return RestoredRangeExport(0f, available)
    }
    val selectionSeconds = remembered.selectionLengthMillis.toDouble() / 1_000.0
    if (selectionSeconds >= available.toDouble()) {
        return RestoredRangeExport(0f, available)
    }
    val maxEndOffsetSeconds = available.toDouble() - selectionSeconds
    val endOffsetSeconds = (remembered.endOffsetMillis.toDouble() / 1_000.0)
        .coerceIn(0.0, maxEndOffsetSeconds)
    val end = available.toDouble() - endOffsetSeconds
    val start = (end - selectionSeconds).coerceAtLeast(0.0)
    // Keep the remembered end when Float conversion collapses a valid short selection.
    val values = ensurePositiveRangeSelection(
        values = RangeEditValues(start.toFloat(), end.toFloat()),
        durationSeconds = available,
        minimumRange = selectionSeconds.toFloat(),
        preferMovingStart = true,
    )
    return RestoredRangeExport(values.startSeconds, values.endSeconds)
}

internal fun rememberedRangeExportFromSavedRange(
    availableSeconds: Double,
    startSeconds: Float,
    endSeconds: Float,
    actualSelectionMillis: Long? = null,
): RememberedRangeExport? {
    val available = availableSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: return null
    if (!startSeconds.isFinite() || !endSeconds.isFinite() || available <= 0.0) return null
    val start = startSeconds.toDouble().coerceIn(0.0, available)
    val end = endSeconds.toDouble().coerceIn(start, available)
    val requestedSelectionMillis = ((end - start) * 1_000.0).roundToLong()
    if (requestedSelectionMillis <= 0L) return null
    val availableMillis = (available * 1_000.0).roundToLong().coerceAtLeast(1L)
    val selectionMillis = actualSelectionMillis
        ?.takeIf { it > 0L }
        ?.coerceAtMost(availableMillis)
        ?: requestedSelectionMillis
    val endOffsetMillis = ((available - end) * 1_000.0).roundToLong().coerceAtLeast(0L)
    return RememberedRangeExport(selectionMillis, endOffsetMillis)
}

internal data class RangeEditUpdate(
    val values: RangeEditValues,
)
internal const val RANGE_BLOB_MORPH_HANDOFF_PROGRESS = 0.07f

// These are the exact base-radius terms used by AudioBlobView's shader/fallback renderer:
// baseRadius = 0.095 + life * (0.235 + activity * 0.018).
private const val AUDIO_BLOB_BASE_RADIUS_FRACTION = 0.095f
private const val AUDIO_BLOB_LIFE_RADIUS_FRACTION = 0.235f
private const val AUDIO_BLOB_ACTIVITY_RADIUS_FRACTION = 0.018f
private const val AUDIO_BLOB_COLLAPSED_LIFE = 0.38f
private const val AUDIO_BLOB_DISABLED_LIFE = 0.36f

internal data class RangeMorphSourceGeometry(
    val rootLeftInRootPx: Float,
    val rootTopInRootPx: Float,
    val centerXInRootPx: Float,
    val centerYInRootPx: Float,
    val bodyDiameterPx: Float,
) {
    val centerXInLocalPx: Float get() = centerXInRootPx - rootLeftInRootPx
    val centerYInLocalPx: Float get() = centerYInRootPx - rootTopInRootPx
}

internal fun rangeBlobBaseRadiusFraction(
    active: Boolean,
    enabled: Boolean,
    activity: Float,
): Float {
    val life = when {
        !enabled -> AUDIO_BLOB_DISABLED_LIFE
        active -> 1f
        else -> AUDIO_BLOB_COLLAPSED_LIFE
    }
    val liveActivity = if (active) activity.coerceIn(0f, 1f) else 0f
    return AUDIO_BLOB_BASE_RADIUS_FRACTION +
        life * (AUDIO_BLOB_LIFE_RADIUS_FRACTION + liveActivity * AUDIO_BLOB_ACTIVITY_RADIUS_FRACTION)
}

internal fun rangeMorphSourceGeometry(
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

internal fun rangeBlobMorphStartScaleX(blobDiameterPx: Float, timelineWidthPx: Float): Float =
    (blobDiameterPx / timelineWidthPx.coerceAtLeast(1f)).coerceAtLeast(0f)

internal fun rangeBlobMorphStartScaleY(blobDiameterPx: Float, timelineHeightPx: Float): Float =
    (blobDiameterPx / timelineHeightPx.coerceAtLeast(1f)).coerceAtLeast(0f)

internal fun rangeBlobMorphTranslation(progress: Float, sourceCenterPx: Float, targetCenterPx: Float): Float =
    (sourceCenterPx - targetCenterPx) * (1f - progress.coerceIn(0f, 1f))

internal fun rangeTimelineDragDeltaPx(
    initialMarkerCenterPx: Float,
    currentMarkerCenterPx: Float,
    downLocalX: Float,
    currentLocalX: Float,
): Float = (currentMarkerCenterPx + currentLocalX) - (initialMarkerCenterPx + downLocalX)

// At long offsets the 50 ms gap can round to zero. Repair only collapsed results,
// preserving the intended fixed endpoint where a neighbouring Float is available.
private fun ensurePositiveRangeSelection(
    values: RangeEditValues,
    durationSeconds: Float,
    minimumRange: Float,
    preferMovingStart: Boolean,
): RangeEditValues {
    val start = values.startSeconds
    val end = values.endSeconds
    if (durationSeconds <= 0f || !start.isFinite() || !end.isFinite() || start < end) return values
    return if ((preferMovingStart && end > 0f) || start >= durationSeconds) {
        val earlier = (end - minimumRange).coerceAtLeast(0f)
        RangeEditValues(if (earlier < end) earlier else java.lang.Math.nextDown(end), end)
    } else {
        val later = (start + minimumRange).coerceAtMost(durationSeconds)
        RangeEditValues(start, if (later > start) later else java.lang.Math.nextUp(start))
    }
}

internal fun adjustRangeEditTarget(
    values: RangeEditValues,
    target: RangeEditTarget,
    requestedSeconds: Float,
    durationSeconds: Float,
    minRangeSeconds: Float = 0.05f,
): RangeEditUpdate {
    val minimumRange = minRangeSeconds.coerceAtLeast(0f)
    val duration = durationSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val boundedMinimum = minimumRange.coerceAtMost(duration)
    var start = values.startSeconds.coerceIn(0f, duration)
    var end = values.endSeconds.coerceIn(start, duration)
    if (end - start < boundedMinimum) {
        end = (start + boundedMinimum).coerceAtMost(duration)
        start = (end - boundedMinimum).coerceAtLeast(0f)
    }
    when (target) {
        RangeEditTarget.START -> {
            val requested = requestedSeconds.coerceIn(0f, (duration - boundedMinimum).coerceAtLeast(0f))
            if (requested > end - boundedMinimum) {
                start = requested
                end = (start + boundedMinimum).coerceAtMost(duration)
            } else {
                start = requested
            }
        }
        RangeEditTarget.END -> {
            val requested = requestedSeconds.coerceIn(boundedMinimum.coerceAtMost(duration), duration)
            if (requested < start + boundedMinimum) {
                end = requested
                start = (end - boundedMinimum).coerceAtLeast(0f)
            } else {
                end = requested
            }
        }
    }
    return RangeEditUpdate(ensurePositiveRangeSelection(
        values = RangeEditValues(start, end),
        durationSeconds = duration,
        minimumRange = boundedMinimum,
        preferMovingStart = target == RangeEditTarget.END,
    ))
}

internal fun rangeSelectionDurationExactSeconds(
    startSeconds: Float,
    endSeconds: Float,
): Double {
    if (!startSeconds.isFinite() || !endSeconds.isFinite()) return 0.0
    return (endSeconds.toDouble() - startSeconds.toDouble()).coerceAtLeast(0.0)
}

internal fun rangeExportSelectionWithinLimit(
    selectedDurationSeconds: Double,
    maximumDurationSeconds: Double,
): Boolean = selectedDurationSeconds.isFinite() &&
    maximumDurationSeconds.isFinite() &&
    selectedDurationSeconds > 0.0 &&
    maximumDurationSeconds >= 0.0 &&
    selectedDurationSeconds <= maximumDurationSeconds

internal fun rangeSelectionDurationReachableLimitSeconds(
    values: RangeEditValues,
    target: RangeEditTarget,
    timelineDurationSeconds: Float,
): Double {
    val duration = timelineDurationSeconds.takeIf { it.isFinite() }
        ?.coerceAtLeast(0f)?.toDouble() ?: 0.0
    val start = values.startSeconds.takeIf { it.isFinite() }
        ?.toDouble()?.coerceIn(0.0, duration) ?: 0.0
    val end = values.endSeconds.takeIf { it.isFinite() }
        ?.toDouble()?.coerceIn(start, duration) ?: start
    return when (target) {
        RangeEditTarget.START -> end
        RangeEditTarget.END -> (duration - start).coerceAtLeast(0.0)
    }
}

internal fun rangeSelectionDurationWheelLimitSeconds(
    values: RangeEditValues,
    target: RangeEditTarget,
    timelineDurationSeconds: Float,
    exportLimitSeconds: Double,
): Double {
    val exportLimit = exportLimitSeconds.takeIf { it.isFinite() }
        ?.coerceAtLeast(0.0) ?: 0.0
    return minOf(
        exportLimit,
        rangeSelectionDurationReachableLimitSeconds(values, target, timelineDurationSeconds),
    )
}

internal fun resizeRangeSelectionDuration(
    values: RangeEditValues,
    target: RangeEditTarget,
    requestedDurationSeconds: Float,
    durationSeconds: Float,
    minRangeSeconds: Float = 0.05f,
): RangeEditUpdate {
    val duration = durationSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val minimumRange = minRangeSeconds.coerceAtLeast(0f).coerceAtMost(duration)
    var start = values.startSeconds.coerceIn(0f, duration)
    var end = values.endSeconds.coerceIn(start, duration)
    val currentDuration = (end - start).coerceAtLeast(minimumRange)
    val requested = requestedDurationSeconds.takeIf { it.isFinite() }
        ?.coerceAtLeast(minimumRange) ?: currentDuration
    when (target) {
        RangeEditTarget.START -> {
            start = (end - requested).coerceIn(0f, (end - minimumRange).coerceAtLeast(0f))
        }
        RangeEditTarget.END -> {
            end = (start + requested).coerceIn(
                (start + minimumRange).coerceAtMost(duration),
                duration,
            )
        }
    }
    return RangeEditUpdate(ensurePositiveRangeSelection(
        values = RangeEditValues(start, end),
        durationSeconds = duration,
        minimumRange = minimumRange,
        preferMovingStart = target == RangeEditTarget.START,
    ))
}

private const val RANGE_FINE_TUNE_HORIZONTAL_SEEK_GAIN = 1f / 0.62f
private const val RANGE_FINE_TUNE_STATE_UPDATE_NANOS = 33_333_333L
internal const val RANGE_FINE_TUNE_PUCK_RADIUS_DP = 24f
internal const val RANGE_FINE_TUNE_PUCK_HIT_RADIUS_DP = 32f

internal fun rangeFineTunePuckContains(
    pointerX: Float,
    pointerY: Float,
    puckX: Float,
    puckY: Float,
    radius: Float,
): Boolean {
    val dx = pointerX - puckX
    val dy = pointerY - puckY
    val safeRadius = radius.coerceAtLeast(0f)
    return dx * dx + dy * dy <= safeRadius * safeRadius
}

internal fun rangeFineTuneMovementExceedsSlop(
    deltaX: Float,
    deltaY: Float,
    touchSlop: Float,
): Boolean {
    val slop = touchSlop.coerceAtLeast(0f)
    return deltaX * deltaX + deltaY * deltaY > slop * slop
}

/** The existing clickable occupies a square, independently of the circular drag-start test. */
internal fun fineSeekPointerTapInsideBounds(
    pointerX: Float,
    pointerY: Float,
    puckX: Float,
    puckY: Float,
    halfSize: Float,
): Boolean = abs(pointerX - puckX) <= halfSize && abs(pointerY - puckY) <= halfSize

private class FineAdjustCommitAccumulator {
    var deltaSeconds = 0f
    var elapsedNanos = 0L

    fun reset() {
        deltaSeconds = 0f
        elapsedNanos = 0L
    }

    fun add(deltaSeconds: Float, elapsedNanos: Long) {
        this.deltaSeconds += deltaSeconds
        this.elapsedNanos += elapsedNanos
    }

    fun takeDelta(): Float {
        val delta = deltaSeconds
        reset()
        return delta
    }
}

/** Pointer, frame and final-delta callbacks share one guard and one private accumulator. */
internal class FineSeekInputSession(
    createInputGesture: ((Boolean) -> Unit) -> RangeInputGesture,
    onCancel: (FineSeekInputSession, Boolean) -> Unit,
) {
    private val commits = FineAdjustCommitAccumulator()
    private var pointerTapAllowed = true
    private val input = createInputGesture { stillCurrent ->
        commits.reset()
        onCancel(this, stillCurrent)
    }

    fun perform(action: () -> Unit): Boolean = input.perform(action)

    fun frame(
        deltaSeconds: Float,
        elapsedNanos: Long,
        onCommit: (Float) -> Unit,
        onShuttle: (Float) -> Unit,
    ): Boolean = input.perform {
        commits.add(deltaSeconds, elapsedNanos)
        if (commits.elapsedNanos >= RANGE_FINE_TUNE_STATE_UPDATE_NANOS) {
            val delta = commits.takeDelta()
            if (delta != 0f) onCommit(delta)
        }
        onShuttle(commits.deltaSeconds)
    }

    fun finishFineAdjust(onCommit: (Float) -> Unit, onEnd: () -> Unit): Boolean = input.finish {
        val delta = commits.takeDelta()
        if (delta != 0f) onCommit(delta)
        onEnd()
    }

    fun finish(action: () -> Unit): Boolean = input.finish(action)

    fun observePointerTap(insideBounds: Boolean, consumed: Boolean) {
        if (!insideBounds || consumed) pointerTapAllowed = false
    }

    fun finishPointerTap(action: () -> Unit): Boolean = input.finish {
        if (pointerTapAllowed) action()
    }

    fun cancel() = input.cancel()
}

internal fun rangeFineTuneHorizontalTouchPull(
    pointerX: Float,
    width: Float,
    horizontalTravel: Float,
): Float {
    val centerX = width * 0.5f
    return ((pointerX - centerX) / horizontalTravel.coerceAtLeast(1f)).coerceIn(-1f, 1f)
}

internal fun rangeFineTuneSeekPull(horizontalVisualPull: Float): Float =
    (horizontalVisualPull * RANGE_FINE_TUNE_HORIZONTAL_SEEK_GAIN).coerceIn(-1f, 1f)

internal fun rangeFineTuneVerticalDragPull(
    startRawVertical: Float,
    dragDeltaY: Float,
    verticalTravel: Float,
): Float = startRawVertical + dragDeltaY / verticalTravel.coerceAtLeast(1f)

internal fun rangeFineTuneConstrainedY(
    rawVerticalPull: Float,
    horizontalPull: Float,
): Float {
    val x = abs(horizontalPull.coerceIn(-1f, 1f))
    val edgeStiffness = cosh(1.65f * x)
    val localRadius = 0.72f / edgeStiffness.pow(0.28f)
    val inputScale = localRadius * 1.18f * edgeStiffness.pow(0.72f)
    return localRadius * tanh(rawVerticalPull / inputScale)
}

internal fun rangeFineTuneTimelineRate(
    horizontalPull: Float,
    verticalPull: Float = 0f,
): Float {
    val pull = horizontalPull.coerceIn(-1f, 1f)
    val magnitude = abs(pull)
    if (magnitude <= 0.002f) return 0f
    val normalized = ((magnitude - 0.002f) / 0.998f).coerceIn(0f, 1f)
    // Fraction of the whole timeline traversed per second. This deliberately
    // contains no absolute seconds, so gesture feel scales with clip length.
    val horizontalRate =
        0.00002f +
            0.00040f * normalized +
            0.0040f * normalized.pow(3) +
            0.055f * normalized.pow(7)
    val y = verticalPull.coerceIn(-1f, 1f)
    val speedScale = if (y <= 0f) {
        1f + 5f * (-y).pow(1.45f)
    } else {
        0.018f + 0.982f * (1f - y).pow(3.1f)
    }
    return sign(pull) * horizontalRate * speedScale
}

internal fun rangeFineTuneDeltaSeconds(
    horizontalPull: Float,
    verticalPull: Float,
    durationSeconds: Float,
    dtSeconds: Float,
): Float = rangeFineTuneTimelineRate(horizontalPull, verticalPull) *
    durationSeconds.coerceAtLeast(0f) *
    dtSeconds.coerceAtLeast(0f)

internal fun rangeFineTuneShuttleRate(
    horizontalPull: Float,
    verticalPull: Float,
    durationSeconds: Float,
): Float {
    val duration = durationSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (duration <= 0f) return 0f
    // Transport follows actual source seconds traversed per real second, not control percentage.
    // Audible pitch is compressed separately by the shuttle renderer.
    return rangeFineTuneTimelineRate(
        horizontalPull = rangeFineTuneSeekPull(horizontalPull),
        verticalPull = verticalPull,
    ) * duration
}

internal fun editTargetValue(values: RangeEditValues, target: RangeEditTarget): Float = when (target) {
    RangeEditTarget.START -> values.startSeconds
    RangeEditTarget.END -> values.endSeconds
}

internal fun projectFineAdjustShuttleTarget(
    values: RangeEditValues,
    target: RangeEditTarget,
    pendingDeltaSeconds: Float,
    durationSeconds: Float,
): Float {
    if (pendingDeltaSeconds == 0f) return editTargetValue(values, target)
    val projected = adjustRangeEditTarget(
        values = values,
        target = target,
        requestedSeconds = editTargetValue(values, target) + pendingDeltaSeconds,
        durationSeconds = durationSeconds,
    )
    return editTargetValue(projected.values, target)
}

internal fun rangeTimelineDurationSeconds(value: Float): Float =
    value.takeIf { it.isFinite() && it > 0f } ?: 0.05f

/** Optional visualization must not take down capture when a retained-chunk read fails. */
internal suspend fun constructRangeWaveform(
    build: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
    onFinished: () -> Unit,
) {
    try {
        build()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // A superseded owner may finish a blocking storage read after cancellation.
        currentCoroutineContext().ensureActive()
        onFailure(error)
    } finally {
        onFinished()
    }
}

internal class RangeExportEditorState(
    initialDurationSeconds: Float,
    private val rememberedRangeExport: RememberedRangeExport? = null,
    onPreviewReleaseFailure: (Throwable) -> Unit = {},
    private val onPreviewFailure: (Throwable) -> Unit = {},
    notifyNativeUiChanged: (() -> Unit)? = null,
) {
    private val previewController = TimelineAudioPreviewController(
        onTrackReleaseFailure = onPreviewReleaseFailure,
    )

    var snapshot by rangeUiState(mutableStateOf<ReverbService.TimelineSnapshot?>(null), notifyNativeUiChanged)
        private set
    var durationSeconds by rangeUiState(mutableFloatStateOf(rangeTimelineDurationSeconds(initialDurationSeconds)), notifyNativeUiChanged)
        private set
    private val initialRestoredRange = restoreRememberedRangeExport(durationSeconds, rememberedRangeExport)
    private var restoreRememberedRangeOnFirstSnapshot = rememberedRangeExport != null

    var startSeconds by rangeUiState(mutableFloatStateOf(initialRestoredRange.startSeconds), notifyNativeUiChanged)
        private set
    var endSeconds by rangeUiState(mutableFloatStateOf(initialRestoredRange.endSeconds), notifyNativeUiChanged)
        private set
    var lastTarget by rangeUiState(mutableStateOf(RangeEditTarget.START), notifyNativeUiChanged)
        private set
    var coarseWaveform by rangeUiState(mutableStateOf(FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)), notifyNativeUiChanged)
        private set
    var coarseWaveformBuiltCount by rangeUiState(mutableIntStateOf(0), notifyNativeUiChanged)
        private set
    var detailWaveform by rangeUiState(mutableStateOf(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)), notifyNativeUiChanged)
        private set
    var detailWaveformBuiltCount by rangeUiState(mutableIntStateOf(0), notifyNativeUiChanged)
        private set
    var waveformPass by rangeUiState(mutableStateOf(RangeWaveformPass.COARSE), notifyNativeUiChanged)
        private set
    var waveformLoading by rangeUiState(mutableStateOf(true), notifyNativeUiChanged)
        private set
    var isPlaying by rangeUiState(mutableStateOf(false), notifyNativeUiChanged)
        private set
    var isScrubbing by rangeUiState(mutableStateOf(false), notifyNativeUiChanged)
        private set
    var selectionDurationInteraction by rangeUiState(mutableStateOf(RangeDurationWheelInteraction()), notifyNativeUiChanged)
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

