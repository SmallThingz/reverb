package app.smallthingz.reverb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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
    onCancelCalibration: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maximumHz: Int = 20_000,
    sampledPeaks: List<Float>? = null,
    calibrationAllowed: Boolean = true,
    calibrating: Boolean = false,
    calibrationProgress: Float? = null,
    calibrationMessage: String? = null,
    calibrationError: String? = null,
    calibrationUnavailableReason: String? = null,
) {
    val chrome = appChrome()
    val colors = MaterialTheme.colorScheme
    val currentConfig by rememberUpdatedState(config)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCalibrating by rememberUpdatedState(calibrating)
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
                if (calibrating) {
                    Text(
                        text = calibrationMessage ?: stringResource(R.string.silence_sampling),
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
                TextButton(
                    onClick = { if (calibrating) onCancelCalibration() else onCalibrate() },
                    enabled = enabled && config.enabled && (calibrating || calibrationAllowed),
                ) {
                    Text(stringResource(if (calibrating) R.string.cancel else R.string.silence_calibrate))
                }
            }
        }
    }
}
