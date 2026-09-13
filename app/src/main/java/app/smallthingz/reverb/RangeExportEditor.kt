package app.smallthingz.reverb

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh

internal enum class RangeEditTarget { START, CURSOR, END }

internal data class RangeEditValues(
    val startSeconds: Float,
    val cursorSeconds: Float,
    val endSeconds: Float,
)

internal data class RangeEditUpdate(
    val values: RangeEditValues,
    val snappedTo: RangeEditTarget?,
)

internal fun adjustRangeEditTarget(
    values: RangeEditValues,
    target: RangeEditTarget,
    requestedSeconds: Float,
    durationSeconds: Float,
    snapThresholdSeconds: Float,
    minRangeSeconds: Float = 0.05f,
): RangeEditUpdate {
    val duration = durationSeconds.coerceAtLeast(minRangeSeconds)
    val threshold = snapThresholdSeconds.coerceAtLeast(0f)
    var start = values.startSeconds.coerceIn(0f, duration)
    var end = values.endSeconds.coerceIn(start, duration)
    var cursor = values.cursorSeconds.coerceIn(0f, duration)
    var snappedTo: RangeEditTarget? = null
    when (target) {
        RangeEditTarget.CURSOR -> {
            var requested = requestedSeconds.coerceIn(0f, duration)
            val startDistance = abs(requested - start)
            val endDistance = abs(requested - end)
            val movingTowardStart = startDistance < abs(cursor - start)
            val movingTowardEnd = endDistance < abs(cursor - end)
            if (movingTowardStart && startDistance <= threshold && startDistance <= endDistance) {
                requested = start
                snappedTo = RangeEditTarget.START
            } else if (movingTowardEnd && endDistance <= threshold) {
                requested = end
                snappedTo = RangeEditTarget.END
            }
            cursor = requested
        }
        RangeEditTarget.START -> {
            var requested = requestedSeconds.coerceIn(0f, (end - minRangeSeconds).coerceAtLeast(0f))
            val cursorDistance = abs(requested - cursor)
            val movingTowardCursor = cursorDistance < abs(start - cursor)
            if (movingTowardCursor && cursor <= end - minRangeSeconds && cursorDistance <= threshold) {
                requested = cursor
                snappedTo = RangeEditTarget.CURSOR
            }
            start = requested.coerceAtMost((end - minRangeSeconds).coerceAtLeast(0f))
        }
        RangeEditTarget.END -> {
            var requested = requestedSeconds.coerceIn((start + minRangeSeconds).coerceAtMost(duration), duration)
            val cursorDistance = abs(requested - cursor)
            val movingTowardCursor = cursorDistance < abs(end - cursor)
            if (movingTowardCursor && cursor >= start + minRangeSeconds && cursorDistance <= threshold) {
                requested = cursor
                snappedTo = RangeEditTarget.CURSOR
            }
            end = requested.coerceAtLeast((start + minRangeSeconds).coerceAtMost(duration))
        }
    }
    return RangeEditUpdate(RangeEditValues(start, cursor, end), snappedTo)
}

private const val RANGE_FINE_TUNE_HORIZONTAL_SEEK_GAIN = 1f / 0.62f

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

