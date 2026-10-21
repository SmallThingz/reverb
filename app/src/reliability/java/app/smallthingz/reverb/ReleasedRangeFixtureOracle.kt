package app.smallthingz.reverb

import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.tanh

// Generated released oracle; see generate_oracle.py. No production source is edited.
private const val FIXTURE_RANGE_TIMELINE_MARKER_EXTRA_HEIGHT_FRACTION = 0.02f
private const val FIXTURE_RANGE_TIMELINE_MARKER_HEIGHT_FRACTION =
    RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION + FIXTURE_RANGE_TIMELINE_MARKER_EXTRA_HEIGHT_FRACTION
private const val FIXTURE_RANGE_TIMELINE_LINE_WIDTH_DP = 1f
private const val FIXTURE_RANGE_TIMELINE_ACTIVE_LINE_WIDTH_DP = 1.4f
private const val FIXTURE_RANGE_TIMELINE_BOUNDARY_GRIP_WIDTH_DP = 8f
private const val FIXTURE_RANGE_TIMELINE_BOUNDARY_GRIP_HEIGHT_DP = 28f
private const val FIXTURE_RANGE_TIMELINE_CURSOR_DOT_DP = 6f
private const val FIXTURE_RANGE_TIMELINE_TIME_LABEL_WIDTH_DP = 84f
private const val FIXTURE_RANGE_TIMELINE_TIME_LABEL_HEIGHT_DP = 28f

