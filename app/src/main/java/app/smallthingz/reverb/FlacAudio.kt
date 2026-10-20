package app.smallthingz.reverb

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.roundToLong

internal const val DEFAULT_FLAC_COMPRESSION = 5
internal const val DEFAULT_QUICK_SAVE_SECONDS = 120
internal fun validFlacCompression(level: Int): Boolean = level in 0..8
internal fun validQuickSaveSeconds(seconds: Int): Boolean = seconds in 1..86_400

internal fun flacPcmChecksumMatches(expected: ByteArray, actual: ByteArray): Boolean =
    expected.all { it == 0.toByte() } || expected.contentEquals(actual)

internal data class FlacInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val frameCount: Long,
    val md5: ByteArray,
    val headerBytes: Int,
    val compressionLevel: Int?,
) {
    val durationMillis: Long get() = frameCount * 1000L / sampleRate
    val pcmLayout: WavPcmLayout get() = WavPcmLayout(
        sampleRate, channelCount, PcmSampleFormat.PCM_16, 0L, frameCount * channelCount * 2L,
    )
}

internal class InvalidFlacException(message: String) : IOException(message)

private fun InputStream.exact(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var position = 0
    while (position < count) {
        val n = read(bytes, position, count - position)
        if (n < 0) throw InvalidFlacException("Truncated FLAC metadata")
        if (n == 0) {
            val b = read()
            if (b < 0) throw InvalidFlacException("Truncated FLAC metadata")
            bytes[position++] = b.toByte()
        } else position += n
    }
    return bytes
}

internal fun readFlacInfo(input: InputStream): FlacInfo {
    if (!input.exact(4).contentEquals(byteArrayOf(102, 76, 97, 67))) throw InvalidFlacException("Not a FLAC file")
    var info: FlacInfo? = null
    var total = 4
    var compression: Int? = null
    var sawComments = false
    var blocks = 0
    do {
        if (++blocks > 64) throw InvalidFlacException("Too many FLAC metadata blocks")
        val head = input.exact(4)
        val type = head[0].toInt() and 127
        val size = ((head[1].toInt() and 255) shl 16) or ((head[2].toInt() and 255) shl 8) or (head[3].toInt() and 255)
        if (size > 1_048_576 || total > 4_194_304 - size - 4) throw InvalidFlacException("FLAC metadata exceeds limit")
        val data = input.exact(size)
        total += 4 + size
        if (blocks == 1 && (type != 0 || size != 34)) throw InvalidFlacException("FLAC STREAMINFO missing")
        if (type == 0) {
            if (info != null || size != 34) throw InvalidFlacException("Invalid FLAC STREAMINFO")
            val packed = ByteBuffer.wrap(data, 10, 8).order(ByteOrder.BIG_ENDIAN).long
            val rate = (packed ushr 44).toInt()
            val channels = ((packed ushr 41) and 7).toInt() + 1
            val bits = ((packed ushr 36) and 31).toInt() + 1
            val frames = packed and 0xF_FFFF_FFFFL
            val minBlock = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
            val maxBlock = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
            if (rate !in 1..655350 || channels !in 1..2 || bits != 16 || frames <= 0 ||
                minBlock < 16 || maxBlock < minBlock) throw InvalidFlacException("Unsupported or incomplete FLAC stream")
            info = FlacInfo(rate, channels, bits, frames, data.copyOfRange(18, 34), total, null)
        } else if (type == 4) {
            if (sawComments) throw InvalidFlacException("Duplicate FLAC comment block")
            sawComments = true
            compression = readFlacCompressionComment(data)
        } else if (type == 127) throw InvalidFlacException("Invalid FLAC metadata type")
    } while ((head[0].toInt() and 128) == 0)
    return requireNotNull(info).copy(headerBytes = total, compressionLevel = compression)
}

internal fun readFlacCompressionComment(data: ByteArray): Int? {
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    fun length(): Int {
        if (buffer.remaining() < 4) throw InvalidFlacException("Truncated FLAC comment")
        val size = buffer.int
        if (size < 0 || size > buffer.remaining()) throw InvalidFlacException("Invalid FLAC comment size")
        return size
    }
    val vendorLength = length()
    buffer.position(buffer.position() + vendorLength)
    if (buffer.remaining() < 4) throw InvalidFlacException("Missing FLAC comment count")
    val count = buffer.int
    if (count < 0 || count > buffer.remaining() / 4) throw InvalidFlacException("Invalid FLAC comment count")
    var level: Int? = null
    var matchingFields = 0
    repeat(count) {
        val comment = ByteArray(length())
        buffer.get(comment)
        val text = String(comment, Charsets.UTF_8)
        val separator = text.indexOf('=')
        if (separator >= 0 && text.substring(0, separator).equals("REVERB_COMPRESSION_LEVEL", ignoreCase = true)) {
            matchingFields++
            val value = text.substring(separator + 1)
            level = value.singleOrNull()?.takeIf { it in '0'..'8' }?.digitToInt()
        }
    }
    if (buffer.hasRemaining()) throw InvalidFlacException("Trailing FLAC comment bytes")
    return level.takeIf { matchingFields == 1 }
}

