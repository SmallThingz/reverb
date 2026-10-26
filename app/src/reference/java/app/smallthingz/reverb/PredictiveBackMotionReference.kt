package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.runtime.getValue
import smallthingz.ui.runtime.mutableIntStateOf
import smallthingz.ui.runtime.mutableStateOf
import smallthingz.ui.runtime.setValue
import kotlin.math.roundToInt

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

internal class PredictiveBackGestureCallbacks(
    val onBack: () -> Unit,
    val onFinished: () -> Unit,
)
