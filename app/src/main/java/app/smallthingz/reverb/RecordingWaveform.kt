package app.smallthingz.reverb

import android.content.Context
import androidx.core.net.toUri
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt


private const val RECORDING_WAVEFORM_CACHE_VERSION = 1
private const val RECORDING_WAVEFORM_ENCODED_LENGTH = (RANGE_WAVEFORM_DETAIL_BUCKETS * 4 + 2) / 3

internal fun recordingWaveformRevision(recording: RecordingEntity): String {
    val identity = recording.fileIdentity.takeIf { it.isNotBlank() } ?: return ""
    return buildString {
        append(RECORDING_WAVEFORM_CACHE_VERSION)
        append('|')
        append(recording.storageType.storageCode.toInt())
        append('|')
        append(identity)
        append('|')
        append(recording.sizeBytes)
        append('|')
        append(recording.durationMillis)
    }
}

internal fun isValidRecordingWaveformCache(
    recording: RecordingEntity,
    waveformData: String,
    waveformRevision: String,
): Boolean {
    val expectedRevision = recordingWaveformRevision(recording)
    return recording.missingSinceMillis == null &&
        expectedRevision.isNotBlank() &&
        waveformRevision == expectedRevision &&
        decodeRecordingWaveform(waveformData) != null
}

internal fun rebindRecordingWaveformCache(
    source: RecordingEntity,
    target: RecordingEntity,
): RecordingEntity {
    val revision = recordingWaveformRevision(target)
    val data = source.waveformData
    val keep = revision.isNotBlank() &&
        isValidRecordingWaveformCache(source, data, source.waveformRevision)
    return target.copy(
        waveformData = if (keep) data else "",
        waveformRevision = if (keep) revision else "",
    )
}

internal fun encodeRecordingWaveform(values: FloatArray): String {
    if (values.size != RANGE_WAVEFORM_DETAIL_BUCKETS) return ""
    val bytes = ByteArray(values.size) { index ->
        (values[index].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }
    return Base64.getEncoder().withoutPadding().encodeToString(bytes)
}

internal fun decodeRecordingWaveform(data: String): FloatArray? {
    if (data.length != RECORDING_WAVEFORM_ENCODED_LENGTH) return null
    val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return null
    if (bytes.size != RANGE_WAVEFORM_DETAIL_BUCKETS) return null
    return FloatArray(bytes.size) { index -> (bytes[index].toInt() and 0xff) / 255f }
}

internal fun coarseWaveformFromDetail(detail: FloatArray): FloatArray {
    if (detail.isEmpty()) return FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)
    return FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS) { bucket ->
        val start = detail.size * bucket / RANGE_WAVEFORM_COARSE_BUCKETS
        val end = maxOf(start + 1, detail.size * (bucket + 1) / RANGE_WAVEFORM_COARSE_BUCKETS)
            .coerceAtMost(detail.size)
        var peak = 0f
        for (index in start until end) peak = maxOf(peak, detail[index])
        peak
    }
}

internal data class WavPcmLayout(
    val sampleRate: Int,
    val channelCount: Int,
    val sampleFormat: PcmSampleFormat,
    val dataOffsetBytes: Long,
    val dataBytes: Long,
) {
    val frameBytes: Int get() = channelCount * sampleFormat.bytesPerSample
    val frameCount: Long get() = dataBytes / frameBytes.toLong()
    val durationSeconds: Double
        get() = if (sampleRate > 0) frameCount.toDouble() / sampleRate.toDouble() else 0.0
}

