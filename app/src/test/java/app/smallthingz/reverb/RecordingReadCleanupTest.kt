package app.smallthingz.reverb

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingReadCleanupTest {
    private class Owner(private val failure: Throwable? = null) : Closeable {
        var closes = 0
        override fun close() {
            closes++
            failure?.let { throw it }
        }
    }

    @Test
    fun completedWaveformReportsTerminalCloseBeforeOptionalCallerDiscardsFailure() {
        val terminal = IOException("descriptor close failed")
        val owner = Owner(terminal)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }
        var readCompleted = false
        var cachePublished = false

        val observed = runCatching {
            cleanup.use(owner, release = { it.close() }) {
                samplePcmWaveformEnvelopeProgressive(
                    layout = WavPcmLayout(8_000, 1, PcmSampleFormat.PCM_16, 0L, 32L),
                    pass = RangeWaveformPass.COARSE,
                    readPcm = { _, frames, scratch ->
                        repeat(frames) { frame ->
                            scratch[frame * 2] = 0
                            scratch[frame * 2 + 1] = 64
                        }
                    },
                    onBucket = { _, _ -> true },
                ).also { readCompleted = true }
            }
            cachePublished = true
        }.exceptionOrNull()

        assertSame(terminal, observed)
        assertTrue(readCompleted)
        assertFalse(cachePublished)
        assertEquals(listOf(terminal), reports)
        assertEquals(1, owner.closes)
    }

    @Test
    fun failedReadKeepsPrimaryAndReportsOnlyActualTerminalClose() {
        val primary = IOException("provider read failed")
        val unrelated = IOException("unrelated provider detail")
        primary.addSuppressed(unrelated)
        val terminal = IOException("descriptor close failed")
        val owner = Owner(terminal)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }

        val observed = assertThrows(IOException::class.java) {
            cleanup.use(owner, release = { it.close() }) { throw primary }
        }

        assertSame(primary, observed)
        assertEquals(listOf(unrelated, terminal), observed.suppressed.toList())
        assertEquals(listOf(terminal), reports)
        assertEquals(1, owner.closes)
    }

    @Test
    fun nativeSetupAndParentCloseFailuresEachReportBeforeQuietSourceRejection() {
        val primary = IOException("decoder setup rejected")
        val codecFailure = IllegalStateException("codec release failed")
        val extractorFailure = IOException("extractor release failed")
        val descriptorFailure = IOException("input close failed")
        val codec = Owner(codecFailure)
        val extractor = Owner(extractorFailure)
        val descriptor = Owner(descriptorFailure)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }

        // The decoder owns both native releases; reader construction then closes its parent.
        val discarded = runCatching {
            cleanup.open(descriptor) {
                cleanup.closeAfterFailure(primary, codec::close)
                cleanup.closeAfterFailure(primary, extractor::close)
                throw primary
            }
        }.exceptionOrNull()

        assertSame(primary, discarded)
        assertEquals(listOf(codecFailure, extractorFailure, descriptorFailure), reports)
        assertEquals(reports, primary.suppressed.toList())
        assertEquals(1, codec.closes)
        assertEquals(1, extractor.closes)
        assertEquals(1, descriptor.closes)
    }

    @Test
    fun sourceConstructionReportsChildStreamCleanupFailureOnce() {
        val primary = IOException("child stream construction failed")
        val terminal = IOException("parent descriptor close failed")
        val descriptor = Owner(terminal)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }

        val observed = assertThrows(IOException::class.java) {
            cleanup.open(descriptor) { throw primary }
        }

        assertSame(primary, observed)
        assertEquals(listOf(terminal), observed.suppressed.toList())
        assertEquals(listOf(terminal), reports)
        assertEquals(1, descriptor.closes)
    }

    @Test
    fun retiredGestureCancellationStillReportsItsActualCleanup() {
        val primary = InterruptedIOException("source setup cancelled")
        val terminal = IOException("cancelled source close failed")
        val reports = mutableListOf<Throwable>()
        var currentGesture = true
        val cleanup = RecordingReadCleanup {
            assertFalse(currentGesture)
            reports += it
        }
        val owner = Owner(terminal)

        val observed = assertThrows(InterruptedIOException::class.java) {
            cleanup.open(owner) {
                currentGesture = false
                throw primary
            }
        }

        assertSame(primary, observed)
        assertEquals(listOf(terminal), observed.suppressed.toList())
        assertEquals(listOf(terminal), reports)
        assertEquals(1, owner.closes)
    }

    @Test
    fun ordinaryUnavailableAndCancelledReadsWithSuccessfulCleanupStayQuiet() {
        for (primary in listOf(
            IOException("provider unavailable"),
            IOException("non-seekable source"),
            InterruptedIOException("cancelled"),
        )) {
            primary.addSuppressed(IOException("optional read detail"))
            val owner = Owner()
            val reports = mutableListOf<Throwable>()
            val cleanup = RecordingReadCleanup { reports += it }

            val observed = assertThrows(Exception::class.java) {
                cleanup.use(owner, release = { it.close() }) { throw primary }
            }

            assertSame(primary, observed)
            assertTrue(reports.isEmpty())
            assertEquals(1, owner.closes)
        }
    }

    @Test
    fun nestedWaveformOwnersRetainBothCloseFailuresWithoutRetry() {
        val nativeFailure = IllegalStateException("decoder release failed")
        val descriptorFailure = IOException("descriptor close failed")
        val nativeOwner = Owner(nativeFailure)
        val descriptor = Owner(descriptorFailure)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }

        val observed = assertThrows(IllegalStateException::class.java) {
            cleanup.use(descriptor, release = { it.close() }) {
                cleanup.use(nativeOwner, release = { it.close() }) { "complete PCM" }
            }
        }

        assertSame(nativeFailure, observed)
        assertEquals(listOf(descriptorFailure), nativeFailure.suppressed.toList())
        assertEquals(listOf(nativeFailure, descriptorFailure), reports)
        assertEquals(1, nativeOwner.closes)
        assertEquals(1, descriptor.closes)
    }

    @Test
    fun reporterFailureCannotReplacePrimaryOrPreventOtherOwnerCleanup() {
        val primary = IOException("setup failed")
        val firstFailure = AssertionError("native release failed")
        val lastFailure = IOException("descriptor close failed")
        val first = Owner(firstFailure)
        val last = Owner(lastFailure)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup {
            reports += it
            throw IllegalStateException("reporter failed")
        }

        val observed = cleanup.closeAfterFailure(primary, first::close)
        val terminalObserved = cleanup.closeAfterFailure(observed, last::close)

        assertSame(primary, terminalObserved)
        assertEquals(listOf(firstFailure, lastFailure), primary.suppressed.toList())
        assertEquals(listOf(firstFailure, lastFailure), reports)
        assertEquals(1, first.closes)
        assertEquals(1, last.closes)
    }

    @Test
    fun successfulAcquisitionLeavesTerminalOwnershipWithCaller() {
        val terminal = AssertionError("terminal source release failed")
        val owner = Owner(terminal)
        val setupReports = mutableListOf<Throwable>()
        val terminalReports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { setupReports += it }

        val returned = cleanup.open(owner) { it }
        assertSame(owner, returned)
        assertEquals(0, owner.closes)
        runCleanupReportingThrowable(returned::close) { terminalReports += it }

        assertTrue(setupReports.isEmpty())
        assertEquals(listOf(terminal), terminalReports)
        assertEquals(1, owner.closes)
    }

    @Test
    fun propagatingExportModeRetainsFailureWithoutAddingAFeedbackOwner() {
        val primary = IOException("export read failed")
        val terminal = IOException("export source close failed")
        val owner = Owner(terminal)

        val observed = assertThrows(IOException::class.java) {
            RecordingReadCleanup.Propagate.use(owner, release = { it.close() }) { throw primary }
        }

        assertSame(primary, observed)
        assertEquals(listOf(terminal), observed.suppressed.toList())
        assertEquals(1, owner.closes)
    }
}
