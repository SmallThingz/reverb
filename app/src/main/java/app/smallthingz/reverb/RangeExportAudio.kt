package app.smallthingz.reverb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

private const val PREVIEW_SAMPLE_RATE = 24_000
private const val PREVIEW_WRITE_BYTES = 2_400
private const val SCRUB_AUDITION_SECONDS = 0.14
private const val SCRUB_AUDITION_LEAD_SECONDS = 0.02
private const val PREVIEW_PROGRESS_INTERVAL_MILLIS = 32L

internal fun ReverbService.TimelineSnapshot.readWaveformEnvelope(bucketCount: Int): FloatArray =
    normalizeWaveformEnvelope(sampleWaveformEnvelope(bucketCount))

internal fun normalizeWaveformEnvelope(raw: FloatArray): FloatArray {
    if (raw.isEmpty()) return raw
    val peak = raw.maxOrNull()?.coerceAtLeast(0f) ?: 0f
    if (peak <= 0.0001f) return FloatArray(raw.size)

    val normalized = FloatArray(raw.size) { index ->
        sqrt((raw[index] / peak).coerceIn(0f, 1f))
    }
    if (normalized.size < 3) return normalized

    val smoothed = FloatArray(normalized.size)
    for (index in normalized.indices) {
        val previous = normalized[(index - 1).coerceAtLeast(0)]
        val current = normalized[index]
        val next = normalized[(index + 1).coerceAtMost(normalized.lastIndex)]
        smoothed[index] = (previous * 0.22f + current * 0.56f + next * 0.22f).coerceIn(0f, 1f)
    }
    return smoothed
}

internal class TimelineAudioPreviewController : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingDeque(),
    ) { runnable ->
        Thread(runnable, "Reverb-range-preview").apply { isDaemon = true }
    }
    private val trackLock = Any()

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var closed = false

    fun play(
        snapshot: ReverbService.TimelineSnapshot,
        fromSeconds: Double,
        onProgress: (Double) -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        val start = fromSeconds.coerceIn(0.0, snapshot.durationSeconds)
        enqueueLatest {
            stream(
                token = token,
                snapshot = snapshot,
                startSeconds = start,
                endSeconds = snapshot.durationSeconds,
                volume = 1f,
                reportProgress = true,
                onProgress = onProgress,
                onFinished = onFinished,
                onError = onError,
            )
        }
    }

    fun audition(snapshot: ReverbService.TimelineSnapshot, atSeconds: Double) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        val center = atSeconds.coerceIn(0.0, snapshot.durationSeconds)
        val start = (center - SCRUB_AUDITION_LEAD_SECONDS).coerceAtLeast(0.0)
        val end = (center + SCRUB_AUDITION_SECONDS).coerceAtMost(snapshot.durationSeconds)
        if (end <= start) return
        enqueueLatest {
            stream(
                token = token,
                snapshot = snapshot,
                startSeconds = start,
                endSeconds = end,
                volume = 0.72f,
                reportProgress = false,
                onProgress = {},
                onFinished = {},
                onError = {},
            )
        }
    }

    fun stop() {
        if (closed) return
        cancelCurrent()
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelCurrent()
        executor.shutdownNow()
    }

    private fun enqueueLatest(block: () -> Unit) {
        executor.queue.clear()
        executor.execute(block)
    }

    private fun cancelCurrent() {
        generation.incrementAndGet()
        executor.queue.clear()
        val track = synchronized(trackLock) {
            activeTrack.also { activeTrack = null }
        }
        if (track != null) releaseTrack(track)
    }

    private fun stream(
        token: Long,
        snapshot: ReverbService.TimelineSnapshot,
        startSeconds: Double,
        endSeconds: Double,
        volume: Float,
        reportProgress: Boolean,
        onProgress: (Double) -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        var child: PersistentAudioChunkStore.RangeLease? = null
        var track: AudioTrack? = null
        try {
            checkCurrent(token)
            child = snapshot.acquireRange(startSeconds, endSeconds)
                ?: throw IOException("No audio available for preview")
            checkCurrent(token)
            track = createTrack(volume)
            synchronized(trackLock) {
                checkCurrent(token)
                activeTrack = track
            }
            track.play()

            var lastProgressAt = 0L
            val readResult = child.readNormalized(
                targetSampleRate = PREVIEW_SAMPLE_RATE,
                targetChannelCount = 1,
                targetSampleFormat = PcmSampleFormat.PCM_16,
            ) { array, offset, count ->
                var position = offset
                val limit = offset + count
                while (position < limit) {
                    checkCurrent(token)
                    val requested = minOf(PREVIEW_WRITE_BYTES, limit - position)
                    val written = track.write(array, position, requested, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw IOException("Audio preview write failed: $written")
                    position += written
                    if (reportProgress) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= PREVIEW_PROGRESS_INTERVAL_MILLIS) {
                            lastProgressAt = now
                            val played = unsignedPlaybackHead(track.playbackHeadPosition)
                            postIfCurrent(token) {
                                onProgress(
                                    (startSeconds + played.toDouble() / PREVIEW_SAMPLE_RATE.toDouble())
                                        .coerceAtMost(endSeconds),
                                )
                            }
                        }
                    }
                }
                count
            }

            if (reportProgress) {
                val targetFrames = (readResult.durationSeconds * PREVIEW_SAMPLE_RATE.toDouble())
                    .roundToLong()
                    .coerceAtLeast(0L)
                while (unsignedPlaybackHead(track.playbackHeadPosition) < targetFrames) {
                    checkCurrent(token)
                    val played = unsignedPlaybackHead(track.playbackHeadPosition)
                    postIfCurrent(token) {
                        onProgress(
                            (startSeconds + played.toDouble() / PREVIEW_SAMPLE_RATE.toDouble())
                                .coerceAtMost(endSeconds),
                        )
                    }
                    Thread.sleep(16L)
                }
            }
            checkCurrent(token)
            postIfCurrent(token, onFinished)
        } catch (_: PreviewCancelled) {
            Unit
        } catch (error: Throwable) {
            if (isCurrent(token)) postIfCurrent(token) { onError(error) }
        } finally {
            runCatching { child?.close() }
            if (track != null) {
                synchronized(trackLock) {
                    if (activeTrack === track) activeTrack = null
                }
                releaseTrack(track)
            }
        }
    }

    private fun createTrack(volume: Float): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            PREVIEW_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(PREVIEW_WRITE_BYTES * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PREVIEW_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()
            .also { it.setVolume(volume.coerceIn(0f, 1f)) }
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun checkCurrent(token: Long) {
        if (!isCurrent(token) || Thread.currentThread().isInterrupted) throw PreviewCancelled()
    }

    private fun isCurrent(token: Long): Boolean = !closed && generation.get() == token

    private fun postIfCurrent(token: Long, block: () -> Unit) {
        mainHandler.post {
            if (isCurrent(token)) block()
        }
    }

    private fun unsignedPlaybackHead(value: Int): Long = value.toLong() and 0xffff_ffffL

    private class PreviewCancelled : RuntimeException()
}
