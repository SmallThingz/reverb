package app.smallthingz.reverb

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val INLINE_PROGRESS_UPDATE_INTERVAL_MS = 48L

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

internal fun adjustInlineFineSeekTarget(
    values: InlineFineSeekValues,
    durationMillis: Int,
    target: InlineFineSeekTarget,
    deltaMillis: Int,
): InlineFineSeekValues {
    val duration = durationMillis.coerceAtLeast(1)
    val start = values.startMillis.coerceIn(0, duration)
    val end = values.endMillis.coerceIn(start, duration)
    val cursor = values.cursorMillis.coerceIn(0, duration)
    val minimumRange = minOf(50, duration)
    return when (target) {
        InlineFineSeekTarget.PLAYHEAD -> InlineFineSeekValues(
            cursorMillis = (cursor + deltaMillis).coerceIn(0, duration),
            startMillis = start,
            endMillis = end,
        )
        InlineFineSeekTarget.TRIM_START -> InlineFineSeekValues(
            cursorMillis = cursor,
            startMillis = (start + deltaMillis).coerceIn(
                0,
                (end - minimumRange).coerceAtLeast(0),
            ),
            endMillis = end,
        )
        InlineFineSeekTarget.TRIM_END -> InlineFineSeekValues(
            cursorMillis = cursor,
            startMillis = start,
            endMillis = (end + deltaMillis).coerceIn(
                (start + minimumRange).coerceAtMost(duration),
                duration,
            ),
        )
    }
}