@Composable
internal fun ReleasedRangeFixtureRangeExportHomeContent(
    snapshot: ReverbService.TimelineSnapshot?,
    fixtureStateObserver: (RangeExportEditorState) -> Unit,
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
    val appContext = context.applicationContext
    val rememberedRangeExport = remember(selectedBuffer) {
        getRememberedRangeExport(context, selectedBuffer)
    }
    val state = remember(selectedBuffer, rememberedRangeExport, appContext) {
        RangeExportEditorState(
            initialDurationSeconds = initialDurationSeconds,
            rememberedRangeExport = rememberedRangeExport,
            onPreviewReleaseFailure = { error ->
                RecordingIncidentStore.recordUnexpectedErrorInBackground(
                    appContext,
                    "Range preview resource cleanup",
                    error,
                )
                AppFeedbackCenter.post(
                    appContext.getString(R.string.audio_preview_release_failed),
                    FeedbackTone.ERROR,
                )
            },
            onPreviewFailure = { error ->
                // The bounded incident description omits suppressed cleanup failures.
                Log.e("ReverbRangeAudio", "Range preview playback failed", error)
                RecordingIncidentStore.recordUnexpectedErrorInBackground(
                    appContext,
                    "Range preview playback",
                    error,
                )
                AppFeedbackCenter.post(
                    appContext.getString(R.string.audio_preview_failed),
                    FeedbackTone.ERROR,
                )
            },
        )
    }
    androidx.compose.runtime.SideEffect { fixtureStateObserver(state) }
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
            state.pausePreview()
        }
    }
    val previewLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(state, previewLifecycleOwner, visualizerVisible) {
        state.setPlaybackAllowed(
            visualizerVisible && previewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.setPlaybackAllowed(visualizerVisible)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> state.setPlaybackAllowed(false)
                else -> Unit
            }
        }
        previewLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            previewLifecycleOwner.lifecycle.removeObserver(observer)
            state.setPlaybackAllowed(false)
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

        constructRangeWaveform(
            build = {
                buildProgressiveWaveform(
                    readPass = { pass, onBucket ->
                        readySnapshot.readWaveformEnvelopeProgressive(pass, onBucket)
                    },
                    onPassStarted = { pass ->
                        if (pass == RangeWaveformPass.DETAIL) state.beginDetailedWaveformPass()
                    },
                    onSnapshot = state::publishWaveformSnapshot,
                )
            },
            onFailure = { error ->
                RecordingIncidentStore.recordUnexpectedErrorInBackground(
                    appContext,
                    "Range waveform construction",
                    error,
                )
                AppFeedbackCenter.post(
                    appContext.getString(R.string.waveform_load_failed),
                    FeedbackTone.ERROR,
                )
            },
            onFinished = state::finishWaveformConstruction,
        )
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
                    ReleasedRangeFixtureRangeSelectionDurationWheel(
                        state = state,
                        maxExportDurationSeconds = maxExportDurationSeconds,
                        enabled = interactionReady && backProgress <= 0f,
                        inputVisible = visualizerVisible,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(160.dp)
                            .graphicsLayer { alpha = chromeAlpha() },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                ReleasedRangeFixtureRangeExportTimeline(
                    state = state,
                    morphProgress = morphProgress,
                    morphStartColor = morphStartColor,
                    chromeAlpha = chromeAlpha,
                    interactionEnabled = interactionReady && backProgress <= 0f && !state.selectionDurationEditing,
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
                ReleasedRangeFixtureSpringFineAdjust(
                    state = state,
                    enabled = interactionReady && backProgress <= 0f && !state.selectionDurationEditing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 120.dp else 132.dp)
                        .graphicsLayer { alpha = chromeAlpha() },
                )
            }
        }

        ReleasedRangeFixtureRangeExportControls(
            // Preparation can take noticeable time on a multi-day history. Keep its Close
            // action visible instead of leaving an invisible but clickable control bar.
            modifier = Modifier.graphicsLayer { alpha = if (snapshot == null) 1f else chromeAlpha() },
            state = state,
            loading = snapshot == null,
            canExport = rangeExportSelectionWithinLimit(
                state.selectionDurationExactSeconds,
                maxExportDurationSeconds,
            ),
            selectedBuffer = selectedBuffer,
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
private fun ReleasedRangeFixtureRangeSelectionDurationWheel(
    state: RangeExportEditorState,
    maxExportDurationSeconds: Double,
    enabled: Boolean,
    inputVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.range_export_duration)
    val wheelMaximumDurationSeconds =
        state.selectionDurationWheelLimitExactSeconds(maxExportDurationSeconds)
    val currentState = rememberUpdatedState(state)
    val currentEnabled = rememberUpdatedState(enabled && inputVisible)
    val currentExportLimit = rememberUpdatedState(maxExportDurationSeconds)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The delegate may survive a recomposition; admission reads live ownership and visibility.
    val lease = remember(state) { booleanArrayOf(true) }
    DisposableEffect(lease) { onDispose { lease[0] = false } }
    AndroidView(
        factory = { context -> RangeDurationWheelView(context) },
        update = { view ->
            view.isEnabled = enabled
            view.setAccessibilityLabel(label)
            view.onInteractionStart = state::beginSelectionDurationEdit
            view.onDurationChanged = { seconds ->
                state.setSelectionDuration(seconds.toFloat())
            }
            view.onInteractionEnd = state::endSelectionDurationEdit
            view.setPalette(
                ink = colors.onSurface.toArgb(),
                muted = colors.onSurfaceVariant.toArgb(),
                border = colors.outlineVariant.toArgb(),
                error = colors.error.toArgb(),
            )
            view.setMaximumDurationSeconds(wheelMaximumDurationSeconds)
            view.setDurationSeconds(state.selectionDurationExactSeconds)
            fun actionReady(): Boolean = lease[0] && currentState.value === state &&
                currentEnabled.value && view.isEnabled && view.isAttachedToWindow &&
                view.isShown && view.hasWindowFocus() &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                state.durationActionAvailable
            fun maximum() = state.selectionDurationWheelLimitExactSeconds(currentExportLimit.value)
            fun commitInput(seconds: Double): Boolean = runRangeDurationAction(
                requestedSeconds = seconds,
                maximumSeconds = maximum(),
                ready = actionReady(),
                begin = state::beginSelectionDurationEdit,
                commit = { state.setSelectionDurationFromAction(it, maximum()); view.setDurationSeconds(state.selectionDurationExactSeconds) },
                end = state::endSelectionDurationEdit,
            )
            fun stepInput(forward: Boolean) = commitInput(
                state.selectionDurationExactSeconds + if (forward) 1.0 else -1.0,
            )
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> stepInput(true)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> stepInput(false)
                    else -> false
                }
            }
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.SeekBar::class.java.name
                    val max = maximum().coerceAtLeast(0.0).toFloat()
                    val min = minOf(0.05f, max)
                    info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                        min, max, state.selectionDurationExactSeconds.toFloat().coerceIn(min, max),
                    )
                    if (actionReady()) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                    }
                }
                override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean =
                    when (action) {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> commitInput(
                            (arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN)
                                ?: Float.NaN).toDouble(),
                        )
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> stepInput(true)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> stepInput(false)
                        else -> super.performAccessibilityAction(host, action, arguments)
                    }
            }
        },
        onRelease = { view ->
            lease[0] = false
            view.setOnKeyListener(null)
            view.accessibilityDelegate = null
            view.clearFocus()
            view.onInteractionStart = null
            view.onDurationChanged = null
            view.onInteractionEnd = null
            state.endSelectionDurationEdit()
        },
        modifier = modifier,
    )
}

