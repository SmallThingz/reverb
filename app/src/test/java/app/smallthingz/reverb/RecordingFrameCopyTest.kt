package app.smallthingz.reverb

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RecordingFrameCopyTest {
    private class Writer(val events: MutableList<String> = mutableListOf()) : RecordingAudioWriter {
        val bytes = ByteArrayOutputStream()
        var writeFailure: IOException? = null
        var closeFailure: IOException? = null
        override val totalSampleBytesWritten get() = bytes.size().toLong()
        override val totalFileBytesWritten get() = totalSampleBytesWritten
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            events += "write:$count"
            writeFailure?.let { throw it }
            this.bytes.write(bytes, offset, count)
        }
        override fun close() { events += "close"; closeFailure?.let { throw it } }
        override fun verify(context: Context, checkCancelled: () -> Unit): StableOutputFingerprint =
            error("Copying must not acquire verification/publication authority")
    }

    @Test fun actualCopyPreservesSelectedBytesAcrossBufferBoundaryAndFormats() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val frameBytes = format.bytesPerSample * channels
            val original = ByteArray(140_003 * frameBytes) { (it * 31).toByte() }
            withSource(original) { source ->
                val writer = Writer()
                source.inputStream().channel.use { channel ->
                    copyRecordingFrames(channel, WavPcmLayout(44_100, channels, format, 0, original.size.toLong()),
                        false, RecordingConversionPlan(221, 139_781, format), writer)
                    assertTrue(channel.isOpen)
                }
                assertArrayEquals(original.copyOfRange(221 * frameBytes, 140_002 * frameBytes), writer.bytes.toByteArray())
                assertEquals(1, writer.events.count { it == "close" })
                assertArrayEquals(original, source.readBytes())
            }
        }
    }

    @Test fun pcm8ConversionKeepsUnsignedMidpointAndLittleEndianSamples() {
        withSource(byteArrayOf(0, 127, 128.toByte(), 255.toByte())) { source ->
            val writer = Writer()
            source.inputStream().channel.use { channel ->
                copyRecordingFrames(channel, WavPcmLayout(8_000, 1, PcmSampleFormat.PCM_8, 0, 4),
                    false, RecordingConversionPlan(0, 4, PcmSampleFormat.PCM_16), writer)
            }
            assertArrayEquals(byteArrayOf(0, -128, 0, -1, 0, 0, 0, 127), writer.bytes.toByteArray())
        }
    }

    @Test fun cancellationBeforeWriteAndAfterLastWriteBothCloseWithoutVerification() {
        for (cancelAt in 1..2) withSource(byteArrayOf(1, 2)) { source ->
            val events = mutableListOf<String>()
            val writer = Writer(events)
            val failure = IOException("cancel")
            var checks = 0
            source.inputStream().channel.use { channel ->
                assertSame(failure, assertThrows(IOException::class.java) {
                    copyRecordingFrames(channel, WavPcmLayout(8_000, 1, PcmSampleFormat.PCM_16, 0, 2),
                        false, RecordingConversionPlan(0, 1, PcmSampleFormat.PCM_16), writer) {
                        events += "check"; if (++checks == cancelAt) throw failure
                    }
                })
                assertTrue(channel.isOpen)
            }
            assertEquals(if (cancelAt == 1) listOf("check", "close")
                else listOf("check", "write:2", "check", "close"), events)
        }
    }

    @Test fun writeFailureRemainsPrimaryWhenCloseAlsoFails() {
        withSource(byteArrayOf(1, 2)) { source ->
            val writer = Writer()
            val primary = IOException("write").also { writer.writeFailure = it }
            val cleanup = IOException("close").also { writer.closeFailure = it }
            source.inputStream().channel.use { channel ->
                assertSame(primary, assertThrows(IOException::class.java) {
                    copyRecordingFrames(channel, WavPcmLayout(8_000, 1, PcmSampleFormat.PCM_16, 0, 2),
                        false, RecordingConversionPlan(0, 1, PcmSampleFormat.PCM_16), writer)
                })
            }
            assertArrayEquals(arrayOf(cleanup), primary.suppressed)
            assertEquals(listOf("write:2", "close"), writer.events)
        }
    }

    private fun withSource(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("frame-copy-", ".pcm")
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
}
