package app.smallthingz.reverb

import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class FlacEncoderFinalizerTest {
    @Test(timeout = 5_000)
    fun cancellationDuringTryAgainProgressOrEosReleasesOnceWithoutFinalizing() {
        for (progress in listOf(false, true)) for (eosAfterDrain in listOf(false, true)) {
            val finalizer = FlacEncoderFinalizer()
            var queued = 0
            var drains = 0
            var finalized = 0
            var codecReleases = 0
            var outputCloses = 0
            var eos = false
            try {
                val failure = assertThrows(InterruptedIOException::class.java) {
                    finalizer.close(
                        previousFailure = null,
                        queueEnd = { queued++ },
                        endOfStream = { eos },
                        drain = {
                            drains++
                            eos = eosAfterDrain
                            Thread.currentThread().interrupt()
                            progress
                        },
                        finalizeOutput = { finalized++ },
                        releaseCodec = { codecReleases++ },
                        closeOutput = { outputCloses++ },
                        nowNanos = { 0L },
                    )
                }
                assertEquals("FLAC export cancelled", failure.message)
                assertTrue("Cancellation flag is preserved for the export owner", Thread.currentThread().isInterrupted)
                assertEquals(1, queued)
                assertEquals(1, drains)
                assertEquals(0, finalized)
                assertEquals(1, codecReleases)
                assertEquals(1, outputCloses)
                finalizer.close(null, { fail("Queued EOS twice") }, { false }, { fail("Drained twice"); false },
                    { fail("Finalized cancelled staging") }, { fail("Released codec twice") }, { fail("Closed output twice") })
            } finally { Thread.interrupted() }
        }
    }

    @Test(timeout = 5_000)
    fun interruptedTerminalKeepsCancellationPrimaryAndBothCleanupFailures() {
        val finalizer = FlacEncoderFinalizer()
        val codecFailure = IOException("release")
        val outputFailure = IOException("close")
        try {
            Thread.currentThread().interrupt()
            val failure = assertThrows(InterruptedIOException::class.java) {
                finalizer.close(null, { fail("Cancelled owner queued EOS") }, { false }, { false },
                    { fail("Cancelled owner rewrote output") }, { throw codecFailure }, { throw outputFailure })
            }
            assertArrayEquals(arrayOf(codecFailure, outputFailure), failure.suppressed)
        } finally { Thread.interrupted() }
    }

    @Test(timeout = 5_000)
    fun normalEndFinalizesOnceAndNoProgressTimeoutStillReleases() {
        for (progress in listOf(false, true)) {
            val finalizer = FlacEncoderFinalizer()
            var eos = false
            var finalizations = 0
            var releases = 0
            var clock = 0L
            fun close() = finalizer.close(null, {}, { eos }, {
                if (progress) eos = true else clock = 31_000_000_000L
                progress
            }, { finalizations++ }, { releases++ }, { releases++ }, nowNanos = { clock })
            if (progress) close() else assertThrows(IOException::class.java) { close() }
            close()
            assertEquals(if (progress) 1 else 0, finalizations)
            assertEquals(2, releases)
        }
    }

    @Test
    fun earlierWriteFailureSkipsFinalizationAndPreservesUnverifiedOutput() {
        val original = IOException("partial PCM input")
        var released = 0
        val failure = assertThrows(IOException::class.java) {
            FlacEncoderFinalizer().close(original, { fail("Queued after write failure") }, { false }, { false },
                { fail("Finalized unverified output") }, { released++ }, { released++ })
        }
        assertSame(original, failure.cause)
        assertEquals(2, released)
    }
}
