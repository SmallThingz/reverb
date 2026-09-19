package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class InlinePlaybackErrorCleanupTest {
    @Test
    fun playbackError_releasesResourcesBeforeThrowingFailureCallback() {
        val events = mutableListOf<String>()
        val expected = IllegalStateException("ui callback failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            handleInlinePlaybackError(
                ownsPlaybackResources = true,
                shouldReportFailure = true,
                releaseResources = { events += "release" },
                reportFailure = {
                    events += "callback"
                    throw expected
                },
            )
        }

        assertSame(expected, thrown)
        assertEquals(listOf("release", "callback"), events)
    }

    @Test
    fun playbackError_releasesOwnedResourcesWhenFailureUiIsUnavailable() {
        val events = mutableListOf<String>()
        handleInlinePlaybackError(
            ownsPlaybackResources = true,
            shouldReportFailure = false,
            releaseResources = { events += "release" },
            reportFailure = { events += "callback" },
        )
        assertEquals(listOf("release"), events)
    }

    @Test
    fun stalePlaybackError_doesNotReleaseReplacementResources() {
        val events = mutableListOf<String>()
        handleInlinePlaybackError(
            ownsPlaybackResources = false,
            shouldReportFailure = false,
            releaseResources = { events += "release" },
            reportFailure = { events += "callback" },
        )
        assertEquals(emptyList<String>(), events)
    }
}
