package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

@Composable
internal fun rememberIncidentsPredictiveBackMotion(
    enabled: Boolean,
    onBack: () -> Unit,
    onStarted: (interruptedOpenProgress: Float?) -> Float = { 1f },
    onCancelled: () -> Unit = {},
    onDisabled: (interruptedOpenProgress: Float?) -> Unit = {},
): IncidentsPredictiveBackMotionState {
    val state = remember { IncidentsPredictiveBackMotionState() }
    val returnScope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnStarted by rememberUpdatedState(onStarted)
    val currentOnCancelled by rememberUpdatedState(onCancelled)
    val currentOnDisabled by rememberUpdatedState(onDisabled)
    val currentEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(enabled) {
        if (enabled) {
            // A retained screen can reopen after a committed predictive dismissal. Start the next
            // lifetime from a neutral motion state rather than carrying the old terminal marker.
            state.commitCompleted = false
            if (!state.gestureActive) state.progress.snapTo(0f)
        } else if (!state.commitCompleted) {
            state.abandonGesture(currentOnDisabled)
            state.progress.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = enabled) { events ->
        val interruptedProgress = if (state.gestureActive) {
            state.startOpenProgress * predictiveBackOpenProgress(state.progress.value)
        } else null
        val owner = state.claimGesture()
        state.commitCompleted = false
        var committed = false
        var cancellationScheduled = false
        try {
            state.startOpenProgress = currentOnStarted(interruptedProgress).coerceIn(0f, 1f)
            state.progress.snapTo(0f)
            state.gestureActive = true
            events.collect { event ->
                state.swipeEdge = event.swipeEdge
                state.progress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            // Gesture completion is only the decision boundary. Continue from the exact release
            // point to the visual terminal before mutating navigation state; resetting to zero here
            // makes every retained destination visibly snap back open before its normal close runs.
            continuePredictiveBackCommit(
                currentProgress = state.progress.value,
                animateToEnd = { durationMillis ->
                    if (durationMillis == 0) {
                        state.progress.snapTo(1f)
                    } else {
                        state.progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = durationMillis,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                },
                onBack = {
                    if (state.generation == owner && currentEnabled) {
                        committed = true
                        state.commitCompleted = true
                        currentOnBack()
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            // Android cancels the handler's coroutine for a cancelled gesture. Animate the
            // return in the still-live composition scope, not in that already-cancelled job.
            if (state.generation == owner && currentEnabled && returnScope.isActive) {
                cancellationScheduled = true
                state.cancellationJob = returnScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        state.progress.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
                    } finally {
                        if (state.generation == owner) {
                            state.gestureActive = false
                            state.swipeEdge = BackEventCompat.EDGE_NONE
                            if (currentEnabled && returnScope.isActive) currentOnCancelled()
                        }
                    }
                }
            }
            throw cancelled
        } finally {
            if (state.generation == owner && !cancellationScheduled) {
                if (!committed) state.commitCompleted = false
                state.gestureActive = false
                state.swipeEdge = BackEventCompat.EDGE_NONE
                if (!committed && currentEnabled && returnScope.isActive) currentOnCancelled()
            }
        }
    }

    return state
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
