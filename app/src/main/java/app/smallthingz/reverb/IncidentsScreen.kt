package app.smallthingz.reverb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IncidentTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm:ss a", Locale.getDefault())

internal fun formatRecordingIncidentTime(timestampMillis: Long): String =
    IncidentTimeFormatter.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

@Composable
internal fun IncidentsScreen(
    incidents: List<RecordingIncident>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backProgress: Float = 0f,
    backDirection: Float = 1f,
) {
    val noiseBrush = rememberAppNoiseBrush()
    val progress = backProgress.coerceIn(0f, 1f)
    Surface(
        modifier = modifier
            .graphicsLayer {
                translationX = backDirection * size.width * 0.08f * progress
                alpha = 1f - progress * 0.18f
            },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .appNoise(noiseBrush),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(AppTopBarContentHeight)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
                    Icon(
                        imageVector = AppIcons.back,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.incidents_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (incidents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.incidents,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            text = stringResource(R.string.incidents_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.incidents_empty_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = 12.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = incidents.asReversed(),
                        key = { incident -> "${incident.kind.storageCode}:${incident.occurredAtMillis}" },
                    ) { incident ->
                        val timestamp = remember(incident.occurredAtMillis) {
                            formatRecordingIncidentTime(incident.occurredAtMillis)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.54f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(
                                    imageVector = AppIcons.incidents,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = timestamp,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = stringResource(R.string.incident_unexpected_shutdown),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
