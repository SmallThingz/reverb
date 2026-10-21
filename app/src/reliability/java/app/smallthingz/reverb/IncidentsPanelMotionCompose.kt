package app.smallthingz.reverb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

@Composable
internal fun rememberIncidentsPanelMotion(open: Boolean, durationMillis: Int): IncidentsPanelMotionState {
    val scope = rememberCoroutineScope()
    val motion = remember { IncidentsPanelMotionState(open, scope, durationMillis) }
    LaunchedEffect(open) { motion.settleTo(open) }
    return motion
}
