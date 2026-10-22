package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import app.smallthingz.nativeui.animation.core.Animatable
import app.smallthingz.nativeui.runtime.getValue
import app.smallthingz.nativeui.runtime.mutableIntStateOf
import app.smallthingz.nativeui.runtime.mutableStateOf
import app.smallthingz.nativeui.runtime.setValue
import kotlin.math.roundToInt

private const val PREDICTIVE_BACK_COMMIT_MAX_DURATION_MS = 220

internal class PredictiveBackMotionState {
    val progress = Animatable(0f)
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
    var gestureActive by mutableStateOf(false)
    var commitCompleted by mutableStateOf(false)

    fun beginForwardMotion() {
        // A retained panel must not carry a completed Back dismissal into its next reveal.
        commitCompleted = false
    }
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

internal class PredictiveBackGestureCallbacks(
    val onBack: () -> Unit,
    val onFinished: () -> Unit,
)


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
