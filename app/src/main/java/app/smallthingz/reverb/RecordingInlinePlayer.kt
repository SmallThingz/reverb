package app.smallthingz.reverb

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INLINE_PROGRESS_UPDATE_INTERVAL_MS = 48L
private const val INLINE_SEEK_JUMP_MS = 10_000

@Composable
internal fun RecordingInlinePlayer(
    recording: RecordingEntity,
    onTrimSaved: (RecordingEntity) -> Unit,
    onCollapse: () -> Unit,
    onPlaybackFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val chrome = appChrome()

    var mediaPlayer by remember(recording.id) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(recording.id) { mutableStateOf(false) }
    var isPlaying by remember(recording.id) { mutableStateOf(false) }
    var currentPosition by remember(recording.id) { mutableIntStateOf(0) }
    var duration by remember(recording.id) {
        mutableIntStateOf(recording.durationMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
    }
    var isScrubbing by remember(recording.id) { mutableStateOf(false) }
    var resumeAfterScrub by remember(recording.id) { mutableStateOf(false) }
    var released by remember(recording.id) { mutableStateOf(false) }
    var trimMode by remember(recording.id) { mutableStateOf(false) }
    var trimStartMillis by remember(recording.id) { mutableIntStateOf(0) }
    var trimEndMillis by remember(recording.id) { mutableIntStateOf(duration) }
    var trimSaving by remember(recording.id) { mutableStateOf(false) }
    var trimError by remember(recording.id) { mutableStateOf(false) }

    var coarseWaveform by remember(recording.id) { mutableStateOf(FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)) }
    var coarseBuiltCount by remember(recording.id) { mutableIntStateOf(0) }
    var detailWaveform by remember(recording.id) { mutableStateOf(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)) }
    var detailBuiltCount by remember(recording.id) { mutableIntStateOf(0) }
    var waveformPass by remember(recording.id) { mutableStateOf(RangeWaveformPass.COARSE) }
    var waveformLoading by remember(recording.id) { mutableStateOf(true) }
    var waveformMorphStarted by remember(recording.id) { mutableStateOf(false) }
    val waveformMorph by animateFloatAsState(
        targetValue = if (waveformMorphStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing),
        label = "recordingCardWaveformMorph",
    )

    BackHandler(enabled = true) {
        if (trimMode && !trimSaving) {
            trimMode = false
            trimError = false
        } else if (!trimSaving) {
            onCollapse()
        }
    }

    fun releasePlayer() {
        if (released) return
        released = true
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
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

    DisposableEffect(recording.id) {
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
            duration = preparedPlayer.duration.coerceAtLeast(1)
            if (!trimMode) trimEndMillis = duration
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
        try {
            when (resolveRecordingStorageType(recording)) {
                RecordingStorageType.FILE -> player.setDataSource(File(recording.id).absolutePath)
                RecordingStorageType.DOCUMENT,
                RecordingStorageType.MEDIASTORE,
                -> player.setDataSource(appContext, recording.id.toUri())
                null -> throw IllegalArgumentException("Unknown recording storage type")
            }
            player.prepareAsync()
            mediaPlayer = player
        } catch (_: Exception) {
            player.release()
            released = true
            onPlaybackFailed()
        }

        onDispose {
            disposed = true
            releasePlayer()
        }
    }

    DisposableEffect(lifecycleOwner, recording.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && isPlaying) {
                runCatching { mediaPlayer?.pause() }
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isPlaying, isScrubbing, recording.id) {
        if (isPlaying && !isScrubbing) {
            while (true) {
                delay(INLINE_PROGRESS_UPDATE_INTERVAL_MS)
                if (released) break
                val position = runCatching { mediaPlayer?.currentPosition ?: currentPosition }
                    .getOrDefault(currentPosition)
                currentPosition = position.coerceIn(0, duration.coerceAtLeast(1))
            }
        }
    }

    LaunchedEffect(recording.id) {
        waveformMorphStarted = true
        coarseWaveform = FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)
        detailWaveform = FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)
        coarseBuiltCount = 0
        detailBuiltCount = 0
        waveformPass = RangeWaveformPass.COARSE
        waveformLoading = true

        suspend fun constructPass(pass: RangeWaveformPass) {
            val updates = Channel<Pair<Int, Float>>(Channel.UNLIMITED)
            val worker = launch(Dispatchers.IO) {
                try {
                    readRecordingWaveformEnvelopeProgressive(appContext, recording, pass) { index, magnitude ->
                        updates.trySend(index to magnitude).isSuccess
                    }
                } finally {
                    updates.close()
                }
            }
            try {
                for ((index, magnitude) in updates) {
                    when (pass) {
                        RangeWaveformPass.COARSE -> {
                            val next = coarseWaveform.copyOf()
                            if (index in next.indices) {
                                next[index] = magnitude
                                coarseWaveform = next
                                coarseBuiltCount = maxOf(coarseBuiltCount, index + 1)
                            }
                        }
                        RangeWaveformPass.DETAIL -> {
                            val next = detailWaveform.copyOf()
                            if (index in next.indices) {
                                next[index] = magnitude
                                detailWaveform = next
                                detailBuiltCount = maxOf(detailBuiltCount, index + 1)
                            }
                        }
                    }
                }
                worker.join()
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
    val selectionStartFraction = if (trimMode && duration > 0) {
        (trimStartMillis.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val selectionEndFraction = if (trimMode && duration > 0) {
        (trimEndMillis.toFloat() / duration.toFloat()).coerceIn(selectionStartFraction, 1f)
    } else progressFraction

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
    ) {
        if (trimMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                .pointerInput(recording.id, duration, prepared, trimMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!prepared || trimSaving || size.width <= 0) return@awaitEachGesture
                        down.consume()
                        resumeAfterScrub = !trimMode && isPlaying
                        if (isPlaying) {
                            runCatching { mediaPlayer?.pause() }
                            isPlaying = false
                        }
                        isScrubbing = true
                        val initialMillis = (
                            duration.toFloat() * (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        ).toInt()
                        val dragStartBoundary = trimMode &&
                            kotlin.math.abs(initialMillis - trimStartMillis) <=
                            kotlin.math.abs(initialMillis - trimEndMillis)

                        fun updateFromX(x: Float) {
                            val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            val millis = (duration.toFloat() * fraction).toInt().coerceIn(0, duration)
                            if (trimMode) {
                                val minimumRange = minOf(50, duration.coerceAtLeast(1))
                                if (dragStartBoundary) {
                                    trimStartMillis = millis.coerceIn(
                                        0,
                                        (trimEndMillis - minimumRange).coerceAtLeast(0),
                                    )
                                    currentPosition = trimStartMillis
                                } else {
                                    trimEndMillis = millis.coerceIn(
                                        (trimStartMillis + minimumRange).coerceAtMost(duration),
                                        duration,
                                    )
                                    currentPosition = trimEndMillis
                                }
                                trimError = false
                            } else {
                                currentPosition = millis
                            }
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
                morphProgress = waveformMorph,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) {
                if (trimMode) {
                    val startX = size.width * selectionStartFraction
                    val endX = size.width * selectionEndFraction
                    for (x in listOf(startX, endX)) {
                        drawLine(
                            color = chrome.ink.copy(alpha = 0.82f),
                            start = Offset(x, size.height * 0.08f),
                            end = Offset(x, size.height * 0.92f),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = chrome.ink,
                            start = Offset(x, size.height * 0.38f),
                            end = Offset(x, size.height * 0.62f),
                            strokeWidth = 7.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                val playheadX = size.width * progressFraction
                drawLine(
                    color = chrome.ink.copy(alpha = if (trimMode) 0.42f else 0.74f),
                    start = Offset(playheadX, size.height * 0.12f),
                    end = Offset(playheadX, size.height * 0.88f),
                    strokeWidth = 1.35.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (!trimMode) {
                    drawCircle(
                        color = chrome.ink,
                        radius = 3.dp.toPx(),
                        center = Offset(playheadX, size.height * 0.12f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (trimMode) {
                    formatRangeTimeInput(trimStartMillis / 1000.0)
                } else {
                    formatPlaybackTime(currentPosition)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = chrome.ink,
            )
            Text(
                text = if (trimMode) {
                    formatRangeTimeInput(trimEndMillis / 1000.0)
                } else {
                    formatPlaybackTime(duration)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (trimMode) chrome.ink else chrome.muted,
            )
        }

        if (!trimMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    onClick = { seekTo(currentPosition - INLINE_SEEK_JUMP_MS, resume = isPlaying) },
                    enabled = prepared,
                    icon = AppIcons.seekBack,
                    contentDescription = stringResource(R.string.player_seek_back),
                )
                Spacer(Modifier.width(12.dp))
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    IconButton(
                        onClick = {
                            val player = mediaPlayer ?: return@IconButton
                            if (!prepared || released) return@IconButton
                            runCatching {
                                if (player.isPlaying) {
                                    player.pause()
                                    isPlaying = false
                                } else {
                                    if (currentPosition >= duration) {
                                        player.seekTo(0)
                                        currentPosition = 0
                                    }
                                    player.start()
                                    isPlaying = true
                                }
                            }
                        },
                        enabled = prepared,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) AppIcons.pause else AppIcons.play,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.player_pause else R.string.player_play,
                            ),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                TransportButton(
                    onClick = { seekTo(currentPosition + INLINE_SEEK_JUMP_MS, resume = isPlaying) },
                    enabled = prepared,
                    icon = AppIcons.seekForward,
                    contentDescription = stringResource(R.string.player_seek_forward),
                )
            }

            Surface(
                onClick = {
                    if (isPlaying) {
                        runCatching { mediaPlayer?.pause() }
                        isPlaying = false
                    }
                    trimStartMillis = 0
                    trimEndMillis = duration
                    currentPosition = 0
                    seekTo(0)
                    trimError = false
                    trimMode = true
                },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.trim,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = stringResource(R.string.trim_recording),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
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
                                runCatching {
                                    saveTrimmedRecordingCopy(
                                        context = appContext,
                                        recording = recording,
                                        startMillis = trimStartMillis,
                                        endMillis = trimEndMillis,
                                    )
                                }.onSuccess { trimmed ->
                                    trimSaving = false
                                    trimMode = false
                                    onTrimSaved(trimmed)
                                }.onFailure {
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

@Composable
private fun TransportButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    val chrome = appChrome()
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, chrome.border),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