internal class RangeExportEditorState(
    initialDurationSeconds: Float,
) {
    private val previewController = TimelineAudioPreviewController()

    var snapshot by mutableStateOf<ReverbService.TimelineSnapshot?>(null)
        private set
    var durationSeconds by mutableFloatStateOf(initialDurationSeconds.coerceAtLeast(0.05f))
        private set

    var startSeconds by mutableFloatStateOf(0f)
        private set
    var endSeconds by mutableFloatStateOf(durationSeconds)
        private set
    var cursorSeconds by mutableFloatStateOf(0f)
        private set
    var lastTarget by mutableStateOf(RangeEditTarget.CURSOR)
        private set
    var snappedTo by mutableStateOf<RangeEditTarget?>(null)
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
    private var lastAuditionAtMillis = 0L

    val selectionDurationSeconds: Float
        get() = (endSeconds - startSeconds).coerceAtLeast(0f)

    val snapshotReady: Boolean
        get() = snapshot != null

    fun attachSnapshot(value: ReverbService.TimelineSnapshot) {
        invalidateTextEditing()
        val previousDuration = durationSeconds
        val nextDuration = value.durationSeconds.toFloat().coerceAtLeast(0.05f)
        val endWasAtLiveEdge = kotlin.math.abs(endSeconds - previousDuration) <= 0.15f
        snapshot = value
        durationSeconds = nextDuration
        startSeconds = startSeconds.coerceIn(0f, (nextDuration - 0.05f).coerceAtLeast(0f))
        endSeconds = if (endWasAtLiveEdge) {
            nextDuration
        } else {
            endSeconds.coerceIn((startSeconds + 0.05f).coerceAtMost(nextDuration), nextDuration)
        }
        cursorSeconds = cursorSeconds.coerceIn(0f, nextDuration)
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

    fun publishWaveformBucket(pass: RangeWaveformPass, index: Int, magnitude: Float) {
        when (pass) {
            RangeWaveformPass.COARSE -> {
                if (index !in coarseWaveform.indices) return
                val next = coarseWaveform.copyOf()
                next[index] = magnitude.coerceIn(0f, 1f)
                coarseWaveform = next
                coarseWaveformBuiltCount = maxOf(coarseWaveformBuiltCount, index + 1)
            }
            RangeWaveformPass.DETAIL -> {
                if (index !in detailWaveform.indices) return
                val next = detailWaveform.copyOf()
                next[index] = magnitude.coerceIn(0f, 1f)
                detailWaveform = next
                detailWaveformBuiltCount = maxOf(detailWaveformBuiltCount, index + 1)
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

    fun setTarget(
        target: RangeEditTarget,
        requestedSeconds: Float,
        snapThresholdSeconds: Float,
    ): Boolean {
        lastTarget = target
        val previousSnap = snappedTo
        val update = adjustRangeEditTarget(
            values = RangeEditValues(startSeconds, cursorSeconds, endSeconds),
            target = target,
            requestedSeconds = requestedSeconds,
            durationSeconds = durationSeconds,
            snapThresholdSeconds = snapThresholdSeconds,
        )
        startSeconds = update.values.startSeconds
        cursorSeconds = update.values.cursorSeconds
        endSeconds = update.values.endSeconds
        snappedTo = update.snappedTo
        return update.snappedTo != null && update.snappedTo != previousSnap
    }

    fun commitTarget(target: RangeEditTarget, requestedSeconds: Float): Boolean {
        if (!requestedSeconds.isFinite() || requestedSeconds !in 0f..durationSeconds) return false
        when (target) {
            RangeEditTarget.START -> if (requestedSeconds >= endSeconds) return false
            RangeEditTarget.END -> if (requestedSeconds <= startSeconds) return false
            RangeEditTarget.CURSOR -> Unit
        }
        // A blur may be caused by selecting another bar. Committing the old field must not
        // steal selection back from the newly touched target.
        val selectedTarget = lastTarget
        val update = adjustRangeEditTarget(
            values = RangeEditValues(startSeconds, cursorSeconds, endSeconds),
            target = target,
            requestedSeconds = requestedSeconds,
            durationSeconds = durationSeconds,
            snapThresholdSeconds = 0f,
        )
        startSeconds = update.values.startSeconds
        cursorSeconds = update.values.cursorSeconds
        endSeconds = update.values.endSeconds
        snappedTo = null
        lastTarget = selectedTarget
        return true
    }

    fun beginBoundaryEdit(target: RangeEditTarget) {
        selectTarget(target)
        pausePreview()
        previewController.stop()
    }

    fun beginCursorScrub() {
        invalidateTextEditing()
        selectTarget(RangeEditTarget.CURSOR)
        resumeAfterScrub = isPlaying
        if (isPlaying) {
            previewController.stop()
            isPlaying = false
        }
        isScrubbing = true
        auditionCursor(force = true)
    }

    fun updateCursorScrub(requestedSeconds: Float, snapThresholdSeconds: Float) {
        setTarget(RangeEditTarget.CURSOR, requestedSeconds, snapThresholdSeconds)
        auditionCursor()
    }

    fun endCursorScrub() {
        isScrubbing = false
        previewController.stop()
        if (resumeAfterScrub) {
            resumeAfterScrub = false
            startPreview()
        }
    }

    fun beginFineAdjust() {
        invalidateTextEditing()
        if (lastTarget == RangeEditTarget.CURSOR) {
            resumeAfterScrub = isPlaying
            if (isPlaying) {
                previewController.stop()
                isPlaying = false
            }
            isScrubbing = true
            auditionCursor(force = true)
        } else {
            pausePreview()
        }
    }

    fun fineAdjust(deltaSeconds: Float, snapThresholdSeconds: Float) {
        val target = lastTarget
        setTarget(target, targetValue(target) + deltaSeconds, snapThresholdSeconds)
        if (target == RangeEditTarget.CURSOR) auditionCursor()
    }

    fun endFineAdjust() {
        if (lastTarget == RangeEditTarget.CURSOR) {
            isScrubbing = false
            previewController.stop()
            if (resumeAfterScrub) {
                resumeAfterScrub = false
                startPreview()
            }
        }
    }

    fun togglePreview() {
        invalidateTextEditing()
        if (isPlaying) pausePreview() else startPreview()
    }

    fun pausePreview() {
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
        if (cursorSeconds >= durationSeconds - 0.01f) cursorSeconds = 0f
        previewError = null
        isPlaying = true
        isScrubbing = false
        previewController.play(
            snapshot = readySnapshot,
            fromSeconds = cursorSeconds.toDouble(),
            onProgress = { seconds ->
                cursorSeconds = seconds.toFloat().coerceIn(0f, durationSeconds)
            },
            onFinished = {
                cursorSeconds = durationSeconds
                isPlaying = false
            },
            onError = { error ->
                isPlaying = false
                previewError = error.message
            },
        )
    }

    private fun auditionCursor(force: Boolean = false) {
        val readySnapshot = snapshot ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAuditionAtMillis < 55L) return
        lastAuditionAtMillis = now
        previewController.audition(readySnapshot, cursorSeconds.toDouble())
    }

    private fun targetValue(target: RangeEditTarget): Float = when (target) {
        RangeEditTarget.START -> startSeconds
        RangeEditTarget.CURSOR -> cursorSeconds
        RangeEditTarget.END -> endSeconds
    }
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
    isListening: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    maxExportDurationSeconds: Float,
    visualizerVisible: Boolean,
    onCancel: () -> Unit,
    onExport: (startSeconds: Float, endSeconds: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(selectedBuffer) { RangeExportEditorState(initialDurationSeconds) }
    var transitionStarted by remember(selectedBuffer) { mutableStateOf(false) }
    val transitionProgress by animateFloatAsState(
        targetValue = if (transitionStarted) 1f else 0f,
        animationSpec = tween(
            durationMillis = 760,
            easing = FastOutSlowInEasing,
        ),
        label = "blobToRangeTimeline",
    )

    LaunchedEffect(selectedBuffer) { transitionStarted = true }
    DisposableEffect(state) {
        onDispose { state.close() }
    }
    LaunchedEffect(snapshot) {
        val readySnapshot = snapshot ?: return@LaunchedEffect
        state.attachSnapshot(readySnapshot)
        state.resetWaveformConstruction()

        suspend fun constructPass(pass: RangeWaveformPass) {
            val updates = Channel<Pair<Int, Float>>(Channel.UNLIMITED)
            val worker = launch(Dispatchers.IO) {
                try {
                    readySnapshot.readWaveformEnvelopeProgressive(pass) { index, magnitude ->
                        updates.trySend(index to magnitude).isSuccess
                    }
                } finally {
                    updates.close()
                }
            }
            try {
                for ((index, magnitude) in updates) {
                    state.publishWaveformBucket(pass, index, magnitude)
                }
                worker.join()
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

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val compact = maxHeight < 390.dp
            val density = LocalDensity.current
            val blobSize = minOf(maxWidth * 0.90f, maxHeight * 0.94f, 372.dp)
            // AudioBlobView's live body is roughly two thirds of its square view. Transform the
            // existing view wrapper so that body, not the view bounds, lands on the timeline.
            val blobVisualDiameter = blobSize * 0.66f
            val targetBlobScaleX = ((maxWidth - 24.dp).coerceAtLeast(1.dp) / blobVisualDiameter)
                .coerceIn(1.18f, 1.72f)
            val targetBlobScaleY = (146.dp / blobVisualDiameter).coerceIn(0.50f, 0.78f)
            val blobFade = (1f - ((transitionProgress - 0.34f) / 0.54f).coerceIn(0f, 1f))
            val timelineFade = ((transitionProgress - 0.12f) / 0.62f).coerceIn(0f, 1f)
            val chromeFade = ((transitionProgress - 0.46f) / 0.42f).coerceIn(0f, 1f)
            val timelineScaleX = 0.30f + 0.70f * transitionProgress
            val timelineScaleY = 1.62f - 0.62f * transitionProgress
            if (transitionProgress < 0.995f) {
                BufferBlobPage(
                    bufferSlot = selectedBuffer,
                    activeBuffer = activeBuffer,
                    metrics = blobMetrics,
                    bufferEnabled = blobEnabled,
                    oneShotFull = oneShotFull,
                    isListening = isListening,
                    isSaving = isSaving,
                    service = service,
                    blobController = blobController,
                    flipDegrees = 0f,
                    onListenToggle = {},
                    onOpenBufferSettings = {},
                    visualizerVisible = visualizerVisible,
                    interactionEnabled = false,
                    contentAlpha = (1f - transitionProgress * 2.7f).coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = blobFade
                            scaleX = 1f + (targetBlobScaleX - 1f) * transitionProgress
                            scaleY = 1f + (targetBlobScaleY - 1f) * transitionProgress
                            translationY = -with(density) { 58.dp.toPx() } * transitionProgress
                        },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!compact) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.alpha(chromeFade),
                    ) {
                        Text(
                            text = formatRangeTimeInput(state.selectionDurationSeconds.toDouble()),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.range_export_selected),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                RangeExportTimeline(
                    state = state,
                    morphProgress = transitionProgress,
                    chromeAlpha = chromeFade,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 232.dp else 250.dp)
                        .graphicsLayer {
                            alpha = timelineFade
                            scaleX = timelineScaleX
                            scaleY = timelineScaleY
                        },
                )
                Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                SpringFineAdjust(
                    state = state,
                    enabled = transitionProgress >= 0.98f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 120.dp else 132.dp)
                        .alpha(chromeFade),
                )
                if (state.selectionDurationSeconds > maxExportDurationSeconds) {
                    Text(
                        text = stringResource(
                            R.string.range_export_limit_hint,
                            formatRangeTimeInput(maxExportDurationSeconds.toDouble()),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        RangeExportControls(
            state = state,
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
}

@Composable
private fun RangeExportTimeline(
    state: RangeExportEditorState,
    morphProgress: Float,
    chromeAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
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
        val snapThreshold = state.durationSeconds * with(density) { 8.dp.toPx() } / timelineWidthPx

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
                .pointerInput(state.durationSeconds, timelineWidthPx, chromeAlpha) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (chromeAlpha < 0.90f) return@awaitEachGesture
                        state.invalidateTextEditing()
                        focusManager.clearFocus(force = true)
                        state.beginCursorScrub()
                        try {
                            state.updateCursorScrub(
                                secondsFor(down.position.x + insetPx),
                                snapThreshold,
                            )
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                pressed = change.pressed
                                if (pressed) {
                                    change.consume()
                                    state.updateCursorScrub(
                                        secondsFor(change.position.x + insetPx),
                                        snapThreshold,
                                    )
                                }
                            }
                        } finally {
                            state.endCursorScrub()
                        }
                    }
                },
        ) {
            WaveformCanvas(
                coarseWaveform = state.coarseWaveform,
                coarseBuiltCount = state.coarseWaveformBuiltCount,
                detailWaveform = state.detailWaveform,
                detailBuiltCount = state.detailWaveformBuiltCount,
                waveformPass = state.waveformPass,
                startFraction = state.startSeconds / state.durationSeconds,
                endFraction = state.endSeconds / state.durationSeconds,
                loading = state.waveformLoading,
                morphProgress = morphProgress,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Draw the cursor first. When it snaps onto an endpoint, the endpoint's center grip
        // stays on top while the rest of the cursor line remains directly draggable.
        RangeTimelineBar(
            target = RangeEditTarget.CURSOR,
            state = state,
            xPx = xFor(state.cursorSeconds),
            topPx = timelineTopPx,
            heightPx = timelineHeightPx,
            hitWidthPx = hitWidthPx,
            timelineWidthPx = timelineWidthPx,
            snapThresholdSeconds = snapThreshold,
            visualAlpha = chromeAlpha,
            onSnap = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
        )
        RangeTimelineBar(
            target = RangeEditTarget.START,
            state = state,
            xPx = xFor(state.startSeconds),
            topPx = timelineTopPx,
            heightPx = timelineHeightPx,
            hitWidthPx = hitWidthPx,
            timelineWidthPx = timelineWidthPx,
            snapThresholdSeconds = snapThreshold,
            visualAlpha = chromeAlpha,
            onSnap = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
        )
        RangeTimelineBar(
            target = RangeEditTarget.END,
            state = state,
            xPx = xFor(state.endSeconds),
            topPx = timelineTopPx,
            heightPx = timelineHeightPx,
            hitWidthPx = hitWidthPx,
            timelineWidthPx = timelineWidthPx,
            snapThresholdSeconds = snapThreshold,
            visualAlpha = chromeAlpha,
            onSnap = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
        )

        TimelineTimeInput(
            target = RangeEditTarget.START,
            valueSeconds = state.startSeconds,
            active = state.lastTarget == RangeEditTarget.START,
            editorState = state,
            visualAlpha = chromeAlpha,
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
            valueSeconds = state.endSeconds,
            active = state.lastTarget == RangeEditTarget.END,
            editorState = state,
            visualAlpha = chromeAlpha,
            modifier = Modifier.offset {
                IntOffset(
                    bubbleOffset(xFor(state.endSeconds), fullWidthPx, bubbleWidthPx),
                    with(density) { 3.dp.roundToPx() },
                )
            },
            onFocus = { state.beginBoundaryEdit(RangeEditTarget.END) },
        )
        TimelineTimeInput(
            target = RangeEditTarget.CURSOR,
            valueSeconds = state.cursorSeconds,
            active = state.lastTarget == RangeEditTarget.CURSOR,
            editorState = state,
            visualAlpha = chromeAlpha,
            modifier = Modifier.offset {
                IntOffset(
                    bubbleOffset(xFor(state.cursorSeconds), fullWidthPx, bubbleWidthPx),
                    (timelineTopPx + timelineHeightPx + with(density) { 7.dp.toPx() }).roundToInt(),
                )
            },
            onFocus = {
                state.selectTarget(RangeEditTarget.CURSOR)
                state.pausePreview()
            },
        )

        Text(
            text = formatRangeTimeInput(0.0),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = horizontalInset).alpha(chromeAlpha),
        )
        Text(
            text = formatRangeTimeInput(state.durationSeconds.toDouble()),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = horizontalInset).alpha(chromeAlpha),
        )
    }
}

private fun bubbleOffset(xPx: Float, fullWidthPx: Float, bubbleWidthPx: Float): Int {
    val desiredLeft = xPx - bubbleWidthPx * 0.5f
    return desiredLeft.coerceIn(0f, (fullWidthPx - bubbleWidthPx).coerceAtLeast(0f)).roundToInt()
}

@Composable
private fun WaveformCanvas(
    coarseWaveform: FloatArray,
    coarseBuiltCount: Int,
    detailWaveform: FloatArray,
    detailBuiltCount: Int,
    waveformPass: RangeWaveformPass,
    startFraction: Float,
    endFraction: Float,
    loading: Boolean,
    morphProgress: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var wobblePhase by remember { mutableFloatStateOf(0f) }
    val coarseAvailableFraction = if (coarseWaveform.isEmpty()) 0f else
        (coarseBuiltCount.toFloat() / coarseWaveform.size.toFloat()).coerceIn(0f, 1f)
    val detailAvailableFraction = if (detailWaveform.isEmpty()) 0f else
        (detailBuiltCount.toFloat() / detailWaveform.size.toFloat()).coerceIn(0f, 1f)
    // Let the blob become a single provisional ribbon before committing the sampled shape.
    // The worker may finish early, but visual construction still reads as one left-to-right sweep.
    val coarseTarget = if (morphProgress >= 0.64f) coarseAvailableFraction else 0f
    val detailTarget = if (morphProgress >= 0.90f && waveformPass == RangeWaveformPass.DETAIL) {
        detailAvailableFraction
    } else 0f
    val visibleCoarse by animateFloatAsState(
        targetValue = coarseTarget,
        animationSpec = tween(durationMillis = 430, easing = FastOutSlowInEasing),
        label = "rangeWaveformCoarseReveal",
    )
    val visibleDetailRaw by animateFloatAsState(
        targetValue = detailTarget,
        animationSpec = tween(durationMillis = 330, easing = FastOutSlowInEasing),
        label = "rangeWaveformDetailReveal",
    )
    val visibleDetail = minOf(visibleDetailRaw, visibleCoarse)

    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                withFrameNanos { frameNanos ->
                    wobblePhase = (frameNanos / 1_000_000_000.0).toFloat()
                }
            }
        } else {
            repeat(14) {
                withFrameNanos { frameNanos ->
                    wobblePhase = (frameNanos / 1_000_000_000.0).toFloat()
                }
            }
        }
    }

    Canvas(modifier) {
        val path = waveformConstructionPath(
            coarseWaveform = coarseWaveform,
            coarseBuiltCount = coarseBuiltCount,
            detailWaveform = detailWaveform,
            detailBuiltCount = detailBuiltCount,
            visibleCoarseFraction = visibleCoarse,
            visibleDetailFraction = visibleDetail,
            morphProgress = morphProgress,
            phase = wobblePhase,
            width = size.width,
            height = size.height,
        ) ?: return@Canvas
        val centerY = size.height * 0.5f
        val builtRight = size.width * visibleCoarse.coerceIn(0f, 1f)
        val detailRight = size.width * visibleDetail.coerceIn(0f, 1f)
        val selectedLeft = size.width * startFraction.coerceIn(0f, 1f)
        val selectedRight = size.width * endFraction.coerceIn(startFraction, 1f)

        drawLine(
            color = colors.onSurfaceVariant.copy(alpha = 0.10f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx(),
        )

        // The unresolved suffix remains live material. It contracts from the original blob
        // silhouette into a ribbon while fixed audio is progressively committed from the left.
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(
                    colors.primary.copy(alpha = 0.08f),
                    colors.primary.copy(alpha = 0.14f),
                    colors.primary.copy(alpha = 0.08f),
                ),
            ),
        )
        drawPath(
            path = path,
            color = colors.primary.copy(alpha = 0.08f),
            style = Stroke(width = 1.dp.toPx()),
        )

        if (builtRight > 0f) {
            clipRect(left = 0f, right = builtRight) {
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            colors.primary.copy(alpha = 0.24f),
                            colors.primary.copy(alpha = 0.32f),
                            colors.primary.copy(alpha = 0.24f),
                        ),
                    ),
                )
                drawPath(
                    path = path,
                    color = colors.primary.copy(alpha = 0.16f),
                    style = Stroke(width = 1.25.dp.toPx()),
                )
                clipRect(left = selectedLeft, right = selectedRight) {
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            listOf(
                                colors.primary.copy(alpha = 0.94f),
                                colors.primary,
                                colors.primary.copy(alpha = 0.94f),
                            ),
                        ),
                    )
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.Transparent,
                                colors.primary.copy(alpha = 0.10f),
                            ),
                        ),
                    )
                    drawPath(
                        path = path,
                        color = colors.primary.copy(alpha = 0.24f),
                        style = Stroke(width = 1.7.dp.toPx()),
                    )
                }
            }
        }

        // A soft construction front makes the left-to-right materialization read as a sweep,
        // rather than a hard clip edge. The second pass uses a smaller polishing front.
        if (visibleCoarse in 0.002f..0.998f) {
            drawLine(
                color = colors.primary.copy(alpha = 0.12f),
                start = Offset(builtRight, size.height * 0.10f),
                end = Offset(builtRight, size.height * 0.90f),
                strokeWidth = 13.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.36f),
                start = Offset(builtRight, size.height * 0.13f),
                end = Offset(builtRight, size.height * 0.87f),
                strokeWidth = 1.15.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (
            waveformPass == RangeWaveformPass.DETAIL &&
            visibleDetail in 0.002f..0.998f &&
            visibleCoarse > 0.95f
        ) {
            drawLine(
                color = colors.tertiary.copy(alpha = 0.13f),
                start = Offset(detailRight, size.height * 0.13f),
                end = Offset(detailRight, size.height * 0.87f),
                strokeWidth = 9.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(detailRight, size.height * 0.16f),
                end = Offset(detailRight, size.height * 0.84f),
                strokeWidth = 0.9.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun waveformConstructionPath(
    coarseWaveform: FloatArray,
    coarseBuiltCount: Int,
    detailWaveform: FloatArray,
    detailBuiltCount: Int,
    visibleCoarseFraction: Float,
    visibleDetailFraction: Float,
    morphProgress: Float,
    phase: Float,
    width: Float,
    height: Float,
): Path? {
    if (detailWaveform.size < 2 || coarseWaveform.size < 2 || width <= 0f || height <= 0f) return null
    val center = height * 0.5f
    val maxAmplitude = height * 0.44f
    val minimumAmplitude = height * 0.035f
    val visibleCoarse = visibleCoarseFraction.coerceIn(0f, 1f)
    val visibleDetail = visibleDetailFraction.coerceIn(0f, visibleCoarse)
    val coarseAvailable = (coarseBuiltCount.toFloat() / coarseWaveform.size.toFloat()).coerceIn(0f, 1f)
    val detailAvailable = (detailBuiltCount.toFloat() / detailWaveform.size.toFloat()).coerceIn(0f, 1f)
    val ribbonProgress = ((morphProgress - 0.20f) / 0.80f).coerceIn(0f, 1f)
    val morph = ribbonProgress * ribbonProgress * (3f - 2f * ribbonProgress)
    val path = Path()

    fun sampledValue(values: FloatArray, builtCount: Int, u: Float): Float? {
        if (builtCount <= 0 || values.isEmpty()) return null
        val position = u.coerceIn(0f, 1f) * values.lastIndex.toFloat()
        val first = position.toInt().coerceIn(0, values.lastIndex)
        if (first >= builtCount) return null
        val second = minOf(first + 1, builtCount - 1, values.lastIndex)
        val fraction = (position - first.toFloat()).coerceIn(0f, 1f)
        return values[first] + (values[second] - values[first]) * fraction
    }

    fun revealWeight(front: Float, u: Float, feather: Float): Float {
        if (front >= 0.999f) return 1f
        val distance = front - u
        if (distance >= 0f) return 1f
        if (distance <= -feather) return 0f
        val t = ((distance + feather) / feather).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun blobEnvelopeAt(u: Float): Float {
        val x = (u - 0.5f) * 2f
        return 0.12f + 0.76f * kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f))
    }

    fun provisionalValue(u: Float): Float {
        val blobEnvelope = blobEnvelopeAt(u)
        val ribbonWobble = (
            0.27f +
                0.070f * kotlin.math.sin(u * 31f + phase * 2.25f) +
                0.040f * kotlin.math.sin(u * 67f - phase * 1.62f) +
                0.022f * kotlin.math.sin(u * 113f + phase * 1.08f)
            ).coerceIn(0.08f, 0.68f)
        return blobEnvelope + (ribbonWobble - blobEnvelope) * morph
    }

    fun resolvedDuringMorph(u: Float, resolved: Float): Float {
        val blobEnvelope = blobEnvelopeAt(u)
        return blobEnvelope + (resolved.coerceIn(0f, 1f) - blobEnvelope) * morph
    }

    fun amplitudeAt(index: Int): Float {
        val u = index.toFloat() / detailWaveform.lastIndex.toFloat()
        var sample = provisionalValue(u)

        if (u <= coarseAvailable + 0.04f) {
            val coarse = sampledValue(coarseWaveform, coarseBuiltCount, u)
            if (coarse != null) {
                val fixedShape = resolvedDuringMorph(u, coarse)
                val weight = revealWeight(visibleCoarse, u, 0.036f)
                sample += (fixedShape - sample) * weight
            }
        }
        if (u <= detailAvailable + 0.03f) {
            val detail = sampledValue(detailWaveform, detailBuiltCount, u)
            if (detail != null) {
                val fixedShape = resolvedDuringMorph(u, detail)
                val weight = revealWeight(visibleDetail, u, 0.024f)
                sample += (fixedShape - sample) * weight
            }
        }
        return minimumAmplitude + maxAmplitude * sample.coerceIn(0f, 1f)
    }

    for (index in detailWaveform.indices) {
        val x = width * index.toFloat() / detailWaveform.lastIndex.toFloat()
        val amplitude = amplitudeAt(index)
        if (index == 0) path.moveTo(x, center - amplitude) else path.lineTo(x, center - amplitude)
    }
    for (index in detailWaveform.lastIndex downTo 0) {
        val x = width * index.toFloat() / detailWaveform.lastIndex.toFloat()
        path.lineTo(x, center + amplitudeAt(index))
    }
    path.close()
    return path
}

@Composable
private fun RangeTimelineBar(
    target: RangeEditTarget,
    state: RangeExportEditorState,
    xPx: Float,
    topPx: Float,
    heightPx: Float,
    hitWidthPx: Float,
    timelineWidthPx: Float,
    snapThresholdSeconds: Float,
    visualAlpha: Float,
    onSnap: () -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val active = state.lastTarget == target || state.snappedTo == target
    val colors = MaterialTheme.colorScheme
    val lineColor = if (active) colors.tertiary else colors.onSurface
    val tapInteraction = remember { MutableInteractionSource() }
    var dragOrigin by remember(target) { mutableFloatStateOf(0f) }
    var accumulatedDrag by remember(target) { mutableFloatStateOf(0f) }

    val interactionEnabled = visualAlpha >= 0.90f

    fun focusTarget() {
        state.invalidateTextEditing()
        focusManager.clearFocus(force = true)
        if (target == RangeEditTarget.CURSOR) {
            state.selectTarget(RangeEditTarget.CURSOR)
            state.pausePreview()
        } else {
            state.beginBoundaryEdit(target)
        }
    }

    val dragModifier = if (interactionEnabled) {
        Modifier
            .pointerInput(target, interactionEnabled) {
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
            .clickable(
                interactionSource = tapInteraction,
                indication = null,
                onClick = ::focusTarget,
            )
            .pointerInput(target, state.durationSeconds, timelineWidthPx) {
                detectDragGestures(
                    onDragStart = {
                        state.invalidateTextEditing()
                        focusManager.clearFocus(force = true)
                        dragOrigin = when (target) {
                            RangeEditTarget.START -> state.startSeconds
                            RangeEditTarget.CURSOR -> state.cursorSeconds
                            RangeEditTarget.END -> state.endSeconds
                        }
                        accumulatedDrag = 0f
                        if (target == RangeEditTarget.CURSOR) state.beginCursorScrub()
                        else state.beginBoundaryEdit(target)
                    },
                    onDragEnd = {
                        if (target == RangeEditTarget.CURSOR) state.endCursorScrub()
                    },
                    onDragCancel = {
                        if (target == RangeEditTarget.CURSOR) state.endCursorScrub()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    accumulatedDrag += dragAmount.x
                    val requested = dragOrigin + accumulatedDrag / timelineWidthPx * state.durationSeconds
                    val snapped = if (target == RangeEditTarget.CURSOR) {
                        val before = state.snappedTo
                        state.updateCursorScrub(requested, snapThresholdSeconds)
                        state.snappedTo != null && state.snappedTo != before
                    } else {
                        state.setTarget(target, requested, snapThresholdSeconds)
                    }
                    if (snapped) onSnap()
                }
            }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (xPx - hitWidthPx * 0.5f).roundToInt(),
                    topPx.roundToInt(),
                )
            }
            .size(
                width = with(density) { hitWidthPx.toDp() },
                height = with(density) { heightPx.toDp() },
            )
            .graphicsLayer { alpha = visualAlpha.coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (active) 3.dp else 2.dp)
                .fillMaxHeight()
                .background(lineColor, RoundedCornerShape(99.dp)),
        )
        if (target == RangeEditTarget.CURSOR) {
            Box(
                modifier = Modifier.fillMaxSize().then(dragModifier),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = CircleShape,
                    color = lineColor,
                    shadowElevation = if (active) 5.dp else 1.dp,
                ) {}
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().then(dragModifier),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(12.dp, 32.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = lineColor,
                    shadowElevation = if (active) 4.dp else 1.dp,
                ) {}
            }
        }
    }
}

