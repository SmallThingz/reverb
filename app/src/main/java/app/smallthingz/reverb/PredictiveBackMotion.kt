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

internal class PredictiveBackMotionState {
    val progress = Animatable(0f)
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
    var gestureActive by mutableStateOf(false)
}

@Composable
internal fun rememberPredictiveBackMotion(
    enabled: Boolean,
    onBack: () -> Unit,
): PredictiveBackMotionState {
    val state = remember { PredictiveBackMotionState() }
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(enabled) {
        if (!enabled) {
            state.gestureActive = false
            state.swipeEdge = BackEventCompat.EDGE_NONE
            state.progress.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = enabled) { events ->
        state.gestureActive = true
        var committed = false
        try {
            events.collect { event ->
                state.swipeEdge = event.swipeEdge
                state.progress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            state.progress.snapTo(1f)
            committed = true
            currentOnBack()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            state.progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            )
        } finally {
            if (committed) state.progress.snapTo(0f)
            state.gestureActive = false
            state.swipeEdge = BackEventCompat.EDGE_NONE
        }
    }

    return state
}

internal fun predictiveBackOpenProgress(backProgress: Float): Float =
    1f - backProgress.coerceIn(0f, 1f)

internal fun predictiveBackHorizontalDirection(swipeEdge: Int): Float =
    if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
