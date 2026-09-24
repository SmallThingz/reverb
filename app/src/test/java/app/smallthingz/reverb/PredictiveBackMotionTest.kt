package app.smallthingz.reverb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveBackMotionTest {
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
