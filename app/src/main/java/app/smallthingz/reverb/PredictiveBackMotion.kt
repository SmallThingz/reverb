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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private const val PREDICTIVE_BACK_COMMIT_MAX_DURATION_MS = 220

internal class PredictiveBackMotionState {
    val progress = Animatable(0f)
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
    var gestureActive by mutableStateOf(false)
    var commitCompleted by mutableStateOf(false)
}

internal fun predictiveBackCommitDurationMillis(progress: Float): Int {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    if (remaining <= 0f) return 0
    return (PREDICTIVE_BACK_COMMIT_MAX_DURATION_MS * remaining)
        .roundToInt()
        .coerceAtLeast(1)
}

internal suspend inline fun continuePredictiveBackCommit(
    currentProgress: Float,
    crossinline animateToEnd: suspend (durationMillis: Int) -> Unit,
    onBack: () -> Unit,
) {
    animateToEnd(predictiveBackCommitDurationMillis(currentProgress))
    onBack()
}

@Composable
internal fun rememberPredictiveBackMotion(
    enabled: Boolean,
    onBack: () -> Unit,
): PredictiveBackMotionState {
    val state = remember { PredictiveBackMotionState() }
    val currentOnBack by rememberUpdatedState(onBack)

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
                    currentOnBack()
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
    }

    return state
}

internal fun predictiveBackOpenProgress(backProgress: Float): Float =
    1f - backProgress.coerceIn(0f, 1f)

internal fun predictiveBackPanelOpenProgress(
    settledProgress: Float,
    gestureActive: Boolean,
    gestureProgress: Float,
    commitCompleted: Boolean,
    surfaceVisible: Boolean,
): Float = when {
    gestureActive -> predictiveBackOpenProgress(gestureProgress)
    commitCompleted && !surfaceVisible -> 0f
    else -> settledProgress.coerceIn(0f, 1f)
}

internal fun predictiveBackHorizontalDirection(swipeEdge: Int): Float =
    if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
