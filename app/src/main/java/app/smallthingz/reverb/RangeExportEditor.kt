package app.smallthingz.reverb

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.tanh

internal enum class RangeEditTarget { START, END }

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

private const val RANGE_TIMELINE_MARKER_EXTRA_HEIGHT_FRACTION = 0.02f
private const val RANGE_TIMELINE_MARKER_HEIGHT_FRACTION =
    RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION + RANGE_TIMELINE_MARKER_EXTRA_HEIGHT_FRACTION
private const val RANGE_TIMELINE_LINE_WIDTH_DP = 1f
private const val RANGE_TIMELINE_ACTIVE_LINE_WIDTH_DP = 1.4f
private const val RANGE_TIMELINE_BOUNDARY_GRIP_WIDTH_DP = 8f
private const val RANGE_TIMELINE_BOUNDARY_GRIP_HEIGHT_DP = 28f
private const val RANGE_TIMELINE_CURSOR_DOT_DP = 6f

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
    return RestoredRangeExport(start.toFloat(), end.toFloat())
}

internal fun rememberedRangeExportFromSavedRange(
    availableSeconds: Double,
    startSeconds: Float,
    endSeconds: Float,
): RememberedRangeExport? {
    val available = availableSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: return null
    if (!startSeconds.isFinite() || !endSeconds.isFinite() || available <= 0.0) return null
    val start = startSeconds.toDouble().coerceIn(0.0, available)
    val end = endSeconds.toDouble().coerceIn(start, available)
    val selectionMillis = ((end - start) * 1_000.0).roundToLong()
    if (selectionMillis <= 0L) return null
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

internal fun adjustRangeEditTarget(
    values: RangeEditValues,
    target: RangeEditTarget,
    requestedSeconds: Float,
    durationSeconds: Float,
    minRangeSeconds: Float = 0.05f,
): RangeEditUpdate {
    val minimumRange = minRangeSeconds.coerceAtLeast(0f)
    val duration = durationSeconds.coerceAtLeast(minimumRange)
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
    return RangeEditUpdate(RangeEditValues(start, end))
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
    selectedDurationSeconds >= 0.0 &&
    maximumDurationSeconds >= 0.0 &&
    selectedDurationSeconds <= maximumDurationSeconds

internal fun resizeRangeSelectionDuration(
    values: RangeEditValues,
    target: RangeEditTarget,
    requestedDurationSeconds: Float,
    durationSeconds: Float,
    minRangeSeconds: Float = 0.05f,
): RangeEditUpdate {
    val duration = durationSeconds.takeIf { it.isFinite() }
        ?.coerceAtLeast(minRangeSeconds) ?: minRangeSeconds
    var start = values.startSeconds.coerceIn(0f, duration)
    var end = values.endSeconds.coerceIn(start, duration)
    val currentDuration = (end - start).coerceAtLeast(minRangeSeconds)
    val requested = requestedDurationSeconds.takeIf { it.isFinite() }
        ?.coerceAtLeast(minRangeSeconds) ?: currentDuration
    when (target) {
        RangeEditTarget.START -> {
            start = (end - requested).coerceIn(0f, (end - minRangeSeconds).coerceAtLeast(0f))
        }
        RangeEditTarget.END -> {
            end = (start + requested).coerceIn(
                (start + minRangeSeconds).coerceAtMost(duration),
                duration,
            )
        }
    }
    return RangeEditUpdate(RangeEditValues(start, end))
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

internal fun rangeFineTuneSpeedScale(verticalPull: Float): Float {
    val y = verticalPull.coerceIn(-1f, 1f)
    return if (y <= 0f) {
        1f + 5f * (-y).pow(1.45f)
    } else {
        0.018f + 0.982f * (1f - y).pow(3.1f)
    }
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
    return sign(pull) * horizontalRate * rangeFineTuneSpeedScale(verticalPull)
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
): Float {
    val pull = rangeFineTuneSeekPull(horizontalPull)
    val magnitude = abs(pull)
    if (magnitude <= 0.002f) return 0f
    val normalized = ((magnitude - 0.002f) / 0.998f).coerceIn(0f, 1f)
    // This is scrub aggressiveness, not an AudioTrack playback-rate multiplier.
    // A value from 1x..8x controls both source-head catch-up and how much source audio
    // each fixed-duration output grain time-compresses.
    val baseSpeed = 1f + 7f * normalized.pow(2f)
    val verticalScale = rangeFineTuneSpeedScale(verticalPull).pow(0.32f)
    return sign(pull) * (baseSpeed * verticalScale).coerceIn(1f, 8f)
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

internal class RangeExportEditorState(
    initialDurationSeconds: Float,
    private val rememberedRangeExport: RememberedRangeExport? = null,
) {
    private val previewController = TimelineAudioPreviewController()

    var snapshot by mutableStateOf<ReverbService.TimelineSnapshot?>(null)
        private set
    var durationSeconds by mutableFloatStateOf(initialDurationSeconds.coerceAtLeast(0.05f))
        private set
    private val initialRestoredRange = restoreRememberedRangeExport(durationSeconds, rememberedRangeExport)
    private var restoreRememberedRangeOnFirstSnapshot = rememberedRangeExport != null

    var startSeconds by mutableFloatStateOf(initialRestoredRange.startSeconds)
        private set
    var endSeconds by mutableFloatStateOf(initialRestoredRange.endSeconds)
        private set
    var lastTarget by mutableStateOf(RangeEditTarget.START)
        private set
    var coarseWaveform by mutableStateOf(FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS))
        private set
    var coarseWaveformBuiltCount by mutableIntStateOf(0)
        private set
    var detailWaveform by mutableStateOf(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS))
        private set
    var detailWaveformBuiltCount by mutableIntStateOf(0)
        private set
    var waveformPass by mutableStateOf(RangeWaveformPass.COARSE)
        private set
    var waveformLoading by mutableStateOf(true)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isScrubbing by mutableStateOf(false)
        private set
    var previewError by mutableStateOf<String?>(null)
        private set
    var textEditGeneration by mutableLongStateOf(0L)
        private set
    private var activeTextTarget: RangeEditTarget? = null
    private var activeTextDraft: String? = null

    private var resumeAfterScrub = false
    private var fineAdjustShuttleActive = false
    private var lastAuditionAtMillis = 0L

    val selectionDurationExactSeconds: Double
        get() = rangeSelectionDurationExactSeconds(startSeconds, endSeconds)

    val selectionDurationSeconds: Float
        get() = selectionDurationExactSeconds.toFloat()

    val snapshotReady: Boolean
        get() = snapshot != null

    fun attachSnapshot(value: ReverbService.TimelineSnapshot) {
        invalidateTextEditing()
        val previousDuration = durationSeconds
        val nextDuration = value.durationSeconds.toFloat().coerceAtLeast(0.05f)
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

    fun beginTextEditing(target: RangeEditTarget, draft: String) {
        activeTextTarget = target
        activeTextDraft = draft
    }

    fun updateTextDraft(target: RangeEditTarget, draft: String) {
        if (activeTextTarget == target) activeTextDraft = draft
    }

    fun commitActiveTextEditing(): Boolean {
        val target = activeTextTarget ?: return true
        val parsed = parseRangeTimeInput(activeTextDraft.orEmpty())?.toFloat() ?: return false
        if (!commitTarget(target, parsed)) return false
        activeTextTarget = null
        activeTextDraft = null
        textEditGeneration++
        return true
    }

    fun invalidateTextEditing() {
        activeTextTarget = null
        activeTextDraft = null
        textEditGeneration++
    }

    fun selectTarget(target: RangeEditTarget) {
        lastTarget = target
    }

    fun setTarget(target: RangeEditTarget, requestedSeconds: Float) {
        lastTarget = target
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
        if (activeTextTarget != null || activeTextDraft != null) {
            invalidateTextEditing()
        }
        pausePreview()
    }

    fun setSelectionDuration(requestedDurationSeconds: Float) {
        applyEditUpdate(
            resizeRangeSelectionDuration(
                values = currentEditValues(),
                target = lastTarget,
                requestedDurationSeconds = requestedDurationSeconds,
                durationSeconds = durationSeconds,
            ),
        )
    }

    private fun currentEditValues() = RangeEditValues(startSeconds, endSeconds)

    fun targetSeconds(target: RangeEditTarget): Float = targetValue(target)

    private fun applyEditUpdate(update: RangeEditUpdate) {
        startSeconds = update.values.startSeconds
        endSeconds = update.values.endSeconds
    }

    fun commitTarget(target: RangeEditTarget, requestedSeconds: Float): Boolean {
        if (!requestedSeconds.isFinite() || requestedSeconds !in 0f..durationSeconds) return false
        when (target) {
            RangeEditTarget.START -> if (requestedSeconds >= endSeconds) return false
            RangeEditTarget.END -> if (requestedSeconds <= startSeconds) return false
        }
        // A blur may be caused by selecting another bar. Committing the old field must not
        // steal selection back from the newly touched target.
        val selectedTarget = lastTarget
        applyEditUpdate(
            adjustRangeEditTarget(
                values = currentEditValues(),
                target = target,
                requestedSeconds = requestedSeconds,
                durationSeconds = durationSeconds,
            ),
        )
        lastTarget = selectedTarget
        return true
    }

    fun beginBoundaryEdit(target: RangeEditTarget) {
        selectTarget(target)
        pausePreview()
        previewController.stop()
        auditionSelectedBoundary(force = true)
    }

    fun beginBoundaryScrub(target: RangeEditTarget) {
        invalidateTextEditing()
        selectTarget(target)
        resumeAfterScrub = isPlaying
        if (isPlaying) {
            previewController.stop()
            isPlaying = false
        }
        isScrubbing = true
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
        invalidateTextEditing()
        resumeAfterScrub = isPlaying
        if (isPlaying) {
            previewController.stop()
            isPlaying = false
        }
        isScrubbing = true
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
            atSeconds = projectFineAdjustShuttleTarget(
                values = currentEditValues(),
                target = lastTarget,
                pendingDeltaSeconds = pendingDeltaSeconds,
                durationSeconds = durationSeconds,
            ).toDouble(),
            rate = shuttleRate,
        )
    }

    fun fineAdjust(deltaSeconds: Float) {
        val target = lastTarget
        setTarget(target, targetValue(target) + deltaSeconds)
    }

    fun endFineAdjust() {
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
        invalidateTextEditing()
        if (isPlaying) pausePreview() else startPreview()
    }

    fun pausePreview() {
        if (fineAdjustShuttleActive) {
            fineAdjustShuttleActive = false
            previewController.stopShuttle()
            isScrubbing = false
        }
        if (!isPlaying) return
        previewController.stop()
        isPlaying = false
    }

    fun close() {
        previewController.close()
    }

    private fun startPreview() {
        val readySnapshot = snapshot ?: return
        if (durationSeconds <= 0f) return
        previewError = null
        isPlaying = true
        isScrubbing = false
        val window = boundaryCursorPreviewWindow(
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            endBoundaryActive = lastTarget == RangeEditTarget.END,
        )
        previewController.play(
            snapshot = readySnapshot,
            fromSeconds = window.startSeconds.toDouble(),
            untilSeconds = window.endSeconds.toDouble(),
            onProgress = {},
            onFinished = { isPlaying = false },
            onError = { error ->
                isPlaying = false
                previewError = error.message
            },
        )
    }

    private fun auditionSelectedBoundary(force: Boolean = false) {
        if (fineAdjustShuttleActive) return
        val readySnapshot = snapshot ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAuditionAtMillis < 55L) return
        lastAuditionAtMillis = now
        previewController.audition(readySnapshot, targetValue(lastTarget).toDouble())
    }

    private fun targetValue(target: RangeEditTarget): Float = editTargetValue(currentEditValues(), target)
}

@Composable
internal fun RangeExportHomeContent(
    snapshot: ReverbService.TimelineSnapshot?,
    initialDurationSeconds: Float,
    selectedBuffer: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot?,
    blobMetrics: BufferMetrics,
    blobEnabled: Boolean,
    blobController: AudioBlobController,
    sourceGeometry: RangeMorphSourceGeometry?,
    isListening: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    maxExportDurationSeconds: Double,
    modifier: Modifier = Modifier,
    backProgress: Float = 0f,
    visualizerVisible: Boolean,
    onCancel: () -> Unit,
    onExport: (startSeconds: Float, endSeconds: Float) -> Unit,
) {
    val context = LocalContext.current
    val rememberedRangeExport = remember(selectedBuffer) {
        getRememberedRangeExport(context, selectedBuffer)
    }
    val state = remember(selectedBuffer, rememberedRangeExport) {
        RangeExportEditorState(initialDurationSeconds, rememberedRangeExport)
    }
    val colors = MaterialTheme.colorScheme
    var transitionStarted by remember(selectedBuffer) { mutableStateOf(false) }
    var interactionReady by remember(selectedBuffer) { mutableStateOf(false) }
    var waveformTargetBoundsInRoot by remember(selectedBuffer) { mutableStateOf<Rect?>(null) }
    val transitionProgress = animateFloatAsState(
        targetValue = if (transitionStarted) 1f else 0f,
        animationSpec = tween(
            durationMillis = 760,
            easing = FastOutSlowInEasing,
        ),
        label = "blobToRangeTimeline",
    )
    val predictiveOpenProgress = predictiveBackOpenProgress(backProgress)
    val visualProgress = remember(transitionProgress, predictiveOpenProgress) {
        { transitionProgress.value * predictiveOpenProgress }
    }
    val morphProgress = remember(visualProgress) {
        {
            ((visualProgress() - RANGE_BLOB_MORPH_HANDOFF_PROGRESS) /
                (1f - RANGE_BLOB_MORPH_HANDOFF_PROGRESS)).coerceIn(0f, 1f)
        }
    }
    val chromeAlpha = remember(visualProgress) {
        { ((visualProgress() - 0.46f) / 0.42f).coerceIn(0f, 1f) }
    }

    LaunchedEffect(selectedBuffer, sourceGeometry, waveformTargetBoundsInRoot) {
        if (sourceGeometry == null || waveformTargetBoundsInRoot == null || transitionStarted) {
            return@LaunchedEffect
        }
        transitionStarted = true
    }
    LaunchedEffect(selectedBuffer, transitionStarted) {
        if (!transitionStarted) {
            interactionReady = false
            return@LaunchedEffect
        }
        // Source geometry can keep changing with live activity after the morph starts. Readiness
        // must depend only on the opening animation or those updates can cancel this waiter and
        // leave the settled range editor permanently disabled.
        snapshotFlow { transitionProgress.value }.first { it >= 0.98f }
        interactionReady = true
    }
    LaunchedEffect(backProgress > 0f) {
        if (backProgress > 0f) {
            state.invalidateTextEditing()
            state.pausePreview()
        }
    }
    DisposableEffect(state) {
        onDispose { state.close() }
    }
    LaunchedEffect(snapshot) {
        val readySnapshot = snapshot ?: return@LaunchedEffect
        state.attachSnapshot(readySnapshot)
        state.resetWaveformConstruction()
        // Keep disk sampling and Compose publication out of the geometry-morph critical path.
        // The light zero-pass body already gives immediate visual feedback; once the morph has
        // essentially settled, resolve real audio into it left-to-right as before.
        snapshotFlow { transitionProgress.value }.first { it >= 0.96f }

        suspend fun constructPass(pass: RangeWaveformPass) {
            val updates = Channel<ProgressiveWaveformSnapshot>(Channel.CONFLATED)
            val worker = launch(Dispatchers.IO) {
                val accumulator = ProgressiveWaveformAccumulator(pass.bucketCount)
                try {
                    readySnapshot.readWaveformEnvelopeProgressive(pass) { index, magnitude ->
                        val update = accumulator.record(index, magnitude)
                        update == null || updates.trySend(update).isSuccess
                    }
                    accumulator.finish()?.let { updates.trySend(it) }
                } finally {
                    updates.close()
                }
            }
            try {
                for (update in updates) {
                    state.publishWaveformSnapshot(pass, update)
                }
            } finally {
                worker.cancel()
                updates.close()
            }
        }

        try {
            constructPass(RangeWaveformPass.COARSE)
            // Let the coarse materialization visibly settle before the finer left-to-right polish begins.
            delay(280L)
            state.beginDetailedWaveformPass()
            constructPass(RangeWaveformPass.DETAIL)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            state.finishWaveformConstruction()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val compact = maxHeight < 390.dp
            // Zero-pass range material is the same light primary material as the final timeline.
            val morphStartColor = colors.primary
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!compact) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer { alpha = chromeAlpha() },
                    ) {
                        RangeSelectionDurationWheel(
                            state = state,
                            maxExportDurationSeconds = maxExportDurationSeconds,
                            enabled = interactionReady && backProgress <= 0f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .height(160.dp),
                        )
                        Text(
                            text = stringResource(R.string.range_export_selected),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                RangeExportTimeline(
                    state = state,
                    morphProgress = morphProgress,
                    morphStartColor = morphStartColor,
                    chromeAlpha = chromeAlpha,
                    interactionEnabled = interactionReady && backProgress <= 0f,
                    sourceGeometry = sourceGeometry,
                    transitionStarted = transitionStarted,
                    targetBoundsInRoot = waveformTargetBoundsInRoot,
                    onTargetBoundsInRoot = { bounds ->
                        if (waveformTargetBoundsInRoot != bounds) waveformTargetBoundsInRoot = bounds
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 232.dp else 250.dp),
                )
                Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                SpringFineAdjust(
                    state = state,
                    enabled = interactionReady && backProgress <= 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 120.dp else 132.dp)
                        .graphicsLayer { alpha = chromeAlpha() },
                )
            }
        }

        RangeExportControls(
            modifier = Modifier.graphicsLayer { alpha = chromeAlpha() },
            state = state,
            canExport = rangeExportSelectionWithinLimit(
                state.selectionDurationExactSeconds,
                maxExportDurationSeconds,
            ),
            selectedBuffer = selectedBuffer,
            activeBuffer = activeBuffer,
            isListening = isListening,
            oneShotEnabled = oneShotEnabled,
            oneShotFull = oneShotFull,
            loopingEnabled = loopingEnabled,
            onCancel = onCancel,
            onExport = { onExport(state.startSeconds, state.endSeconds) },
        )
            Spacer(Modifier.height(18.dp))
        }

        // Before the target waveform has reported its final bounds, keep an exact canonical
        // source circle on screen. It uses the measured home center/diameter, so there is no
        // blank/dark frame while the final layout is being measured.
        val source = sourceGeometry
        if (!transitionStarted && source != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = colors.primary,
                    radius = source.bodyDiameterPx * 0.5f,
                    center = Offset(source.centerXInLocalPx, source.centerYInLocalPx),
                )
            }
        }
    }
}