internal class RecordingPcm16MonoReader private constructor(
    private val channel: FileChannel,
    private val validateRead: () -> Boolean,
    private val closeAction: () -> Unit,
    internal val layout: WavPcmLayout,
) : Closeable {
    private var closed = false

    val durationSeconds: Double get() = layout.durationSeconds

    fun readRange(
        startSeconds: Double,
        endSeconds: Double,
        targetSampleRate: Int,
    ): ByteArray {
        check(!closed) { "Recording PCM reader is closed" }
        if (!validateRead()) throw IOException("Recording identity changed while reading")
        val output = readWavPcm16MonoRange(
            channel = channel,
            layout = layout,
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            targetSampleRate = targetSampleRate,
        )
        if (!validateRead()) throw IOException("Recording identity changed while reading")
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        closeAction()
    }

    companion object {
        fun open(context: Context, recording: RecordingEntity): RecordingPcm16MonoReader {
            return when (recording.storageType) {
                RecordingStorageType.FILE -> {
                    val input = openVerifiedFileInputStream(recording)
                        ?: throw IOException("Recording changed on disk")
                    try {
                        RecordingPcm16MonoReader(
                            channel = input.channel,
                            validateRead = {
                                fileDescriptorIdentityMatches(
                                    recording.fileIdentity,
                                    resolveFileDescriptorIdentity(input.fd),
                                )
                            },
                            closeAction = { runCatching { input.close() } },
                            layout = readWavPcmLayout(input.channel),
                        )
                    } catch (error: Throwable) {
                        runCatching { input.close() }
                        throw error
                    }
                }
                RecordingStorageType.DOCUMENT,
                RecordingStorageType.MEDIASTORE,
                -> {
                    if (!recordingContentIdentityMatches(context, recording)) {
                        throw IOException("Recording changed in provider")
                    }
                    val descriptor = context.contentResolver.openFileDescriptor(recording.id.toUri(), "r")
                        ?: throw IOException("Unable to open recording for reading")
                    val input = openChildOrCloseOwner(descriptor) { opened ->
                        FileInputStream(opened.fileDescriptor)
                    }
                    try {
                        val layout = readWavPcmLayout(input.channel)
                        if (!recordingContentIdentityMatches(context, recording)) {
                            throw IOException("Recording changed in provider while opening")
                        }
                        RecordingPcm16MonoReader(
                            channel = input.channel,
                            validateRead = { recordingContentIdentityMatches(context, recording) },
                            closeAction = {
                                runCatching { input.close() }
                                runCatching { descriptor.close() }
                            },
                            layout = layout,
                        )
                    } catch (error: Throwable) {
                        runCatching { input.close() }
                        runCatching { descriptor.close() }
                        throw error
                    }
                }
            }
        }
    }
}

