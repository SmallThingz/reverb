package app.smallthingz.reverb

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OptionalProbeCleanupTest {
    private class Owner(
        private val failure: Throwable? = null,
        private val onRelease: () -> Unit = {},
    ) {
        var releases = 0
            private set

        fun release() {
            releases++
            onRelease()
            failure?.let { throw it }
        }
    }

    @Test
    fun successfulProbePublishesOnlyAfterItsOwnerReleases() {
        val events = mutableListOf<String>()
        val owner = Owner(onRelease = { events += "release" })
        val cleanup = RecordingReadCleanup { events += "report" }
        val observed = cleanup.use(owner, release = { it.release() }) {
            events += "probe"
            "verified metadata"
        }.also { events += "publish" }

        assertEquals("verified metadata", observed)
        assertEquals(listOf("probe", "release", "publish"), events)
        assertEquals(1, owner.releases)
    }

    @Test
    fun successfulProbeWithFailedReleaseReportsBeforeOptionalFallback() {
        val events = mutableListOf<String>()
        val terminal = IOException("native release failed")
        val owner = Owner(terminal) { events += "release" }
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it; events += "report" }
        val observed = try {
            cleanup.use(owner, release = { it.release() }) {
                events += "probe"
                "optional metadata"
            }
        } catch (error: Exception) {
            assertSame(terminal, error)
            events += "fallback"
            "verified structure"
        }

        assertEquals("verified structure", observed)
        assertEquals(listOf("probe", "release", "report", "fallback"), events)
        assertEquals(listOf(terminal), reports)
        assertEquals(1, owner.releases)
    }

    @Test
    fun failedProbeReportsOnlyActualReleaseAndRetainsPrimaryChain() {
        val primary = IOException("metadata unavailable")
        val unrelated = IOException("provider diagnostic")
        primary.addSuppressed(unrelated)
        val terminal = IOException("descriptor close failed")
        val owner = Owner(terminal)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }
        val outcome = runCatching {
            cleanup.use(owner, release = { it.release() }) { throw primary }
        }

        assertNull(outcome.getOrNull())
        assertSame(primary, outcome.exceptionOrNull())
        assertEquals(listOf(unrelated, terminal), primary.suppressed.toList())
        assertEquals(listOf(terminal), reports)
        assertEquals(1, owner.releases)
    }

    @Test
    fun unavailableOrCancelledProbeWithSuccessfulReleaseStaysQuiet() {
        for (primary in listOf(IOException("unavailable"), CancellationException("retired"))) {
            val unrelated = IOException("provider diagnostic")
            primary.addSuppressed(unrelated)
            val owner = Owner()
            val reports = mutableListOf<Throwable>()
            val cleanup = RecordingReadCleanup { reports += it }
            val outcome = runCatching {
                cleanup.use(owner, release = { it.release() }) { throw primary }
            }

            assertNull(outcome.getOrNull())
            assertSame(primary, outcome.exceptionOrNull())
            assertEquals(listOf(unrelated), primary.suppressed.toList())
            assertEquals(emptyList<Throwable>(), reports)
            assertEquals(1, owner.releases)
        }
    }

    @Test
    fun nullableIdentityRejectionDoesNotHideFailedCloseOrRetryOwner() {
        val terminal = IOException("descriptor close failed")
        val owner = Owner(terminal)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }
        val outcome = runCatching {
            cleanup.use(owner, release = { it.release() }) { null }
        }

        assertNull(outcome.getOrNull())
        assertSame(terminal, outcome.exceptionOrNull())
        assertEquals(listOf(terminal), reports)
        assertEquals(1, owner.releases)
    }

    @Test
    fun reportingFailureCannotReplaceProbeOrCleanupFailure() {
        for (primary in listOf(null, IOException("probe failed"))) {
            val terminal = IOException("native release failed")
            val owner = Owner(terminal)
            var reports = 0
            val cleanup = RecordingReadCleanup {
                assertSame(terminal, it)
                reports++
                throw IllegalStateException("report unavailable")
            }
            val outcome = runCatching {
                cleanup.use(owner, release = { it.release() }) {
                    if (primary != null) throw primary
                    "metadata"
                }
            }

            assertSame(primary ?: terminal, outcome.exceptionOrNull())
            if (primary != null) assertEquals(listOf(terminal), primary.suppressed.toList())
            assertEquals(1, reports)
            assertEquals(1, owner.releases)
        }
    }

    @Test
    fun nativeErrorReportsWithoutBroadeningExceptionOnlyMediaFallback() {
        val terminal = AssertionError("native owner failure")
        val owner = Owner(terminal)
        val reports = mutableListOf<Throwable>()
        val cleanup = RecordingReadCleanup { reports += it }
        val observed = assertThrows(AssertionError::class.java) {
            try {
                cleanup.use(owner, release = { it.release() }) { "metadata" }
            } catch (_: Exception) {
                null
            }
        }

        assertSame(terminal, observed)
        assertEquals(listOf(terminal), reports)
        assertEquals(1, owner.releases)
    }
}
