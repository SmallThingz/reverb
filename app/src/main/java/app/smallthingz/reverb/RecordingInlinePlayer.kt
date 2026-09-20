package app.smallthingz.reverb

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val INLINE_PROGRESS_UPDATE_INTERVAL_MS = 48L
private const val INLINE_PLAYER_TAG = "ReverbInlinePlayer"

internal fun canEnterInlineTrim(prepared: Boolean, durationMillis: Int): Boolean =
    prepared && durationMillis > 0

internal enum class InlineFineSeekTarget {
    PLAYHEAD,
    TRIM_START,
    TRIM_END,
}

internal data class InlineFineSeekValues(
    val cursorMillis: Int,
    val startMillis: Int,
    val endMillis: Int,
)

internal fun adjustInlineTrimTarget(
    values: InlineFineSeekValues,
    durationMillis: Int,
    target: InlineFineSeekTarget,
    requestedMillis: Int,
): InlineFineSeekValues {
    require(target != InlineFineSeekTarget.PLAYHEAD)
    val duration = durationMillis.coerceAtLeast(1)
    val minimumRange = minOf(50, duration)
    var start = values.startMillis.coerceIn(0, duration)
    var end = values.endMillis.coerceIn(start, duration)
    if (end - start < minimumRange) {
        end = (start + minimumRange).coerceAtMost(duration)
        start = (end - minimumRange).coerceAtLeast(0)
    }
    when (target) {
        InlineFineSeekTarget.TRIM_START -> {
            val requested = requestedMillis.coerceIn(0, (duration - minimumRange).coerceAtLeast(0))
            if (requested > end - minimumRange) {
                start = requested
                end = (start + minimumRange).coerceAtMost(duration)
            } else {
                start = requested
            }
        }
        InlineFineSeekTarget.TRIM_END -> {
            val requested = requestedMillis.coerceIn(minimumRange.coerceAtMost(duration), duration)
            if (requested < start + minimumRange) {
                end = requested
                start = (end - minimumRange).coerceAtLeast(0)
            } else {
                end = requested
            }
        }
        InlineFineSeekTarget.PLAYHEAD -> error("Playhead is not a trim boundary")
    }
    return InlineFineSeekValues(
        cursorMillis = values.cursorMillis.coerceIn(0, duration),
        startMillis = start,
        endMillis = end,
    )
}

internal fun adjustInlineFineSeekTarget(
    values: InlineFineSeekValues,
    durationMillis: Int,
    target: InlineFineSeekTarget,
    deltaMillis: Int,
): InlineFineSeekValues {
    val duration = durationMillis.coerceAtLeast(1)
    val cursor = values.cursorMillis.coerceIn(0, duration)
    if (target == InlineFineSeekTarget.PLAYHEAD) {
        val requested = (cursor.toLong() + deltaMillis.toLong()).coerceIn(0L, duration.toLong()).toInt()
        val start = values.startMillis.coerceIn(0, duration)
        val end = values.endMillis.coerceIn(start, duration)
        return InlineFineSeekValues(requested, start, end)
    }
    val current = when (target) {
        InlineFineSeekTarget.TRIM_START -> values.startMillis
        InlineFineSeekTarget.TRIM_END -> values.endMillis
        InlineFineSeekTarget.PLAYHEAD -> cursor
    }
    val requested = (current.toLong() + deltaMillis.toLong())
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()
    return adjustInlineTrimTarget(values, duration, target, requested)
}

internal fun inlineTrimGestureTarget(
    pointerMillis: Int,
    startMillis: Int,
    endMillis: Int,
    handleThresholdMillis: Int,
    activeBoundary: InlineFineSeekTarget,
): InlineFineSeekTarget {
    require(activeBoundary != InlineFineSeekTarget.PLAYHEAD)
    val threshold = handleThresholdMillis.coerceAtLeast(0)
    val startDistance = kotlin.math.abs(pointerMillis - startMillis)
    val endDistance = kotlin.math.abs(pointerMillis - endMillis)
    return when {
        startDistance <= threshold && startDistance <= endDistance -> InlineFineSeekTarget.TRIM_START
        endDistance <= threshold -> InlineFineSeekTarget.TRIM_END
        else -> activeBoundary
    }
}

private data class InlinePlayerMediaSource(
    val descriptor: FileDescriptor,
    val owner: Closeable,
) : Closeable {
    override fun close() = owner.close()
}

private fun openInlinePlayerMediaSource(
    context: Context,
    recording: RecordingEntity,
): InlinePlayerMediaSource = when (recording.storageType) {
    RecordingStorageType.FILE -> {
        val stream = openVerifiedFileInputStream(recording)
            ?: throw IllegalStateException("Recording changed on disk")
        InlinePlayerMediaSource(stream.fd, stream)
    }
    RecordingStorageType.DOCUMENT,
    RecordingStorageType.MEDIASTORE,
    -> {
        if (!recordingReadIdentityIsStable(recording) ||
            !recordingContentIdentityMatches(context, recording)
        ) {
            throw IllegalStateException("Recording changed in provider")
        }
        val descriptor = context.contentResolver.openFileDescriptor(recording.id.toUri(), "r")
            ?: throw IllegalStateException("Recording unavailable in provider")
        if (!recordingContentIdentityMatches(context, recording)) {
            throwAfterClosePreservingPrimary(
                IllegalStateException("Recording changed in provider while opening"),
            ) {
                descriptor.close()
            }
        }
        InlinePlayerMediaSource(descriptor.fileDescriptor, descriptor)
    }
}

