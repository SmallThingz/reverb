package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.runtime.getValue
import smallthingz.ui.runtime.mutableIntStateOf
import smallthingz.ui.runtime.mutableFloatStateOf
import smallthingz.ui.runtime.mutableStateOf
import smallthingz.ui.runtime.setValue
import kotlinx.coroutines.Job

// Incidents owns interrupted entry and Back return independently of the other panels.
internal class IncidentsPredictiveBackMotionState {
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


internal fun incidentsPanelOpenProgress(
    settledProgress: Float,
    gestureActive: Boolean,
    gestureProgress: Float,
    commitCompleted: Boolean,
    surfaceVisible: Boolean,
    gestureStartOpenProgress: Float = 1f,
): Float = when {
    gestureActive -> gestureStartOpenProgress.coerceIn(0f, 1f) * predictiveBackOpenProgress(gestureProgress)
    commitCompleted && !surfaceVisible -> 0f
    else -> settledProgress.coerceIn(0f, 1f)
}
