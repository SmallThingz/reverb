package app.smallthingz.reverb

import android.os.SystemClock
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

internal data class RangeFineTunePull(
    val horizontal: Float,
    val rawVertical: Float,
)

internal fun rangeFineTuneDragPull(
    startHorizontal: Float,
    startRawVertical: Float,
    dragDeltaX: Float,
    dragDeltaY: Float,
    horizontalTravel: Float,
    verticalTravel: Float,
): RangeFineTunePull = RangeFineTunePull(
    horizontal = (
        startHorizontal + dragDeltaX / horizontalTravel.coerceAtLeast(1f)
    ).coerceIn(-1f, 1f),
    rawVertical = startRawVertical + dragDeltaY / verticalTravel.coerceAtLeast(1f),
)

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
    val snapshot: ReverbService.TimelineSnapshot,
) {
    val durationSeconds = snapshot.durationSeconds.toFloat().coerceAtLeast(0.05f)
    private val previewController = TimelineAudioPreviewController()

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
    var waveform by mutableStateOf(FloatArray(0))
    var waveformLoading by mutableStateOf(true)
    var isPlaying by mutableStateOf(false)
        private set
    var isScrubbing by mutableStateOf(false)
        private set
    var previewError by mutableStateOf<String?>(null)
        private set

    private var resumeAfterScrub = false
    private var lastAuditionAtMillis = 0L

    val selectionDurationSeconds: Float
        get() = (endSeconds - startSeconds).coerceAtLeast(0f)

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
        setTarget(target, requestedSeconds, snapThresholdSeconds = 0f)
        return true
    }

    fun beginBoundaryEdit(target: RangeEditTarget) {
        selectTarget(target)
        pausePreview()
        previewController.stop()
    }

    fun beginCursorScrub() {
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
        if (durationSeconds <= 0f) return
        if (cursorSeconds >= durationSeconds - 0.01f) cursorSeconds = 0f
        previewError = null
        isPlaying = true
        isScrubbing = false
        previewController.play(
            snapshot = snapshot,
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
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAuditionAtMillis < 55L) return
        lastAuditionAtMillis = now
        previewController.audition(snapshot, cursorSeconds.toDouble())
    }

    private fun targetValue(target: RangeEditTarget): Float = when (target) {
        RangeEditTarget.START -> startSeconds
        RangeEditTarget.CURSOR -> cursorSeconds
        RangeEditTarget.END -> endSeconds
    }
}

