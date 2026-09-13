package app.smallthingz.reverb

import android.content.Context
import androidx.core.net.toUri
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Base64
import kotlin.math.abs


private const val RECORDING_WAVEFORM_CACHE_VERSION = 1

internal fun recordingWaveformRevision(recording: RecordingEntity): String = buildString {
    append(RECORDING_WAVEFORM_CACHE_VERSION)
    append('|')
    append(recording.storageType)
    append('|')
    append(recording.fileIdentity.ifBlank { recording.id })
    append('|')
    append(recording.sizeBytes)
    append('|')
    append(recording.durationMillis)
}

internal fun encodeRecordingWaveform(values: FloatArray): String {
    if (values.size != RANGE_WAVEFORM_DETAIL_BUCKETS) return ""
    val bytes = ByteArray(values.size) { index ->
        (values[index].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }
    return Base64.getEncoder().withoutPadding().encodeToString(bytes)
}

internal fun decodeRecordingWaveform(data: String): FloatArray? {
    if (data.isBlank()) return null
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

internal fun <T> withRecordingWavChannel(
    context: Context,
    recording: RecordingEntity,
    block: (FileChannel) -> T,
): T = when (resolveRecordingStorageType(recording)) {
    RecordingStorageType.FILE -> {
        val input = openVerifiedFileInputStream(recording)
            ?: throw IOException("Recording changed on disk")
        input.use { source -> source.channel.use(block) }
    }
    RecordingStorageType.DOCUMENT,
    RecordingStorageType.MEDIASTORE,
    -> {
        val uri = recording.id.toUri()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open recording for reading")
        descriptor.use {
            FileInputStream(it.fileDescriptor).channel.use(block)
        }
    }
    null -> throw IOException("Unknown recording storage type")
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