@Composable
private fun RangeSelectionDurationWheel(
    state: RangeExportEditorState,
    maxExportDurationSeconds: Double,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.range_export_selected)
    AndroidView(
        factory = { context -> RangeDurationWheelView(context) },
        update = { view ->
            view.isEnabled = enabled
            view.setAccessibilityLabel(label)
            view.onInteractionStart = state::beginSelectionDurationEdit
            view.onDurationChanged = { seconds ->
                state.setSelectionDuration(seconds.toFloat())
            }
            view.setPalette(
                ink = colors.onSurface.toArgb(),
                muted = colors.onSurfaceVariant.toArgb(),
                border = colors.outlineVariant.toArgb(),
                error = colors.error.toArgb(),
            )
            view.setMaximumDurationSeconds(maxExportDurationSeconds)
            view.setDurationSeconds(state.selectionDurationExactSeconds)
        },
        modifier = modifier,
    )
}

@Composable
private fun RangeExportTimeline(
    state: RangeExportEditorState,
    morphProgress: () -> Float,
    modifier: Modifier = Modifier,
    morphStartColor: androidx.compose.ui.graphics.Color? = null,
    chromeAlpha: () -> Float,
    interactionEnabled: Boolean,
    sourceGeometry: RangeMorphSourceGeometry?,
    transitionStarted: Boolean,
    targetBoundsInRoot: Rect?,
    onTargetBoundsInRoot: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val timelineTop = 42.dp
    val timelineHeight = 146.dp
    val horizontalInset = 12.dp
    val hitWidth = 44.dp

    BoxWithConstraints(modifier = modifier) {
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val insetPx = with(density) { horizontalInset.toPx() }
        val timelineWidthPx = (fullWidthPx - insetPx * 2f).coerceAtLeast(1f)
        val timelineTopPx = with(density) { timelineTop.toPx() }
        val timelineHeightPx = with(density) { timelineHeight.toPx() }
        val hitWidthPx = with(density) { hitWidth.toPx() }
        val bubbleWidthPx = with(density) { 100.dp.toPx() }

        fun xFor(seconds: Float): Float =
            insetPx + timelineWidthPx * (seconds / state.durationSeconds).coerceIn(0f, 1f)

        fun secondsFor(x: Float): Float =
            ((x - insetPx) / timelineWidthPx * state.durationSeconds).coerceIn(0f, state.durationSeconds)

        Box(
            modifier = Modifier
                .offset(y = timelineTop)
                .padding(horizontal = horizontalInset)
                .fillMaxWidth()
                .height(timelineHeight)
                .pointerInput(state.durationSeconds, timelineWidthPx, interactionEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        if (!interactionEnabled) return@awaitEachGesture
                        state.invalidateTextEditing()
                        focusManager.clearFocus(force = true)
                        val downSeconds = secondsFor(down.position.x + insetPx)
                        val target = state.lastTarget
                        state.beginBoundaryScrub(target)
                        try {
                            state.updateBoundaryScrub(target, downSeconds)
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                pressed = change.pressed
                                if (pressed) {
                                    change.consume()
                                    state.updateBoundaryScrub(
                                        target,
                                        secondsFor(change.position.x + insetPx),
                                    )
                                }
                            }
                        } finally {
                            state.endBoundaryScrub()
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        onTargetBoundsInRoot(coordinates.boundsInRoot())
                    },
            ) {
                ProgressiveWaveformCanvas(
                    coarseWaveform = state.coarseWaveform,
                    coarseBuiltCount = state.coarseWaveformBuiltCount,
                    detailWaveform = state.detailWaveform,
                    detailBuiltCount = state.detailWaveformBuiltCount,
                    waveformPass = state.waveformPass,
                    startFraction = state.startSeconds / state.durationSeconds,
                    endFraction = state.endSeconds / state.durationSeconds,
                    loading = state.waveformLoading,
                    morphProgress = morphProgress,
                    morphStartColor = morphStartColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val source = sourceGeometry
                            val target = targetBoundsInRoot
                            if (!transitionStarted || source == null || target == null) {
                                alpha = 0f
                            } else {
                                val morph = morphProgress()
                                val startScaleX = rangeBlobMorphStartScaleX(
                                    source.bodyDiameterPx,
                                    target.width,
                                )
                                val startScaleY = rangeBlobMorphStartScaleY(
                                    source.bodyDiameterPx,
                                    target.height,
                                )
                                alpha = 1f
                                scaleX = startScaleX + (1f - startScaleX) * morph
                                scaleY = startScaleY + (1f - startScaleY) * morph
                                translationX = rangeBlobMorphTranslation(
                                    morph,
                                    source.centerXInRootPx,
                                    target.center.x,
                                )
                                translationY = rangeBlobMorphTranslation(
                                    morph,
                                    source.centerYInRootPx,
                                    target.center.y,
                                )
                            }
                        },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = chromeAlpha() },
        ) {
        RangeTimelineBar(
            target = RangeEditTarget.START,
            state = state,
            xPx = { xFor(state.startSeconds) },
            topPx = timelineTopPx,
            heightPx = timelineHeightPx,
            hitWidthPx = hitWidthPx,
            timelineWidthPx = timelineWidthPx,
            visualAlpha = 1f,
        )
        RangeTimelineBar(
            target = RangeEditTarget.END,
            state = state,
            xPx = { xFor(state.endSeconds) },
            topPx = timelineTopPx,
            heightPx = timelineHeightPx,
            hitWidthPx = hitWidthPx,
            timelineWidthPx = timelineWidthPx,
            visualAlpha = 1f,
        )

        TimelineTimeInput(
            target = RangeEditTarget.START,
            active = state.lastTarget == RangeEditTarget.START,
            editorState = state,
            visualAlpha = 1f,
            modifier = Modifier.offset {
                IntOffset(
                    bubbleOffset(xFor(state.startSeconds), fullWidthPx, bubbleWidthPx),
                    with(density) { 3.dp.roundToPx() },
                )
            },
            onFocus = { state.beginBoundaryEdit(RangeEditTarget.START) },
        )
        TimelineTimeInput(
            target = RangeEditTarget.END,
            active = state.lastTarget == RangeEditTarget.END,
            editorState = state,
            visualAlpha = 1f,
            modifier = Modifier.offset {
                IntOffset(
                    bubbleOffset(xFor(state.endSeconds), fullWidthPx, bubbleWidthPx),
                    with(density) { 3.dp.roundToPx() },
                )
            },
            onFocus = { state.beginBoundaryEdit(RangeEditTarget.END) },
        )
        Text(
            text = formatRangeTimeInput(0.0),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = horizontalInset),
        )
            Text(
                text = formatRangeTimeInput(state.durationSeconds.toDouble()),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = horizontalInset),
            )
        }
    }
}

