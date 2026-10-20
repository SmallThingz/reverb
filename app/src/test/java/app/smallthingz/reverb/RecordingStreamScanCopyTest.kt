package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecordingStreamScanCopyTest {
    @Test fun flacMagicUnderWavNameReachesSeekableCopyWithItsPrefixIntact() = fixture { directory ->
        val bytes = byteArrayOf(102, 76, 97, 67) + ByteArray(131_079) { (it * 31).toByte() }
        val source = PushbackInputStream(ByteArrayInputStream(bytes), 4)
        assertTrue(recordingStreamNeedsSeekableScan("clip.wav", source))
        withSeekableRecordingScanCopy(source, directory, maxBytes = bytes.size.toLong()) { channel ->
            assertEquals(bytes.size.toLong(), channel.size())
            assertTrue(channelHasFlac(channel))
            channel.position(0L)
            val target = ByteBuffer.allocate(bytes.size)
            while (target.hasRemaining()) assertTrue(channel.read(target) > 0)
            assertArrayEquals(bytes, target.array())
        }
    }

    @Test fun ordinaryWavKeepsStreamingValidationWithoutASeekableCopy() {
        val pcm = ByteArray(16_000)
        val bytes = buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
        val source = PushbackInputStream(ByteArrayInputStream(bytes), 4)
        assertFalse(recordingStreamNeedsSeekableScan("clip.WAV", source))
        assertEquals(1_000L, structurallyCompleteRecordingDurationMillis("clip.WAV", source))
    }

    @Test fun flacExtensionKeepsExistingStagingEvenWhenPayloadHasWavMagic() {
        val bytes = "RIFFanother format".toByteArray()
        val source = PushbackInputStream(ByteArrayInputStream(bytes), 4)
        assertTrue(recordingStreamNeedsSeekableScan("clip.FLAC", source))
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun fragmentedAndZeroLengthPrefixReadsPreserveEveryByte() {
        val bytes = byteArrayOf(102, 76, 97, 67, 9, 8, 7)
        val fragmented = object : ByteArrayInputStream(bytes) {
            var firstRead = true
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (firstRead) { firstRead = false; return 0 }
                return super.read(target, offset, minOf(length, 1))
            }
        }
        val source = PushbackInputStream(fragmented, 4)
        assertTrue(recordingStreamNeedsSeekableScan("clip.wav", source))
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun truncatedMagicRemainsAReadableInvalidPrefix() {
        val magic = byteArrayOf(102, 76, 97, 67)
        for (length in 0 until magic.size) {
            val bytes = magic.copyOf(length)
            val source = PushbackInputStream(ByteArrayInputStream(bytes), 4)
            assertFalse(recordingStreamNeedsSeekableScan("clip.wav", source))
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun prefixIoFailureRemainsUnavailableInsteadOfMalformed() {
        val failure = IOException("provider disconnected during prefix")
        var reads = 0
        val failing = object : InputStream() {
            override fun read(): Int = if (reads++ == 0) 102 else throw failure
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                target[offset] = read().toByte()
                return 1
            }
        }
        val source = PushbackInputStream(failing, 4)
        assertSame(failure, assertThrows(IOException::class.java) {
            recordingStreamNeedsSeekableScan("clip.wav", source)
        })
    }

    @Test fun cancellationDuringPrefixReadPreservesTheInterrupt() {
        val source = object : ByteArrayInputStream(byteArrayOf(102, 76, 97, 67)) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(target, offset, length)
                Thread.currentThread().interrupt()
                return count
            }
        }
        try {
            assertThrows(InterruptedIOException::class.java) {
                recordingStreamNeedsSeekableScan("clip.wav", PushbackInputStream(source, 4))
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun unknownLengthStreamReachesSeekableValidationCompletelyAndWithoutTakingSourceOwnership() = fixture { directory ->
        val bytes = ByteArray(300_007) { (it * 31).toByte() }
        var sourceClosed = false
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() { sourceClosed = true }
        }
        var retainedChannel: FileChannel? = null
        val result = withSeekableRecordingScanCopy(source, directory, maxBytes = bytes.size.toLong()) { channel ->
            retainedChannel = channel
            assertEquals(bytes.size.toLong(), channel.size())
            assertEquals(0L, channel.position())
            assertTrue(directory.listFiles()!!.isEmpty())
            val copied = ByteArray(bytes.size)
            val target = ByteBuffer.wrap(copied)
            while (target.hasRemaining()) assertTrue(channel.read(target) > 0)
            assertArrayEquals(bytes, copied)
            // Decoders may seek backwards; a consumed pipe cannot satisfy this read itself.
            val tail = ByteBuffer.allocate(23)
            assertEquals(23, channel.read(tail, bytes.size - 23L))
            assertArrayEquals(bytes.takeLast(23).toByteArray(), tail.array())
            "validated"
        }
        assertEquals("validated", result)
        assertFalse(retainedChannel!!.isOpen)
        assertFalse(sourceClosed)
    }

    @Test fun zeroLengthProviderReadsDoNotDiscardTheNextByte() = fixture { directory ->
        val bytes = byteArrayOf(102, 76, 97, 67, 1, 2, 3)
        val source = object : ByteArrayInputStream(bytes) {
            var firstRead = true
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (firstRead) { firstRead = false; return 0 }
                return super.read(target, offset, length)
            }
        }
        withSeekableRecordingScanCopy(source, directory, maxBytes = 100) { channel ->
            val target = ByteBuffer.allocate(bytes.size)
            while (target.hasRemaining()) assertTrue(channel.read(target) > 0)
            assertArrayEquals(bytes, target.array())
        }
    }

    @Test fun partialProviderIoFailureRemainsUnavailableAndNeverRunsValidation() = fixture { directory ->
        val failure = IOException("provider disconnected")
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = if (reads++ == 0) 102 else throw failure
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                target[offset] = read().toByte()
                return 1
            }
        }
        val caught = assertThrows(IOException::class.java) {
            withSeekableRecordingScanCopy(source, directory, maxBytes = 100) {
                fail("Incomplete source must not be validated")
            }
        }
        assertSame(failure, caught)
    }

    @Test fun capacityExhaustionRemainsUnavailableAndDoesNotValidateATruncatedCopy() = fixture { directory ->
        assertThrows(IOException::class.java) {
            withSeekableRecordingScanCopy(ByteArrayInputStream(ByteArray(17)), directory, maxBytes = 16) {
                fail("A capacity-limited prefix is not a complete recording")
            }
        }
    }

    @Test fun decoderFailurePreservesItsCauseAndClosesTheTemporaryChannel() = fixture { directory ->
        val failure = IOException("truncated FLAC payload")
        var retainedChannel: FileChannel? = null
        val caught = assertThrows(IOException::class.java) {
            withSeekableRecordingScanCopy(ByteArrayInputStream(byteArrayOf(102, 76, 97, 67)), directory, maxBytes = 100) {
                retainedChannel = it
                throw failure
            }
        }
        assertSame(failure, caught)
        assertFalse(retainedChannel!!.isOpen)
    }

    @Test fun cancellationDuringCopyPreservesTheInterruptAndSkipsValidation() = fixture { directory ->
        val source = object : ByteArrayInputStream(ByteArray(17)) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(target, offset, length)
                Thread.currentThread().interrupt()
                return count
            }
        }
        try {
            assertThrows(InterruptedIOException::class.java) {
                withSeekableRecordingScanCopy(source, directory, maxBytes = 100) {
                    fail("Cancelled source must not be validated")
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun fixture(block: (File) -> Unit) {
        val parent = File("build/tmp/recording-stream-scan-test").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "scan-").toFile()
        try {
            block(directory)
            assertTrue("Temporary scan payload must be removed on every terminal path", directory.listFiles()!!.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