internal fun channelHasFlac(channel: FileChannel): Boolean {
    if (channel.size() < 4) return false
    val bytes = ByteArray(4)
    requireReadAt(channel, 0, bytes, 4)
    return bytes.contentEquals(byteArrayOf(102, 76, 97, 67))
}

internal fun readFlacInfo(channel: FileChannel): FlacInfo {
    val old = channel.position()
    return try {
        channel.position(0)
        readFlacInfo(Channels.newInputStream(channel))
    } finally { channel.position(old) }
}

private fun flacComment(level: Int): ByteArray {
    require(validFlacCompression(level))
    val vendor = "Reverb".toByteArray()
    val comment = "REVERB_COMPRESSION_LEVEL=$level".toByteArray()
    val payload = ByteBuffer.allocate(12 + vendor.size + comment.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(vendor.size).put(vendor).putInt(1).putInt(comment.size).put(comment).array()
    return byteArrayOf(0x84.toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
}

/** MediaCodec owns the compression algorithm. PCM and encoded payloads stay bounded and streaming. */
internal class FlacAudioFileWriter(
    private val context: Context,
    private val target: RecordingOutputTarget,
    private val sampleRate: Int,
    private val channels: Int,
    private val level: Int,
) : RecordingAudioWriter {
    private val codec: MediaCodec
    private val output: WavSeekableOutput
    private val bufferInfo = MediaCodec.BufferInfo()
    private val pcmMd5 = MessageDigest.getInstance("MD5")
    private val encodedDigest = MessageDigest.getInstance("SHA-256")
    private var finalEncodedDigest: ByteArray? = null
    private var prefix: ByteArray? = null
    private var encodedBytes = 0L
    private var eos = false
    private var closed = false
    private val finalizer = FlacEncoderFinalizer()
    private var failure: Throwable? = null
    override var totalSampleBytesWritten = 0L
        private set
    override val totalFileBytesWritten: Long get() = requireNotNull(prefix).size + encodedBytes

    init {
        require(validFlacCompression(level))
        require(sampleRate in 1..655350 && channels in 1..2)
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, level)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            output = openWavSeekableOutput(context, target, sampleRate, channels, PcmSampleFormat.PCM_16)
            codec = created
        } catch (error: Throwable) {
            closePreservingPrimaryFailure(error) { created.release() }
            throw error
        }
    }

    private fun writeAll(bytes: ByteArray) {
        val b = ByteBuffer.wrap(bytes)
        while (b.hasRemaining()) {
            checkFlacExportNotInterrupted()
            if (output.write(b) <= 0) throw IOException("FLAC output stalled")
        }
    }

    private fun config(bytes: ByteArray) {
        if (prefix != null) return
        val stream = when {
            bytes.size == 34 -> bytes
            bytes.size >= 42 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(102, 76, 97, 67)) -> bytes.copyOfRange(8, 42)
            else -> throw IOException("Invalid FLAC encoder configuration")
        }
        prefix = byteArrayOf(102, 76, 97, 67, 0, 0, 0, 34) + stream + flacComment(level)
        writeAll(requireNotNull(prefix))
    }

    private fun drain(waitUs: Long): Boolean {
        val index = codec.dequeueOutputBuffer(bufferInfo, waitUs)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            codec.outputFormat.getByteBuffer("csd-0")?.let { b ->
                val copy = ByteArray(b.remaining()); b.duplicate().get(copy); config(copy)
            }
            return true
        }
        if (index < 0) return false
        try {
            val b = codec.getOutputBuffer(index) ?: throw IOException("Missing FLAC encoder output")
            b.position(bufferInfo.offset); b.limit(bufferInfo.offset + bufferInfo.size)
            val bytes = ByteArray(bufferInfo.size); b.get(bytes)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                config(bytes)
            } else if (bytes.isNotEmpty()) {
                check(prefix != null) { "FLAC encoder omitted STREAMINFO" }
                writeAll(bytes); encodedDigest.update(bytes); encodedBytes += bytes.size
            }
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
        } finally { codec.releaseOutputBuffer(index, false) }
        return true
    }

    private fun inputIndex(): Int {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (true) {
            checkFlacExportNotInterrupted()
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(10_000)
            if (System.nanoTime() > deadline) throw IOException("FLAC encoder timed out")
        }
    }

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        check(!closed && failure == null)
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count && count % (channels * 2) == 0)
        try {
            var position = offset
            while (position < offset + count) {
                val index = inputIndex()
                val input = codec.getInputBuffer(index) ?: throw IOException("Missing FLAC encoder input")
                input.clear()
                val amount = minOf(input.remaining(), offset + count - position) / (channels * 2) * (channels * 2)
                if (amount <= 0) throw IOException("FLAC encoder input is too small")
                input.put(bytes, position, amount)
                codec.queueInputBuffer(index, 0, amount, totalSampleBytesWritten / (channels * 2) * 1_000_000L / sampleRate, 0)
                pcmMd5.update(bytes, position, amount)
                totalSampleBytesWritten += amount
                position += amount
                while (drain(0)) { checkFlacExportNotInterrupted() }
            }
        } catch (error: Throwable) { failure = error; throw error }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            finalizer.close(
                previousFailure = failure,
                queueEnd = {
                    val index = inputIndex()
                    codec.queueInputBuffer(index, 0, 0, totalSampleBytesWritten / (channels * 2) * 1_000_000L / sampleRate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                },
                endOfStream = { eos },
                drain = { drain(10_000) },
                finalizeOutput = {
                    val frames = totalSampleBytesWritten / (channels * 2)
                    if (frames !in 1..0xF_FFFF_FFFFL || encodedBytes <= 0) throw IOException("FLAC output has no complete audio")
                    val header = requireNotNull(prefix)
                    val packed = (sampleRate.toLong() shl 44) or ((channels - 1L) shl 41) or (15L shl 36) or frames
                    ByteBuffer.wrap(header, 18, 8).order(ByteOrder.BIG_ENDIAN).putLong(packed)
                    pcmMd5.digest().copyInto(header, 26)
                    checkFlacExportNotInterrupted()
                    output.position(0); writeAll(header)
                    checkFlacExportNotInterrupted()
                    output.truncate(totalFileBytesWritten)
                    checkFlacExportNotInterrupted()
                    output.force(true)
                    checkFlacExportNotInterrupted()
                    finalEncodedDigest = encodedDigest.digest()
                },
                releaseCodec = { codec.release() },
                closeOutput = { output.close() },
            )
        } catch (error: Throwable) { failure = error; throw error }
    }

    override fun verify(context: Context, checkCancelled: () -> Unit): StableOutputFingerprint {
        check(closed && failure == null)
        val header = requireNotNull(prefix)
        val fingerprint = verifyWavOutputTargetAndDigest(context, target, totalFileBytesWritten, header,
            header.size.toLong(), encodedBytes, requireNotNull(finalEncodedDigest), checkCancelled = checkCancelled)
        val identity = fingerprint.fileKey ?: fingerprint.providerIdentity.orEmpty()
        val provisional = buildRecordingEntity(context, target, 1, "FLAC", totalFileBytesWritten).copy(fileIdentity = identity)
        val info = withRecordingWavChannel(context, provisional, RecordingReadCleanup.Propagate) { channel ->
            verifyFlacPcm(channel, expectedPcmMd5 = header.copyOfRange(26, 42), checkCancelled = checkCancelled)
        }
        checkCancelled()
        VerifiedFlacMetadataCache.remember(fingerprint.digest, info)
        return fingerprint
    }
}

