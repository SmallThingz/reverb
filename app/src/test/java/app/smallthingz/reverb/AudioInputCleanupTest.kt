package app.smallthingz.reverb

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputCleanupTest {
    private class InputOwner(
        val supported: Boolean = true,
        val setupFailure: Exception? = null,
        val releaseFailure: Throwable? = null,
    ) {
        var releases = 0

        fun inspect(): Boolean {
            setupFailure?.let { throw it }
            return supported
        }

        fun release() {
            releases++
            releaseFailure?.let { throw it }
        }
    }

    private fun probe(owner: InputOwner, report: (Throwable) -> Unit): Boolean =
        probeAudioInputConfiguration(
            acquire = { owner },
            release = InputOwner::release,
            onReleaseFailure = report,
            probe = InputOwner::inspect,
        )

    @Test
    fun ordinaryUnsupportedProbeStillPermitsQuietFallback() {
        for (preferred in listOf(
            InputOwner(supported = false),
            InputOwner(setupFailure = IllegalArgumentException("unsupported configuration")),
        )) {
            val fallback = InputOwner()
            val reports = mutableListOf<Throwable>()
            val candidates = listOf(preferred, fallback)

            val selected = candidates.firstOrNull { probe(it) { error -> reports += error } }

            assertSame(fallback, selected)
            assertEquals(1, preferred.releases)
            assertEquals(1, fallback.releases)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun supportedProbeWithFailedReleaseIsNotCacheableAndReportsBeforeSuccessfulFallback() {
        val cleanup = IOException("preferred release failed")
        val preferred = InputOwner(releaseFailure = cleanup)
        val fallback = InputOwner()
        val reports = mutableListOf<Throwable>()
        val supportedResults = mutableListOf<Boolean>()

        val selected = listOf(preferred, fallback).firstOrNull { owner ->
            if (owner === fallback) assertEquals(listOf(cleanup), reports)
            probe(owner) { reports += it }.also { supportedResults += it }
        }

        assertSame(fallback, selected)
        assertEquals(listOf(false, true), supportedResults)
        assertEquals(listOf(cleanup), reports)
        assertEquals(1, preferred.releases)
        assertEquals(1, fallback.releases)
    }

    @Test
    fun rejectedProbeReportsOnlyItsReleaseFailureAndPreservesSetupPrimary() {
        val primary = IOException("probe setup rejected")
        val unrelated = IOException("ordinary setup detail")
        primary.addSuppressed(unrelated)
        val cleanup = IOException("probe release failed")
        val owner = InputOwner(setupFailure = primary, releaseFailure = cleanup)
        val reports = mutableListOf<Throwable>()

        assertFalse(probe(owner) { reports += it })

        assertEquals(listOf(cleanup), reports)
        assertEquals(listOf(unrelated, cleanup), primary.suppressed.toList())
        assertEquals(1, owner.releases)
    }

    @Test
    fun currentAndRetiredCaptureSetupReportOnlyActualCleanupOnce() {
        for (retireDuringSetup in listOf(false, true)) {
            val primary = IOException("capture routing rejected")
            val cleanup = IOException("capture setup release failed")
            val owner = InputOwner(releaseFailure = cleanup)
            var operationCurrent = true
            val reports = mutableListOf<Throwable>()

            val observed = assertThrows(IOException::class.java) {
                configureOwnedResourceOrRelease(
                    owner = owner,
                    release = { acquired ->
                        releaseAudioInputWithReporting(
                            release = acquired::release,
                            onFailure = { error ->
                                assertEquals(!retireDuringSetup, operationCurrent)
                                reports += error
                            },
                        )
                    },
                    configure = {
                        if (retireDuringSetup) operationCurrent = false
                        throw primary
                    },
                )
            }

            // The caller still owns its ordinary primary result. Cleanup does not emit it again.
            assertSame(primary, observed)
            assertEquals(listOf(cleanup), primary.suppressed.toList())
            assertEquals(listOf(cleanup), reports)
            assertEquals(1, owner.releases)
        }
    }

    @Test
    fun cancelledOrUnavailableProbeWithSuccessfulReleaseAddsNoCleanupNotice() {
        for (primary in listOf(
            CancellationException("sampling cancelled"),
            IOException("input unavailable"),
        )) {
            primary.addSuppressed(IOException("non-cleanup detail"))
            val owner = InputOwner(setupFailure = primary)
            val reports = mutableListOf<Throwable>()

            assertFalse(probe(owner) { reports += it })

            assertTrue(reports.isEmpty())
            assertEquals(1, owner.releases)
        }
    }

    @Test
    fun reportingFailureCannotReplaceCleanupOrPreventFallback() {
        val cleanup = IOException("probe release failed")
        val reporting = IllegalStateException("reporting failed")
        val preferred = InputOwner(releaseFailure = cleanup)
        val fallback = InputOwner()
        var reports = 0

        val selected = listOf(preferred, fallback).firstOrNull { owner ->
            probe(owner) { error ->
                assertSame(cleanup, error)
                reports++
                throw reporting
            }
        }

        assertSame(fallback, selected)
        assertEquals(listOf(reporting), cleanup.suppressed.toList())
        assertEquals(1, reports)
        assertEquals(1, preferred.releases)
        assertEquals(1, fallback.releases)
    }

    @Test
    fun nativeErrorIsReportedWithoutChangingExistingExceptionOnlyProbeClassification() {
        val cleanup = AssertionError("native release error")
        val owner = InputOwner(releaseFailure = cleanup)
        val reports = mutableListOf<Throwable>()

        val observed = assertThrows(AssertionError::class.java) { probe(owner) { reports += it } }

        assertSame(cleanup, observed)
        assertEquals(listOf(cleanup), reports)
        assertEquals(1, owner.releases)
    }

    @Test
    fun successfulCaptureSetupTransfersOwnershipWithoutReleaseOrFeedback() {
        val owner = InputOwner()
        val reports = mutableListOf<Throwable>()

        val configured = configureOwnedResourceOrRelease(
            owner = owner,
            release = { acquired ->
                releaseAudioInputWithReporting(acquired::release) { reports += it }
            },
            configure = { assertTrue(it.inspect()) },
        )

        assertSame(owner, configured)
        assertEquals(0, owner.releases)
        assertTrue(reports.isEmpty())
    }
}
