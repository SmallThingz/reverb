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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The surrounding Audio group owns this section's surface, clipping, and dividers. */
@Composable
internal fun OutputCompressionSettings(
    enabled: Boolean,
    level: Int,
    onEnabledChange: (Boolean) -> Unit,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    levelRange: IntRange = 0..8,
    interactionEnabled: Boolean = true,
) {
    require(levelRange.first < levelRange.last) { "Compression level range needs at least two values" }
    val chrome = appChrome()
    val compressionLevelLabel = stringResource(R.string.compression_level)

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = enabled,
                    enabled = interactionEnabled,
                    role = Role.Switch,
                    onValueChange = onEnabledChange,
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.output_compression),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (interactionEnabled) chrome.ink else chrome.muted,
            )
            Switch(
                checked = enabled,
                onCheckedChange = null,
                enabled = interactionEnabled,
            )
        }

        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Slider(
                    value = level.coerceIn(levelRange).toFloat(),
                    onValueChange = { value ->
                        val next = value.roundToInt().coerceIn(levelRange)
                        if (next != level) onLevelChange(next)
                    },
                    enabled = interactionEnabled && enabled,
                    valueRange = levelRange.first.toFloat()..levelRange.last.toFloat(),
                    steps = levelRange.last - levelRange.first - 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = compressionLevelLabel },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.compression_faster),
                        style = MaterialTheme.typography.bodySmall,
                        color = chrome.muted,
                    )
                    Text(
                        text = stringResource(R.string.compression_smaller),
                        style = MaterialTheme.typography.bodySmall,
                        color = chrome.muted,
                    )
                }
            }
        }
    }
}