private class ChannelMediaSource(private val channel: FileChannel) : MediaDataSource() {
    override fun getSize(): Long = channel.size()
    override fun close() = Unit // The identity-bound outer owner closes the descriptor.
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) throw IOException("Invalid media read")
        if (size == 0) return 0
        if (position >= channel.size()) return -1
        return channel.read(ByteBuffer.wrap(buffer, offset, minOf(size.toLong(), channel.size() - position).toInt()), position)
    }
}

internal interface FlacPcmVerificationSource : Closeable {
    val info: FlacInfo
    fun readFrames(start: Long, count: Long, consume: (ByteArray, Int, Int) -> Unit)
}

internal class FlacPcmDecoder(
    private val channel: FileChannel,
    setupCleanup: RecordingReadCleanup,
) : FlacPcmVerificationSource {
    override val info = readFlacInfo(channel)
    private val extractor = MediaExtractor()
    private val codec: MediaCodec?
    // A waveform pass seeks once per bucket; retain bounded raw buffers across those reads.
    private val rawBuffer by lazy { ByteBuffer.allocate(65536 * info.channelCount * 2) }
    private val rawScratch by lazy { ByteArray(rawBuffer.capacity()) }
    private var rawBlockStart = -1L
    private var rawBlockFrames = 0
    internal var rawBlockDecodeCount = 0L
        private set
    private var used = false
    private var closed = false
    init {
        var created: MediaCodec? = null
        try {
            extractor.setDataSource(ChannelMediaSource(channel))
            if (extractor.trackCount != 1) throw IOException("Unexpected FLAC tracks")
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
            extractor.selectTrack(0)
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                // Android's native FLAC extractor can expose already-decoded PCM.
                validatePcmFormat(format)
                codec = null
            } else {
                if (mime != MediaFormat.MIMETYPE_AUDIO_FLAC) throw IOException("Unexpected FLAC extractor format: $mime")
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
                created.configure(format, null, null, 0); created.start(); codec = created
            }
        } catch (error: Throwable) {
            setupCleanup.closeAfterFailure(error) { created?.release() }
            setupCleanup.closeAfterFailure(error) { extractor.release() }
            throw error
        }
    }

    override fun readFrames(start: Long, count: Long, consume: (ByteArray, Int, Int) -> Unit) {
        check(!closed)
        require(start >= 0 && count >= 0 && start <= info.frameCount - count)
        if (count == 0L) return
        val codec = codec ?: return try {
            readRawFrames(start, count, consume)
        } catch (error: Throwable) {
            rawBlockStart = -1L
            rawBlockFrames = 0
            throw error
        }
        if (used) codec.flush()
        used = true
        extractor.seekTo(start * 1_000_000L / info.sampleRate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnd = false
        var outputEnd = false
        var copied = 0L
        var deadline = System.nanoTime() + 30_000_000_000L
        val wholeFile = start == 0L && count == info.frameCount
        while (!outputEnd && (copied < count || wholeFile)) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("FLAC read cancelled")
            var progress = false
            if (!inputEnd) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index) ?: throw IOException("Missing FLAC decoder input")
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    inputEnd = size < 0
                    codec.queueInputBuffer(index, 0, maxOf(0, size), if (inputEnd) 0 else extractor.sampleTime,
                        if (inputEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (!inputEnd) extractor.advance()
                    progress = true
                }
            }
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val output = codec.outputFormat
                validatePcmFormat(output)
                progress = true
            } else if (index >= 0) {
                try {
                    val output = codec.getOutputBuffer(index) ?: throw IOException("Missing FLAC decoder output")
                    val frameBytes = info.channelCount * 2
                    if (bufferInfo.size % frameBytes != 0) throw IOException("Unaligned FLAC decoder output")
                    val first = (bufferInfo.presentationTimeUs.toDouble() * info.sampleRate / 1_000_000.0).roundToLong()
                    val available = bufferInfo.size / frameBytes
                    if (first < 0 || first > info.frameCount - available) throw IOException("FLAC exceeds its declared sample count")
                    val skip = (start + copied - first).coerceIn(0, available.toLong()).toInt()
                    if (first > start + copied && available > 0) throw IOException("FLAC decoder skipped audio")
                    val take = minOf((available - skip).toLong(), count - copied).toInt()
                    if (take > 0) {
                        output.position(bufferInfo.offset + skip * frameBytes)
                        output.limit(bufferInfo.offset + (skip + take) * frameBytes)
                        val bytes = ByteArray(take * frameBytes); output.get(bytes)
                        consume(bytes, 0, bytes.size); copied += take
                    }
                    outputEnd = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                } finally { codec.releaseOutputBuffer(index, false) }
                progress = true
            }
            if (progress) deadline = System.nanoTime() + 30_000_000_000L
            else if (System.nanoTime() > deadline) throw IOException("FLAC decoder timed out")
        }
        if (copied != count) throw IOException("Truncated FLAC audio: $copied of $count frames")
    }

    private fun validatePcmFormat(format: MediaFormat) {
        if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != info.sampleRate ||
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != info.channelCount ||
            (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)) {
            throw IOException("FLAC decoder changed PCM precision or geometry: $format")
        }
    }

    internal fun hasRetainedRawFrames(start: Long, count: Long): Boolean =
        !(start == 0L && count == info.frameCount) && rawBlockStart >= 0L &&
            start >= rawBlockStart && count >= 0L &&
            count <= rawBlockFrames.toLong() - (start - rawBlockStart)

    private fun readRawFrames(start: Long, count: Long, consume: (ByteArray, Int, Int) -> Unit) {
        val frameBytes = info.channelCount * 2
        // The maximum FLAC block has 65535 samples. Do not allocate from a supplied size.
        val buffer = rawBuffer
        val scratch = rawScratch
        // A partial read leaves its complete decoded block here. Nearby waveform probes can
        // reuse it, but a whole-file verification must still inspect the extractor's terminal.
        if (hasRetainedRawFrames(start, count)) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("FLAC read cancelled")
            val offset = (start - rawBlockStart).toInt() * frameBytes
            val bytes = count.toInt() * frameBytes
            buffer.limit(offset + bytes)
            buffer.position(offset)
            buffer.get(scratch, 0, bytes)
            consume(scratch, 0, bytes)
            return
        }
        rawBlockStart = -1L
        rawBlockFrames = 0
        extractor.seekTo(start * 1_000_000L / info.sampleRate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var copied = 0L
        var more = true
        while (copied < count && more) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("FLAC read cancelled")
            buffer.clear()
            val stamp = extractor.sampleTime
            val bytes = extractor.readSampleData(buffer, 0)
            if (bytes < 0 || stamp < 0) break
            rawBlockDecodeCount++
            if (bytes == 0 || bytes % frameBytes != 0) throw IOException("Invalid decoded FLAC block")
            val first = (stamp.toDouble() * info.sampleRate / 1_000_000.0).roundToLong()
            val available = bytes / frameBytes
            if (first < 0 || first > info.frameCount - available) throw IOException("FLAC exceeds its declared sample count")
            if (first > start + copied) throw IOException("FLAC extractor skipped audio")
            rawBlockStart = first
            rawBlockFrames = available
            val skip = (start + copied - first).coerceIn(0, available.toLong()).toInt()
            val take = minOf((available - skip).toLong(), count - copied).toInt()
            if (take > 0) {
                buffer.position(skip * frameBytes); buffer.limit((skip + take) * frameBytes)
                buffer.get(scratch, 0, take * frameBytes)
                consume(scratch, 0, take * frameBytes)
                copied += take
            }
            more = extractor.advance()
        }
        if (copied != count) throw IOException("Truncated FLAC audio: $copied of $count frames")
        if (start == 0L && count == info.frameCount && more) throw IOException("FLAC has undeclared trailing samples")
    }

    fun range(start: Long, count: Int): ByteArray {
        require(count in 0..(16 * 1024 * 1024 / (info.channelCount * 2))) { "FLAC preview range is too large" }
        val output = ByteArray(count * info.channelCount * 2)
        var position = 0
        readFrames(start, count.toLong()) { bytes, offset, length -> bytes.copyInto(output, position, offset, offset + length); position += length }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        var error = closePreservingPrimaryFailure(null) { codec?.release() }
        error = closePreservingPrimaryFailure(error) { extractor.release() }
        if (error != null) throw error
    }
}

