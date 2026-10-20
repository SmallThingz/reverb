package app.smallthingz.reverb

import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class FlacPcmVerificationTest {
    private val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private fun pcmDigest(): ByteArray = MessageDigest.getInstance("MD5").digest(pcm)
    private fun differentDigest(): ByteArray = pcmDigest().also { it[0] = (it[0].toInt() xor 1).toByte() }

    @Test fun metadataChecksumMismatchIsInvalidOnlyAfterCompleteReadAndSuccessfulClose() {
        val source = Source(differentDigest())
        assertThrows(InvalidFlacException::class.java) { verifyFlacPcmSource(source) }
        assertEquals(listOf("read", "read-complete", "close"), source.events)
        assertEquals(1, source.closes)
    }

    @Test fun matchingChecksumHashesOnlyConsumedPcmAndReturnsMetadataAfterClose() {
        val source = Source(pcmDigest())
        assertSame(source.info, verifyFlacPcmSource(source))
        assertEquals(listOf("read", "read-complete", "close"), source.events)
        assertEquals(1, source.closes)
    }

    @Test fun absentExternalChecksumIsLegalButDoesNotBypassExplicitOutputChecksum() {
        val external = Source(ByteArray(16))
        assertSame(external.info, verifyFlacPcmSource(external))
        assertEquals(1, external.closes)

        val output = Source(ByteArray(16))
        val failure = assertThrows(IOException::class.java) {
            verifyFlacPcmSource(output, expectedPcmMd5 = ByteArray(16))
        }
        assertEquals(IOException::class.java, failure.javaClass)
        assertEquals(1, output.closes)
    }

    @Test fun explicitExpectedDigestRetainsPriorityOverStreamMetadata() {
        val correctOutput = Source(differentDigest())
        assertSame(correctOutput.info, verifyFlacPcmSource(correctOutput, expectedPcmMd5 = pcmDigest()))
        assertEquals(1, correctOutput.closes)

        val incorrectOutput = Source(pcmDigest())
        val failure = assertThrows(IOException::class.java) {
            verifyFlacPcmSource(incorrectOutput, expectedPcmMd5 = differentDigest())
        }
        assertEquals(IOException::class.java, failure.javaClass)
        assertEquals("FLAC decoded audio checksum mismatch", failure.message)
        assertEquals(1, incorrectOutput.closes)
    }

    @Test fun readAndDecodeFailuresKeepTheirIdentityAndSuppressReleaseFailure() {
        for (failure in listOf(IOException("read failed"), IllegalStateException("decoder failed"))) {
            val cleanup = IOException("release failed")
            val source = Source(differentDigest(), readFailure = failure, closeFailure = cleanup)
            val actual = assertThrows(Exception::class.java) { verifyFlacPcmSource(source) }
            assertSame(failure, actual)
            assertArrayEquals(arrayOf(cleanup), actual.suppressed)
            assertEquals(listOf("read", "close"), source.events)
            assertEquals(1, source.closes)
        }
    }

    @Test fun releaseFailureCannotBecomeSkippableInvalidContent() {
        for (metadata in listOf(pcmDigest(), differentDigest(), ByteArray(16))) {
            val failure = IOException("release failed")
            val source = Source(metadata, closeFailure = failure)
            assertSame(failure, assertThrows(IOException::class.java) { verifyFlacPcmSource(source) })
            assertEquals(listOf("read", "read-complete", "close"), source.events)
            assertEquals(1, source.closes)
        }
    }

    @Test fun cancellationBeforeOrDuringReadStillClosesAndRemainsPrimary() {
        for (cancelAtCheck in listOf(1, 2, 4)) {
            val cancellation = InterruptedIOException("cancelled")
            val cleanup = IOException("release failed")
            val source = Source(differentDigest(), closeFailure = cleanup)
            var checks = 0
            val actual = assertThrows(InterruptedIOException::class.java) {
                verifyFlacPcmSource(source, checkCancelled = {
                    if (++checks == cancelAtCheck) throw cancellation
                })
            }
            assertSame(cancellation, actual)
            assertArrayEquals(arrayOf(cleanup), actual.suppressed)
            assertEquals(1, source.closes)
            assertEquals(if (cancelAtCheck == 1) 0 else 1, source.reads)
        }
    }

    @Test fun cancellationDuringSuccessfulReleaseWinsBeforeMismatchClassification() {
        val cancellation = InterruptedIOException("cancelled during release")
        var cancelled = false
        val source = Source(differentDigest(), onClose = { cancelled = true })
        val actual = assertThrows(InterruptedIOException::class.java) {
            verifyFlacPcmSource(source, checkCancelled = { if (cancelled) throw cancellation })
        }
        assertSame(cancellation, actual)
        assertEquals(listOf("read", "read-complete", "close"), source.events)
        assertEquals(1, source.closes)
    }

    private inner class Source(
        checksum: ByteArray,
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val onClose: () -> Unit = {},
    ) : FlacPcmVerificationSource {
        override val info = FlacInfo(8000, 1, 16, pcm.size / 2L, checksum, 42, null)
        val events = mutableListOf<String>()
        var reads = 0
        var closes = 0

        override fun readFrames(start: Long, count: Long, consume: (ByteArray, Int, Int) -> Unit) {
            reads++
            events += "read"
            assertEquals(0L, start)
            assertEquals(info.frameCount, count)
            // Sentinels and separate callbacks prove the verifier hashes exactly the PCM slices.
            val padded = byteArrayOf(0x55) + pcm + byteArrayOf(0x66)
            val firstCount = pcm.size / 2
            consume(padded, 1, firstCount)
            readFailure?.let { throw it }
            consume(padded, 1 + firstCount, pcm.size - firstCount)
            events += "read-complete"
        }

        override fun close() {
            closes++
            events += "close"
            onClose()
            closeFailure?.let { throw it }
        }
    }
}
