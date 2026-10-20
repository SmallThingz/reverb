package app.smallthingz.reverb

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt


// Version 2 replaces FLAC envelopes produced by the former single-probe sampler.
private const val RECORDING_WAVEFORM_CACHE_VERSION = 2
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

internal fun fileReadIdentityRemainsCurrent(
    expectedIdentity: String,
    descriptorIdentity: String,
    pathIdentity: String,
): Boolean =
    fileDescriptorIdentityMatches(expectedIdentity, descriptorIdentity) &&
        fileIdentityMatches(expectedIdentity, pathIdentity)

internal inline fun <T> consumeProviderReadAfterVerifiedHandoff(
    expectedIdentity: String,
    beforeOpenIdentity: String,
    afterOpenIdentity: () -> String,
    consume: () -> T,
): T {
    val after = afterOpenIdentity()
    if (!providerReadHandoffMatchesExpected(expectedIdentity, beforeOpenIdentity, after)) {
        throw IOException("Recording changed while opening provider read")
    }
    return consume()
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

internal class RecordingPcm16MonoReader internal constructor(
    private val channel: FileChannel,
    private val validateRead: () -> Boolean,
    private val closeAction: () -> Unit,
    internal val layout: WavPcmLayout,
    private val flac: FlacPcmDecoder? = null,
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
        val output = if (flac != null) readFlacPcm16MonoRange(flac, startSeconds, endSeconds, targetSampleRate) else readWavPcm16MonoRange(
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
        val error = closePreservingPrimaryFailure(null) { flac?.close() }
        val terminal = closePreservingPrimaryFailure(error, closeAction)
        if (terminal != null) throw terminal
    }

    companion object {
        fun open(
            context: Context,
            recording: RecordingEntity,
            cleanup: RecordingReadCleanup,
        ): RecordingPcm16MonoReader {
            return when (recording.storageType) {
                RecordingStorageType.FILE -> {
                    val input = openVerifiedFileInputStream(recording, cleanup)
                        ?: throw IOException("Recording changed on disk")
                    try {
                        val validateRead = {
                            fileReadIdentityRemainsCurrent(
                                expectedIdentity = recording.fileIdentity,
                                descriptorIdentity = resolveFileDescriptorIdentity(input.fd),
                                pathIdentity = resolveFileIdentity(File(recording.id)),
                            )
                        }
                        val layout = if (channelHasFlac(input.channel)) readFlacInfo(input.channel).pcmLayout else readWavPcmLayout(input.channel)
                        if (!validateRead()) {
                            throw IOException("Recording changed on disk while opening")
                        }
                        RecordingPcm16MonoReader(
                            channel = input.channel,
                            validateRead = validateRead,
                            closeAction = input::close,
                            layout = layout,
                            flac = if (channelHasFlac(input.channel)) FlacPcmDecoder(input.channel, cleanup) else null,
                        )
                    } catch (error: Throwable) {
                        throw cleanup.closeAfterFailure(error) { input.close() }
                    }
                }
                RecordingStorageType.DOCUMENT,
                RecordingStorageType.MEDIASTORE,
                -> {
                    if (!recordingReadIdentityIsStable(recording)) {
                        throw IOException("Recording identity is unavailable in provider")
                    }
                    val uri = recording.id.toUri()
                    val beforeOpenIdentity = resolveProviderRecordingIdentity(
                        context, recording.storageType, uri, recording.fileIdentity,
                    )
                    if (!providerRecordingIdentityMatches(recording.fileIdentity, beforeOpenIdentity)) {
                        throw IOException("Recording changed in provider")
                    }
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IOException("Unable to open recording for reading")
                    val input = cleanup.open(descriptor) { opened ->
                        ParcelFileDescriptor.AutoCloseInputStream(opened)
                    }
                    try {
                        val validateRead = {
                            recordingContentIdentityMatches(context, recording) &&
                                providerDescriptorMatchesIdentity(recording.fileIdentity, input.fd)
                        }
                        if (!providerDescriptorMatchesIdentity(recording.fileIdentity, input.fd)) {
                            throw IOException("Recording descriptor changed in provider while opening")
                        }
                        val layout = consumeProviderReadAfterVerifiedHandoff(
                            expectedIdentity = recording.fileIdentity,
                            beforeOpenIdentity = beforeOpenIdentity,
                            afterOpenIdentity = {
                                resolveProviderRecordingIdentity(context, recording.storageType, uri, recording.fileIdentity)
                            },
                            consume = { if (channelHasFlac(input.channel)) readFlacInfo(input.channel).pcmLayout else readWavPcmLayout(input.channel) },
                        )
                        if (!validateRead()) {
                            throw IOException("Recording changed in provider while opening")
                        }
                        RecordingPcm16MonoReader(
                            channel = input.channel,
                            validateRead = validateRead,
                            closeAction = input::close,
                            layout = layout,
                            flac = if (channelHasFlac(input.channel)) FlacPcmDecoder(input.channel, cleanup) else null,
                        )
                    } catch (error: Throwable) {
                        throw cleanup.closeAfterFailure(error) { input.close() }
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

    val sourceStep = layout.sampleRate.toDouble() / targetSampleRate.toDouble()
    val radius = pcmResamplingRadius(sourceStep)
    val firstFrame = (floor(start * layout.sampleRate.toDouble()).toLong() - radius)
        .coerceIn(0L, layout.frameCount - 1L)
    val endFrameCeil = ceil(end * layout.sampleRate.toDouble()).toLong()
    val lastNeededExclusive = if (endFrameCeil >= layout.frameCount) {
        layout.frameCount
    } else {
        (endFrameCeil + 1L + radius).coerceIn(firstFrame + 1L, layout.frameCount)
    }
    val sourceFrameCount = lastNeededExclusive - firstFrame
    val sourceByteCount = sourceFrameCount * layout.frameBytes.toLong()
    if (sourceByteCount > Int.MAX_VALUE.toLong()) {
        throw IOException("Requested PCM source range is too large")
    }
    val requestedOutputFrames = ceil((end - start) * targetSampleRate.toDouble())
    if (!requestedOutputFrames.isFinite() || requestedOutputFrames > Int.MAX_VALUE.toDouble() / 2.0) {
        throw IOException("Requested PCM output range is too large")
    }
    val sourceFrames = sourceFrameCount.toInt()
    val sourceBytes = ByteArray(sourceByteCount.toInt())
    requireReadAt(
        channel = channel,
        position = layout.dataOffsetBytes + firstFrame * layout.frameBytes.toLong(),
        bytes = sourceBytes,
        count = sourceBytes.size,
    )

    val outputFrames = requestedOutputFrames.toInt().coerceAtLeast(1)
    val output = ByteArray(outputFrames * 2)
    val sourceStartPosition = start * layout.sampleRate.toDouble() - firstFrame.toDouble()

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

    val converter = PcmRateConverter(sourceFrames, sourceStep, ::sample)
    for (outputFrame in 0 until outputFrames) {
        val sourcePosition = sourceStartPosition + outputFrame.toDouble() * sourceStep
        var mono = 0f
        repeat(layout.channelCount) { channelIndex ->
            mono += converter.sample(sourcePosition, channelIndex)
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
    cleanup: RecordingReadCleanup,
    block: (FileChannel, sourceStillCurrent: () -> Boolean) -> T,
): T = when (recording.storageType) {
    RecordingStorageType.FILE -> {
        val input = openVerifiedFileInputStream(recording, cleanup)
            ?: throw IOException("Recording changed on disk")
        cleanup.use(input, release = { it.close() }) { source ->
            val sourceStillCurrent = {
                fileReadIdentityRemainsCurrent(
                    expectedIdentity = recording.fileIdentity,
                    descriptorIdentity = resolveFileDescriptorIdentity(input.fd),
                    pathIdentity = resolveFileIdentity(File(recording.id)),
                )
            }
            if (!sourceStillCurrent()) {
                throw IOException("Recording changed on disk while opening")
            }
            block(source.channel, sourceStillCurrent)
        }
    }
    RecordingStorageType.DOCUMENT,
    RecordingStorageType.MEDIASTORE,
    -> {
        if (!recordingReadIdentityIsStable(recording) ||
            !recordingContentIdentityMatches(context, recording)
        ) {
            throw IOException("Recording changed in provider")
        }
        val uri = recording.id.toUri()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open recording for reading")
        val input = cleanup.open(descriptor) { opened ->
            ParcelFileDescriptor.AutoCloseInputStream(opened)
        }
        cleanup.use(input, release = { it.close() }) { source ->
            val sourceStillCurrent = {
                recordingContentIdentityMatches(context, recording) &&
                    providerDescriptorMatchesIdentity(recording.fileIdentity, source.fd)
            }
            if (!sourceStillCurrent()) {
                throw IOException("Recording changed in provider while opening")
            }
            block(source.channel, sourceStillCurrent)
        }
    }
}

internal fun <T> withRecordingWavChannel(
    context: Context,
    recording: RecordingEntity,
    cleanup: RecordingReadCleanup,
    block: (FileChannel) -> T,
): T = withRecordingWavChannelIdentityGuard(context, recording, cleanup) { channel, sourceStillCurrent ->
    val result = block(channel)
    if (!sourceStillCurrent()) throw IOException("Recording changed while reading")
    result
}

internal class InvalidWavStructureException(message: String) : IOException(message)

internal fun readWavPcmLayout(channel: FileChannel): WavPcmLayout {
    val fileSize = channel.size()
    if (fileSize < 44L) throw InvalidWavStructureException("WAV header is incomplete")
    val riff = ByteArray(12)
    requireReadAt(channel, 0L, riff, riff.size)
    if (!riff.asciiEquals(0, "RIFF") || !riff.asciiEquals(8, "WAVE")) {
        throw InvalidWavStructureException("Recording is not a RIFF/WAVE file")
    }

    val containerBytes = littleEndianUInt(riff, 4) + 8L
    if (containerBytes < 44L || containerBytes != fileSize) {
        throw InvalidWavStructureException("WAV RIFF size does not match the file")
    }

    var sampleRate = 0
    var channelCount = 0
    var sampleFormat: PcmSampleFormat? = null
    var blockAlign = 0
    var byteRate = 0L
    var dataOffset = -1L
    var dataBytes = -1L
    var sawFormat = false
    var sawData = false
    var cursor = 12L
    val header = ByteArray(8)

    while (cursor < containerBytes) {
        if (containerBytes - cursor < header.size.toLong()) {
            throw InvalidWavStructureException("Truncated WAV chunk header")
        }
        requireReadAt(channel, cursor, header, header.size)
        val chunkSize = littleEndianUInt(header, 4)
        val paddedChunkSize = chunkSize + (chunkSize and 1L)
        val payloadOffset = cursor + header.size.toLong()
        if (paddedChunkSize > containerBytes - payloadOffset) {
            throw InvalidWavStructureException("Truncated WAV chunk")
        }
        when {
            header.asciiEquals(0, "fmt ") -> {
                if (sawFormat || chunkSize < 16L) throw InvalidWavStructureException("Invalid WAV fmt chunk")
                val fmt = ByteArray(16)
                requireReadAt(channel, payloadOffset, fmt, fmt.size)
                val formatTag = littleEndianUShort(fmt, 0)
                channelCount = littleEndianUShort(fmt, 2)
                val sampleRateLong = littleEndianUInt(fmt, 4)
                if (sampleRateLong !in 1L..Int.MAX_VALUE.toLong()) throw InvalidWavStructureException("Invalid WAV sample rate")
                sampleRate = sampleRateLong.toInt()
                byteRate = littleEndianUInt(fmt, 8)
                blockAlign = littleEndianUShort(fmt, 12)
                val bitsPerSample = littleEndianUShort(fmt, 14)
                sampleFormat = when {
                    formatTag == 1 && bitsPerSample == 8 -> PcmSampleFormat.PCM_8
                    formatTag == 1 && bitsPerSample == 16 -> PcmSampleFormat.PCM_16
                    formatTag == 3 && bitsPerSample == 32 -> PcmSampleFormat.PCM_FLOAT
                    else -> throw InvalidWavStructureException("Unsupported WAV PCM format")
                }
                sawFormat = true
            }
            header.asciiEquals(0, "data") -> {
                if (sawData) throw InvalidWavStructureException("Duplicate WAV data chunk")
                dataOffset = payloadOffset
                dataBytes = chunkSize
                sawData = true
            }
        }
        cursor = payloadOffset + paddedChunkSize
    }

    if (cursor != containerBytes) throw InvalidWavStructureException("WAV container did not end at RIFF boundary")
    val format = sampleFormat ?: throw InvalidWavStructureException("WAV fmt chunk missing")
    if (!sawFormat || !sawData || channelCount !in 1..2) throw InvalidWavStructureException("Invalid WAV format")
    val expectedFrameBytes = channelCount * format.bytesPerSample
    val expectedByteRate = sampleRate.toLong() * expectedFrameBytes.toLong()
    if (blockAlign != expectedFrameBytes) throw InvalidWavStructureException("Unsupported WAV block alignment")
    if (byteRate != expectedByteRate) throw InvalidWavStructureException("Unsupported WAV byte rate")
    if (dataOffset < 0L || dataBytes < expectedFrameBytes.toLong()) throw InvalidWavStructureException("WAV data chunk missing")
    if (dataBytes % expectedFrameBytes.toLong() != 0L) {
        throw InvalidWavStructureException("WAV data chunk is not frame aligned")
    }
    return WavPcmLayout(sampleRate, channelCount, format, dataOffset, dataBytes)
}

internal fun readRecordingWaveformEnvelopeProgressive(
    context: Context,
    recording: RecordingEntity,
    pass: RangeWaveformPass,
    cleanup: RecordingReadCleanup,
    onBucket: (bucketIndex: Int, magnitude: Float) -> Boolean,
): FloatArray = withRecordingWavChannel(context, recording, cleanup) { channel ->
    if (channelHasFlac(channel)) {
        cleanup.use(FlacPcmDecoder(channel, cleanup), release = { it.close() }) { decoder ->
            samplePcmWaveformEnvelopeProgressive(decoder.info.pcmLayout, pass, { first, frames, scratch ->
                var position = 0
                decoder.readFrames(first, frames.toLong()) { bytes, offset, count ->
                    bytes.copyInto(scratch, position, offset, offset + count)
                    position += count
                }
            }, onBucket)
        }
    } else {
        val layout = readWavPcmLayout(channel)
        sampleWavWaveformEnvelopeProgressive(channel, layout, pass, onBucket)
    }
}

internal fun sampleWavWaveformEnvelopeProgressive(
    channel: FileChannel,
    layout: WavPcmLayout,
    pass: RangeWaveformPass,
    onBucket: (bucketIndex: Int, magnitude: Float) -> Boolean,
): FloatArray = samplePcmWaveformEnvelopeProgressive(layout, pass, { first, frames, scratch ->
    requireReadAt(channel, layout.dataOffsetBytes + first * layout.frameBytes, scratch, frames * layout.frameBytes)
}, onBucket)

/** Both containers sample the same PCM positions and reuse one probe-sized buffer. */
internal fun samplePcmWaveformEnvelopeProgressive(
    layout: WavPcmLayout,
    pass: RangeWaveformPass,
    readPcm: (first: Long, frames: Int, scratch: ByteArray) -> Unit,
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
            readPcm(startFrame, readFrames, scratch)
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

internal fun readFlacPcm16MonoRange(
    decoder: FlacPcmDecoder, startSeconds: Double, endSeconds: Double, targetRate: Int,
): ByteArray {
    require(targetRate > 0)
    val info = decoder.info
    val duration = info.frameCount.toDouble() / info.sampleRate
    val start = startSeconds.takeIf { it.isFinite() }?.coerceIn(0.0, duration) ?: return ByteArray(0)
    val end = endSeconds.takeIf { it.isFinite() }?.coerceIn(start, duration) ?: return ByteArray(0)
    if (end <= start) return ByteArray(0)
    val sourceStep = info.sampleRate.toDouble() / targetRate
    val radius = pcmResamplingRadius(sourceStep)
    val first = (floor(start * info.sampleRate).toLong() - radius).coerceIn(0L, info.frameCount - 1)
    val endFrame = minOf(info.frameCount, ceil(end * info.sampleRate).toLong() + 1 + radius)
    val frameCount = endFrame - first
    require(frameCount <= 16 * 1024 * 1024 / (info.channelCount * 2)) { "FLAC preview is too large" }
    val count = ceil((end - start) * targetRate).toLong()
    require(count in 1..8_388_608L) { "FLAC preview is too large" }
    val input = decoder.range(first, frameCount.toInt())
    val output = ByteArray(count.toInt() * 2)
    fun sample(frame: Int): Float {
        val base = frame.coerceIn(0, frameCount.toInt() - 1) * info.channelCount * 2
        var sum = 0f
        repeat(info.channelCount) { c ->
            val offset = base + c * 2
            sum += (((input[offset].toInt() and 255) or (input[offset + 1].toInt() shl 8)).toShort().toInt()).toFloat()
        }
        return sum / info.channelCount
    }
    val converter = PcmRateConverter(frameCount.toInt(), sourceStep) { frame, _ -> sample(frame) }
    repeat(count.toInt()) { i ->
        val at = start * info.sampleRate - first + i.toDouble() * info.sampleRate / targetRate
        val value = converter.sample(at, 0).roundToInt().coerceIn(-32768, 32767)
        output[i * 2] = value.toByte(); output[i * 2 + 1] = (value shr 8).toByte()
    }
    return output
}
