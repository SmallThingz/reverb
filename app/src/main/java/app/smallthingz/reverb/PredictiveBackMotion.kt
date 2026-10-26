package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import kotlin.math.roundToInt

private const val PREDICTIVE_BACK_COMMIT_MAX_DURATION_MS = 220

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
