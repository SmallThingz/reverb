package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RangePreviewFailureDeliveryTest {
    private fun failTrackConfiguration(): Pair<Throwable, Throwable> {
        val primary = IOException("Unable to configure audio preview volume")
        val cleanup = IOException("configuration release failed")
        var capturedRelease: Throwable? = null
        var releaseAttempts = 0
        val observed = org.junit.Assert.assertThrows(IOException::class.java) {
            configureRangePreviewTrackOrRelease(
                owner = Any(),
                release = { releaseAttempts++; throw cleanup },
                onReleaseFailure = { capturedRelease = it },
                configure = { throw primary },
            )
        }
        assertSame(primary, observed)
        assertSame(cleanup, capturedRelease)
        assertEquals(1, releaseAttempts)
        assertEquals(listOf(cleanup), primary.suppressed.toList())
        return primary to requireNotNull(capturedRelease)
    }

    private class Attempt {
        var generation = 1L
        var closed = false
        val queued = ArrayDeque<() -> Unit>()
        val playbackFailures = mutableListOf<Throwable>()
        val releaseFailures = mutableListOf<Throwable>()

        fun complete(
            failure: Throwable?,
            releaseFailure: Throwable? = null,
            token: Long = generation,
            explicitPlayback: Boolean = true,
        ) = completeRangePreviewFailure(
            playbackFailure = failure,
            releaseFailure = releaseFailure,
            isCurrent = { !closed && generation == token },
            post = { queued.addLast(it) },
            onPlaybackFailure = if (explicitPlayback) ({ playbackFailures += it }) else null,
            onReleaseFailure = { releaseFailures += it },
        )

        fun deliverQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().invoke()
        }
    }

    @Test
    fun rejectedNativeBufferReachesCurrentExplicitAttemptExactlyOnce() {
        val expected = org.junit.Assert.assertThrows(IOException::class.java) {
            resolvePreviewAudioTrackBufferBytes(-2, 9_600)
        }
        val attempt = Attempt()
        attempt.complete(expected)

        assertEquals(1, attempt.queued.size)
        assertTrue(attempt.playbackFailures.isEmpty())
        attempt.deliverQueued()
        assertEquals(listOf(expected), attempt.playbackFailures)
        assertTrue(attempt.releaseFailures.isEmpty())
    }

    @Test
    fun writeFailureAndReleaseFailureProduceOneReportWithPrimaryPreserved() {
        val primary = IOException("Audio preview write failed: -6")
        val cleanup = IOException("AudioTrack release failed")
        val attempt = Attempt()
        attempt.complete(primary, cleanup)
        attempt.deliverQueued()

        assertEquals(listOf(primary), attempt.playbackFailures)
        assertEquals(listOf(cleanup), primary.suppressed.toList())
        assertTrue(attempt.releaseFailures.isEmpty())
    }

    @Test
    fun trackConfigurationFailureKeepsAlreadySuppressedReleaseProvenance() {
        val (primary, cleanup) = failTrackConfiguration()
        val attempt = Attempt()
        attempt.complete(primary, cleanup)
        attempt.deliverQueued()

        assertSame(primary, attempt.playbackFailures.single())
        assertEquals(listOf(cleanup), primary.suppressed.toList())
        assertTrue(attempt.releaseFailures.isEmpty())
    }

    @Test
    fun configurationReleaseSurvivesStaleAttemptBeforeQueueingWithoutItsPrimary() {
        val (primary, cleanup) = failTrackConfiguration()
        val attempt = Attempt()
        attempt.generation++
        attempt.complete(primary, cleanup, token = 1L)

        assertTrue(attempt.queued.isEmpty())
        assertTrue(attempt.playbackFailures.isEmpty())
        assertEquals(listOf(cleanup), attempt.releaseFailures)
    }

    @Test
    fun configurationReleaseSurvivesRetirementAfterQueueingWithoutItsPrimary() {
        val (primary, cleanup) = failTrackConfiguration()
        val attempt = Attempt()
        attempt.complete(primary, cleanup)
        attempt.closed = true
        attempt.deliverQueued()

        assertTrue(attempt.playbackFailures.isEmpty())
        assertEquals(listOf(cleanup), attempt.releaseFailures)
    }

    @Test
    fun quietAuditionStillReportsLexicallyCapturedConfigurationRelease() {
        val (primary, cleanup) = failTrackConfiguration()
        val attempt = Attempt()
        attempt.complete(primary, cleanup, explicitPlayback = false)

        assertTrue(attempt.queued.isEmpty())
        assertTrue(attempt.playbackFailures.isEmpty())
        assertEquals(listOf(cleanup), attempt.releaseFailures)
    }

    @Test
    fun arbitrarySuppressedFailureIsNotMistakenForCapturedNativeCleanup() {
        val primary = IOException("late read failed")
        primary.addSuppressed(IOException("unrelated secondary read failure"))
        val attempt = Attempt()
        attempt.generation++
        attempt.complete(primary, token = 1L)

        assertTrue(attempt.queued.isEmpty())
        assertTrue(attempt.playbackFailures.isEmpty())
        assertTrue(attempt.releaseFailures.isEmpty())
    }

    @Test
    fun staleBeforeDispatchDoesNotQueuePrimaryButStillReportsReleaseFailure() {
        val primary = IOException("late source read")
        val cleanup = IOException("late release")
        val attempt = Attempt()
        attempt.generation++
        attempt.complete(primary, cleanup, token = 1L)

        assertTrue(attempt.queued.isEmpty())
        assertTrue(attempt.playbackFailures.isEmpty())
        assertEquals(listOf(cleanup), attempt.releaseFailures)
    }

    @Test
    fun pauseAfterQueueSilencesPrimaryButKeepsReleaseFailureObservable() {
        val primary = IOException("source read failed")
        val cleanup = IOException("release failed")
        val attempt = Attempt()
        attempt.complete(primary, cleanup)
        attempt.generation++ // pause/stop revokes the queued attempt before main dispatch
        attempt.deliverQueued()

        assertTrue(attempt.playbackFailures.isEmpty())
        assertEquals(listOf(cleanup), attempt.releaseFailures)
    }

    @Test
    fun closeAfterQueueSilencesAnOtherwiseCurrentPlaybackError() {
        val attempt = Attempt()
        attempt.complete(IOException("read finished after close"))
        attempt.closed = true
        attempt.deliverQueued()

        assertTrue(attempt.playbackFailures.isEmpty())
        assertTrue(attempt.releaseFailures.isEmpty())
    }

    @Test
    fun freshFailureSurvivesRetirementOfOlderQueuedAttempt() {
        val old = IOException("old read failed")
        val fresh = IOException("new read failed")
        val attempt = Attempt()
        attempt.complete(old)
        attempt.generation++
        attempt.complete(fresh)
        attempt.deliverQueued()

        assertEquals(listOf(fresh), attempt.playbackFailures)
        assertTrue(attempt.releaseFailures.isEmpty())
    }

    @Test
    fun normalCompletionAndCancellationStayQuietUnlessCleanupActuallyFails() {
        val cleanup = IOException("cleanup after cancellation")
        val attempt = Attempt()
        attempt.complete(null)
        attempt.generation++
        attempt.complete(null, cleanup, token = 1L)
        attempt.deliverQueued()

        assertTrue(attempt.playbackFailures.isEmpty())
        assertEquals(listOf(cleanup), attempt.releaseFailures)
    }

    @Test
    fun scrubAuditionDoesNotBecomeExplicitPlaybackFeedbackOrHideCleanupFailure() {
        val cleanup = IOException("audition release failed")
        val attempt = Attempt()
        attempt.complete(IOException("audition read failed"), cleanup, explicitPlayback = false)
        attempt.deliverQueued()

        assertTrue(attempt.playbackFailures.isEmpty())
        assertTrue(attempt.queued.isEmpty())
        assertEquals(listOf(cleanup), attempt.releaseFailures)
    }

    @Test
    fun cleanupReporterFailureCannotEscapeTerminalHandling() {
        completeRangePreviewFailure(
            playbackFailure = null,
            releaseFailure = IOException("release failed"),
            isCurrent = { false },
            post = { error("cleanup-only failures do not need a current playback callback") },
            onPlaybackFailure = { error("no playback failure") },
            onReleaseFailure = { throw IllegalStateException("reporter failed") },
        )
    }
}