internal fun inlineShuttleFailureShouldResume(
    shuttleActive: Boolean,
    resumeAfterScrub: Boolean,
    lifecycleResumed: Boolean,
): Boolean = shuttleActive && resumeAfterScrub && lifecycleResumed

internal inline fun runInlinePlaybackCleanupReportingFailure(
    cleanup: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    try {
        cleanup()
    } catch (error: Exception) {
        runCatching { onFailure(error) }
    }
}

internal inline fun handleInlinePlaybackError(
    ownsPlaybackResources: Boolean,
    shouldReportFailure: Boolean,
    releaseResources: () -> Unit,
    reportFailure: () -> Unit,
) {
    if (!ownsPlaybackResources) return
    releaseResources()
    if (shouldReportFailure) reportFailure()
}

private class InlinePlayerBookkeeping {
    var resumeAfterScrub = false
    var released = false
    var fineSeekShuttleActive = false
    var initialAutoStartPending = true
}

internal class InlineTrimUiCallbackGate(
    onSaved: (RecordingEntity) -> Unit,
    onFailed: (Throwable) -> Unit,
    visible: Boolean,
) {
    private var savedCallback: ((RecordingEntity) -> Unit)? = onSaved
    private var failedCallback: ((Throwable) -> Unit)? = onFailed
    var attached: Boolean = true
        private set
    var visible: Boolean = visible
        private set

    fun setVisible(visible: Boolean) {
        if (attached) this.visible = visible
    }

    fun detach() {
        attached = false
        visible = false
        savedCallback = null
        failedCallback = null
    }

    fun saved(recording: RecordingEntity): Boolean {
        if (!attached) return false
        savedCallback?.invoke(recording)
        return visible
    }

    fun failed(error: Throwable): Boolean {
        if (!attached) return false
        failedCallback?.invoke(error)
        return visible
    }
}

internal class InlineTrimResultReceiver(
    onSaved: (RecordingEntity) -> Unit,
    onFailed: (Throwable) -> Unit,
    uiVisible: Boolean,
    private val onDetachedSuccess: (RecordingEntity) -> Unit,
    private val onDetachedFailure: (Throwable) -> Unit,
    private val onTerminal: (InlineTrimResultReceiver) -> Unit,
) {
    private val uiCallbacks = InlineTrimUiCallbackGate(onSaved, onFailed, uiVisible)
    private val terminalDelivered = AtomicBoolean(false)

    fun setUiVisible(visible: Boolean) = uiCallbacks.setVisible(visible)

    fun detachUi() = uiCallbacks.detach()

    fun terminal(result: Result<RecordingEntity>) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        deliverTerminalResult(
            deliver = {
                result.fold(
                    onSuccess = { recording ->
                        deliverVisibleTerminalOrFallback(
                            deliverVisible = { uiCallbacks.saved(recording) },
                            fallback = { onDetachedSuccess(recording) },
                        )
                    },
                    onFailure = { error ->
                        deliverVisibleTerminalOrFallback(
                            deliverVisible = { uiCallbacks.failed(error) },
                            fallback = { onDetachedFailure(error) },
                        )
                    },
                )
            },
            finish = {
                uiCallbacks.detach()
                onTerminal(this)
            },
        )
    }
}

internal fun inlinePlaybackCallbackIsCurrent(
    released: Boolean,
    disposed: Boolean,
    samePlayer: Boolean,
): Boolean = !released && !disposed && samePlayer

internal fun inlinePlaybackShouldAutoStart(
    prepared: Boolean,
    initialAutoStartPending: Boolean,
    lifecycleResumed: Boolean,
    blocked: Boolean = false,
): Boolean = prepared && initialAutoStartPending && lifecycleResumed && !blocked

