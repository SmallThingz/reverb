package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WavAudioFileWriterTest {
    @Test
    fun payloadWriteFailure_closePreservesPhysicallyWrittenUnverifiedPrefix() {
        val parent = File("build/tmp/wav-writer-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "partial-write-").toFile()
        val file = File(root, "partial.wav")
        try {
            val output = PrefixThenFailWavOutput(
                file = file,
                payloadOffset = 44L,
                prefixBytesBeforeFailure = 7,
            )
            val target = RecordingOutputTarget(
                id = file.path,
                displayName = file.name,
                mimeType = "audio/wav",
                storageType = RecordingStorageType.FILE,
                directoryId = root.path,
                startedAtMillis = 1L,
                file = file,
                staging = true,
            )
            val writer = WavAudioFileWriter(
                target = target,
                sampleRate = 8_000,
                channelCount = 1,
                sampleFormat = PcmSampleFormat.PCM_16,
                output = output,
            )
            val payload = ByteArray(8) { index -> (index * 17 + 3).toByte() }

            val writeFailure = assertThrows(IOException::class.java) {
                writer.write(payload, 0, payload.size)
            }
            assertTrue(writeFailure.message?.contains("Injected WAV payload failure") == true)

            val beforeClose = file.readBytes()
            assertEquals(44 + 7, beforeClose.size)
            assertArrayEquals(payload.copyOf(7), beforeClose.copyOfRange(44, beforeClose.size))

            val closeFailure = assertThrows(IOException::class.java) { writer.close() }
            assertTrue(closeFailure.message?.contains("preserving unverified staging bytes") == true)
            assertTrue(closeFailure.cause === writeFailure)

            val afterClose = file.readBytes()
            assertEquals(
                "Failed payload staging must remain byte-for-byte untouched by close",
                beforeClose.size,
                afterClose.size,
            )
            assertArrayEquals(beforeClose, afterClose)
        } finally {
            root.deleteRecursively()
        }
    }

    private class PrefixThenFailWavOutput(
        file: File,
        private val payloadOffset: Long,
        private val prefixBytesBeforeFailure: Int,
    ) : WavSeekableOutput {
        private val access = RandomAccessFile(file, "rw")
        private val channel: FileChannel = access.channel
        private var payloadPrefixReturned = false

        override fun position(position: Long) {
            channel.position(position)
        }

        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() < payloadOffset) {
                return channel.write(buffer)
            }
            if (!payloadPrefixReturned) {
                val allowed = minOf(prefixBytesBeforeFailure, buffer.remaining())
                val previousLimit = buffer.limit()
                buffer.limit(buffer.position() + allowed)
                val written = try {
                    channel.write(buffer)
                } finally {
                    buffer.limit(previousLimit)
                }
                payloadPrefixReturned = true
                return written
            }
            throw IOException("Injected WAV payload failure")
        }

        override fun truncate(size: Long) {
            channel.truncate(size)
        }

        override fun force(metadata: Boolean) {
            channel.force(metadata)
        }

        override fun close() {
            access.close()
        }
    }

    @Test
    fun invalidConfiguration_doesNotAcquireWritableOutput() {
        var opened = false

        assertThrows(IllegalArgumentException::class.java) {
            openAfterWavConfigurationValidation(
                sampleRate = 0,
                channelCount = 1,
                sampleFormat = PcmSampleFormat.PCM_16,
            ) {
                opened = true
                Any()
            }
        }
        assertFalse(opened)

        assertThrows(IllegalArgumentException::class.java) {
            openAfterWavConfigurationValidation(
                sampleRate = 48_000,
                channelCount = 3,
                sampleFormat = PcmSampleFormat.PCM_16,
            ) {
                opened = true
                Any()
            }
        }
        assertFalse(opened)
    }

}
