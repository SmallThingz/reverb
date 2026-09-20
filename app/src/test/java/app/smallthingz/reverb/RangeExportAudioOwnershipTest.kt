package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeExportAudioOwnershipTest {
    @Test
    fun previewTrackBuffer_rejectsPlatformErrorSentinel() {
        for (reported in listOf(-2, -1, 0)) {
            org.junit.Assert.assertThrows(IOException::class.java) {
                resolvePreviewAudioTrackBufferBytes(
                    reportedMinBufferBytes = reported,
                    minimumBufferBytes = 9_600,
                )
            }
        }
    }

    @Test
    fun previewTrackBuffer_keepsLargerHardwareMinimum() {
        assertEquals(12_288, resolvePreviewAudioTrackBufferBytes(12_288, 9_600))
    }

    @Test
    fun previewTrackBuffer_appliesPositiveFloor() {
        assertEquals(9_600, resolvePreviewAudioTrackBufferBytes(4_096, 9_600))
    }

    @Test
    fun previewReleaseFallbackStartFailure_isReportedWithoutEscapingCleanup() {
        val expected = IOException("thread start failed")
        var observed: Throwable? = null

        startPreviewReleaseFallbackReportingFailure(
            start = { throw expected },
            onFailure = { observed = it },
        )

        assertSame(expected, observed)
    }

    @Test
    fun previewReleaseFallbackReporterFailure_cannotEscapeCleanup() {
        startPreviewReleaseFallbackReportingFailure(
            start = { throw IOException("thread start failed") },
            onFailure = { throw IllegalStateException("report failed") },
        )
    }

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
    fun shuttlePrefers96kAndFallsBackWhenUnsupported() {
        assertEquals(96_000, resolveShuttleSampleRate(4_096))
        assertEquals(48_000, resolveShuttleSampleRate(0))
        assertEquals(48_000, resolveShuttleSampleRate(-2))
    }

    @Test
    fun shuttleSourceRateSanitizesInvalidAndExtremeInput() {
        assertEquals(0f, sanitizedShuttleSourceRate(Float.NaN), 0f)
        assertEquals(0f, sanitizedShuttleSourceRate(Float.POSITIVE_INFINITY), 0f)
        assertEquals(4_096f, sanitizedShuttleSourceRate(10_000f), 0f)
        assertEquals(-4_096f, sanitizedShuttleSourceRate(-10_000f), 0f)
        assertEquals(1.25f, sanitizedShuttleSourceRate(1.25f), 0f)
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
