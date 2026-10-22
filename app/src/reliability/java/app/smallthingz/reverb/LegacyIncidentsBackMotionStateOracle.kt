package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job

internal class LegacyIncidentsPredictiveBackMotionState {
    val progress = Animatable(0f)
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
    var gestureActive by mutableStateOf(false)
    var commitCompleted by mutableStateOf(false)
    var startOpenProgress by mutableFloatStateOf(1f)
    var generation = 0L
        private set
    var cancellationJob: Job? = null

    fun claimGesture(): Long {
        generation++
        cancellationJob?.cancel()
        cancellationJob = null
        gestureActive = false
        swipeEdge = BackEventCompat.EDGE_NONE
        return generation
    }

    fun abandonGesture(onDisabled: (interruptedOpenProgress: Float?) -> Unit) {
        val visibleProgress = if (gestureActive) {
            startOpenProgress * predictiveBackOpenProgress(progress.value)
        } else null
        claimGesture()
        onDisabled(visibleProgress)
    }

    fun beginForwardMotion() {
        // A retained panel must not carry a completed Back dismissal into its next reveal.
        claimGesture()
        commitCompleted = false
    }


}