@Composable
private fun ReleasedRangeFixtureRangeExportTimeline(
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
    val currentInteractionEnabled = rememberUpdatedState(interactionEnabled)
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
        val bubbleWidthPx = with(density) { FIXTURE_RANGE_TIMELINE_TIME_LABEL_WIDTH_DP.dp.toPx() }

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
                        val gesture = state.inputGesture(
                            interactionAllowed = { currentInteractionEnabled.value },
                            onCancel = { stillCurrent -> if (stillCurrent) state.pausePreview() },
                        )
                        val downSeconds = secondsFor(down.position.x + insetPx)
                        val target = state.lastTarget
                        if (!gesture.perform { state.beginBoundaryScrub(target) }) return@awaitEachGesture
                        try {
                            if (!gesture.perform { state.updateBoundaryScrub(target, downSeconds) }) {
                                return@awaitEachGesture
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    rangePointerReleaseWasNormal(change, gesture::cancel)
                                    break
                                }
                                if (!gesture.perform {
                                        change.consume()
                                        state.updateBoundaryScrub(
                                            target,
                                            secondsFor(change.position.x + insetPx),
                                        )
                                    }
                                ) break
                            }
                        } catch (cancelled: CancellationException) {
                            gesture.cancel()
                            throw cancelled
                        } finally {
                            gesture.finish(state::endBoundaryScrub)
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
        ReleasedRangeFixtureRangeTimelineBar(
            target = RangeEditTarget.START,
            state = state,
            interactionEnabled = interactionEnabled,
            xPx = { xFor(state.startSeconds) },
            topPx = timelineTopPx,
            heightPx = timelineHeightPx,
            hitWidthPx = hitWidthPx,
            timelineWidthPx = timelineWidthPx,
            visualAlpha = 1f,
        )
        ReleasedRangeFixtureRangeTimelineBar(
            target = RangeEditTarget.END,
            state = state,
            interactionEnabled = interactionEnabled,
            xPx = { xFor(state.endSeconds) },
            topPx = timelineTopPx,
            heightPx = timelineHeightPx,
            hitWidthPx = hitWidthPx,
            timelineWidthPx = timelineWidthPx,
            visualAlpha = 1f,
        )

        ReleasedRangeFixtureTimelineTimeLabel(
            target = RangeEditTarget.START,
            active = state.lastTarget == RangeEditTarget.START,
            editorState = state,
            visualAlpha = 1f,
            modifier = Modifier.offset {
                IntOffset(
                    ReleasedRangeFixturebubbleOffset(xFor(state.startSeconds), fullWidthPx, bubbleWidthPx),
                    with(density) { 3.dp.roundToPx() },
                )
            },
        )
        ReleasedRangeFixtureTimelineTimeLabel(
            target = RangeEditTarget.END,
            active = state.lastTarget == RangeEditTarget.END,
            editorState = state,
            visualAlpha = 1f,
            modifier = Modifier.offset {
                IntOffset(
                    ReleasedRangeFixturebubbleOffset(xFor(state.endSeconds), fullWidthPx, bubbleWidthPx),
                    with(density) { 3.dp.roundToPx() },
                )
            },
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

private fun ReleasedRangeFixturebubbleOffset(xPx: Float, fullWidthPx: Float, bubbleWidthPx: Float): Int {
    val desiredLeft = xPx - bubbleWidthPx * 0.5f
    return desiredLeft.coerceIn(0f, (fullWidthPx - bubbleWidthPx).coerceAtLeast(0f)).roundToInt()
}


@Composable
private fun ReleasedRangeFixtureRangeTimelineBar(
    target: RangeEditTarget,
    state: RangeExportEditorState,
    interactionEnabled: Boolean,
    xPx: () -> Float,
    topPx: Float,
    heightPx: Float,
    hitWidthPx: Float,
    timelineWidthPx: Float,
    visualAlpha: Float,
) {
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val active = state.lastTarget == target
    val currentInteractionEnabled = rememberUpdatedState(interactionEnabled)
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lease = remember(state, target, view, lifecycle) { booleanArrayOf(true) }
    val keyOwner = remember(lease) { RangeEndpointKeyOwner() }
    DisposableEffect(lease, keyOwner) {
        onDispose {
            lease[0] = false
            keyOwner.clear()
        }
    }
    fun actionReady(): Boolean = lease[0] && currentInteractionEnabled.value &&
        view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && state.durationActionAvailable
    fun actionGesture(): RangeInputGesture = state.inputGesture(
        interactionAllowed = ::actionReady,
        // Discrete input owns no scrub or audio cleanup, particularly after a newer wheel edit.
        onCancel = {},
    )
    fun selectOrAdjust(requestedSeconds: Double?, gesture: RangeInputGesture = actionGesture()): Boolean =
        state.editEndpointFromAction(target, requestedSeconds, gesture)
    val label = stringResource(
        if (target == RangeEditTarget.START) R.string.range_start_accessibility
        else R.string.range_end_accessibility,
    )
    val earlierLabel = stringResource(R.string.range_endpoint_earlier)
    val laterLabel = stringResource(R.string.range_endpoint_later)
    val limits = rangeEndpointLimits(state.durationSeconds, target)
    val actionEnabled = interactionEnabled && state.durationActionAvailable && limits != null
    val endpointActions = Modifier
        .semantics {
            contentDescription = label
            stateDescription = formatRangeTimeInput(state.targetSeconds(target).toDouble())
            selected = active
            if (!actionEnabled) disabled()
            if (limits != null) {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    state.targetSeconds(target).coerceIn(limits),
                    limits,
                )
            }
            onClick { selectOrAdjust(null) }
            setProgress { seconds -> selectOrAdjust(seconds.toDouble()) }
            customActions = listOf(
                CustomAccessibilityAction(earlierLabel) {
                    selectOrAdjust(state.targetSeconds(target).toDouble() - 1.0)
                },
                CustomAccessibilityAction(laterLabel) {
                    selectOrAdjust(state.targetSeconds(target).toDouble() + 1.0)
                },
            )
        }
        .onFocusChanged { focus ->
            if (focus.isFocused) selectOrAdjust(null) else keyOwner.clear()
        }
        .onKeyEvent { event ->
            val requested = when (event.key) {
                Key.DirectionLeft, Key.DirectionDown -> state.targetSeconds(target).toDouble() - 1.0
                Key.DirectionRight, Key.DirectionUp -> state.targetSeconds(target).toDouble() + 1.0
                Key.MoveHome -> rangeEndpointLimits(state.durationSeconds, target)?.start?.toDouble()
                    ?: return@onKeyEvent false
                Key.MoveEnd -> rangeEndpointLimits(state.durationSeconds, target)?.endInclusive?.toDouble()
                    ?: return@onKeyEvent false
                Key.Enter, Key.NumPadEnter, Key.Spacebar -> null
                else -> return@onKeyEvent false
            }
            val keyCode = event.nativeKeyEvent.keyCode
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val gesture = keyOwner.keyDown(keyCode, event.nativeKeyEvent.repeatCount, ::actionGesture)
                    if (gesture != null) selectOrAdjust(requested, gesture)
                    true
                }
                KeyEventType.KeyUp -> {
                    keyOwner.keyUp(keyCode)
                    true
                }
                else -> false
            }
        }
        .focusable(actionEnabled)

    val dragModifier = if (interactionEnabled) {
        Modifier.pointerInput(target, state.durationSeconds, timelineWidthPx, touchSlop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val gesture = state.inputGesture(
                    // Native wheel DOWN takes ownership before Compose redraws this modifier.
                    interactionAllowed = { currentInteractionEnabled.value },
                    onCancel = { stillCurrent -> if (stillCurrent) state.pausePreview() },
                )
                if (!gesture.perform {
                        down.consume()
                        state.selectTarget(target)
                    }
                ) return@awaitEachGesture
                val origin = when (target) {
                    RangeEditTarget.START -> state.startSeconds
                    RangeEditTarget.END -> state.endSeconds
                }
                val markerCenterAtDown = xPx()
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            rangePointerReleaseWasNormal(change, gesture::cancel)
                            break
                        }
                        if (!gesture.perform {
                                val deltaX = rangeTimelineDragDeltaPx(
                                    initialMarkerCenterPx = markerCenterAtDown,
                                    currentMarkerCenterPx = xPx(),
                                    downLocalX = down.position.x,
                                    currentLocalX = change.position.x,
                                )
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
                        ) break
                    }
                } catch (cancelled: CancellationException) {
                    gesture.cancel()
                    throw cancelled
                } finally {
                    gesture.finish {
                        if (dragging) state.endBoundaryScrub()
                        else state.beginBoundaryEdit(target)
                    }
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
        RangeTimelineMarkerVisual(
            active = active,
            cursor = active,
            visualAlpha = visualAlpha,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().then(endpointActions).then(dragModifier))
    }
}

