package app.smallthingz.reverb

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal class WavAudioFileWriter(
    context: Context,
    val target: RecordingOutputTarget,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
) : Closeable {
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
    private val parcelFileDescriptor: ParcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
    private val outputStream = FileOutputStream(parcelFileDescriptor.fileDescriptor)
    private val channel: FileChannel = outputStream.channel
    private val headerSize = if (sampleFormat == PcmSampleFormat.PCM_FLOAT) FLOAT_HEADER_SIZE else PCM_HEADER_SIZE
    private val headerBuffer = ByteBuffer.allocate(headerSize)
    @Volatile
    var totalSampleBytesWritten: Long = 0
        private set
    val totalFileBytesWritten: Long
        get() = headerSize.toLong() + paddedDataSize(totalSampleBytesWritten)

    init {
        try {
            writeHeader(dataSize = 0)
        } catch (e: Exception) {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
            throw e
        }
    }

    @Synchronized
    fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
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
        while (buf.hasRemaining()) {
            val n = channel.write(buf)
            if (n <= 0) throw IOException("Failed to write WAV data")
        }
        totalSampleBytesWritten += count.toLong()
    }

    @Synchronized
    override fun close() {
        try {
            val paddedDataSize = paddedDataSize(totalSampleBytesWritten)
            if (paddedDataSize != totalSampleBytesWritten) {
                channel.position(headerSize.toLong() + totalSampleBytesWritten)
                val padding = ByteBuffer.wrap(byteArrayOf(0))
                while (padding.hasRemaining()) {
                    if (channel.write(padding) <= 0) throw IOException("Failed to pad WAV data")
                }
            }
            writeHeader(totalSampleBytesWritten)
            channel.truncate(headerSize.toLong() + paddedDataSize)
            channel.force(true)
        } finally {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    @Synchronized
    private fun writeHeader(dataSize: Long) {
        val chunkSize = headerSize.toLong() - 8L + paddedDataSize(dataSize)
        headerBuffer.clear()
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.put(RIFF_BYTES)
        headerBuffer.putInt((chunkSize and 0xFFFF_FFFFL).toInt())
        headerBuffer.put(WAVE_BYTES)
        headerBuffer.put(FMT_BYTES)
        headerBuffer.putInt(if (sampleFormat == PcmSampleFormat.PCM_FLOAT) FLOAT_FMT_SIZE else PCM_FMT_SIZE)
        headerBuffer.putShort(sampleFormat.wavFormatTag)
        headerBuffer.putShort(channelCount.toShort())
        headerBuffer.putInt(sampleRate)
        headerBuffer.putInt(byteRate)
        headerBuffer.putShort(blockAlign)
        headerBuffer.putShort(sampleFormat.bitsPerSample.toShort())
        if (sampleFormat == PcmSampleFormat.PCM_FLOAT) {
            headerBuffer.putShort(0)
            headerBuffer.put(FACT_BYTES)
            headerBuffer.putInt(FACT_DATA_SIZE)
            headerBuffer.putInt((dataSize / (blockAlign.toInt() and 0xFFFF).toLong()).toInt())
        }
        headerBuffer.put(DATA_BYTES)
        headerBuffer.putInt((dataSize and 0xFFFF_FFFFL).toInt())
        headerBuffer.flip()
        channel.position(0L)
        while (headerBuffer.hasRemaining()) {
            val written = channel.write(headerBuffer)
            if (written <= 0) throw IOException("Failed to write WAV header")
        }
    }

    private val maxPayloadBytes: Long
        get() = 0xFFFF_FFFFL - headerSize.toLong()

    private fun paddedDataSize(dataSize: Long): Long = dataSize + (dataSize and 1L)

    private companion object {
        const val PCM_HEADER_SIZE = 44
        const val FLOAT_HEADER_SIZE = 58
        const val PCM_FMT_SIZE = 16
        const val FLOAT_FMT_SIZE = 18
        const val FACT_DATA_SIZE = 4
        private val RIFF_BYTES = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        private val WAVE_BYTES = byteArrayOf(0x57, 0x41, 0x56, 0x45)
        private val FMT_BYTES = byteArrayOf(0x66, 0x6D, 0x74, 0x20)
        private val FACT_BYTES = byteArrayOf(0x66, 0x61, 0x63, 0x74)
        private val DATA_BYTES = byteArrayOf(0x64, 0x61, 0x74, 0x61)
    }
}
