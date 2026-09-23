package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingInlinePlayerLogicTest {
    @Test
    fun rapidSeeks_waitForLatestTargetBeforeResuming() {
        val seeks = InlinePlaybackSeekQueue()
        assertEquals(9_000, seeks.request(9_000, false))
        assertNull(seeks.request(2_000, true))
        assertNull(seeks.request(3_000, true))
        assertEquals(3_000, seeks.completed())
        assertTrue(seeks.pending)
        assertNull(seeks.completed())
        assertFalse(seeks.pending)
        assertTrue(seeks.resume)
    }

    @Test
    fun pauseWhileSeekPending_doesNotResumeOnCompletion() {
        val seeks = InlinePlaybackSeekQueue()
        seeks.request(1_000, true)
        seeks.resume = false
        seeks.completed()
        assertFalse(seeks.resume)
        assertFalse(seeks.pending)
    }

    @Test
    fun teardown_discardsQueuedSeekAndResume() {
        val seeks = InlinePlaybackSeekQueue()
        seeks.request(1_000, true)
        seeks.request(2_000, true)
        seeks.cancel()
        assertFalse(seeks.pending)
        assertFalse(seeks.resume)
        assertNull(seeks.completed())
    }

    @Test
    fun trimResume_auditionsBeforeEndInsteadOfStartingAtStopBoundary() {
        assertEquals(7_000, inlineTrimPlaybackStartMillis(2_000, 10_000, InlineFineSeekTarget.TRIM_END))
        assertEquals(2_000, inlineTrimPlaybackStartMillis(2_000, 2_050, InlineFineSeekTarget.TRIM_END))
        assertEquals(2_000, inlineTrimPlaybackStartMillis(2_000, 10_000, InlineFineSeekTarget.TRIM_START))
    }

    @Test
    fun playbackCleanupFailure_isReportedWithoutEscapingCleanup() {
        val expected = IOException("close failed")
        var observed: Exception? = null

        runInlinePlaybackCleanupReportingFailure(
            cleanup = { throw expected },
            onFailure = { observed = it },
        )

        assertSame(expected, observed)
    }

    @Test
    fun playbackCleanupReporterFailure_cannotEscapeCleanup() {
        runInlinePlaybackCleanupReportingFailure(
            cleanup = { throw IOException("close failed") },
            onFailure = { throw IllegalStateException("report failed") },
        )
    }

    @Test
    fun shuttleFailure_resumesOnlyWhenItHadTakenAudiblePlaybackOwnership() {
        assertTrue(
            inlineShuttleFailureShouldResume(
                shuttleActive = true, resumeAfterScrub = true, lifecycleResumed = true,
            ),
        )
        assertFalse(
            inlineShuttleFailureShouldResume(
                shuttleActive = false, resumeAfterScrub = true, lifecycleResumed = true,
            ),
        )
        assertFalse(
            inlineShuttleFailureShouldResume(
                shuttleActive = true, resumeAfterScrub = false, lifecycleResumed = true,
            ),
        )
        assertFalse(
            inlineShuttleFailureShouldResume(
                shuttleActive = true, resumeAfterScrub = true, lifecycleResumed = false,
            ),
        )
    }
}
