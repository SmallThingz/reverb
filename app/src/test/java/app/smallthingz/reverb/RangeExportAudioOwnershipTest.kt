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
        assertEquals(listOf(96_000, 48_000), shuttleSampleRateCandidates(4_096))
        assertEquals(listOf(48_000), shuttleSampleRateCandidates(0))
        assertEquals(listOf(48_000), shuttleSampleRateCandidates(-2))
    }

    @Test
    fun shuttleFallsBackWhenPreferredTrackCreationFails() {
        val attempts = mutableListOf<Int>()
        val (created, sampleRate) = createShuttleWithSampleRateFallback(4_096) { rate ->
            attempts += rate
            if (rate == 96_000) throw IOException("96 kHz track rejected")
            "track"
        }

        assertEquals("track", created)
        assertEquals(48_000, sampleRate)
        assertEquals(listOf(96_000, 48_000), attempts)
    }

    @Test
    fun shuttleFallbackFailureRetainsPreferredFailure() {
        val failure = org.junit.Assert.assertThrows(IOException::class.java) {
            createShuttleWithSampleRateFallback<Unit>(4_096) { rate ->
                throw IOException("$rate rejected")
            }
        }

        assertEquals("48000 rejected", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertEquals("96000 rejected", failure.suppressed.single().message)
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
