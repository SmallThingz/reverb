package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.security.MessageDigest
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

    @Test fun wavDiscoveryConsumesAllChunksAndPaddingAndRecoveryHashesThoseBytes() {
        val payload = ByteArray(8) { it.toByte() }
        fun wav(data: ByteArray) = riff(
            wavFormat(), wavChunk("JUNK", byteArrayOf(9, 8, 7)), wavChunk("data", data), wavChunk("LIST", byteArrayOf(6)),
        )
        val bytes = wav(payload)
        val discovery = TrackingInput(bytes, zeroLengthReads = true)
        val recovery = TrackingInput(bytes, zeroLengthReads = true)
        assertEquals(4L, structurallyCompleteRecordingDurationMillis("clip.wav", discovery))
        val recovered = requireNotNull(readRecoverableStagingWav(recovery))
        assertEquals(4L, recovered.durationMillis)
        assertEquals(bytes.size.toLong(), recovered.digest.byteCount)
        assertEquals("05bc12134e59e729f2cfb7a14aab016c37d583163c0285bdcecae35e799c616c",
            recovered.digest.sha256.toHexString())
        for (source in listOf(discovery, recovery)) {
            assertEquals(bytes.size, source.consumed)
            assertEquals(1, source.eofReads)
        }

        val changed = wav(payload.also { it[0] = 23 })
        assertEquals(4L, structurallyCompleteRecordingDurationMillis("clip.wav", TrackingInput(changed)))
        val changedDigest = requireNotNull(readRecoverableStagingWav(TrackingInput(changed))).digest
        assertFalse(copyDigestMatches(recovered.digest, changedDigest))
    }

    @Test fun wavDiscoveryAndRecoveryRejectTheSameMalformedContainers() {
        val format = wavFormat()
        val data = wavChunk("data", ByteArray(8))
        val complete = riff(format, data)
        val padded = riff(format, data, wavChunk("JUNK", byteArrayOf(9)))
        val malformed = mapOf(
            "truncated data" to complete.copyOf(complete.size - 2),
            "truncated chunk header" to complete.copyOf(40),
            "inconsistent RIFF size" to complete.clone().also { it[4] = (it[4] - 2).toByte() },
            "duplicate format" to riff(format, data, format),
            "duplicate data" to riff(format, data, data),
            "invalid format" to riff(format.clone().also { it[8] = 7 }, data),
            "invalid byte rate" to riff(format.clone().also { it[16] = 0 }, data),
            "invalid block alignment" to riff(format.clone().also { it[20] = 1 }, data),
            "missing padding" to padded.copyOf(padded.size - 1),
            "partial frame" to riff(format, wavChunk("data", ByteArray(7))),
            "trailing byte" to (complete + 0.toByte()),
        )
        for ((description, bytes) in malformed) {
            assertEquals(description, 0L, structurallyCompleteRecordingDurationMillis("clip.wav", TrackingInput(bytes)))
            assertNull(description, readRecoverableStagingWav(TrackingInput(bytes)))
        }
    }

    @Test fun wavDiscoveryAndRecoveryPropagatePayloadIoFailures() {
        val bytes = riff(wavFormat(), wavChunk("data", ByteArray(8)))
        val failure = IOException("provider stream failed mid-payload")
        fun failingInput() = TrackingInput(bytes, failAt = 47, failure = failure)
        assertSame(failure, assertThrows(IOException::class.java) {
            structurallyCompleteRecordingDurationMillis("clip.wav", failingInput())
        })
        assertSame(failure, assertThrows(IOException::class.java) { readRecoverableStagingWav(failingInput()) })
    }

    @Test fun exactCopyDigestConsumesExpectedBytesAndEofEvenWithZeroLengthBulkReads() {
        val bytes = ByteArray(131_079) { (it * 31).toByte() }
        val input = TrackingInput(bytes, zeroLengthReads = true)
        val digest = sha256Exact(input, bytes.size.toLong())
        assertEquals(bytes.size.toLong(), digest.byteCount)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), digest.sha256)
        assertEquals(bytes.size, input.consumed)
        assertEquals(1, input.eofReads)
    }

    @Test fun exactCopyDigestRejectsShortInputAndStopsAtTheFirstExtraByte() {
        val short = TrackingInput(ByteArray(4))
        assertThrows(IOException::class.java) { sha256Exact(short, 5L) }
        assertEquals(4, short.consumed)
        assertEquals(1, short.eofReads)
        val extra = TrackingInput(ByteArray(128))
        assertThrows(IOException::class.java) { sha256Exact(extra, 5L) }
        assertEquals(6, extra.consumed)
        assertEquals(0, extra.eofReads)
        val failure = IOException("provider EOF probe failed")
        val failedEof = TrackingInput(ByteArray(5), failAt = 5, failure = failure)
        assertSame(failure, assertThrows(IOException::class.java) { sha256Exact(failedEof, 5L) })
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

    private fun wavFormat(): ByteArray = buildWavHeaderBytes(1_000, 1, PcmSampleFormat.PCM_16, 8L).copyOfRange(12, 36)

    private fun wavChunk(name: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size + (payload.size and 1)).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(name.toByteArray(Charsets.US_ASCII))
            putInt(payload.size)
            put(payload)
            if (payload.size % 2 != 0) put(0x5a.toByte())
        }.array()

    private fun riff(vararg chunks: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + chunks.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(capacity() - 8)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            chunks.forEach { put(it) }
        }.array()

    private class TrackingInput(
        private val bytes: ByteArray,
        private val zeroLengthReads: Boolean = false,
        private val failAt: Int = Int.MAX_VALUE,
        private val failure: IOException = IOException(),
    ) : InputStream() {
        var consumed = 0
            private set
        var eofReads = 0
            private set
        private var returnZero = true

        override fun read(): Int {
            if (consumed >= failAt) throw failure
            if (consumed == bytes.size) {
                eofReads++
                return -1
            }
            return bytes[consumed++].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (consumed >= failAt) throw failure
            if (consumed == bytes.size) {
                eofReads++
                return -1
            }
            if (zeroLengthReads) {
                returnZero = !returnZero
                if (!returnZero) return 0
            }
            val count = minOf(length, bytes.size - consumed, failAt - consumed)
            bytes.copyInto(target, offset, consumed, consumed + count)
            consumed += count
            return count
        }

        override fun skip(count: Long): Long = throw AssertionError("Validation must read the payload")
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