@Composable
private fun ReleasedRangeFixtureTimelineTimeLabel(
    target: RangeEditTarget,
    active: Boolean,
    editorState: RangeExportEditorState,
    visualAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .zIndex(if (active) 30f else 0f)
            .width(FIXTURE_RANGE_TIMELINE_TIME_LABEL_WIDTH_DP.dp)
            .height(FIXTURE_RANGE_TIMELINE_TIME_LABEL_HEIGHT_DP.dp)
            .graphicsLayer { alpha = visualAlpha.coerceIn(0f, 1f) },
        shape = RoundedCornerShape(10.dp),
        color = if (active) colors.primaryContainer else colors.surfaceContainerHighest,
        shadowElevation = if (active) 1.dp else 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatRangeTimeInput(editorState.targetSeconds(target).toDouble()),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReleasedRangeFixtureSpringFineAdjust(
    state: RangeExportEditorState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentEnabled = rememberUpdatedState(enabled && state.snapshotReady)
    SpringFineSeekControl(
        enabled = enabled && state.snapshotReady,
        isPlaying = state.isPlaying,
        durationSeconds = state.durationSeconds,
        interactionKey = state.lastTarget,
        onTogglePlayback = {
            if (currentEnabled.value && state.durationActionAvailable) state.togglePreview()
        },
        onInteractionStart = {},
        onBeginFineAdjust = state::beginFineAdjust,
        onFineAdjust = { deltaSeconds, _ -> state.fineAdjust(deltaSeconds) },
        onUpdateFineAdjustShuttle = state::updateFineAdjustShuttle,
        onEndFineAdjust = state::endFineAdjust,
        onCancelFineAdjust = state::pausePreview,
        createInputGesture = { onCancel ->
            state.inputGesture(
                interactionAllowed = { currentEnabled.value },
                onCancel = onCancel,
            )
        },
        modifier = modifier,
    )
}


@Composable
private fun ReleasedRangeFixtureRangeExportControls(
    modifier: Modifier = Modifier,
    state: RangeExportEditorState,
    loading: Boolean,
    canExport: Boolean,
    selectedBuffer: ReverbService.BufferSlot,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    val chrome = appChrome()
    val exportEnabled = !loading && state.snapshotReady && !state.selectionDurationEditing && canExport
    val exportContainerColor = if (exportEnabled) MaterialTheme.colorScheme.primary else chrome.raised
    val exportContentColor = if (exportEnabled) MaterialTheme.colorScheme.onPrimary else chrome.muted
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = chrome.field,
        border = androidx.compose.foundation.BorderStroke(1.dp, chrome.border),
        modifier = modifier.fillMaxWidth().height(70.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(50.dp),
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
                val bufferPillContent = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.58f)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when (selectedBuffer) {
                                ReverbService.BufferSlot.ONE_SHOT -> AppIcons.oneShot
                                ReverbService.BufferSlot.LOOPING -> AppIcons.looping
                            },
                            contentDescription = null,
                            tint = bufferPillContent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(
                                when (selectedBuffer) {
                                    ReverbService.BufferSlot.ONE_SHOT -> R.string.buffer_one_shot
                                    ReverbService.BufferSlot.LOOPING -> R.string.buffer_loop
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = bufferPillContent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
            Surface(
                onClick = {
                    // Native wheel input can begin before this enabled state redraws.
                    val selectedDuration = state.selectionDurationExactSeconds
                    if (!state.selectionDurationEditing && selectedDuration.isFinite() && selectedDuration > 0.0) {
                        onExport()
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
                    if (loading) {
                        val label = stringResource(R.string.preparing_audio_selection)
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).semantics { contentDescription = label },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = AppIcons.save,
                            contentDescription = null,
                            tint = exportContentColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
