package app.smallthingz.reverb

import smallthingz.ui.animation.predictiveBackCommitDurationMillis

import androidx.activity.BackEventCompat
import kotlin.math.roundToInt

internal suspend inline fun continuePredictiveBackCommit(
    currentProgress: Float,
    crossinline animateToEnd: suspend (durationMillis: Int) -> Unit,
    onBack: () -> Unit,
) {
    animateToEnd(predictiveBackCommitDurationMillis(currentProgress))
    onBack()
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