private fun bubbleOffset(xPx: Float, fullWidthPx: Float, bubbleWidthPx: Float): Int {
    val desiredLeft = xPx - bubbleWidthPx * 0.5f
    return desiredLeft.coerceIn(0f, (fullWidthPx - bubbleWidthPx).coerceAtLeast(0f)).roundToInt()
}

@Composable
internal fun RangeTimelineBoundaryVisual(
    active: Boolean,
    visualAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val lineColor = if (active) colors.tertiary else colors.onSurface
    Box(
        modifier = modifier.graphicsLayer { alpha = visualAlpha.coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight(RANGE_TIMELINE_MARKER_HEIGHT_FRACTION)
                .width(RANGE_TIMELINE_BOUNDARY_GRIP_WIDTH_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(
                        if (active) RANGE_TIMELINE_ACTIVE_LINE_WIDTH_DP.dp
                        else RANGE_TIMELINE_LINE_WIDTH_DP.dp,
                    )
                    .fillMaxHeight()
                    .background(lineColor, RoundedCornerShape(99.dp)),
            )
            Surface(
                modifier = Modifier.size(
                    RANGE_TIMELINE_BOUNDARY_GRIP_WIDTH_DP.dp,
                    RANGE_TIMELINE_BOUNDARY_GRIP_HEIGHT_DP.dp,
                ),
                shape = RoundedCornerShape(7.dp),
                color = lineColor,
                shadowElevation = if (active) 3.dp else 1.dp,
            ) {}
        }
    }
}

