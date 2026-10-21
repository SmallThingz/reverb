package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive

@Composable
internal fun rememberPredictiveBackMotion(
    enabled: Boolean,
    onGestureStarted: (() -> PredictiveBackGestureCallbacks)? = null,
    onBack: () -> Unit,
): PredictiveBackMotionState {
    val state = remember { PredictiveBackMotionState() }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnGestureStarted by rememberUpdatedState(onGestureStarted)

    LaunchedEffect(enabled) {
        if (enabled) {
            // A retained screen can reopen after a committed predictive dismissal. Start the next
            // lifetime from a neutral motion state rather than carrying the old terminal marker.
            state.commitCompleted = false
            if (!state.gestureActive) state.progress.snapTo(0f)
        } else if (!state.commitCompleted) {
            state.gestureActive = false
            state.swipeEdge = BackEventCompat.EDGE_NONE
            state.progress.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = enabled) { events ->
        var gestureCallbacks: PredictiveBackGestureCallbacks? = null
        try {
            // Optional owners pin their navigation before any suspended progress work.
            gestureCallbacks = currentOnGestureStarted?.invoke()
            state.commitCompleted = false
            state.progress.snapTo(0f)
            state.gestureActive = true
            var committed = false
            try {
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
                        committed = true
                        state.commitCompleted = true
                        val commit = gestureCallbacks?.onBack ?: currentOnBack
                        commit()
                    },
                )
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                state.progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                )
            } finally {
                if (!committed) state.commitCompleted = false
                state.gestureActive = false
                state.swipeEdge = BackEventCompat.EDGE_NONE
            }
        } finally {
            gestureCallbacks?.onFinished?.invoke()
        }
    }

    return state
}