@Composable
private fun TimelineTimeInput(
    target: RangeEditTarget,
    valueSeconds: Float,
    active: Boolean,
    editorState: RangeExportEditorState,
    visualAlpha: Float,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(formatRangeTimeInput(valueSeconds.toDouble())) }
    var wasFocused by remember { mutableStateOf(false) }
    val editGeneration = editorState.textEditGeneration
    var focusGeneration by remember { mutableLongStateOf(editGeneration) }
    var invalid by remember { mutableStateOf(false) }

    LaunchedEffect(valueSeconds) {
        val formatted = formatRangeTimeInput(valueSeconds.toDouble())
        if (focused && editGeneration == focusGeneration) {
            // Programmatic motion owns the target once its underlying position changes.
            editorState.invalidateTextEditing()
            text = formatted
            focusManager.clearFocus(force = true)
        } else if (!focused) {
            text = formatted
        }
    }
    LaunchedEffect(editGeneration) {
        if (editGeneration != focusGeneration) {
            text = formatRangeTimeInput(valueSeconds.toDouble())
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
                                text = formatRangeTimeInput(valueSeconds.toDouble())
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
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var dragging by remember { mutableStateOf(false) }
    var horizontalPull by remember { mutableFloatStateOf(0f) }
    var rawVerticalPull by remember { mutableFloatStateOf(0f) }
    var dragStartRawVertical by remember { mutableFloatStateOf(0f) }
    var lastFrameNanos by remember { mutableLongStateOf(0L) }

    val constrainedY = rangeFineTuneConstrainedY(rawVerticalPull, horizontalPull)

    LaunchedEffect(enabled) {
        if (!enabled) {
            dragging = false
            horizontalPull = 0f
            rawVerticalPull = 0f
            state.endFineAdjust()
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
        lastFrameNanos = 0L
        while (dragging) {
            withFrameNanos { frameNanos ->
                val previous = lastFrameNanos
                lastFrameNanos = frameNanos
                if (previous == 0L) return@withFrameNanos
                val dtSeconds =
                    ((frameNanos - previous).coerceAtMost(50_000_000L)) / 1_000_000_000f
                val liveY = rangeFineTuneConstrainedY(rawVerticalPull, horizontalPull)
                val deltaSeconds = rangeFineTuneDeltaSeconds(
                    horizontalPull = rangeFineTuneSeekPull(horizontalPull),
                    verticalPull = liveY,
                    durationSeconds = state.durationSeconds,
                    dtSeconds = dtSeconds,
                )
                if (deltaSeconds != 0f) {
                    state.fineAdjust(deltaSeconds, snapThresholdSeconds = 0.04f)
                }
            }
        }
    }

    val gestureModifier = if (enabled) {
        Modifier.pointerInput(state.lastTarget) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                state.invalidateTextEditing()
                focusManager.clearFocus(force = true)
                val edgePadding = 10.dp.toPx()
                val puckRadius = 16.dp.toPx()
                val visualHorizontalTravel = (size.width * 0.5f - edgePadding - puckRadius)
                    .coerceAtLeast(1f)
                val visualVerticalTravel = (size.height * 0.5f - edgePadding - puckRadius)
                    .coerceAtLeast(1f)
                val verticalInputTravel = visualVerticalTravel * 2.35f

                dragging = true
                dragStartRawVertical = rawVerticalPull
                horizontalPull = rangeFineTuneHorizontalTouchPull(
                    pointerX = down.position.x,
                    width = size.width.toFloat(),
                    horizontalTravel = visualHorizontalTravel,
                )
                state.beginFineAdjust()
                down.consume()

                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
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
                } finally {
                    if (dragging) {
                        dragging = false
                        state.endFineAdjust()
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier.then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val edgePadding = 10.dp.toPx()
            val puckRadius = 16.dp.toPx()
            val horizontalTravel = (size.width * 0.5f - edgePadding - puckRadius)
                .coerceAtLeast(1f)
            val verticalTravel = (size.height * 0.5f - edgePadding - puckRadius)
                .coerceAtLeast(1f)
            val puck = Offset(
                x = center.x + horizontalPull * horizontalTravel,
                y = center.y + constrainedY * verticalTravel,
            )
            val leftTipX = edgePadding
            val rightTipX = size.width - edgePadding
            val leftSpan = (puck.x - puckRadius - leftTipX).coerceAtLeast(1f)
            val rightSpan = (rightTipX - puck.x - puckRadius).coerceAtLeast(1f)
            val topY = puck.y - puckRadius
            val bottomY = puck.y + puckRadius

            val field = Path().apply {
                moveTo(leftTipX, center.y)
                cubicTo(
                    leftTipX + leftSpan * 0.30f,
                    center.y,
                    (puck.x - puckRadius - leftSpan * 0.28f).coerceAtLeast(leftTipX),
                    topY,
                    puck.x,
                    topY,
                )
                cubicTo(
                    (puck.x + puckRadius + rightSpan * 0.28f).coerceAtMost(rightTipX),
                    topY,
                    rightTipX - rightSpan * 0.30f,
                    center.y,
                    rightTipX,
                    center.y,
                )
                cubicTo(
                    rightTipX - rightSpan * 0.30f,
                    center.y,
                    (puck.x + puckRadius + rightSpan * 0.28f).coerceAtMost(rightTipX),
                    bottomY,
                    puck.x,
                    bottomY,
                )
                cubicTo(
                    (puck.x - puckRadius - leftSpan * 0.28f).coerceAtLeast(leftTipX),
                    bottomY,
                    leftTipX + leftSpan * 0.30f,
                    center.y,
                    leftTipX,
                    center.y,
                )
                close()
            }

            val horizontalPower = abs(horizontalPull).pow(0.72f)
            val yMagnitude = (abs(constrainedY) / 0.72f).coerceIn(0f, 1f)
            val fieldColor = when {
                constrainedY < 0f -> lerp(colors.primary, colors.tertiary, yMagnitude)
                constrainedY > 0f -> lerp(colors.primary, colors.secondary, yMagnitude)
                else -> colors.primary
            }
            val fieldAlpha = 0.12f + 0.13f * horizontalPower + 0.06f * yMagnitude
            drawPath(
                path = field,
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
                path = field,
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
                    radius = puckRadius * 1.42f,
                    center = puck,
                )
            }
            // The puck itself always stays the Material foreground color. Only
            // the surrounding field communicates fast/fine mode through color.
            drawCircle(
                color = colors.onSurface,
                radius = puckRadius,
                center = puck,
            )
        }
    }
}

@Composable
private fun RangeExportControls(
    state: RangeExportEditorState,
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                IconButton(
                    onClick = {
                        val accepted = state.commitActiveTextEditing()
                        focusManager.clearFocus(force = true)
                        if (accepted) state.togglePreview()
                    },
                    enabled = state.snapshotReady,
                    modifier = Modifier.size(50.dp),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) AppIcons.pause else AppIcons.play,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play,
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatRangeTimeInput(state.selectionDurationSeconds.toDouble()),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = chrome.ink,
                        maxLines = 1,
                    )
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
                    enabled = state.snapshotReady,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
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
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.export),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