@Composable
internal fun RecordingInlinePlayer(
    recording: RecordingEntity,
    trimRequested: Boolean,
    screenActive: Boolean,
    onTrimRequestConsumed: () -> Unit,
    onTrimSaved: (RecordingEntity) -> Unit,
    onTrimStateUncertain: () -> Unit,
    onWaveformCached: (RecordingEntity) -> Unit,
    onCollapse: () -> Unit,
    onPlaybackFailed: () -> Unit,
    modifier: Modifier = Modifier,
    onBusyChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val screenActiveState = rememberUpdatedState(screenActive)
    val chrome = appChrome()
    val recordingRevisionKey = remember(
        recording.id,
        recording.fileIdentity,
        recording.sizeBytes,
        recording.durationMillis,
        recording.lastSeenAtMillis,
    ) {
        "${recording.id}|${recording.fileIdentity}|${recording.sizeBytes}|${recording.durationMillis}|${recording.lastSeenAtMillis}"
    }
    val fineSeekPreviewController = remember(recordingRevisionKey, appContext) {
        TimelineAudioPreviewController(
            onTrackReleaseFailure = { error ->
                Log.e(INLINE_PLAYER_TAG, "Saved recording preview AudioTrack.release failed", error)
                AppFeedbackCenter.post(
                    appContext.getString(R.string.audio_preview_release_failed),
                    FeedbackTone.ERROR,
                )
            },
        )
    }
    val playbackBookkeeping = remember(recordingRevisionKey) { InlinePlayerBookkeeping() }

    var mediaPlayer by remember(recordingRevisionKey) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(recordingRevisionKey) { mutableStateOf(false) }
    var isPlaying by remember(recordingRevisionKey) { mutableStateOf(false) }
    var currentPosition by remember(recordingRevisionKey) { mutableIntStateOf(0) }
    var duration by remember(recordingRevisionKey) {
        mutableIntStateOf(recording.durationMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
    }
    var isScrubbing by remember(recordingRevisionKey) { mutableStateOf(false) }
    val pinnedMediaSource = remember(recordingRevisionKey) { AtomicReference<InlinePlayerMediaSource?>(null) }
    var trimMode by remember(recordingRevisionKey) { mutableStateOf(false) }
    var trimStartMillis by remember(recordingRevisionKey) { mutableIntStateOf(0) }
    var trimEndMillis by remember(recordingRevisionKey) { mutableIntStateOf(duration) }
    // The physical trim write is NonCancellable once started. A provider/path revision can
    // change while that write is still running, so busy ownership must survive revision-key
    // recreation and keep the row locked until the operation itself reaches terminal.
    var trimSaving by remember(recording.id) { mutableStateOf(false) }
    var trimError by remember(recordingRevisionKey) { mutableStateOf(false) }
    var fineSeekTarget by remember(recordingRevisionKey) { mutableStateOf(InlineFineSeekTarget.PLAYHEAD) }
    val activeTrimReceiver = remember(recording.id) { AtomicReference<InlineTrimResultReceiver?>(null) }

    LaunchedEffect(trimSaving) { onBusyChange(trimSaving) }
    SideEffect {
        activeTrimReceiver.get()?.setUiVisible(
            screenActive && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            activeTrimReceiver.getAndSet(null)?.detachUi()
            onBusyChange(false)
        }
    }

    var coarseWaveform by remember(recordingRevisionKey) { mutableStateOf(FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)) }
    var coarseBuiltCount by remember(recordingRevisionKey) { mutableIntStateOf(0) }
    var detailWaveform by remember(recordingRevisionKey) { mutableStateOf(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)) }
    var detailBuiltCount by remember(recordingRevisionKey) { mutableIntStateOf(0) }
    var waveformPass by remember(recordingRevisionKey) { mutableStateOf(RangeWaveformPass.COARSE) }
    var waveformLoading by remember(recordingRevisionKey) { mutableStateOf(true) }
    var waveformMorphStarted by remember(recordingRevisionKey) { mutableStateOf(false) }
    val waveformMorph by animateFloatAsState(
        targetValue = if (waveformMorphStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing),
        label = "recordingCardWaveformMorph",
    )

    val backMotion = rememberPredictiveBackMotion(
        enabled = true,
        onBack = {
            if (!trimSaving && trimMode) {
                trimMode = false
                trimError = false
            } else if (!trimSaving) {
                onCollapse()
            }
        },
    )
    val backProgress = if (!trimSaving && backMotion.gestureActive) {
        backMotion.progress.value.coerceIn(0f, 1f)
    } else {
        0f
    }
    val backDirection = predictiveBackHorizontalDirection(backMotion.swipeEdge)
    val trimBackProgress = if (trimMode) backProgress else 0f
    val collapseBackProgress = if (!trimMode) backProgress else 0f

    fun reportPlaybackCleanupFailure(error: Exception) {
        Log.e(INLINE_PLAYER_TAG, "Saved recording playback cleanup failed", error)
        AppFeedbackCenter.post(
            appContext.getString(R.string.recording_read_cleanup_failed),
            FeedbackTone.ERROR,
        )
    }

    fun releasePlayer() {
        if (playbackBookkeeping.released) return
        playbackBookkeeping.released = true
        val player = mediaPlayer
        mediaPlayer = null
        player?.runCatching { stop() }
        if (player != null) {
            runInlinePlaybackCleanupReportingFailure(
                cleanup = { player.release() },
                onFailure = ::reportPlaybackCleanupFailure,
            )
        }
        runInlinePlaybackCleanupReportingFailure(
            cleanup = { pinnedMediaSource.getAndSet(null)?.close() },
            onFailure = ::reportPlaybackCleanupFailure,
        )
        prepared = false
        isPlaying = false
    }

    fun seekTo(positionMillis: Int, resume: Boolean = false) {
        val bounded = positionMillis.coerceIn(0, duration.coerceAtLeast(1))
        currentPosition = bounded
        if (!prepared || playbackBookkeeping.released) return
        val player = mediaPlayer ?: return
        runCatching {
            player.seekTo(bounded)
            if (resume && !player.isPlaying) {
                player.start()
                isPlaying = true
            }
        }
    }

    fun toggleFineSeekPlayback() {
        val player = mediaPlayer ?: return
        if (!prepared || playbackBookkeeping.released || trimSaving) return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
            } else {
                val restartAt = when {
                    trimMode -> {
                        val window = boundaryCursorPreviewWindow(
                            startSeconds = trimStartMillis / 1_000f,
                            endSeconds = trimEndMillis / 1_000f,
                            endBoundaryActive = fineSeekTarget == InlineFineSeekTarget.TRIM_END,
                        )
                        (window.startSeconds * 1_000f).roundToInt()
                    }
                    currentPosition >= duration -> 0
                    else -> currentPosition
                }.coerceIn(0, duration)
                currentPosition = restartAt
                player.seekTo(restartAt)
                player.start()
                isPlaying = true
            }
        }
    }

    fun currentInlineFineSeekValues(): InlineFineSeekValues = InlineFineSeekValues(
        cursorMillis = currentPosition,
        startMillis = trimStartMillis,
        endMillis = trimEndMillis,
    )

    fun fineSeekTargetMillis(values: InlineFineSeekValues = currentInlineFineSeekValues()): Int =
        when (if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD) {
            InlineFineSeekTarget.PLAYHEAD -> values.cursorMillis
            InlineFineSeekTarget.TRIM_START -> values.startMillis
            InlineFineSeekTarget.TRIM_END -> values.endMillis
        }

    fun beginInlineFineSeek(shuttleRate: Float) {
        if (!prepared || playbackBookkeeping.released || trimSaving || playbackBookkeeping.fineSeekShuttleActive) return
        playbackBookkeeping.resumeAfterScrub = isPlaying
        // MediaPlayer cannot reverse and repeated seekTo() calls produce silence/stutter.
        // Keep it sounding until the first AudioTrack grain is ready, then hand off. This avoids
        // a silent source-open gap while keeping logical playback state unchanged for release.
        isScrubbing = true
        playbackBookkeeping.fineSeekShuttleActive = true
        fineSeekPreviewController.startRecordingShuttle(
            context = appContext,
            recording = recording,
            atSeconds = fineSeekTargetMillis().toDouble() / 1_000.0,
            rate = shuttleRate,
            onStarted = {
                if (playbackBookkeeping.fineSeekShuttleActive && playbackBookkeeping.resumeAfterScrub) {
                    runCatching { mediaPlayer?.pause() }
                }
            },
            onFailureAfterStart = {
                if (inlineShuttleFailureShouldResume(
                        shuttleActive = playbackBookkeeping.fineSeekShuttleActive,
                        resumeAfterScrub = playbackBookkeeping.resumeAfterScrub,
                        lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    )
                ) {
                    val player = mediaPlayer
                    if (player != null) {
                        runCatching { player.start() }
                            .onSuccess { isPlaying = true }
                            .onFailure {
                                releasePlayer()
                                onPlaybackFailed()
                            }
                    }
                }
            },
        )
    }

    fun applyInlineFineSeek(deltaSeconds: Float) {
        if (!deltaSeconds.isFinite() || duration <= 0) return
        val deltaMillis = (deltaSeconds * 1000f).roundToInt()
        if (deltaMillis == 0) return
        val target = if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD
        val adjusted = adjustInlineFineSeekTarget(
            values = currentInlineFineSeekValues(),
            durationMillis = duration,
            target = target,
            deltaMillis = deltaMillis,
        )
        currentPosition = adjusted.cursorMillis
        trimStartMillis = adjusted.startMillis
        trimEndMillis = adjusted.endMillis
        if (trimMode && target != InlineFineSeekTarget.PLAYHEAD) {
            currentPosition = fineSeekTargetMillis(adjusted)
            trimError = false
        }
    }

    fun updateInlineFineSeekShuttle(shuttleRate: Float, pendingDeltaSeconds: Float) {
        if (!playbackBookkeeping.fineSeekShuttleActive || duration <= 0) return
        val pendingMillis = if (pendingDeltaSeconds.isFinite()) {
            (pendingDeltaSeconds * 1_000f).roundToInt()
        } else {
            0
        }
        val target = if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD
        val projected = adjustInlineFineSeekTarget(
            values = currentInlineFineSeekValues(),
            durationMillis = duration,
            target = target,
            deltaMillis = pendingMillis,
        )
        fineSeekPreviewController.updateShuttle(
            atSeconds = fineSeekTargetMillis(projected).toDouble() / 1_000.0,
            rate = shuttleRate,
        )
    }

    fun endInlineFineSeek() {
        if (!isScrubbing && !playbackBookkeeping.fineSeekShuttleActive) return
        fineSeekPreviewController.stopShuttle()
        playbackBookkeeping.fineSeekShuttleActive = false
        isScrubbing = false
        val shouldResume = playbackBookkeeping.resumeAfterScrub
        playbackBookkeeping.resumeAfterScrub = false
        if (trimMode) currentPosition = fineSeekTargetMillis()
        seekTo(currentPosition, resume = shouldResume)
    }

    fun enterTrimMode(): Boolean {
        if (trimSaving || !canEnterInlineTrim(prepared, duration)) return false
        if (isPlaying) {
            runCatching { mediaPlayer?.pause() }
            isPlaying = false
        }
        trimStartMillis = 0
        trimEndMillis = duration
        fineSeekTarget = InlineFineSeekTarget.TRIM_START
        currentPosition = 0
        seekTo(0)
        trimError = false
        trimMode = true
        return true
    }

    LaunchedEffect(trimRequested, recordingRevisionKey, prepared, duration) {
        if (trimRequested && enterTrimMode()) {
            onTrimRequestConsumed()
        }
    }

    DisposableEffect(fineSeekPreviewController) {
        onDispose { fineSeekPreviewController.close() }
    }

    DisposableEffect(recordingRevisionKey) {
        var disposed = false
        playbackBookkeeping.released = false
        playbackBookkeeping.initialAutoStartPending = true
        val player = configureOwnedResourceOrRelease(
            owner = MediaPlayer(),
            release = { it.release() },
        ) { configuredPlayer ->
            configuredPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            configuredPlayer.setOnPreparedListener { preparedPlayer ->
                if (!inlinePlaybackCallbackIsCurrent(
                        released = playbackBookkeeping.released,
                        disposed = disposed,
                        samePlayer = mediaPlayer === preparedPlayer,
                    )
                ) {
                    return@setOnPreparedListener
                }
                prepared = true
                val previousDuration = duration
                duration = preparedPlayer.duration.coerceAtLeast(1)
                if (!trimMode || trimEndMillis >= previousDuration - 1) trimEndMillis = duration
                currentPosition = currentPosition.coerceIn(0, duration)
                if (inlinePlaybackShouldAutoStart(
                        prepared = true,
                        initialAutoStartPending = playbackBookkeeping.initialAutoStartPending,
                        lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    )
                ) {
                    runCatching { preparedPlayer.start() }
                        .onSuccess {
                            playbackBookkeeping.initialAutoStartPending = false
                            isPlaying = true
                        }
                        .onFailure {
                            releasePlayer()
                            onPlaybackFailed()
                        }
                }
            }
            configuredPlayer.setOnSeekCompleteListener { activePlayer ->
                if (
                    inlinePlaybackCallbackIsCurrent(
                        released = playbackBookkeeping.released,
                        disposed = disposed,
                        samePlayer = mediaPlayer === activePlayer,
                    ) &&
                    !isScrubbing
                ) {
                    currentPosition = runCatching { activePlayer.currentPosition.coerceAtLeast(0) }
                        .getOrDefault(currentPosition)
                }
            }
            configuredPlayer.setOnCompletionListener { completedPlayer ->
                if (inlinePlaybackCallbackIsCurrent(
                        released = playbackBookkeeping.released,
                        disposed = disposed,
                        samePlayer = mediaPlayer === completedPlayer,
                    )
                ) {
                    isPlaying = false
                    currentPosition = duration
                }
            }
            configuredPlayer.setOnErrorListener { errorPlayer, _, _ ->
                val samePlayer = mediaPlayer === errorPlayer
                val ownsPlaybackResources = !playbackBookkeeping.released && samePlayer
                val shouldReportFailure = inlinePlaybackCallbackIsCurrent(
                    released = playbackBookkeeping.released,
                    disposed = disposed,
                    samePlayer = samePlayer,
                )
                handleInlinePlaybackError(
                    ownsPlaybackResources = ownsPlaybackResources,
                    shouldReportFailure = shouldReportFailure,
                    releaseResources = ::releasePlayer,
                    reportFailure = onPlaybackFailed,
                )
                true
            }
        }
        mediaPlayer = player

        onDispose {
            disposed = true
            releasePlayer()
        }
    }

    // Capture the effect key during composition. DisposableEffect can install the player
    // before a null-keyed coroutine starts; reading live state there prepares that same
    // player twice when recomposition subsequently launches the player-keyed effect.
    val playerToPrepare = mediaPlayer
    LaunchedEffect(recordingRevisionKey, playerToPrepare) {
        val player = playerToPrepare ?: return@LaunchedEffect
        if (playbackBookkeeping.released) return@LaunchedEffect
        // withContext has prompt cancellation when returning to Main. Keep ownership in an
        // atomic slot before the IO block returns so a collapse at that exact boundary cannot
        // leak the opened descriptor even when the result itself is discarded.
        val openedRef = AtomicReference<InlinePlayerMediaSource?>(null)
        try {
            val opened = withContext(Dispatchers.IO) {
                openInlinePlayerMediaSource(appContext, recording).also(openedRef::set)
            }
            if (playbackBookkeeping.released || mediaPlayer !== player) return@LaunchedEffect
            openedRef.compareAndSet(opened, null)
            runInlinePlaybackCleanupReportingFailure(
                cleanup = { pinnedMediaSource.getAndSet(opened)?.close() },
                onFailure = ::reportPlaybackCleanupFailure,
            )
            player.setDataSource(opened.descriptor)
            player.prepareAsync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!playbackBookkeeping.released && mediaPlayer === player) {
                releasePlayer()
                onPlaybackFailed()
            }
        } finally {
            runInlinePlaybackCleanupReportingFailure(
                cleanup = { openedRef.getAndSet(null)?.close() },
                onFailure = ::reportPlaybackCleanupFailure,
            )
        }
    }

    DisposableEffect(lifecycleOwner, recordingRevisionKey) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activeTrimReceiver.get()?.setUiVisible(screenActiveState.value)
                    if (inlinePlaybackShouldAutoStart(
                            prepared = prepared,
                            initialAutoStartPending = playbackBookkeeping.initialAutoStartPending,
                            lifecycleResumed = true,
                            blocked = trimSaving,
                        )
                    ) {
                        val player = mediaPlayer
                        runCatching { player?.start() }
                            .onSuccess {
                                if (player != null) {
                                    playbackBookkeeping.initialAutoStartPending = false
                                    isPlaying = true
                                }
                            }
                            .onFailure {
                                releasePlayer()
                                onPlaybackFailed()
                            }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    activeTrimReceiver.get()?.setUiVisible(false)
                    if (playbackBookkeeping.fineSeekShuttleActive) {
                        fineSeekPreviewController.stopShuttle()
                        playbackBookkeeping.fineSeekShuttleActive = false
                        isScrubbing = false
                        playbackBookkeeping.resumeAfterScrub = false
                    }
                    if (isPlaying) {
                        runCatching { mediaPlayer?.pause() }
                        isPlaying = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isPlaying, isScrubbing, recordingRevisionKey) {
        if (isPlaying && !isScrubbing) {
            while (true) {
                delay(INLINE_PROGRESS_UPDATE_INTERVAL_MS)
                if (playbackBookkeeping.released) break
                val position = runCatching { mediaPlayer?.currentPosition ?: currentPosition }
                    .getOrDefault(currentPosition)
                if (trimMode && position >= trimEndMillis) {
                    runCatching { mediaPlayer?.pause() }
                    isPlaying = false
                    currentPosition = trimEndMillis
                    seekTo(trimEndMillis)
                    break
                }
                currentPosition = position.coerceIn(0, duration.coerceAtLeast(1))
            }
        }
    }

    LaunchedEffect(recordingRevisionKey) {
        waveformMorphStarted = true
        val expectedRevision = recordingWaveformRevision(recording)
        val cachedDetail = if (
            expectedRevision.isNotBlank() && recording.waveformRevision == expectedRevision
        ) {
            decodeRecordingWaveform(recording.waveformData)
        } else {
            null
        }
        if (cachedDetail != null) {
            detailWaveform = cachedDetail
            detailBuiltCount = cachedDetail.size
            coarseWaveform = coarseWaveformFromDetail(cachedDetail)
            coarseBuiltCount = coarseWaveform.size
            waveformPass = RangeWaveformPass.DETAIL
            waveformLoading = false
            return@LaunchedEffect
        }

        coarseWaveform = FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)
        detailWaveform = FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)
        coarseBuiltCount = 0
        detailBuiltCount = 0
        waveformPass = RangeWaveformPass.COARSE
        waveformLoading = true

        try {
            buildProgressiveWaveform(
                readPass = { pass, onBucket ->
                    readRecordingWaveformEnvelopeProgressive(appContext, recording, pass, onBucket)
                },
                onPassStarted = { waveformPass = it },
            ) { pass, update ->
                when (pass) {
                    RangeWaveformPass.COARSE -> if (update.values.size == coarseWaveform.size) {
                        coarseWaveform = update.values
                        coarseBuiltCount = update.builtCount.coerceIn(0, coarseWaveform.size)
                    }
                    RangeWaveformPass.DETAIL -> if (update.values.size == detailWaveform.size) {
                        detailWaveform = update.values
                        detailBuiltCount = update.builtCount.coerceIn(0, detailWaveform.size)
                    }
                }
            }
            val encoded = encodeRecordingWaveform(detailWaveform)
            if (encoded.isNotBlank() && RecordingRepository.cacheWaveform(
                    context = appContext,
                    recording = recording,
                    waveformData = encoded,
                    waveformRevision = expectedRevision,
                )
            ) {
                onWaveformCached(
                    recording.copy(
                        waveformData = encoded,
                        waveformRevision = expectedRevision,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Playback remains useful if a provider does not expose a seekable WAV descriptor.
        } finally {
            waveformLoading = false
        }
    }

    val progressFraction = if (duration <= 0) 0f else
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val rawTrimStartFraction = if (trimMode && duration > 0) {
        (trimStartMillis.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val rawTrimEndFraction = if (trimMode && duration > 0) {
        (trimEndMillis.toFloat() / duration.toFloat()).coerceIn(rawTrimStartFraction, 1f)
    } else progressFraction
    val trimVisualAlpha = (1f - trimBackProgress).coerceIn(0f, 1f)
    val selectionStartFraction = if (trimMode) {
        rawTrimStartFraction * trimVisualAlpha
    } else {
        0f
    }
    val selectionEndFraction = if (trimMode) {
        rawTrimEndFraction + (progressFraction - rawTrimEndFraction) * trimBackProgress
    } else {
        progressFraction
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .predictiveBackCollapse(collapseBackProgress)
            .graphicsLayer {
                translationX = backDirection * size.width * 0.08f * trimBackProgress
            }
            .padding(start = 14.dp, end = 14.dp),
    ) {
        if (trimMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = trimVisualAlpha
                        translationX = backDirection * size.width * 0.08f * trimBackProgress
                    }
                    .padding(bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.trim,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.trim_recording),
                    style = MaterialTheme.typography.labelLarge,
                    color = chrome.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatRangeTimeInput((trimEndMillis - trimStartMillis).coerceAtLeast(0) / 1000.0),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = chrome.muted,
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .pointerInput(recordingRevisionKey, duration, prepared, trimMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!prepared || trimSaving || size.width <= 0) return@awaitEachGesture
                        down.consume()
                        playbackBookkeeping.resumeAfterScrub = isPlaying
                        if (isPlaying) {
                            runCatching { mediaPlayer?.pause() }
                            isPlaying = false
                        }
                        isScrubbing = true
                        val initialMillis = (
                            duration.toFloat() * (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        ).toInt()
                        val handleThresholdMillis = if (trimMode) {
                            val handleRadiusPx = with(density) { 24.dp.toPx() }
                            (duration.toFloat() * handleRadiusPx / size.width.toFloat())
                                .roundToInt()
                                .coerceAtLeast(1)
                        } else {
                            0
                        }
                        fineSeekTarget = if (trimMode) {
                            inlineTrimGestureTarget(
                                pointerMillis = initialMillis,
                                startMillis = trimStartMillis,
                                endMillis = trimEndMillis,
                                handleThresholdMillis = handleThresholdMillis,
                                activeBoundary = fineSeekTarget,
                            )
                        } else {
                            InlineFineSeekTarget.PLAYHEAD
                        }
                        val dragTarget = fineSeekTarget

                        fun updateFromX(x: Float) {
                            val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            val millis = (duration.toFloat() * fraction).toInt().coerceIn(0, duration)
                            if (!trimMode || dragTarget == InlineFineSeekTarget.PLAYHEAD) {
                                currentPosition = millis
                                return
                            }
                            val adjusted = adjustInlineTrimTarget(
                                values = currentInlineFineSeekValues(),
                                durationMillis = duration,
                                target = dragTarget,
                                requestedMillis = millis,
                            )
                            trimStartMillis = adjusted.startMillis
                            trimEndMillis = adjusted.endMillis
                            currentPosition = when (dragTarget) {
                                InlineFineSeekTarget.TRIM_START -> adjusted.startMillis
                                InlineFineSeekTarget.TRIM_END -> adjusted.endMillis
                                InlineFineSeekTarget.PLAYHEAD -> currentPosition
                            }
                            trimError = false
                        }
                        updateFromX(down.position.x)
                        try {
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                pressed = change.pressed
                                if (pressed) {
                                    change.consume()
                                    updateFromX(change.position.x)
                                }
                            }
                        } finally {
                            isScrubbing = false
                            val shouldResume = playbackBookkeeping.resumeAfterScrub
                            playbackBookkeeping.resumeAfterScrub = false
                            if (trimMode) currentPosition = fineSeekTargetMillis()
                            seekTo(currentPosition, resume = shouldResume)
                        }
                    }
                },
        ) {
            ProgressiveWaveformCanvas(
                coarseWaveform = coarseWaveform,
                coarseBuiltCount = coarseBuiltCount,
                detailWaveform = detailWaveform,
                detailBuiltCount = detailBuiltCount,
                waveformPass = waveformPass,
                startFraction = selectionStartFraction,
                endFraction = selectionEndFraction,
                loading = waveformLoading,
                morphProgress = { waveformMorph },
                modifier = Modifier.fillMaxSize(),
            )
            val visualWidth = 12.dp
            val visualWidthPx = with(density) { visualWidth.toPx() }
            val waveformWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            if (!trimMode) {
                RangeTimelineMarkerVisual(
                    active = false,
                    cursor = true,
                    visualAlpha = 0.74f,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (waveformWidthPx * progressFraction - visualWidthPx * 0.5f).roundToInt(),
                                0,
                            )
                        }
                        .width(visualWidth)
                        .fillMaxHeight(),
                )
            } else {
                val startIsCursor = fineSeekTarget == InlineFineSeekTarget.TRIM_START
                val endIsCursor = fineSeekTarget == InlineFineSeekTarget.TRIM_END
                val startModifier = Modifier
                    .offset {
                        IntOffset(
                            (waveformWidthPx * selectionStartFraction - visualWidthPx * 0.5f).roundToInt(),
                            0,
                        )
                    }
                    .width(visualWidth)
                    .fillMaxHeight()
                val endModifier = Modifier
                    .offset {
                        IntOffset(
                            (waveformWidthPx * selectionEndFraction - visualWidthPx * 0.5f).roundToInt(),
                            0,
                        )
                    }
                    .width(visualWidth)
                    .fillMaxHeight()
                RangeTimelineMarkerVisual(
                    active = startIsCursor,
                    cursor = startIsCursor,
                    visualAlpha = trimVisualAlpha,
                    modifier = startModifier,
                )
                RangeTimelineMarkerVisual(
                    active = endIsCursor,
                    cursor = endIsCursor,
                    visualAlpha = trimVisualAlpha,
                    modifier = endModifier,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trimMode) {
                Text(
                    text = formatRangeTimeInput(trimStartMillis / 1000.0),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (fineSeekTarget == InlineFineSeekTarget.TRIM_START) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        chrome.ink
                    },
                )
                Text(
                    text = formatRangeTimeInput(trimEndMillis / 1000.0),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (fineSeekTarget == InlineFineSeekTarget.TRIM_END) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        chrome.ink
                    },
                )
            } else {
                Text(
                    text = formatPlaybackTime(currentPosition),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = chrome.ink,
                )
                Text(
                    text = formatPlaybackTime(duration),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = chrome.muted,
                )
            }
        }

        SpringFineSeekControl(
            enabled = prepared && !trimSaving,
            isPlaying = isPlaying,
            durationSeconds = duration.toFloat() / 1000f,
            interactionKey = "$recordingRevisionKey:${if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD}",
            onTogglePlayback = ::toggleFineSeekPlayback,
            onInteractionStart = {
                if (!trimMode) fineSeekTarget = InlineFineSeekTarget.PLAYHEAD
            },
            onBeginFineAdjust = ::beginInlineFineSeek,
            onFineAdjust = { deltaSeconds, _ -> applyInlineFineSeek(deltaSeconds) },
            onUpdateFineAdjustShuttle = ::updateInlineFineSeekShuttle,
            onEndFineAdjust = ::endInlineFineSeek,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (trimMode) 104.dp else 120.dp)
                .graphicsLayer { alpha = if (trimMode) trimVisualAlpha else 1f },
        )

        if (trimMode) {
            if (trimError) {
                Text(
                    text = stringResource(R.string.trim_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = {
                        if (!trimSaving) {
                            trimMode = false
                            trimError = false
                        }
                    },
                    enabled = !trimSaving,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = AppIcons.close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = {
                        if (!trimSaving && trimEndMillis > trimStartMillis) {
                            trimSaving = true
                            trimError = false
                            onBusyChange(true)
                            lateinit var receiver: InlineTrimResultReceiver
                            receiver = InlineTrimResultReceiver(
                                onSaved = { trimmed ->
                                    trimSaving = false
                                    onBusyChange(false)
                                    trimMode = false
                                    if (
                                        screenActiveState.value &&
                                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        onTrimSaved(trimmed)
                                    }
                                },
                                onFailed = { error ->
                                    trimSaving = false
                                    onBusyChange(false)
                                    trimError = true
                                    if (
                                        error is RecordingCatalogIdentityChangedException &&
                                        screenActiveState.value &&
                                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        onTrimStateUncertain()
                                    }
                                },
                                uiVisible = screenActive &&
                                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                                onDetachedSuccess = { trimmed ->
                                    NotifyFileReceiver(appContext).fileReady(trimmed)
                                },
                                onDetachedFailure = { error ->
                                    NotifyFileReceiver(appContext).fileFailed(
                                        appContext.getString(R.string.trim_failed),
                                        error,
                                    )
                                },
                                onTerminal = { completed ->
                                    activeTrimReceiver.compareAndSet(completed, null)
                                },
                            )
                            activeTrimReceiver.set(receiver)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                runCommittedInlineTrim(
                                    save = {
                                        saveTrimmedRecordingCopy(
                                            context = appContext,
                                            recording = recording,
                                            startMillis = trimStartMillis,
                                            endMillis = trimEndMillis,
                                        )
                                    },
                                    onTerminal = receiver::terminal,
                                )
                            }
                        }
                    },
                    enabled = !trimSaving && trimEndMillis > trimStartMillis,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (trimSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = AppIcons.save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = stringResource(
                                if (trimSaving) R.string.trim_saving else R.string.trim_save_copy,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.predictiveBackCollapse(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    return this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val visibleHeight = (placeable.height * (1f - p)).roundToInt().coerceAtLeast(0)
            layout(placeable.width, visibleHeight) {
                placeable.placeRelative(0, 0)
            }
        }
        .clipToBounds()
        .graphicsLayer {
            alpha = 1f - p
            scaleY = 1f - 0.04f * p
            transformOrigin = TransformOrigin(0.5f, 0f)
        }
}
