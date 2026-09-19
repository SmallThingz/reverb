package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeExportAudioOwnershipTest {
    @Test
    fun previewTrackReleaseFailure_isReportedWithoutEscapingCleanup() {
        val expected = IOException("release failed")
        var observed: Throwable? = null

        releasePreviewTrackReportingFailure(
            release = { throw expected },
            onFailure = { observed = it },
        )

        assertSame(expected, observed)
    }

    @Test
    fun previewTrackReleaseReporterFailure_cannotEscapeCleanup() {
        releasePreviewTrackReportingFailure(
            release = { throw IOException("release failed") },
            onFailure = { throw IllegalStateException("report failed") },
        )
    }

    @Test
    fun successfulPreviewTrackRelease_doesNotReportFailure() {
        var released = false
        var reported = false

        releasePreviewTrackReportingFailure(
            release = { released = true },
            onFailure = { reported = true },
        )

        assertTrue(released)
        assertFalse(reported)
    }
}
