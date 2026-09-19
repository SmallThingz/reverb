package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingInlinePlayerLogicTest {
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
