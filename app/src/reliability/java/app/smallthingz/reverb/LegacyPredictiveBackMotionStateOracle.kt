package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

internal class LegacyPredictiveBackMotionState {
    val progress = Animatable(0f)
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
    var gestureActive by mutableStateOf(false)
    var commitCompleted by mutableStateOf(false)

    fun beginForwardMotion() {
        // A retained panel must not carry a completed Back dismissal into its next reveal.
        commitCompleted = false
    }
}
