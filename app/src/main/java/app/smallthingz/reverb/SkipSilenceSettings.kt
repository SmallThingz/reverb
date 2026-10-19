package app.smallthingz.reverb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Edits the enclosing Settings draft. Sampling and cancellation belong to the caller:
 * opening the control does not start the microphone or persist a change.
 *
 * The containing audio section owns its continuous background, outline and separators.
 */
@Composable
internal fun SkipSilenceSettings(
    config: SilenceGateConfig,
    onConfigChange: (SilenceGateConfig) -> Unit,
    onCalibrate: () -> Unit,
    onBeginHeldCalibration: () -> Boolean,
    onCalibrationTap: () -> Unit,
    onFinishHeldCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maximumHz: Int = 20_000,
    sampledPeaks: List<Float>? = null,
    calibrationAllowed: Boolean = true,
    calibrating: Boolean = false,
    holdingCalibration: Boolean = false,
    calibrationProgress: Float? = null,
    calibrationError: String? = null,
    calibrationUnavailableReason: String? = null,
) {
    val chrome = appChrome()
    val colors = MaterialTheme.colorScheme
    val currentConfig by rememberUpdatedState(config)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCalibrating by rememberUpdatedState(calibrating)
    val canBeginCalibration by rememberUpdatedState(
        enabled && config.enabled && calibrationAllowed && !calibrating,
    )
    val beginHeldCalibration by rememberUpdatedState(onBeginHeldCalibration)
    val completeCalibrationTap by rememberUpdatedState(onCalibrationTap)
    val finishHeldCalibration by rememberUpdatedState(onFinishHeldCalibration)
    val cancelCalibration by rememberUpdatedState(onCancelCalibration)
    var calibrationPointerDown by remember { mutableStateOf(false) }
    val changeConfig by rememberUpdatedState(onConfigChange)
    var selected by remember { mutableIntStateOf(1.coerceAtMost(config.points.lastIndex)) }
    val selectedPoint = selected.coerceIn(config.points.indices)

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = config.enabled,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = {
                        if (calibrating) onCancelCalibration()
                        changeConfig(currentConfig.copy(enabled = it))
                    },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.silence_title),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                color = chrome.ink,
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = config.enabled,
                enabled = enabled,
                onCheckedChange = null,
            )
        }
        AnimatedVisibility(
            visible = config.enabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AndroidView(
                    factory = { SilenceCurveView(it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 245.dp),
                    update = { curve ->
                        curve.bind(
                            config = config,
                            selectedPoint = selectedPoint,
                            peaks = sampledPeaks,
                            maxHz = maximumHz,
                            accentColor = colors.primary.toArgb(),
                            inkColor = chrome.ink.toArgb(),
                            mutedColor = chrome.muted.toArgb(),
                            editable = enabled && config.enabled && !calibrating,
                        )
                        curve.onCommit = { points, index ->
                            if (currentEnabled && currentConfig.enabled && !currentCalibrating) {
                                selected = index.coerceIn(points.indices)
                                changeConfig(currentConfig.copy(points = points))
                            }
                        }
                        curve.onSelect = { index ->
                            if (currentEnabled && currentConfig.enabled && !currentCalibrating) {
                                selected = index.coerceIn(currentConfig.points.indices)
                            }
                        }
                    },
                    onReset = null,
                    onRelease = { curve ->
                        curve.onCommit = null
                        curve.onSelect = null
                        curve.bind(
                            currentConfig,
                            selected.coerceIn(currentConfig.points.indices),
                            null,
                            maximumHz,
                            colors.primary.toArgb(),
                            chrome.ink.toArgb(),
                            chrome.muted.toArgb(),
                            false,
                        )
                    },
                )
                // Keep this action before changing status text: starting a sample must not move
                // the button away from the finger that owns its held gesture.
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            // Keyboard and accessibility clicks have no pointer lifetime.
                            if (!calibrationPointerDown && canBeginCalibration) onCalibrate()
                        },
                        enabled = enabled && config.enabled &&
                            (calibrationAllowed || calibrationPointerDown) &&
                            (!calibrating || calibrationPointerDown),
                        modifier = Modifier.weight(1f).pointerInput(Unit) {
                            // Neither callback updates nor running/progress recomposition may
                            // restart this detector while its original pointer is still down.
                            awaitEachGesture {
                                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                if (!canBeginCalibration) return@awaitEachGesture
                                calibrationPointerDown = true
                                var cancelled = false
                                var released = false
                                var ownsSample = false
                                fun cancelSample() {
                                    if (!cancelled) {
                                        cancelled = true
                                        if (ownsSample) {
                                            ownsSample = false
                                            cancelCalibration()
                                        }
                                    }
                                }
                                try {
                                    // Start on DOWN; the release chooses timed versus held.
                                    // Leave DOWN/moves available to the parent scroll container.
                                    ownsSample = beginHeldCalibration()
                                    cancelled = !ownsSample
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null) {
                                            cancelSample()
                                            break
                                        }
                                        if (change.isConsumed ||
                                            event.changes.any { it.id != down.id && it.pressed } ||
                                            change.position.x < 0f || change.position.y < 0f ||
                                            change.position.x > size.width || change.position.y > size.height ||
                                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                        ) cancelSample()
                                        if (!change.pressed) {
                                            // Suppress TextButton's pointer click, including after
                                            // cancellation; it must not start a second timed run.
                                            change.consume()
                                            released = true
                                            if (!cancelled) {
                                                ownsSample = false
                                                if (change.uptimeMillis - down.uptimeMillis >=
                                                    viewConfiguration.longPressTimeoutMillis
                                                ) finishHeldCalibration() else completeCalibrationTap()
                                            }
                                            break
                                        }
                                        // A parent may claim scrolling later in this event. The
                                        // button's own DOWN consumption is intentionally ignored.
                                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                                        val finalChange = finalEvent.changes.firstOrNull { it.id == down.id }
                                        if (finalChange != null && finalChange.isConsumed &&
                                            finalChange.position != finalChange.previousPosition
                                        ) cancelSample()
                                    }
                                } finally {
                                    calibrationPointerDown = false
                                    if (!released) cancelSample()
                                }
                            }
                        },
                    ) {
                        Text(stringResource(
                            if (holdingCalibration) R.string.silence_release_to_finish
                            else R.string.silence_calibrate,
                        ))
                    }
                    if (calibrating && !holdingCalibration && !calibrationPointerDown) {
                        TextButton(onClick = onCancelCalibration) { Text(stringResource(R.string.cancel)) }
                    }
                }
                Text(
                    text = stringResource(R.string.silence_calibration_hint),
                    color = chrome.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (calibrating && !holdingCalibration) {
                    Text(
                        text = stringResource(R.string.silence_sampling),
                        color = chrome.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    calibrationProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                calibrationError?.let { message ->
                    Text(
                        text = message,
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (!calibrationAllowed && !calibrating) {
                    calibrationUnavailableReason?.let { message ->
                        Text(
                            text = message,
                            color = chrome.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
