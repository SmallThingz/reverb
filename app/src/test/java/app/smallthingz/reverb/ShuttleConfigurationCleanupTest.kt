package app.smallthingz.reverb

import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttleConfigurationCleanupTest {
    private class TrackOwner(
        val rate: Int,
        val configurationFailure: Exception? = null,
        val releaseFailure: Throwable? = null,
    ) {
        var releases = 0

        fun configured(onReleaseFailure: (Throwable) -> Unit): TrackOwner =
            configureRangePreviewTrackOrRelease(
                owner = this,
                release = { owner ->
                    owner.releases++
                    owner.releaseFailure?.let { throw it }
                },
                onReleaseFailure = onReleaseFailure,
            ) { owner ->
                owner.configurationFailure?.let { throw it }
            }
    }

    @Test
    fun preferredCleanupIsReportedBeforeSuccessfulFallbackOwnsTrack() {
        val primary = IOException("96 kHz configuration rejected")
        val cleanup = IOException("preferred track release failed")
        val preferred = TrackOwner(96_000, primary, cleanup)
        val fallback = TrackOwner(48_000)
        val events = mutableListOf<String>()
        val reports = mutableListOf<Throwable>()

        val (owner, rate) = createShuttleWithSampleRateFallback(
            preferredMinBufferBytes = 4_096,
            onConfigurationReleaseFailure = {
                events += "cleanup"
                reports += it
            },
        ) { sampleRate, reportCleanup ->
            events += "create-$sampleRate"
            (if (sampleRate == 96_000) preferred else fallback).configured(reportCleanup)
        }

        assertSame(fallback, owner)
        assertEquals(48_000, rate)
        assertEquals(listOf("create-96000", "cleanup", "create-48000"), events)
        assertEquals(listOf(cleanup), reports)
        assertEquals(listOf(cleanup), primary.suppressed.toList())
        assertEquals(1, preferred.releases)
        assertEquals(0, fallback.releases)
    }

    @Test
    fun bothRejectedAttemptsReportTheirOwnCleanupOnceBeforeTerminalFailure() {
        val firstPrimary = IOException("96 kHz configuration rejected")
        val lastPrimary = IOException("48 kHz configuration rejected")
        val firstCleanup = AssertionError("preferred release failed")
        val lastCleanup = IOException("fallback release failed")
        val preferred = TrackOwner(96_000, firstPrimary, firstCleanup)
        val fallback = TrackOwner(48_000, lastPrimary, lastCleanup)
        val reports = mutableListOf<Throwable>()

        val observed = assertThrows(IOException::class.java) {
            createShuttleWithSampleRateFallback(
                preferredMinBufferBytes = 4_096,
                onConfigurationReleaseFailure = { reports += it },
            ) { rate, reportCleanup ->
                (if (rate == 96_000) preferred else fallback).configured(reportCleanup)
            }
        }

        assertSame(lastPrimary, observed)
        assertEquals(listOf(firstCleanup, lastCleanup), reports)
        assertEquals(listOf(firstCleanup), firstPrimary.suppressed.toList())
        assertEquals(listOf(lastCleanup, firstPrimary), lastPrimary.suppressed.toList())
        assertEquals(1, preferred.releases)
        assertEquals(1, fallback.releases)
    }

    @Test
    fun cancelledTerminalSetupStillReportsOnlyItsActualCleanup() {
        for (cleanup in listOf(null, IOException("cancelled owner release failed"))) {
            val cancellation = InterruptedIOException("setup cancelled before audio starts")
            val owner = TrackOwner(48_000, cancellation, cleanup)
            val reports = mutableListOf<Throwable>()
            val attemptedRates = mutableListOf<Int>()

            val observed = assertThrows(InterruptedIOException::class.java) {
                createShuttleWithSampleRateFallback(
                    preferredMinBufferBytes = 0,
                    onConfigurationReleaseFailure = { reports += it },
                ) { rate, reportCleanup ->
                    attemptedRates += rate
                    owner.configured(reportCleanup)
                }
            }

            assertSame(cancellation, observed)
            assertEquals(listOf(48_000), attemptedRates)
            assertEquals(listOfNotNull(cleanup), reports)
            assertEquals(listOfNotNull(cleanup), cancellation.suppressed.toList())
            assertEquals(1, owner.releases)
        }
    }

    @Test
    fun configurationSuppressionWithoutReleaseFailureDoesNotBecomeCleanupFeedback() {
        val primary = IOException("96 kHz configuration rejected")
        primary.addSuppressed(IOException("unrelated configuration detail"))
        val preferred = TrackOwner(96_000, primary)
        val fallback = TrackOwner(48_000)
        val reports = mutableListOf<Throwable>()

        val (owner, _) = createShuttleWithSampleRateFallback(
            preferredMinBufferBytes = 4_096,
            onConfigurationReleaseFailure = { reports += it },
        ) { rate, reportCleanup ->
            (if (rate == 96_000) preferred else fallback).configured(reportCleanup)
        }

        assertSame(fallback, owner)
        assertTrue(reports.isEmpty())
        assertEquals(1, preferred.releases)
        assertEquals(0, fallback.releases)
    }

    @Test
    fun cleanupReporterFailureDoesNotReplacePrimaryOrPreventFallback() {
        val primary = IOException("96 kHz configuration rejected")
        val cleanup = IOException("preferred track release failed")
        val preferred = TrackOwner(96_000, primary, cleanup)
        val fallback = TrackOwner(48_000)
        val reports = mutableListOf<Throwable>()

        val (owner, _) = createShuttleWithSampleRateFallback(
            preferredMinBufferBytes = 4_096,
            onConfigurationReleaseFailure = {
                reports += it
                throw IllegalStateException("cleanup reporter failed")
            },
        ) { rate, reportCleanup ->
            (if (rate == 96_000) preferred else fallback).configured(reportCleanup)
        }

        assertSame(fallback, owner)
        assertEquals(listOf(cleanup), reports)
        assertEquals(listOf(cleanup), primary.suppressed.toList())
        assertEquals(1, preferred.releases)
        assertEquals(0, fallback.releases)
    }
}
