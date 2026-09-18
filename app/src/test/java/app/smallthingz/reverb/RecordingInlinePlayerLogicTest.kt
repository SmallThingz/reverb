package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingInlinePlayerLogicTest {
    @Test
    fun playbackSourceCloseFailure_isReportedWithoutEscapingCleanup() {
        val expected = IOException("close failed")
        var observed: Exception? = null

        closeInlinePlaybackSourceReportingFailure(
            close = { throw expected },
            onFailure = { observed = it },
        )

        assertSame(expected, observed)
    }

    @Test
    fun playbackSourceCloseReporterFailure_cannotEscapeCleanup() {
        closeInlinePlaybackSourceReportingFailure(
            close = { throw IOException("close failed") },
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
