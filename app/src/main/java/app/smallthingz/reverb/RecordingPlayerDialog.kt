package app.smallthingz.reverb

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File

private const val PROGRESS_UPDATE_INTERVAL_MS = 100L
private const val SEEK_JUMP_MS = 10_000

@Composable
fun RecordingPlayerDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onInfoClick: () -> Unit,
    onPlaybackFailed: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var mediaPlayer by remember(recording.id) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(recording.id) { mutableStateOf(false) }
    var isPlaying by remember(recording.id) { mutableStateOf(false) }
    var currentPosition by remember(recording.id) { mutableIntStateOf(0) }
    var duration by remember(recording.id) {
        mutableIntStateOf(recording.durationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    var isDragging by remember(recording.id) { mutableStateOf(false) }
    var released by remember(recording.id) { mutableStateOf(false) }

    val playerCodecSummary = remember(recording.codecSummary) { buildPlayerCodecSummary(recording.codecSummary) }
    val sizeText = remember(recording.sizeBytes) { formatShortFileSize(recording.sizeBytes) }

    fun releasePlayer() {
        if (released) return
        released = true
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        prepared = false
        isPlaying = false
    }

    DisposableEffect(recording.id) {
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
        )
        player.setOnPreparedListener { preparedPlayer ->
            if (released) return@setOnPreparedListener
            prepared = true
            val dur = preparedPlayer.duration.coerceAtLeast(0)
            duration = dur.coerceAtLeast(1)
            if (!released) {
                preparedPlayer.start()
                isPlaying = true
            }
        }
        player.setOnSeekCompleteListener {
            if (released) return@setOnSeekCompleteListener
            currentPosition = try { it.currentPosition.coerceAtLeast(0) } catch (_: IllegalStateException) { 0 }
        }
        player.setOnCompletionListener {
            isPlaying = false
            currentPosition = duration
        }
        player.setOnErrorListener { _, _, _ ->
            if (!released) {
                Handler(Looper.getMainLooper()).post { onPlaybackFailed() }
            }
            releasePlayer()
            true
        }
        try {
            when (resolveRecordingStorageType(recording)) {
                RecordingStorageType.FILE -> player.setDataSource(File(recording.id).absolutePath)
                RecordingStorageType.DOCUMENT -> player.setDataSource(context, Uri.parse(recording.id))
                null -> throw IllegalArgumentException("Unknown recording storage type")
            }
            player.prepareAsync()
            mediaPlayer = player
        } catch (_: Exception) {
            player.release()
            released = true
            onPlaybackFailed()
        }

        onDispose { releasePlayer() }
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

    LaunchedEffect(isPlaying, isDragging) {
        if (isPlaying && !isDragging) {
            while (true) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                if (released) break
                val pos = try {
                    mediaPlayer?.currentPosition?.coerceAtLeast(0) ?: 0
                } catch (_: IllegalStateException) { 0 }
                currentPosition = pos
            }
        }
    }

    val dismissRequest = remember(recording.id, onDismiss) {
        {
            releasePlayer()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = dismissRequest,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recording.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onInfoClick) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.recording_info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = dismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = playerCodecSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { currentPosition = it.toInt(); isDragging = true },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    onValueChangeFinished = {
                        if (prepared && !released) runCatching { mediaPlayer?.seekTo(currentPosition) }
                        isDragging = false
                    },
                    enabled = prepared,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        thumbColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val positionText = remember(currentPosition) { formatPlaybackTime(currentPosition) }
                    val durationText = remember(duration) { formatPlaybackTime(duration) }
                    Text(
                        text = positionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                val seekBack = remember(recording.id) {
                    {
                        val player = mediaPlayer
                        if (player != null && prepared && !released) {
                            runCatching {
                                val pos = player.currentPosition.coerceAtLeast(0)
                                player.seekTo((pos - SEEK_JUMP_MS).coerceAtLeast(0))
                            }
                        }
                    }
                }
                val togglePlay = remember(recording.id) {
                    {
                        val player = mediaPlayer
                        if (player != null && prepared && !released) {
                            runCatching {
                                if (player.isPlaying) {
                                    player.pause()
                                    isPlaying = false
                                } else {
                                    player.start()
                                    isPlaying = true
                                }
                            }
                        }
                    }
                }
                val seekForward = remember(recording.id) {
                    {
                        val player = mediaPlayer
                        if (player != null && prepared && !released) {
                            runCatching {
                                val pos = player.currentPosition.coerceAtLeast(0)
                                player.seekTo((pos + SEEK_JUMP_MS).coerceAtMost(duration))
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        IconButton(
                            onClick = seekBack,
                            enabled = prepared,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_seek_back),
                                contentDescription = stringResource(R.string.player_seek_back),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        IconButton(
                            onClick = togglePlay,
                            enabled = prepared,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                                ),
                                contentDescription = stringResource(
                                    if (isPlaying) R.string.player_pause else R.string.player_play,
                                ),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        IconButton(
                            onClick = seekForward,
                            enabled = prepared,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_seek_forward),
                                contentDescription = stringResource(R.string.player_seek_forward),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}
