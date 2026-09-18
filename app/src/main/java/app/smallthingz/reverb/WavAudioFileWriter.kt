package app.smallthingz.reverb

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

private const val WAV_PCM_HEADER_SIZE = 44
private const val WAV_FLOAT_HEADER_SIZE = 58
private const val WAV_PCM_FMT_SIZE = 16
private const val WAV_FLOAT_FMT_SIZE = 18
private const val WAV_FACT_DATA_SIZE = 4
private val WAV_RIFF_BYTES = byteArrayOf(0x52, 0x49, 0x46, 0x46)
private val WAV_WAVE_BYTES = byteArrayOf(0x57, 0x41, 0x56, 0x45)
private val WAV_FMT_BYTES = byteArrayOf(0x66, 0x6D, 0x74, 0x20)
private val WAV_FACT_BYTES = byteArrayOf(0x66, 0x61, 0x63, 0x74)
private val WAV_DATA_BYTES = byteArrayOf(0x64, 0x61, 0x74, 0x61)

internal fun buildWavHeaderBytes(
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat,
    dataSize: Long,
): ByteArray {
    require(sampleRate > 0)
    require(channelCount in 1..2)
    require(dataSize >= 0L)
    val blockAlign = channelCount * sampleFormat.bytesPerSample
    require(blockAlign in 1..0xFFFF)
    val byteRate = sampleRate.toLong() * blockAlign.toLong()
    require(byteRate in 1..0xFFFF_FFFFL)
    val headerSize = if (sampleFormat == PcmSampleFormat.PCM_FLOAT) WAV_FLOAT_HEADER_SIZE else WAV_PCM_HEADER_SIZE
    val paddedDataSize = dataSize + (dataSize and 1L)
    val chunkSize = headerSize.toLong() - 8L + paddedDataSize
    require(chunkSize <= 0xFFFF_FFFFL)

    val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(WAV_RIFF_BYTES)
    buffer.putInt((chunkSize and 0xFFFF_FFFFL).toInt())
    buffer.put(WAV_WAVE_BYTES)
    buffer.put(WAV_FMT_BYTES)
    buffer.putInt(if (sampleFormat == PcmSampleFormat.PCM_FLOAT) WAV_FLOAT_FMT_SIZE else WAV_PCM_FMT_SIZE)
    buffer.putShort(sampleFormat.wavFormatTag)
    buffer.putShort(channelCount.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(byteRate.toInt())
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(sampleFormat.bitsPerSample.toShort())
    if (sampleFormat == PcmSampleFormat.PCM_FLOAT) {
        buffer.putShort(0)
        buffer.put(WAV_FACT_BYTES)
        buffer.putInt(WAV_FACT_DATA_SIZE)
        buffer.putInt((dataSize / blockAlign.toLong()).toInt())
    }
    buffer.put(WAV_DATA_BYTES)
    buffer.putInt((dataSize and 0xFFFF_FFFFL).toInt())
    return buffer.array()
}

internal interface WavSeekableOutput : Closeable {
    fun position(position: Long)
    fun write(buffer: ByteBuffer): Int
    fun truncate(size: Long)
    fun force(metadata: Boolean)
}

private class ParcelWavSeekableOutput(
    private val stream: ParcelFileDescriptor.AutoCloseOutputStream,
) : WavSeekableOutput {
    private val channel: FileChannel = stream.channel

    override fun position(position: Long) {
        channel.position(position)
    }

    override fun write(buffer: ByteBuffer): Int = channel.write(buffer)

    override fun truncate(size: Long) {
        channel.truncate(size)
    }

    override fun force(metadata: Boolean) {
        channel.force(metadata)
    }

    override fun close() {
        stream.close()
    }
}

internal inline fun <T> openAfterWavConfigurationValidation(
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat,
    open: () -> T,
): T {
    require(sampleRate > 0) { "Invalid WAV sample rate: $sampleRate" }
    require(channelCount in 1..2) { "Invalid WAV channel count: $channelCount" }
    val blockAlign = channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
    require(blockAlign in 1..0xFFFFL) { "Invalid WAV block alignment: $blockAlign" }
    val byteRate = sampleRate.toLong() * blockAlign
    require(byteRate in 1..0xFFFF_FFFFL) { "Invalid WAV byte rate: $byteRate" }
    return open()
}

private fun openWavSeekableOutput(
    context: Context,
    target: RecordingOutputTarget,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat,
): WavSeekableOutput = openAfterWavConfigurationValidation(
    sampleRate = sampleRate,
    channelCount = channelCount,
    sampleFormat = sampleFormat,
) {
    val stream = openChildOrCloseOwner(
        openWritableParcelFileDescriptor(context, target),
    ) { descriptor ->
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }
    ParcelWavSeekableOutput(stream)
}

internal class WavAudioFileWriter internal constructor(
    val target: RecordingOutputTarget,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    private val output: WavSeekableOutput,
) : Closeable {
    constructor(
        context: Context,
        target: RecordingOutputTarget,
        sampleRate: Int,
        channelCount: Int,
        sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    ) : this(
        target = target,
        sampleRate = sampleRate,
        channelCount = channelCount,
        sampleFormat = sampleFormat,
        output = openWavSeekableOutput(
            context = context,
            target = target,
            sampleRate = sampleRate,
            channelCount = channelCount,
            sampleFormat = sampleFormat,
        ),
    )
    private val blockAlign: Short = run {
        require(sampleRate > 0) { "Invalid WAV sample rate: $sampleRate" }
        require(channelCount in 1..2) { "Invalid WAV channel count: $channelCount" }
        val computed = channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
        require(computed in 1..0xFFFFL) { "Invalid WAV block alignment: $computed" }
        computed.toShort()
    }
    private val byteRate: Int = run {
        val computed = sampleRate.toLong() * (blockAlign.toInt() and 0xFFFF).toLong()
        require(computed in 1..0xFFFF_FFFFL) { "Invalid WAV byte rate: $computed" }
        computed.toInt()
    }
    private val headerSize = if (sampleFormat == PcmSampleFormat.PCM_FLOAT) WAV_FLOAT_HEADER_SIZE else WAV_PCM_HEADER_SIZE
    private val payloadDigest = MessageDigest.getInstance("SHA-256")
    private var finalizedPayloadDigest: ByteArray? = null
    private var payloadWriteFailure: Throwable? = null
    private var closed = false
    @Volatile
    var totalSampleBytesWritten: Long = 0
        private set
    val totalFileBytesWritten: Long
        get() = headerSize.toLong() + paddedDataSize(totalSampleBytesWritten)
    val payloadOffsetBytes: Long
        get() = headerSize.toLong()
    val payloadSha256: ByteArray
        get() = requireNotNull(finalizedPayloadDigest) { "WAV writer has not been durably closed" }.clone()
    val expectedHeaderBytes: ByteArray
        get() = buildWavHeaderBytes(sampleRate, channelCount, sampleFormat, totalSampleBytesWritten)

    init {
        try {
            writeHeader(dataSize = 0)
        } catch (error: Throwable) {
            throw requireNotNull(
                closePreservingPrimaryFailure(error) { output.close() },
            )
        }
    }

    @Synchronized
    fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        check(!closed) { "WAV writer is closed" }
        payloadWriteFailure?.let { failure ->
            throw IOException("WAV writer cannot continue after a payload write failure", failure)
        }
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count) {
            "Invalid WAV write range offset=$offset count=$count size=${bytes.size}"
        }
        if (count == 0) return
        val frameBytes = blockAlign.toInt() and 0xFFFF
        require(count % frameBytes == 0) {
            "WAV writes must contain complete frames: count=$count frameBytes=$frameBytes"
        }
        val nextDataSize = totalSampleBytesWritten + count.toLong()
        if (paddedDataSize(nextDataSize) > maxPayloadBytes) {
            throw IOException("WAV file exceeds RIFF size limit")
        }
        val buf = ByteBuffer.wrap(bytes, offset, count)
        try {
            while (buf.hasRemaining()) {
                val n = output.write(buf)
                if (n <= 0) throw IOException("Failed to write WAV data")
            }
        } catch (error: Throwable) {
            // FileChannel-style writes may have advanced the physical staging object before
            // surfacing a later failure. That prefix has no verified digest/header authority.
            // Poison this writer so close only releases the descriptor and never truncates or
            // rewrites those unverified bytes.
            if (payloadWriteFailure == null) payloadWriteFailure = error
            throw error
        }
        payloadDigest.update(bytes, offset, count)
        totalSampleBytesWritten += count.toLong()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        var failure: Throwable? = payloadWriteFailure?.let { writeFailure ->
            IOException(
                "WAV payload write failed; preserving unverified staging bytes without finalization",
                writeFailure,
            )
        }
        if (failure == null) {
            try {
                val paddedDataSize = paddedDataSize(totalSampleBytesWritten)
                if (paddedDataSize != totalSampleBytesWritten) {
                    output.position(headerSize.toLong() + totalSampleBytesWritten)
                    val padding = ByteBuffer.wrap(byteArrayOf(0))
                    while (padding.hasRemaining()) {
                        if (output.write(padding) <= 0) throw IOException("Failed to pad WAV data")
                    }
                }
                writeHeader(totalSampleBytesWritten)
                output.truncate(headerSize.toLong() + paddedDataSize)
                output.force(true)
            } catch (error: Throwable) {
                failure = error
            }
        }

        failure = closePreservingPrimaryFailure(failure) { output.close() }
        closed = true
        failure?.let { throw it }
        finalizedPayloadDigest = payloadDigest.digest()
    }

    @Synchronized
    private fun writeHeader(dataSize: Long) {
        val headerBuffer = ByteBuffer.wrap(buildWavHeaderBytes(sampleRate, channelCount, sampleFormat, dataSize))
        output.position(0L)
        while (headerBuffer.hasRemaining()) {
            val written = output.write(headerBuffer)
            if (written <= 0) throw IOException("Failed to write WAV header")
        }
    }

    private val maxPayloadBytes: Long
        get() = 0xFFFF_FFFFL - headerSize.toLong()

    private fun paddedDataSize(dataSize: Long): Long = dataSize + (dataSize and 1L)

}