internal fun inlineTrimGestureTarget(
    pointerMillis: Int,
    startMillis: Int,
    endMillis: Int,
    handleThresholdMillis: Int,
): InlineFineSeekTarget {
    val threshold = handleThresholdMillis.coerceAtLeast(0)
    val startDistance = kotlin.math.abs(pointerMillis - startMillis)
    val endDistance = kotlin.math.abs(pointerMillis - endMillis)
    return when {
        startDistance <= threshold && startDistance <= endDistance -> InlineFineSeekTarget.TRIM_START
        endDistance <= threshold -> InlineFineSeekTarget.TRIM_END
        else -> InlineFineSeekTarget.PLAYHEAD
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
): InlinePlayerMediaSource = when (resolveRecordingStorageType(recording)) {
    RecordingStorageType.FILE -> {
        val stream = openVerifiedFileInputStream(recording)
            ?: throw IllegalStateException("Recording changed on disk")
        InlinePlayerMediaSource(stream.fd, stream)
    }
    RecordingStorageType.DOCUMENT,
    RecordingStorageType.MEDIASTORE,
    -> {
        if (!recordingContentIdentityMatches(context, recording)) {
            throw IllegalStateException("Recording changed in provider")
        }
        val descriptor = context.contentResolver.openFileDescriptor(android.net.Uri.parse(recording.id), "r")
            ?: throw IllegalStateException("Recording unavailable in provider")
        if (!recordingContentIdentityMatches(context, recording)) {
            runCatching { descriptor.close() }
            throw IllegalStateException("Recording changed in provider while opening")
        }
        InlinePlayerMediaSource(descriptor.fileDescriptor, descriptor)
    }
    null -> throw IllegalArgumentException("Unknown recording storage type")
}

@Composable
internal fun RecordingInlinePlayer(
    recording: RecordingEntity,
    trimRequested: Boolean,
    onTrimRequestConsumed: () -> Unit,
    onTrimSaved: (RecordingEntity) -> Unit,
    onWaveformCached: (RecordingEntity) -> Unit,
    onCollapse: () -> Unit,
    onPlaybackFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
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
    val fineSeekPreviewController = remember(recordingRevisionKey) { TimelineAudioPreviewController() }

    var mediaPlayer by remember(recordingRevisionKey) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(recordingRevisionKey) { mutableStateOf(false) }
    var isPlaying by remember(recordingRevisionKey) { mutableStateOf(false) }
    var currentPosition by remember(recordingRevisionKey) { mutableIntStateOf(0) }
    var duration by remember(recordingRevisionKey) {
        mutableIntStateOf(recording.durationMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
    }
    var isScrubbing by remember(recordingRevisionKey) { mutableStateOf(false) }
    var resumeAfterScrub by remember(recordingRevisionKey) { mutableStateOf(false) }
    var released by remember(recordingRevisionKey) { mutableStateOf(false) }
    val pinnedMediaSource = remember(recordingRevisionKey) { AtomicReference<InlinePlayerMediaSource?>(null) }
    var trimMode by remember(recordingRevisionKey) { mutableStateOf(false) }
    var trimStartMillis by remember(recordingRevisionKey) { mutableIntStateOf(0) }
    var trimEndMillis by remember(recordingRevisionKey) { mutableIntStateOf(duration) }
    var trimSaving by remember(recordingRevisionKey) { mutableStateOf(false) }
    var trimError by remember(recordingRevisionKey) { mutableStateOf(false) }
    var fineSeekTarget by remember(recordingRevisionKey) { mutableStateOf(InlineFineSeekTarget.PLAYHEAD) }
    var fineSeekShuttleActive by remember(recordingRevisionKey) { mutableStateOf(false) }

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

    fun releasePlayer() {
        if (released) return
        released = true
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        runCatching { pinnedMediaSource.getAndSet(null)?.close() }
        prepared = false
        isPlaying = false
    }

    fun seekTo(positionMillis: Int, resume: Boolean = false) {
        val bounded = positionMillis.coerceIn(0, duration.coerceAtLeast(1))
        currentPosition = bounded
        if (!prepared || released) return
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
        if (!prepared || released || trimSaving) return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
            } else {
                val restartAt = when {
                    trimMode && (currentPosition < trimStartMillis || currentPosition >= trimEndMillis) -> trimStartMillis
                    !trimMode && currentPosition >= duration -> 0
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
        if (!prepared || released || trimSaving || fineSeekShuttleActive) return
        resumeAfterScrub = isPlaying
        // MediaPlayer cannot reverse and repeated seekTo() calls produce silence/stutter.
        // Keep it sounding until the first AudioTrack grain is ready, then hand off. This avoids
        // a silent source-open gap while keeping logical playback state unchanged for release.
        isScrubbing = true
        fineSeekShuttleActive = true
        fineSeekPreviewController.startRecordingShuttle(
            context = appContext,
            recording = recording,
            atSeconds = fineSeekTargetMillis().toDouble() / 1_000.0,
            rate = shuttleRate,
            onStarted = {
                if (fineSeekShuttleActive && resumeAfterScrub) {
                    runCatching { mediaPlayer?.pause() }
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
        if (trimMode && target != InlineFineSeekTarget.PLAYHEAD) trimError = false
    }

    fun updateInlineFineSeekShuttle(shuttleRate: Float, pendingDeltaSeconds: Float) {
        if (!fineSeekShuttleActive || duration <= 0) return
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
        if (!isScrubbing && !fineSeekShuttleActive) return
        fineSeekPreviewController.stopShuttle()
        fineSeekShuttleActive = false
        isScrubbing = false
        val shouldResume = resumeAfterScrub
        resumeAfterScrub = false
        if (shouldResume && trimMode && currentPosition !in trimStartMillis..trimEndMillis) {
            currentPosition = currentPosition.coerceIn(trimStartMillis, trimEndMillis)
        }
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
        fineSeekTarget = InlineFineSeekTarget.PLAYHEAD
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
        released = false
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
        )
        player.setOnPreparedListener { preparedPlayer ->
            if (released || disposed) return@setOnPreparedListener
            prepared = true
            val previousDuration = duration
            duration = preparedPlayer.duration.coerceAtLeast(1)
            if (!trimMode || trimEndMillis >= previousDuration - 1) trimEndMillis = duration
            currentPosition = currentPosition.coerceIn(0, duration)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                preparedPlayer.start()
                isPlaying = true
            }
        }
        player.setOnSeekCompleteListener { activePlayer ->
            if (!released && !isScrubbing) {
                currentPosition = runCatching { activePlayer.currentPosition.coerceAtLeast(0) }
                    .getOrDefault(currentPosition)
            }
        }
        player.setOnCompletionListener {
            if (!released) {
                isPlaying = false
                currentPosition = duration
            }
        }
        player.setOnErrorListener { _, _, _ ->
            if (!released && !disposed) onPlaybackFailed()
            releasePlayer()
            true
        }
        mediaPlayer = player

        onDispose {
            disposed = true
            releasePlayer()
        }
    }

    LaunchedEffect(recordingRevisionKey, mediaPlayer) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (released) return@LaunchedEffect
        // withContext has prompt cancellation when returning to Main. Keep ownership in an
        // atomic slot before the IO block returns so a collapse at that exact boundary cannot
        // leak the opened descriptor even when the result itself is discarded.
        val openedRef = AtomicReference<InlinePlayerMediaSource?>(null)
        try {
            val opened = withContext(Dispatchers.IO) {
                openInlinePlayerMediaSource(appContext, recording).also(openedRef::set)
            }
            if (released || mediaPlayer !== player) return@LaunchedEffect
            openedRef.compareAndSet(opened, null)
            runCatching { pinnedMediaSource.getAndSet(opened)?.close() }
            player.setDataSource(opened.descriptor)
            player.prepareAsync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!released && mediaPlayer === player) {
                releasePlayer()
                onPlaybackFailed()
            }
        } finally {
            runCatching { openedRef.getAndSet(null)?.close() }
        }
    }

    DisposableEffect(lifecycleOwner, recordingRevisionKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                if (fineSeekShuttleActive) {
                    fineSeekPreviewController.stopShuttle()
                    fineSeekShuttleActive = false
                    isScrubbing = false
                    resumeAfterScrub = false
                }
                if (isPlaying) {
                    runCatching { mediaPlayer?.pause() }
                    isPlaying = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isPlaying, isScrubbing, recordingRevisionKey) {
        if (isPlaying && !isScrubbing) {
            while (true) {
                delay(INLINE_PROGRESS_UPDATE_INTERVAL_MS)
                if (released) break
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

        suspend fun constructPass(pass: RangeWaveformPass) {
            val updates = Channel<ProgressiveWaveformSnapshot>(Channel.CONFLATED)
            val worker = launch(Dispatchers.IO) {
                val accumulator = ProgressiveWaveformAccumulator(pass.bucketCount)
                try {
                    readRecordingWaveformEnvelopeProgressive(appContext, recording, pass) { index, magnitude ->
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
                    when (pass) {
                        RangeWaveformPass.COARSE -> {
                            if (update.values.size == coarseWaveform.size) {
                                coarseWaveform = update.values
                                coarseBuiltCount = update.builtCount.coerceIn(0, coarseWaveform.size)
                            }
                        }
                        RangeWaveformPass.DETAIL -> {
                            if (update.values.size == detailWaveform.size) {
                                detailWaveform = update.values
                                detailBuiltCount = update.builtCount.coerceIn(0, detailWaveform.size)
                            }
                        }
                    }
                }
            } finally {
                worker.cancel()
                updates.close()
            }
        }

        try {
            constructPass(RangeWaveformPass.COARSE)
            delay(280L)
            waveformPass = RangeWaveformPass.DETAIL
            constructPass(RangeWaveformPass.DETAIL)
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .pointerInput(recordingRevisionKey, duration, prepared, trimMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!prepared || trimSaving || size.width <= 0) return@awaitEachGesture
                        down.consume()
                        resumeAfterScrub = isPlaying
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
                            val minimumRange = minOf(50, duration.coerceAtLeast(1))
                            when (dragTarget) {
                                InlineFineSeekTarget.TRIM_START -> {
                                    trimStartMillis = millis.coerceIn(
                                        0,
                                        (trimEndMillis - minimumRange).coerceAtLeast(0),
                                    )
                                }
                                InlineFineSeekTarget.TRIM_END -> {
                                    trimEndMillis = millis.coerceIn(
                                        (trimStartMillis + minimumRange).coerceAtMost(duration),
                                        duration,
                                    )
                                }
                                InlineFineSeekTarget.PLAYHEAD -> Unit
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
                            val shouldResume = resumeAfterScrub
                            resumeAfterScrub = false
                            if (shouldResume && trimMode && currentPosition !in trimStartMillis..trimEndMillis) {
                                currentPosition = currentPosition.coerceIn(trimStartMillis, trimEndMillis)
                            }
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
            Canvas(Modifier.fillMaxSize()) {
                if (trimMode) {
                    val startX = size.width * selectionStartFraction
                    val endX = size.width * selectionEndFraction
                    for (x in listOf(startX, endX)) {
                        drawLine(
                            color = chrome.ink.copy(alpha = 0.82f * trimVisualAlpha),
                            start = Offset(x, size.height * 0.08f),
                            end = Offset(x, size.height * 0.92f),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = chrome.ink.copy(alpha = trimVisualAlpha),
                            start = Offset(x, size.height * 0.38f),
                            end = Offset(x, size.height * 0.62f),
                            strokeWidth = 7.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                val playheadX = size.width * progressFraction
                val cursorAlpha = if (trimMode) {
                    0.84f * trimVisualAlpha + 0.74f * trimBackProgress
                } else {
                    0.74f
                }
                drawLine(
                    color = chrome.ink.copy(alpha = cursorAlpha),
                    start = Offset(playheadX, size.height * 0.12f),
                    end = Offset(playheadX, size.height * 0.88f),
                    strokeWidth = if (trimMode) 1.6.dp.toPx() else 1.35.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = chrome.ink.copy(alpha = if (trimMode) 0.95f else 1f),
                    radius = if (trimMode) 3.4.dp.toPx() else 3.dp.toPx(),
                    center = Offset(playheadX, size.height * 0.12f),
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
                    color = chrome.ink,
                )
                Text(
                    text = formatRangeTimeInput(currentPosition / 1000.0),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatRangeTimeInput(trimEndMillis / 1000.0),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = chrome.ink,
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
                .height(120.dp)
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
                    .padding(top = 10.dp),
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
                            scope.launch {
                                try {
                                    val trimmed = saveTrimmedRecordingCopy(
                                        context = appContext,
                                        recording = recording,
                                        startMillis = trimStartMillis,
                                        endMillis = trimEndMillis,
                                    )
                                    trimSaving = false
                                    trimMode = false
                                    onTrimSaved(trimmed)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    trimSaving = false
                                    trimError = true
                                }
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
