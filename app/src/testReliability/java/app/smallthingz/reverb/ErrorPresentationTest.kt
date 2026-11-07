package app.smallthingz.reverb

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ErrorPresentationTest {
    @Test fun wrappedTypedCauseSurvivesWithoutExposingItsPath() {
        val cause = AccessDeniedException("/private/recordings/secret.flac")
        val wrapper = IOException("Provider rejected content://private/token", cause)
        val detail = appFailureDetail(wrapper)
        assertEquals(AppFailureReason.PERMISSION, detail.reason)
        assertEquals("AccessDeniedException", detail.type)
        assertSame(cause, wrapper.cause)
        assertEquals("/private/recordings/secret.flac", cause.file)
        assertFalse(detail.toString().contains("private"))
    }
    @Test fun ambiguousFileNotFoundDoesNotInventMissingOrPermissionCause() {
        assertEquals(AppFailureReason.UNKNOWN, appFailureDetail(FileNotFoundException("Permission denied")).reason)
        assertEquals(AppFailureReason.MISSING, appFailureDetail(NoSuchFileException("secret")).reason)
    }
    @Test fun meaningfulOuterDetailSurvivesEmptyInnerCause() {
        val message = "The document is locked by another writer"
        val unknown = appFailureDetail(IOException(message, IOException()))
        assertEquals(message, unknown.reported)
        assertEquals("IOException", unknown.type)
        val known = appFailureDetail(IOException(message, SecurityException()))
        assertEquals(message, known.reported)
        assertEquals(AppFailureReason.PERMISSION, known.reason)
    }
    @Test fun arbitraryTextCannotInventDiskFull() {
        val detail = appFailureDetail(IOException("ENOSPC disk full password=secret"))
        assertEquals(AppFailureReason.UNKNOWN, detail.reason)
        assertEquals("IOException", detail.type)
        assertNull(detail.code)
    }
    @Test fun absentCauseAndCancellationAreExplicit() {
        assertEquals(AppFailureDetail(AppFailureReason.UNKNOWN, null), appFailureDetail(null))
        assertEquals(AppFailureReason.CANCELLED, appFailureDetail(IOException(CancellationException())).reason)
    }
    @Test fun meaningfulReportedDetailSurvivesWithoutBecomingAProvenCause() {
        val detail = appFailureDetail(IOException("Provider does not support renaming this document"))
        assertEquals(AppFailureReason.UNKNOWN, detail.reason)
        assertEquals("Provider does not support renaming this document", detail.reported)
    }
    @Test fun reportedDetailsRedactLocationsCredentialsAndControlText() {
        val safe = safeReportedErrorDetail("Cannot open content://private/token file /storage/private.wav password=secret\nBearer abc123\u0000")!!
        assertFalse(safe.contains("private")); assertFalse(safe.contains("secret")); assertFalse(safe.contains("abc123"))
        assertFalse(safe.any { it.isISOControl() })
        assertFalse(safeReportedErrorDetail("Authorization: Bearer abc123")!!.contains("abc123"))
        assertFalse(safeReportedErrorDetail("Authorization: Basic xyz456")!!.contains("xyz456"))
        assertTrue(safe.length <= 160)
        assertNull(safeReportedErrorDetail("An error occurred"))
        assertTrue(safeReportedErrorDetail("x ".repeat(2000))!!.length <= 160)
    }
    @Test fun quotedAuthorizationIsRedactedAtomically() {
        for (value in listOf("\"Bearer secret123\"", "'Basic secret456'", "\"multiple private words\"")) {
            assertEquals("Provider rejected [redacted]", safeReportedErrorDetail("Provider rejected Authorization: $value"))
        }
    }
    @Test fun whitespaceInspectionStopsAtTheBoundedPrefix() {
        assertNull(safeReportedErrorDetail(" ".repeat(2048) + "Private detail outside the inspection budget"))
        assertEquals("Useful detail", safeReportedErrorDetail("Useful detail" + " ".repeat(3000)))
    }
    @Test fun pathsWithSpacesAreRedactedWithoutLosingDelimitedExplanation() {
        assertEquals("Cannot open [path]", safeReportedErrorDetail("Cannot open /storage/emulated/0/Private Recording.wav"))
        assertEquals("Cannot open [path]; provider is offline", safeReportedErrorDetail("Cannot open /storage/emulated/0/Private Recording.wav; provider is offline"))
        assertEquals("Cannot open [path], retry later", safeReportedErrorDetail("""Cannot open "/storage/Private Recording.wav", retry later"""))
        assertEquals("Cannot open [path]; retry later", safeReportedErrorDetail("""Cannot open C:\Users\Private Recording.wav; retry later"""))
        val detail = appFailureDetail(IOException("Cannot open /storage/Private Recording.wav; provider is offline"))
        assertEquals("IOException", detail.type)
        assertEquals(AppFailureReason.UNKNOWN, detail.reason)
        assertEquals("Cannot open [path]; provider is offline", detail.reported)
    }

    @Test fun cyclicAndLongCauseChainsAreBounded() {
        val first = IOException("one"); val second = IOException("two")
        first.initCause(second); second.initCause(first)
        assertEquals(AppFailureReason.UNKNOWN, appFailureDetail(first).reason)
        var deep: Throwable = SecurityException("secret")
        repeat(40) { deep = IOException(deep) }
        assertEquals(AppFailureReason.UNKNOWN, appFailureDetail(deep).reason)
    }
}
