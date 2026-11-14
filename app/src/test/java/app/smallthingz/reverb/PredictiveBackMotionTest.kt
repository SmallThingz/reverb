package app.smallthingz.reverb

import smallthingz.ui.animation.predictiveBackCommitDurationMillis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveBackMotionTest {
    @Test fun libraryToolbar_backBeforeTheFirstOpeningFrameKeepsTheHomeEndpointThroughCancellation() {
        val motion = LibraryTopBarCloseMotion()
        motion.progress(0f, true, false)
        assertEquals(1f, motion.progress(0f, true, true), 0f)
        assertEquals(.75f, motion.progress(.25f, true, true), .0001f)
        assertEquals(0f, motion.progress(1f, true, false), 0f)
    }

    @Test fun libraryToolbar_partialOpeningHandsItsBackGlyphToPredictiveMotionWithoutASwap() {
        val motion = LibraryTopBarCloseMotion()
        assertEquals(0f, motion.progress(.3f, true, false), 0f)
        assertEquals(0f, motion.progress(.3f, true, true), 0f)
        assertEquals(.5f, motion.progress(.15f, true, true), .0001f)
        assertEquals(0f, motion.progress(.3f, true, true), 0f)
        assertEquals(0f, motion.progress(.8f, true, true), 0f)
        assertEquals(0f, motion.progress(1f, true, false), 0f)
    }

    @Test fun libraryToolbar_partialOpeningAcceptedCloseReopenAndPreviewShareTheHeldGlyph() {
        val motion = LibraryTopBarCloseMotion()
        assertEquals(0f, motion.progress(.4f, true, false), 0f)
        motion.onNavigation(wasOpen = true, isOpen = false)
        assertEquals(0f, motion.progress(.4f, false, false), 0f)
        assertEquals(.5f, motion.progress(.2f, false, false), .0001f)
        motion.onNavigation(wasOpen = false, isOpen = true)
        assertEquals(.5f, motion.progress(.2f, true, false), .0001f)
        assertEquals(.25f, motion.progress(.3f, true, false), .0001f)
        assertEquals(.25f, motion.progress(.3f, true, true), .0001f)
        assertEquals(.625f, motion.progress(.15f, true, true), .0001f)
        assertEquals(1f, motion.progress(0f, true, true), 0f)
    }

    @Test fun libraryToolbar_previewCleanupDoesNotResetAnIntermediateGlyph() {
        val motion = LibraryTopBarCloseMotion()
        motion.progress(.4f, true, false)
        motion.progress(.4f, true, true)
        assertEquals(.5f, motion.progress(.2f, true, true), .0001f)
        assertEquals(.5f, motion.progress(.2f, true, false), .0001f)
        assertEquals(.25f, motion.progress(.3f, true, false), .0001f)
        assertEquals(0f, motion.progress(1f, true, false), 0f)
    }

    @Test fun libraryToolbar_acceptedOrdinaryCloseMorphsToHomeAndRetiresItsLatch() {
        val motion = LibraryTopBarCloseMotion()
        motion.onNavigation(wasOpen = true, isOpen = false)
        assertEquals(0f, motion.progress(1f, false, false), 0f)
        assertEquals(.25f, motion.progress(.75f, false, false), 0f)
        assertEquals(.75f, motion.progress(.25f, false, false), 0f)
        assertEquals(1f, motion.progress(0f, false, false), 0f)
        motion.onNavigation(wasOpen = false, isOpen = true)
        assertEquals(0f, motion.progress(.25f, true, false), 0f)
    }

    @Test fun libraryToolbar_interruptedCloseReopensAlongTheSameMorph() {
        val motion = LibraryTopBarCloseMotion()
        motion.onNavigation(wasOpen = true, isOpen = false)
        assertEquals(.6f, motion.progress(.4f, false, false), .0001f)
        motion.onNavigation(wasOpen = false, isOpen = true)
        assertEquals(.6f, motion.progress(.4f, true, false), .0001f)
        assertEquals(.2f, motion.progress(.8f, true, false), .0001f)
        assertEquals(0f, motion.progress(1f, true, false), 0f)
        assertEquals(0f, motion.progress(.7f, true, false), 0f)
    }

    @Test fun libraryToolbar_initialAndCancelledOpeningNeverStartACloseMorph() {
        val motion = LibraryTopBarCloseMotion()
        assertEquals(0f, motion.progress(.2f, false, false), 0f)
        assertEquals(0f, motion.progress(.6f, false, false), 0f)
        assertEquals(0f, motion.progress(.3f, false, false), 0f)
        assertEquals(0f, motion.progress(0f, false, false), 0f)
        motion.onNavigation(wasOpen = false, isOpen = true)
        assertEquals(0f, motion.progress(.4f, true, false), 0f)
        assertEquals(0f, motion.progress(1f, true, false), 0f)
    }

    @Test fun libraryToolbar_predictiveReversalAndCancellationKeepTheirExistingProgress() {
        val motion = LibraryTopBarCloseMotion()
        assertEquals(.7f, motion.progress(.3f, true, true), .0001f)
        assertEquals(.2f, motion.progress(.8f, true, true), .0001f)
        assertEquals(0f, motion.progress(1f, true, true), 0f)
        assertEquals(0f, motion.progress(1f, true, false), 0f)
        motion.onNavigation(wasOpen = true, isOpen = false)
        assertEquals(1f, motion.progress(0f, false, true), 0f)
    }

    @Test
    fun committedRelease_continuesToTerminalBeforeLogicalBack() = runBlocking {
        val events = mutableListOf<String>()
        var observedDuration = -1

        continuePredictiveBackCommit(
            currentProgress = 0.4f,
            animateToEnd = { durationMillis ->
                observedDuration = durationMillis
                events += "animate"
            },
            onBack = { events += "back" },
        )

        assertEquals(listOf("animate", "back"), events)
        assertEquals(132, observedDuration)
    }

    @Test
    fun commitContinuationDuration_scalesWithRemainingDistanceAndClamps() {
        assertEquals(220, predictiveBackCommitDurationMillis(-1f))
        assertEquals(220, predictiveBackCommitDurationMillis(0f))
        assertEquals(110, predictiveBackCommitDurationMillis(0.5f))
        assertEquals(22, predictiveBackCommitDurationMillis(0.9f))
        assertEquals(0, predictiveBackCommitDurationMillis(1f))
        assertEquals(0, predictiveBackCommitDurationMillis(2f))
    }

    @Test
    fun retainedPanel_newForwardMotionClearsCompletedDismissal() {
        val state = PredictiveBackMotionState().apply { commitCompleted = true }

        state.beginForwardMotion()

        assertFalse(state.commitCompleted)
    }

    @Test
    fun retainedPanel_predictiveCommitNeverFallsBackToOpenSettledState() {
        assertEquals(
            0f,
            predictiveBackPanelOpenProgress(
                settledProgress = 1f,
                gestureActive = false,
                gestureProgress = 1f,
                commitCompleted = true,
                surfaceVisible = false,
            ),
            0f,
        )
        assertEquals(
            0.65f,
            predictiveBackPanelOpenProgress(
                settledProgress = 0.65f,
                gestureActive = false,
                gestureProgress = 1f,
                commitCompleted = false,
                surfaceVisible = false,
            ),
            0f,
        )
        assertEquals(
            0.7f,
            predictiveBackPanelOpenProgress(
                settledProgress = 1f,
                gestureActive = true,
                gestureProgress = 0.3f,
                commitCompleted = false,
                surfaceVisible = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun retainedPanel_commitMarkerDoesNotSuppressAVisibleReopen() {
        val progress = predictiveBackPanelOpenProgress(
            settledProgress = 0.4f,
            gestureActive = false,
            gestureProgress = 1f,
            commitCompleted = true,
            surfaceVisible = true,
        )

        assertEquals(0.4f, progress, 0f)
        assertTrue(progress > 0f)
    }
}
