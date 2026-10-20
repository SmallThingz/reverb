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

    @Test
    fun pausedFailure_releasesAndCollapsesBeforeQueueingFeedbackWithoutNavigation() {
        val events = mutableListOf<String>()
        handleInlinePlaybackError(
            ownsPlaybackResources = true,
            shouldReportFailure = true,
            releaseResources = { events += "release" },
            reportFailure = {
                finishInlinePlaybackFailure(
                    retireFailedPlayer = { events += "collapse"; true },
                    navigationAllowed = { false },
                    openRecording = { events += "open" },
                    reportUnavailable = { events += "feedback" },
                )
            },
        )
        assertEquals(listOf("release", "collapse", "feedback"), events)
    }

    @Test
    fun currentForegroundFailure_keepsOwnershipProofAcrossItsOwnCollapse() {
        val events = mutableListOf<String>()
        var expanded = "failed"
        finishInlinePlaybackFailure(
            retireFailedPlayer = {
                val owned = expanded == "failed"
                if (owned) expanded = ""
                events += "collapse"
                owned
            },
            navigationAllowed = { true },
            openRecording = { events += "open" },
            reportUnavailable = { events += "feedback" },
        )
        assertEquals("", expanded)
        assertEquals(listOf("collapse", "open"), events)
    }

    @Test
    fun collapseCallbackRetiringForegroundAuthority_preventsTheFinalLaunch() {
        val events = mutableListOf<String>()
        var foreground = true
        finishInlinePlaybackFailure(
            retireFailedPlayer = { foreground = false; events += "collapse"; true },
            navigationAllowed = { foreground },
            openRecording = { events += "open" },
            reportUnavailable = { events += "feedback" },
        )
        assertEquals(listOf("collapse", "feedback"), events)
    }

    @Test
    fun obsoleteFailure_cannotCollapseOrOpenTheReplacementRow() {
        val events = mutableListOf<String>()
        var expanded = "replacement"
        finishInlinePlaybackFailure(
            retireFailedPlayer = {
                val owned = expanded == "failed"
                if (owned) expanded = ""
                owned
            },
            navigationAllowed = { true },
            openRecording = { events += "open" },
            reportUnavailable = { events += "feedback" },
        )
        assertEquals("replacement", expanded)
        assertEquals(listOf("feedback"), events)
    }

    @Test
    fun rejectedForegroundLaunch_reportsFailureOnceAfterSettlingThePlayer() {
        val events = mutableListOf<String>()
        finishInlinePlaybackFailure(
            retireFailedPlayer = { events += "collapse"; true },
            navigationAllowed = { true },
            openRecording = { events += "open"; throw IllegalStateException("start rejected") },
            reportUnavailable = { events += "feedback" },
        )
        assertEquals(listOf("collapse", "open", "feedback"), events)
    }
}