internal fun readWavPcm16MonoRange(
    channel: FileChannel,
    layout: WavPcmLayout,
    startSeconds: Double,
    endSeconds: Double,
    targetSampleRate: Int,
): ByteArray {
    require(targetSampleRate > 0)
    val duration = layout.durationSeconds.coerceAtLeast(0.0)
    val start = startSeconds.takeIf { it.isFinite() }?.coerceIn(0.0, duration) ?: return ByteArray(0)
    val end = endSeconds.takeIf { it.isFinite() }?.coerceIn(start, duration) ?: return ByteArray(0)
    if (end <= start || layout.frameCount <= 0L) return ByteArray(0)

    val firstFrame = floor(start * layout.sampleRate.toDouble()).toLong()
        .coerceIn(0L, layout.frameCount - 1L)
    val lastNeededExclusive = (ceil(end * layout.sampleRate.toDouble()).toLong() + 1L)
        .coerceIn(firstFrame + 1L, layout.frameCount)
    val sourceFrames = (lastNeededExclusive - firstFrame).toInt()
    val sourceBytes = ByteArray(sourceFrames * layout.frameBytes)
    requireReadAt(
        channel = channel,
        position = layout.dataOffsetBytes + firstFrame * layout.frameBytes.toLong(),
        bytes = sourceBytes,
        count = sourceBytes.size,
    )

    val outputFrames = ceil((end - start) * targetSampleRate.toDouble())
        .toInt()
        .coerceAtLeast(1)
    val output = ByteArray(outputFrames * 2)
    val sourceStartPosition = start * layout.sampleRate.toDouble() - firstFrame.toDouble()
    val sourceStep = layout.sampleRate.toDouble() / targetSampleRate.toDouble()

    fun sample(frame: Int, channelIndex: Int): Float {
        val boundedFrame = frame.coerceIn(0, sourceFrames - 1)
        val offset = boundedFrame * layout.frameBytes +
            channelIndex.coerceIn(0, layout.channelCount - 1) * layout.sampleFormat.bytesPerSample
        return when (layout.sampleFormat) {
            PcmSampleFormat.PCM_8 -> ((sourceBytes[offset].toInt() and 0xff) - 128) / 128f
            PcmSampleFormat.PCM_16 -> {
                val bits = (sourceBytes[offset].toInt() and 0xff) or
                    (sourceBytes[offset + 1].toInt() shl 8)
                bits.toShort() / 32768f
            }
            PcmSampleFormat.PCM_FLOAT -> {
                val bits = (sourceBytes[offset].toInt() and 0xff) or
                    ((sourceBytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((sourceBytes[offset + 2].toInt() and 0xff) shl 16) or
                    (sourceBytes[offset + 3].toInt() shl 24)
                Float.fromBits(bits).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            }
        }
    }

    for (outputFrame in 0 until outputFrames) {
        val sourcePosition = sourceStartPosition + outputFrame.toDouble() * sourceStep
        val base = floor(sourcePosition).toInt().coerceIn(0, sourceFrames - 1)
        val next = minOf(base + 1, sourceFrames - 1)
        val fraction = (sourcePosition - base.toDouble()).toFloat().coerceIn(0f, 1f)
        var mono = 0f
        repeat(layout.channelCount) { channelIndex ->
            val first = sample(base, channelIndex)
            val second = sample(next, channelIndex)
            mono += first + (second - first) * fraction
        }
        mono /= layout.channelCount.toFloat()
        val encoded = (mono.coerceIn(-1f, 1f) * 32767f).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        output[outputFrame * 2] = (encoded and 0xff).toByte()
        output[outputFrame * 2 + 1] = ((encoded ushr 8) and 0xff).toByte()
    }
    return output
}

internal fun <T> withRecordingWavChannelIdentityGuard(
    context: Context,
    recording: RecordingEntity,
    block: (FileChannel, sourceStillCurrent: () -> Boolean) -> T,
): T = when (recording.storageType) {
    RecordingStorageType.FILE -> {
        val input = openVerifiedFileInputStream(recording)
            ?: throw IOException("Recording changed on disk")
        input.use { source ->
            val sourceStillCurrent = {
                fileDescriptorIdentityMatches(
                    recording.fileIdentity,
                    resolveFileDescriptorIdentity(input.fd),
                )
            }
            block(source.channel, sourceStillCurrent)
        }
    }
    RecordingStorageType.DOCUMENT,
    RecordingStorageType.MEDIASTORE,
    -> {
        if (!recordingContentIdentityMatches(context, recording)) {
            throw IOException("Recording changed in provider")
        }
        val uri = recording.id.toUri()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open recording for reading")
        descriptor.use { opened ->
            FileInputStream(opened.fileDescriptor).channel.use { channel ->
                val sourceStillCurrent = { recordingContentIdentityMatches(context, recording) }
                if (!sourceStillCurrent()) {
                    throw IOException("Recording changed in provider while opening")
                }
                block(channel, sourceStillCurrent)
            }
        }
    }
}

internal fun <T> withRecordingWavChannel(
    context: Context,
    recording: RecordingEntity,
    block: (FileChannel) -> T,
): T = withRecordingWavChannelIdentityGuard(context, recording) { channel, sourceStillCurrent ->
    val result = block(channel)
    if (!sourceStillCurrent()) throw IOException("Recording changed while reading")
    result
}

internal fun readWavPcmLayout(channel: FileChannel): WavPcmLayout {
    val riff = ByteArray(12)
    requireReadAt(channel, 0L, riff, riff.size)
    if (!riff.asciiEquals(0, "RIFF") || !riff.asciiEquals(8, "WAVE")) {
        throw IOException("Recording is not a RIFF/WAVE file")
    }

    var sampleRate = 0
    var channelCount = 0
    var sampleFormat: PcmSampleFormat? = null
    var blockAlign = 0
    var dataOffset = -1L
    var dataBytes = -1L
    var cursor = 12L
    val fileSize = channel.size().coerceAtLeast(12L)
    val header = ByteArray(8)

    while (cursor <= fileSize - header.size) {
        requireReadAt(channel, cursor, header, header.size)
        val chunkSize = littleEndianUInt(header, 4)
        val payloadOffset = cursor + header.size
        if (payloadOffset > fileSize || chunkSize > fileSize - payloadOffset) {
            throw IOException("Truncated WAV chunk")
        }
        when {
            header.asciiEquals(0, "fmt ") -> {
                if (chunkSize < 16L) throw IOException("Invalid WAV fmt chunk")
                val fmt = ByteArray(16)
                requireReadAt(channel, payloadOffset, fmt, fmt.size)
                val formatTag = littleEndianUShort(fmt, 0)
                channelCount = littleEndianUShort(fmt, 2)
                sampleRate = littleEndianInt(fmt, 4)
                blockAlign = littleEndianUShort(fmt, 12)
                val bitsPerSample = littleEndianUShort(fmt, 14)
                sampleFormat = when {
                    formatTag == 1 && bitsPerSample == 8 -> PcmSampleFormat.PCM_8
                    formatTag == 1 && bitsPerSample == 16 -> PcmSampleFormat.PCM_16
                    formatTag == 3 && bitsPerSample == 32 -> PcmSampleFormat.PCM_FLOAT
                    else -> throw IOException("Unsupported WAV PCM format")
                }
            }
            header.asciiEquals(0, "data") -> {
                dataOffset = payloadOffset
                dataBytes = chunkSize
            }
        }
        if (sampleFormat != null && dataOffset >= 0L) break
        cursor = payloadOffset + chunkSize + (chunkSize and 1L)
    }

    val format = sampleFormat ?: throw IOException("WAV fmt chunk missing")
    if (sampleRate <= 0 || channelCount !in 1..2) throw IOException("Invalid WAV format")
    val expectedFrameBytes = channelCount * format.bytesPerSample
    if (blockAlign != expectedFrameBytes) throw IOException("Unsupported WAV block alignment")
    if (dataOffset < 0L || dataBytes < expectedFrameBytes.toLong()) throw IOException("WAV data chunk missing")
    val available = (fileSize - dataOffset).coerceAtLeast(0L)
    val boundedBytes = minOf(dataBytes, available)
    val alignedBytes = boundedBytes - boundedBytes % expectedFrameBytes.toLong()
    if (alignedBytes <= 0L) throw IOException("WAV contains no complete audio frames")
    return WavPcmLayout(sampleRate, channelCount, format, dataOffset, alignedBytes)
}

internal fun readRecordingWaveformEnvelopeProgressive(
    context: Context,
    recording: RecordingEntity,
    pass: RangeWaveformPass,
    onBucket: (bucketIndex: Int, magnitude: Float) -> Boolean,
): FloatArray = withRecordingWavChannel(context, recording) { channel ->
    val layout = readWavPcmLayout(channel)
    sampleWavWaveformEnvelopeProgressive(channel, layout, pass, onBucket)
}

internal fun sampleWavWaveformEnvelopeProgressive(
    channel: FileChannel,
    layout: WavPcmLayout,
    pass: RangeWaveformPass,
    onBucket: (bucketIndex: Int, magnitude: Float) -> Boolean,
): FloatArray {
    val buckets = pass.bucketCount
    val envelope = FloatArray(buckets)
    val totalFrames = layout.frameCount
    if (totalFrames <= 0L) return envelope
    val scratch = ByteArray(pass.framesPerProbe * layout.frameBytes)

    for (bucket in 0 until buckets) {
        val bucketStart = totalFrames * bucket.toLong() / buckets.toLong()
        val bucketEndExclusive = totalFrames * (bucket + 1L) / buckets.toLong()
        val bucketFrames = (bucketEndExclusive - bucketStart).coerceAtLeast(1L)
        var peak = 0f
        repeat(pass.probesPerBucket) { probe ->
            val centerFrame = bucketStart +
                bucketFrames * (probe * 2L + 1L) / (pass.probesPerBucket * 2L)
            val readFrames = minOf(pass.framesPerProbe.toLong(), totalFrames).toInt()
            val startFrame = (centerFrame - readFrames / 2L)
                .coerceIn(0L, totalFrames - readFrames.toLong())
            val readBytes = readFrames * layout.frameBytes
            requireReadAt(
                channel,
                layout.dataOffsetBytes + startFrame * layout.frameBytes.toLong(),
                scratch,
                readBytes,
            )
            var offset = 0
            repeat(readFrames) {
                repeat(layout.channelCount) {
                    peak = maxOf(peak, waveformSampleMagnitude(scratch, offset, layout.sampleFormat))
                    offset += layout.sampleFormat.bytesPerSample
                }
            }
        }
        val shaped = shapeWaveformMagnitude(peak.coerceIn(0f, 1f))
        envelope[bucket] = shaped
        if (!onBucket(bucket, shaped)) break
    }
    return envelope
}

internal fun waveformSampleMagnitude(
    bytes: ByteArray,
    offset: Int,
    format: PcmSampleFormat,
): Float = when (format) {
    PcmSampleFormat.PCM_8 -> abs(((bytes[offset].toInt() and 0xff) - 128) / 128f)
    PcmSampleFormat.PCM_16 -> {
        val value = ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8))
            .toShort().toInt()
        abs(value / 32768f)
    }
    PcmSampleFormat.PCM_FLOAT -> {
        val bits = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
        val value = Float.fromBits(bits)
        if (value.isFinite()) abs(value).coerceIn(0f, 1f) else 0f
    }
}

internal fun requireReadAt(
    channel: FileChannel,
    position: Long,
    bytes: ByteArray,
    count: Int,
) {
    require(count in 0..bytes.size)
    var readTotal = 0
    while (readTotal < count) {
        val buffer = ByteBuffer.wrap(bytes, readTotal, count - readTotal)
        val read = channel.read(buffer, position + readTotal)
        if (read <= 0) throw IOException("Unexpected end of WAV file")
        readTotal += read
    }
}

private fun ByteArray.asciiEquals(offset: Int, text: String): Boolean {
    if (offset < 0 || offset + text.length > size) return false
    return text.indices.all { this[offset + it].toInt() and 0xff == text[it].code }
}

private fun littleEndianUShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        (bytes[offset + 3].toInt() shl 24)

private fun littleEndianUInt(bytes: ByteArray, offset: Int): Long =
    littleEndianInt(bytes, offset).toLong() and 0xffff_ffffL