internal fun verifyFlacPcm(
    channel: FileChannel,
    expectedPcmMd5: ByteArray? = null,
    checkCancelled: () -> Unit = {},
): FlacInfo = verifyFlacPcmSource(
    FlacPcmDecoder(channel, RecordingReadCleanup.Propagate), expectedPcmMd5, checkCancelled,
)

internal fun verifyFlacPcmSource(
    source: FlacPcmVerificationSource,
    expectedPcmMd5: ByteArray? = null,
    checkCancelled: () -> Unit = {},
): FlacInfo {
    val (info, actual) = source.use { decoder ->
        val md5 = MessageDigest.getInstance("MD5")
        checkCancelled()
        decoder.readFrames(0, decoder.info.frameCount) { bytes, offset, count ->
            checkCancelled()
            md5.update(bytes, offset, count)
        }
        checkCancelled()
        decoder.info to md5.digest()
    }
    checkCancelled()
    // Discovery may skip invalid content only after every read and native release succeeds.
    // An explicit export checksum remains a failed output verification, not invalid discovery.
    if (expectedPcmMd5 != null) {
        if (!expectedPcmMd5.contentEquals(actual)) throw IOException("FLAC decoded audio checksum mismatch")
    } else if (!flacPcmChecksumMatches(info.md5, actual)) {
        throw InvalidFlacException("FLAC decoded audio checksum mismatch")
    }
    return info
}