@Composable
internal fun RangeExportHomeContent(
    snapshot: ReverbService.TimelineSnapshot,
    selectedBuffer: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot?,
    isListening: Boolean,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    maxExportDurationSeconds: Float,
    onCancel: () -> Unit,
    onExport: (startSeconds: Float, endSeconds: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(snapshot) {
        RangeExportEditorState(snapshot).also { editor ->
            snapshot.initialWaveformEnvelope?.let { raw ->
                editor.waveform = normalizeWaveformEnvelope(raw)
                editor.waveformLoading = false
            }
        }
    }

    DisposableEffect(state) {
        onDispose { state.close() }
    }
    LaunchedEffect(snapshot) {
        if (!state.waveformLoading) return@LaunchedEffect
        try {
            state.waveform = withContext(Dispatchers.IO) {
                snapshot.readWaveformEnvelope(128)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.waveform = FloatArray(0)
        } finally {
            state.waveformLoading = false
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!compact) {
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
                    Spacer(Modifier.height(8.dp))
                }
                RangeExportTimeline(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 232.dp else 250.dp),
                )
                Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                FineTuneField(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 120.dp else 132.dp),
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
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
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
                .pointerInput(state.durationSeconds, timelineWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
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
                waveform = state.waveform,
                startFraction = state.startSeconds / state.durationSeconds,
                endFraction = state.endSeconds / state.durationSeconds,
                loading = state.waveformLoading,
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
            onSnap = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
        )

        TimelineTimeInput(
            target = RangeEditTarget.START,
            valueSeconds = state.startSeconds,
            active = state.lastTarget == RangeEditTarget.START,
            modifier = Modifier.offset {
                IntOffset(
                    bubbleOffset(xFor(state.startSeconds), fullWidthPx, bubbleWidthPx),
                    with(density) { 3.dp.roundToPx() },
                )
            },
            onFocus = { state.beginBoundaryEdit(RangeEditTarget.START) },
            onCommit = { state.commitTarget(RangeEditTarget.START, it) },
        )
        TimelineTimeInput(
            target = RangeEditTarget.END,
            valueSeconds = state.endSeconds,
            active = state.lastTarget == RangeEditTarget.END,
            modifier = Modifier.offset {
                IntOffset(
                    bubbleOffset(xFor(state.endSeconds), fullWidthPx, bubbleWidthPx),
                    with(density) { 3.dp.roundToPx() },
                )
            },
            onFocus = { state.beginBoundaryEdit(RangeEditTarget.END) },
            onCommit = { state.commitTarget(RangeEditTarget.END, it) },
        )
        TimelineTimeInput(
            target = RangeEditTarget.CURSOR,
            valueSeconds = state.cursorSeconds,
            active = state.lastTarget == RangeEditTarget.CURSOR,
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
            onCommit = { state.commitTarget(RangeEditTarget.CURSOR, it) },
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

private fun bubbleOffset(xPx: Float, fullWidthPx: Float, bubbleWidthPx: Float): Int {
    val desiredLeft = xPx - bubbleWidthPx * 0.5f
    return desiredLeft.coerceIn(0f, (fullWidthPx - bubbleWidthPx).coerceAtLeast(0f)).roundToInt()
}

@Composable
private fun WaveformCanvas(
    waveform: FloatArray,
    startFraction: Float,
    endFraction: Float,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val path = waveformPath(waveform, size.width, size.height)
        val centerY = size.height * 0.5f
        drawLine(
            color = colors.onSurfaceVariant.copy(alpha = 0.12f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx(),
        )
        if (path == null) {
            val alpha = if (loading) 0.12f else 0.08f
            drawLine(
                color = colors.primary.copy(alpha = alpha),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(
                    colors.primary.copy(alpha = 0.12f),
                    colors.primary.copy(alpha = 0.18f),
                    colors.primary.copy(alpha = 0.12f),
                ),
            ),
        )
        drawPath(
            path = path,
            color = colors.primary.copy(alpha = 0.10f),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        val left = size.width * startFraction.coerceIn(0f, 1f)
        val right = size.width * endFraction.coerceIn(startFraction, 1f)
        clipRect(left = left, right = right) {
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
                        colors.primary.copy(alpha = 0.12f),
                    ),
                ),
            )
            drawPath(
                path = path,
                color = colors.primary.copy(alpha = 0.28f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private fun waveformPath(waveform: FloatArray, width: Float, height: Float): Path? {
    if (waveform.size < 2 || width <= 0f || height <= 0f) return null
    val center = height * 0.5f
    val maxAmplitude = height * 0.44f
    val minimumAmplitude = height * 0.035f
    val path = Path()
    waveform.forEachIndexed { index, sample ->
        val x = width * index.toFloat() / waveform.lastIndex.toFloat()
        val amplitude = minimumAmplitude + maxAmplitude * sample.coerceIn(0f, 1f)
        if (index == 0) path.moveTo(x, center - amplitude) else path.lineTo(x, center - amplitude)
    }
    for (index in waveform.lastIndex downTo 0) {
        val x = width * index.toFloat() / waveform.lastIndex.toFloat()
        val amplitude = minimumAmplitude + maxAmplitude * waveform[index].coerceIn(0f, 1f)
        path.lineTo(x, center + amplitude)
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
    onSnap: () -> Unit,
) {
    val density = LocalDensity.current
    val active = state.lastTarget == target || state.snappedTo == target
    val colors = MaterialTheme.colorScheme
    val lineColor = if (active) colors.tertiary else colors.onSurface
    val tapInteraction = remember { MutableInteractionSource() }
    var dragOrigin by remember(target) { mutableFloatStateOf(0f) }
    var accumulatedDrag by remember(target) { mutableFloatStateOf(0f) }

    fun focusTarget() {
        if (target == RangeEditTarget.CURSOR) {
            state.selectTarget(RangeEditTarget.CURSOR)
            state.pausePreview()
        } else {
            state.beginBoundaryEdit(target)
        }
    }

    val dragModifier = Modifier
        .clickable(
            interactionSource = tapInteraction,
            indication = null,
            onClick = ::focusTarget,
        )
        .pointerInput(target, state.durationSeconds, timelineWidthPx) {
            detectDragGestures(
                onDragStart = {
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
            ),
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
                modifier = Modifier
                    .width(with(density) { hitWidthPx.toDp() })
                    .height(64.dp)
                    .then(dragModifier),
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
    onFocus: () -> Unit,
    onCommit: (Float) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(formatRangeTimeInput(valueSeconds.toDouble())) }
    var wasFocused by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }

    LaunchedEffect(valueSeconds, focused) {
        if (!focused) text = formatRangeTimeInput(valueSeconds.toDouble())
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
        modifier = modifier.width(100.dp).height(34.dp),
        shape = RoundedCornerShape(13.dp),
        color = background,
        shadowElevation = if (active || focused) 3.dp else 1.dp,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            textStyle = MaterialTheme.typography.labelLarge.copy(
                color = foreground,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            ),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 7.dp, vertical = 8.dp)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && !wasFocused) {
                        wasFocused = true
                        onFocus()
                    } else if (!focusState.isFocused && wasFocused) {
                        wasFocused = false
                        val parsed = parseRangeTimeInput(text)?.toFloat()
                        val accepted = parsed != null && onCommit(parsed)
                        if (!accepted) {
                            invalid = true
                            text = formatRangeTimeInput(valueSeconds.toDouble())
                        }
                    }
                },
        )
    }
}

@Composable
private fun FineTuneField(
    state: RangeExportEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var horizontalPull by remember { mutableFloatStateOf(0f) }
    var rawVerticalPull by remember { mutableFloatStateOf(0f) }
    var dragStartHorizontal by remember { mutableFloatStateOf(0f) }
    var dragStartRawVertical by remember { mutableFloatStateOf(0f) }
    var dragDeltaX by remember { mutableFloatStateOf(0f) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }
    var lastFrameNanos by remember { mutableLongStateOf(0L) }

    val constrainedY = rangeFineTuneConstrainedY(rawVerticalPull, horizontalPull)

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
                    horizontalPull = horizontalPull,
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

    Box(
        modifier = modifier.pointerInput(state.lastTarget) {
            detectDragGestures(
                onDragStart = {
                    dragging = true
                    dragStartHorizontal = horizontalPull
                    dragStartRawVertical = rawVerticalPull
                    dragDeltaX = 0f
                    dragDeltaY = 0f
                    state.beginFineAdjust()
                },
                onDragEnd = {
                    dragging = false
                    state.endFineAdjust()
                },
                onDragCancel = {
                    dragging = false
                    state.endFineAdjust()
                },
            ) { change, dragAmount ->
                change.consume()
                dragDeltaX += dragAmount.x
                dragDeltaY += dragAmount.y
                val edgePadding = 10.dp.toPx()
                val puckRadius = 16.dp.toPx()
                val visualHorizontalTravel = (size.width * 0.5f - edgePadding - puckRadius)
                    .coerceAtLeast(1f)
                val visualVerticalTravel = (size.height * 0.5f - edgePadding - puckRadius)
                    .coerceAtLeast(1f)
                val horizontalInputTravel = visualHorizontalTravel * 0.62f
                val verticalInputTravel = visualVerticalTravel * 2.35f
                val pull = rangeFineTuneDragPull(
                    startHorizontal = dragStartHorizontal,
                    startRawVertical = dragStartRawVertical,
                    dragDeltaX = dragDeltaX,
                    dragDeltaY = dragDeltaY,
                    horizontalTravel = horizontalInputTravel,
                    verticalTravel = verticalInputTravel,
                )
                horizontalPull = pull.horizontal
                rawVerticalPull = pull.rawVertical
            }
        },
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
                IconButton(onClick = onCancel, modifier = Modifier.size(50.dp)) {
                    Icon(
                        imageVector = AppIcons.close,
                        contentDescription = stringResource(R.string.close),
                        tint = chrome.ink,
                    )
                }
                IconButton(onClick = state::togglePreview, modifier = Modifier.size(50.dp)) {
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
                    onClick = onExport,
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
