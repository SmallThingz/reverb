package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

@Composable
internal fun IncidentsScreen(
    state: IncidentHistoryUiState,
    onBack: () -> Unit,
    onToggleAcknowledged: (RecordingIncident) -> Unit,
    onDelete: (RecordingIncident) -> Unit,
    modifier: Modifier = Modifier,
    openProgress: () -> Float = { 1f },
) {
    val history = state.history
    val noiseBrush = rememberAppNoiseBrush()
    val topBarNoiseBrush = rememberAppNoiseBrush(APP_NOISE_SEED_TOP_BAR)
    Surface(
        modifier = modifier.graphicsLayer {
            val progress = openProgress().coerceIn(0f, 1f)
            translationX = incidentsPanelTranslationX(size.width, progress)
            alpha = 0.82f + progress * 0.18f
        },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().appNoise(noiseBrush)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .appNoise(topBarNoiseBrush)
                    .statusBarsPadding()
                    .height(AppTopBarContentHeight)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
                    Icon(AppIcons.back, contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = stringResource(R.string.incidents_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (history.rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.status == IncidentHistoryLoadStatus.LOADING) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(AppIcons.incidents, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                        }
                        if (state.showEmptyHistory) {
                            Text(stringResource(R.string.incidents_empty), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.incidents_empty_detail),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                stringResource(
                                    if (state.status == IncidentHistoryLoadStatus.LOADING) R.string.incidents_loading
                                    else R.string.incidents_unavailable,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.status == IncidentHistoryLoadStatus.UNAVAILABLE) {
                        item(key = "incident-history-status") {
                            Text(
                                stringResource(R.string.incidents_stale),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    items(
                        items = history.rows,
                        key = { it.key },
                    ) { row ->
                        IncidentCard(
                            row = row,
                            canMutate = state.canMutate,
                            modifier = Modifier.animateItem(),
                            onToggleAcknowledged = { onToggleAcknowledged(row.incident) },
                            onDelete = { onDelete(row.incident) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentCard(
    row: IncidentPresentation,
    canMutate: Boolean,
    onToggleAcknowledged: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuCreated by remember { mutableStateOf(false) }
    val openMenu = { menuCreated = true; menuExpanded = true }
    val incident = row.incident
    val unacknowledged = !incident.acknowledged
    val date = row.date
    val stopSummary = row.stopSummary
    val cause = row.cause
    val runtime = row.runtime
    val age = row.age
    val description = row.description
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.incident_copy_label)
    val copiedMessage = stringResource(R.string.incident_copied)
    val copyIncident = {
        val copyText = buildList {
            add(date)
            add(stopSummary)
            if (cause.isNotEmpty()) add(cause)
            if (runtime.isNotEmpty()) add(runtime)
            if (age.isNotEmpty()) add(age)
            if (description != null) add(description)
        }.joinToString("\n")
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(copyLabel, copyText),
        )
        AppFeedbackCenter.post(copiedMessage, FeedbackTone.SUCCESS)
    }
    val hasDetails = row.hasDetails
    val border = if (unacknowledged) MaterialTheme.colorScheme.error.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
    val fill = if (unacknowledged) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f) else MaterialTheme.colorScheme.surfaceContainerHigh

    Box(modifier) {
        val cardShape = RoundedCornerShape(18.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .combinedClickable(
                    onClickLabel = if (canMutate) null else stringResource(R.string.open_options),
                    onClick = if (canMutate) onToggleAcknowledged else openMenu,
                    onLongClick = openMenu,
                ),
            shape = cardShape,
            color = fill,
            border = BorderStroke(1.dp, border),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(date, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(stopSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClickLabel = if (canMutate) null else stringResource(R.string.open_options),
                                onClick = if (canMutate) onToggleAcknowledged else openMenu,
                                onLongClick = openMenu,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (incident.acknowledged) AppIcons.checked else AppIcons.unchecked,
                            contentDescription = if (canMutate) stringResource(
                                if (incident.acknowledged) R.string.incident_mark_unchecked
                                else R.string.incident_mark_checked,
                            ) else null,
                            tint = if (unacknowledged) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                if (hasDetails) {
                    HorizontalDivider(color = border.copy(alpha = 0.55f))
                    if (cause.isNotEmpty()) Text(cause, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (runtime.isNotEmpty()) Text(runtime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (age.isNotEmpty()) Text(age, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Create on first use, then retain through the popup exit animation.
        if (menuCreated) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    text = { Text(stringResource(R.string.incident_copy)) },
                    leadingIcon = { Icon(AppIcons.copy, contentDescription = null) },
                    onClick = { menuExpanded = false; copyIncident() },
                )
                if (canMutate) {
                    DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                        text = { Text(stringResource(R.string.delete_recording), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(AppIcons.delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}