@Composable
internal fun RangeTimelineCursorVisual(
    active: Boolean,
    visualAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val lineColor = if (active) colors.tertiary else colors.onSurface
    Box(
        modifier = modifier.graphicsLayer { alpha = visualAlpha.coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight(RANGE_TIMELINE_MARKER_HEIGHT_FRACTION)
                .width(10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .width(
                        if (active) RANGE_TIMELINE_ACTIVE_LINE_WIDTH_DP.dp
                        else RANGE_TIMELINE_LINE_WIDTH_DP.dp,
                    )
                    .fillMaxHeight()
                    .background(lineColor, RoundedCornerShape(99.dp)),
            )
            Surface(
                modifier = Modifier.size(RANGE_TIMELINE_CURSOR_DOT_DP.dp),
                shape = CircleShape,
                color = lineColor,
                shadowElevation = if (active) 4.dp else 1.dp,
            ) {}
        }
    }
}

@Composable
private fun RangeTimelineBar(
    target: RangeEditTarget,
    state: RangeExportEditorState,
    xPx: () -> Float,
    topPx: Float,
    heightPx: Float,
    hitWidthPx: Float,
    timelineWidthPx: Float,
    visualAlpha: Float,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val active = state.lastTarget == target

    val interactionEnabled = visualAlpha >= 0.90f

    val dragModifier = if (interactionEnabled) {
        Modifier.pointerInput(target, state.durationSeconds, timelineWidthPx, touchSlop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                state.invalidateTextEditing()
                focusManager.clearFocus(force = true)
                state.selectTarget(target)
                val origin = when (target) {
                    RangeEditTarget.START -> state.startSeconds
                    RangeEditTarget.END -> state.endSeconds
                }
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val deltaX = change.position.x - down.position.x
                        if (!dragging && abs(deltaX) > touchSlop) {
                            dragging = true
                            state.beginBoundaryScrub(target)
                        }
                        change.consume()
                        if (dragging) {
                            val requested = origin + deltaX / timelineWidthPx * state.durationSeconds
                            state.updateBoundaryScrub(target, requested)
                        }
                    }
                } finally {
                    if (dragging) state.endBoundaryScrub()
                    else state.beginBoundaryEdit(target)
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (xPx() - hitWidthPx * 0.5f).roundToInt(),
                    topPx.roundToInt(),
                )
            }
            .size(
                width = with(density) { hitWidthPx.toDp() },
                height = with(density) { heightPx.toDp() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            RangeTimelineCursorVisual(
                active = true,
                visualAlpha = visualAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            RangeTimelineBoundaryVisual(
                active = false,
                visualAlpha = visualAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().then(dragModifier))
    }
}

@Composable
private fun TimelineTimeInput(
    target: RangeEditTarget,
    active: Boolean,
    editorState: RangeExportEditorState,
    visualAlpha: Float,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    var text by remember(editorState, target) {
        mutableStateOf(
            formatRangeTimeInput(
                Snapshot.withoutReadObservation { editorState.targetSeconds(target) }.toDouble(),
            ),
        )
    }
    var wasFocused by remember { mutableStateOf(false) }
    val editGeneration = editorState.textEditGeneration
    var focusGeneration by remember { mutableLongStateOf(editGeneration) }
    var invalid by remember { mutableStateOf(false) }

    LaunchedEffect(editorState, target, focused, focusGeneration) {
        snapshotFlow { editorState.targetSeconds(target) }.collect { valueSeconds ->
            val formatted = formatRangeTimeInput(valueSeconds.toDouble())
            if (focused && editorState.textEditGeneration == focusGeneration) {
                // Programmatic motion owns the target once its underlying position changes.
                editorState.invalidateTextEditing()
                if (text != formatted) text = formatted
                focusManager.clearFocus(force = true)
            } else if (!focused && text != formatted) {
                text = formatted
            }
        }
    }
    LaunchedEffect(editGeneration) {
        if (editGeneration != focusGeneration) {
            text = formatRangeTimeInput(editorState.targetSeconds(target).toDouble())
            if (focused) focusManager.clearFocus(force = true)
        }
    }
    LaunchedEffect(invalid) {
        if (invalid) {
            delay(650L)
            invalid = false
        }
    }

    val colors = MaterialTheme.colorScheme
    val background = when {
        invalid -> colors.errorContainer
        active || focused -> colors.primaryContainer
        else -> colors.surfaceContainerHighest
    }
    val foreground = when {
        invalid -> colors.onErrorContainer
        active || focused -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .zIndex(if (active) 30f else if (focused) 20f else 0f)
            .width(100.dp)
            .height(34.dp)
            .graphicsLayer { alpha = visualAlpha.coerceIn(0f, 1f) },
        shape = RoundedCornerShape(13.dp),
        color = background,
        shadowElevation = if (active || focused) 3.dp else 1.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    editorState.updateTextDraft(target, value)
                },
                enabled = visualAlpha >= 0.95f,
                singleLine = true,
                interactionSource = interactionSource,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (editorState.commitActiveTextEditing()) {
                            focusManager.clearFocus(force = true)
                        } else {
                            invalid = true
                        }
                    },
                ),
                textStyle = MaterialTheme.typography.labelLarge.copy(
                    color = foreground,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && !wasFocused) {
                            wasFocused = true
                            focusGeneration = editGeneration
                            editorState.beginTextEditing(target, text)
                            onFocus()
                        } else if (!focusState.isFocused && wasFocused) {
                            wasFocused = false
                            if (focusGeneration != editorState.textEditGeneration) {
                                text = formatRangeTimeInput(editorState.targetSeconds(target).toDouble())
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun SpringFineAdjust(
    state: RangeExportEditorState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    SpringFineSeekControl(
        enabled = enabled && state.snapshotReady,
        isPlaying = state.isPlaying,
        durationSeconds = state.durationSeconds,
        interactionKey = state.lastTarget,
        onTogglePlayback = {
            val accepted = state.commitActiveTextEditing()
            focusManager.clearFocus(force = true)
            if (accepted && state.snapshotReady) state.togglePreview()
        },
        onInteractionStart = {
            state.invalidateTextEditing()
            focusManager.clearFocus(force = true)
        },
        onBeginFineAdjust = state::beginFineAdjust,
        onFineAdjust = { deltaSeconds, _ -> state.fineAdjust(deltaSeconds) },
        onUpdateFineAdjustShuttle = state::updateFineAdjustShuttle,
        onEndFineAdjust = state::endFineAdjust,
        modifier = modifier,
    )
}

@Composable
internal fun SpringFineSeekControl(
    enabled: Boolean,
    isPlaying: Boolean,
    durationSeconds: Float,
    interactionKey: Any?,
    onTogglePlayback: () -> Unit,
    onInteractionStart: () -> Unit,
    onBeginFineAdjust: (Float) -> Unit,
    onFineAdjust: (Float, Float) -> Unit,
    onUpdateFineAdjustShuttle: (Float, Float) -> Unit,
    onEndFineAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var dragging by remember { mutableStateOf(false) }
    var horizontalPull by remember { mutableFloatStateOf(0f) }
    var rawVerticalPull by remember { mutableFloatStateOf(0f) }
    val commitAccumulator = remember { FineAdjustCommitAccumulator() }

    LaunchedEffect(enabled) {
        if (!enabled) {
            dragging = false
            horizontalPull = 0f
            rawVerticalPull = 0f
            onEndFineAdjust()
        }
    }

    LaunchedEffect(dragging) {
        if (dragging) return@LaunchedEffect
        val startX = horizontalPull
        val startY = rawVerticalPull
        if (abs(startX) < 0.0001f && abs(startY) < 0.0001f) {
            horizontalPull = 0f
            rawVerticalPull = 0f
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        val durationNanos = 210_000_000L
        val strength = 3.4f
        val denominator = cosh(strength) - 1f
        while (!dragging) {
            val frameNanos = withFrameNanos { it }
            val t = ((frameNanos - startNanos).toFloat() / durationNanos)
                .coerceIn(0f, 1f)
            val remaining = (cosh(strength * (1f - t)) - 1f) / denominator
            horizontalPull = startX * remaining
            rawVerticalPull = startY * remaining
            if (t >= 1f) {
                horizontalPull = 0f
                rawVerticalPull = 0f
                break
            }
        }
    }

    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        var lastFrameNanos = 0L
        commitAccumulator.reset()
        while (dragging) {
            withFrameNanos { frameNanos ->
                val previous = lastFrameNanos
                lastFrameNanos = frameNanos
                if (previous == 0L) return@withFrameNanos
                val elapsedNanos = (frameNanos - previous).coerceAtMost(50_000_000L)
                val dtSeconds = elapsedNanos / 1_000_000_000f
                val liveHorizontalPull = horizontalPull
                val liveY = rangeFineTuneConstrainedY(rawVerticalPull, liveHorizontalPull)
                val shuttleRate = rangeFineTuneShuttleRate(liveHorizontalPull, liveY)
                commitAccumulator.add(
                    deltaSeconds = rangeFineTuneDeltaSeconds(
                        horizontalPull = rangeFineTuneSeekPull(liveHorizontalPull),
                        verticalPull = liveY,
                        durationSeconds = durationSeconds,
                        dtSeconds = dtSeconds,
                    ),
                    elapsedNanos = elapsedNanos,
                )
                if (commitAccumulator.elapsedNanos >= RANGE_FINE_TUNE_STATE_UPDATE_NANOS) {
                    val deltaSeconds = commitAccumulator.takeDelta()
                    if (deltaSeconds != 0f) {
                        onFineAdjust(deltaSeconds, 0.04f)
                    }
                }
                // Keep Compose commits coalesced, but let audio follow the exact integrated
                // display-rate jog target. This removes the old ~30 Hz source-position stairs
                // without recomposing the timeline every frame.
                onUpdateFineAdjustShuttle(
                    shuttleRate,
                    commitAccumulator.deltaSeconds,
                )
            }
        }
    }

    val gestureModifier = if (enabled) {
        Modifier.pointerInput(interactionKey, touchSlop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val edgePadding = 10.dp.toPx()
                val puckRadius = RANGE_FINE_TUNE_PUCK_RADIUS_DP.dp.toPx()
                val visualHorizontalTravel = (size.width * 0.5f - edgePadding - puckRadius)
                    .coerceAtLeast(1f)
                val visualVerticalTravel = (size.height * 0.5f - edgePadding - puckRadius)
                    .coerceAtLeast(1f)
                val verticalInputTravel = visualVerticalTravel * 2.35f
                val startPuckX = size.width * 0.5f + horizontalPull * visualHorizontalTravel
                val startPuckY = size.height * 0.5f +
                    rangeFineTuneConstrainedY(rawVerticalPull, horizontalPull) * visualVerticalTravel
                val startedOnPuck = rangeFineTunePuckContains(
                    pointerX = down.position.x,
                    pointerY = down.position.y,
                    puckX = startPuckX,
                    puckY = startPuckY,
                    radius = RANGE_FINE_TUNE_PUCK_HIT_RADIUS_DP.dp.toPx(),
                )
                var fineAdjustStarted = false
                var dragStartRawVertical = rawVerticalPull

                fun startFineAdjust(pointerX: Float) {
                    if (fineAdjustStarted) return
                    onInteractionStart()
                    dragStartRawVertical = rawVerticalPull
                    horizontalPull = rangeFineTuneHorizontalTouchPull(
                        pointerX = pointerX,
                        width = size.width.toFloat(),
                        horizontalTravel = visualHorizontalTravel,
                    )
                    dragging = true
                    fineAdjustStarted = true
                    onBeginFineAdjust(
                        rangeFineTuneShuttleRate(
                            horizontalPull = horizontalPull,
                            verticalPull = rangeFineTuneConstrainedY(rawVerticalPull, horizontalPull),
                        ),
                    )
                }

                if (!startedOnPuck) {
                    startFineAdjust(down.position.x)
                    down.consume()
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        if (!fineAdjustStarted && rangeFineTuneMovementExceedsSlop(
                                deltaX = change.position.x - down.position.x,
                                deltaY = change.position.y - down.position.y,
                                touchSlop = touchSlop,
                            )
                        ) {
                            startFineAdjust(change.position.x)
                        }

                        if (fineAdjustStarted) {
                            horizontalPull = rangeFineTuneHorizontalTouchPull(
                                pointerX = change.position.x,
                                width = size.width.toFloat(),
                                horizontalTravel = visualHorizontalTravel,
                            )
                            rawVerticalPull = rangeFineTuneVerticalDragPull(
                                startRawVertical = dragStartRawVertical,
                                dragDeltaY = change.position.y - down.position.y,
                                verticalTravel = verticalInputTravel,
                            )
                            change.consume()
                        }
                    }
                } finally {
                    if (fineAdjustStarted) {
                        val finalDeltaSeconds = commitAccumulator.takeDelta()
                        if (finalDeltaSeconds != 0f) {
                            onFineAdjust(finalDeltaSeconds, 0.04f)
                        }
                        dragging = false
                        onEndFineAdjust()
                    }
                }
            }
        }
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = modifier.then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        val edgePadding = 10.dp
        val puckRadius = RANGE_FINE_TUNE_PUCK_RADIUS_DP.dp
        val horizontalTravelPx = with(density) {
            (maxWidth * 0.5f - edgePadding - puckRadius).coerceAtLeast(1.dp).toPx()
        }
        val verticalTravelPx = with(density) {
            (maxHeight * 0.5f - edgePadding - puckRadius).coerceAtLeast(1.dp).toPx()
        }
        val fieldPath = remember { Path() }

        Canvas(Modifier.fillMaxSize()) {
            // Keep high-frequency pointer state in draw/layout phases. Reading these values in
            // composition made every pointer move recompose the whole spring control.
            val liveHorizontalPull = horizontalPull
            val liveConstrainedY = rangeFineTuneConstrainedY(rawVerticalPull, liveHorizontalPull)
            val puckOffsetX = liveHorizontalPull * horizontalTravelPx
            val puckOffsetY = liveConstrainedY * verticalTravelPx
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val edgePaddingPx = edgePadding.toPx()
            val puckRadiusPx = puckRadius.toPx()
            val puck = Offset(
                x = center.x + puckOffsetX,
                y = center.y + puckOffsetY,
            )
            val leftTipX = edgePaddingPx
            val rightTipX = size.width - edgePaddingPx
            val leftSpan = (puck.x - puckRadiusPx - leftTipX).coerceAtLeast(1f)
            val rightSpan = (rightTipX - puck.x - puckRadiusPx).coerceAtLeast(1f)
            val topY = puck.y - puckRadiusPx
            val bottomY = puck.y + puckRadiusPx

            fieldPath.reset()
            fieldPath.apply {
                moveTo(leftTipX, center.y)
                cubicTo(
                    leftTipX + leftSpan * 0.30f,
                    center.y,
                    (puck.x - puckRadiusPx - leftSpan * 0.28f).coerceAtLeast(leftTipX),
                    topY,
                    puck.x,
                    topY,
                )
                cubicTo(
                    (puck.x + puckRadiusPx + rightSpan * 0.28f).coerceAtMost(rightTipX),
                    topY,
                    rightTipX - rightSpan * 0.30f,
                    center.y,
                    rightTipX,
                    center.y,
                )
                cubicTo(
                    rightTipX - rightSpan * 0.30f,
                    center.y,
                    (puck.x + puckRadiusPx + rightSpan * 0.28f).coerceAtMost(rightTipX),
                    bottomY,
                    puck.x,
                    bottomY,
                )
                cubicTo(
                    (puck.x - puckRadiusPx - leftSpan * 0.28f).coerceAtLeast(leftTipX),
                    bottomY,
                    leftTipX + leftSpan * 0.30f,
                    center.y,
                    leftTipX,
                    center.y,
                )
                close()
            }

            val horizontalPower = abs(liveHorizontalPull).pow(0.72f)
            val yMagnitude = (abs(liveConstrainedY) / 0.72f).coerceIn(0f, 1f)
            val fieldColor = when {
                liveConstrainedY < 0f -> lerp(colors.primary, colors.tertiary, yMagnitude)
                liveConstrainedY > 0f -> lerp(colors.primary, colors.secondary, yMagnitude)
                else -> colors.primary
            }
            val fieldAlpha = 0.12f + 0.13f * horizontalPower + 0.06f * yMagnitude
            drawPath(
                path = fieldPath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        fieldColor.copy(alpha = 0.018f),
                        fieldColor.copy(alpha = fieldAlpha),
                        fieldColor.copy(alpha = 0.018f),
                    ),
                    startX = leftTipX,
                    endX = rightTipX,
                ),
            )
            drawPath(
                path = fieldPath,
                color = fieldColor.copy(alpha = 0.035f + 0.045f * yMagnitude),
            )

            drawCircle(
                color = colors.onSurfaceVariant.copy(alpha = if (dragging) 0.20f else 0.13f),
                radius = 1.6.dp.toPx(),
                center = center,
            )
            if (dragging) {
                drawCircle(
                    color = fieldColor.copy(alpha = 0.055f + 0.055f * horizontalPower),
                    radius = puckRadiusPx * 1.28f,
                    center = puck,
                )
            }
        }

        val puckInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .offset {
                    // Lambda offset defers state reads to layout, so pointer tracking does not
                    // recompose the spring/timeline hierarchy.
                    val liveHorizontalPull = horizontalPull
                    val liveConstrainedY = rangeFineTuneConstrainedY(
                        rawVerticalPull,
                        liveHorizontalPull,
                    )
                    IntOffset(
                        (liveHorizontalPull * horizontalTravelPx).roundToInt(),
                        (liveConstrainedY * verticalTravelPx).roundToInt(),
                    )
                }
                .size(RANGE_FINE_TUNE_PUCK_HIT_RADIUS_DP.dp * 2f)
                .clickable(
                    interactionSource = puckInteractionSource,
                    indication = null,
                    enabled = enabled,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onTogglePlayback,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(puckRadius * 2f),
                shape = CircleShape,
                color = colors.onSurface,
                tonalElevation = if (dragging) 0.dp else 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) AppIcons.pause else AppIcons.play,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.player_pause else R.string.player_play,
                        ),
                        tint = colors.surface,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeExportControls(
    modifier: Modifier = Modifier,
    state: RangeExportEditorState,
    canExport: Boolean,
    selectedBuffer: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot?,
    isListening: Boolean,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    val chrome = appChrome()
    val exportEnabled = state.snapshotReady && canExport
    val exportContainerColor = if (exportEnabled) MaterialTheme.colorScheme.primary else chrome.raised
    val exportContentColor = if (exportEnabled) MaterialTheme.colorScheme.onPrimary else chrome.muted
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val discardDraftOnPointerDown = Modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            state.invalidateTextEditing()
            focusManager.clearFocus(force = true)
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                pressed = change.pressed
            }
        }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = chrome.field,
            border = androidx.compose.foundation.BorderStroke(1.dp, chrome.border),
            modifier = Modifier.alpha(0.54f),
        ) {
            BufferSelector(
                selectedBuffer = selectedBuffer,
                activeBuffer = activeBuffer,
                isListening = isListening,
                oneShotEnabled = oneShotEnabled,
                oneShotFull = oneShotFull,
                loopingEnabled = loopingEnabled,
                interactionEnabled = false,
                onSelectBuffer = {},
                modifier = Modifier.size(242.dp, 54.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = chrome.field,
            border = androidx.compose.foundation.BorderStroke(1.dp, chrome.border),
            modifier = Modifier.fillMaxWidth().height(70.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        state.invalidateTextEditing()
                        focusManager.clearFocus(force = true)
                        onCancel()
                    },
                    modifier = Modifier.size(50.dp).then(discardDraftOnPointerDown),
                ) {
                    Icon(
                        imageVector = AppIcons.close,
                        contentDescription = stringResource(R.string.close),
                        tint = chrome.ink,
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (state.isScrubbing) R.string.range_export_scrubbing
                            else R.string.range_export_selected,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = chrome.muted,
                        maxLines = 1,
                    )
                }
                Surface(
                    onClick = {
                        // Export is a commit boundary. A valid draft becomes the range; an invalid
                        // draft blocks export rather than leaking a stale value into the request.
                        if (state.commitActiveTextEditing()) {
                            focusManager.clearFocus(force = true)
                            view.post(onExport)
                        }
                    },
                    enabled = exportEnabled,
                    shape = RoundedCornerShape(20.dp),
                    color = exportContainerColor,
                    modifier = Modifier.height(50.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = AppIcons.save,
                            contentDescription = null,
                            tint = exportContentColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.export),
                            style = MaterialTheme.typography.labelLarge,
                            color = exportContentColor,
                        )
                    }
                }
            }
        }
    }
}
