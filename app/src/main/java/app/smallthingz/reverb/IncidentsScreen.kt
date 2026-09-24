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

private fun incidentDateFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)

private fun incidentClockFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm:ss a", locale)

internal fun formatRecordingIncidentTime(timestampMillis: Long): String =
    incidentDateFormatter().format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

internal fun recordingIncidentDowntimeMillis(incident: RecordingIncident): Long? =
    incident.resumedAtMillis.takeIf { it > 0L }?.let { resumed ->
        (resumed - incident.occurredAtMillis).coerceAtLeast(0L)
    }

internal fun recordingExitReasonLabel(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_UNKNOWN -> "Unknown process exit"
    ApplicationExitInfo.REASON_EXIT_SELF -> "Process exited"
    EXIT_REASON_ANOMALY -> "Anomaly"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_CRASH -> "Crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Resource limit"
    ApplicationExitInfo.REASON_FREEZER -> "Freezer"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
    EXIT_REASON_MEMORY_LIMITER -> "Memory limiter"
    ApplicationExitInfo.REASON_SIGNALED -> "Signaled"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission change"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested stop"
    ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
    ApplicationExitInfo.REASON_OTHER -> "System stop"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Package state change"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Package updated"
    else -> "Process exit $reason"
}

internal fun formatIncidentStopSummary(
    incident: RecordingIncident,
    clockFormatter: DateTimeFormatter = incidentClockFormatter(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val stoppedAt = clockFormatter.format(
        Instant.ofEpochMilli(incident.occurredAtMillis).atZone(zone),
    )
    val duration = recordingIncidentDowntimeMillis(incident)?.let { millis ->
        val seconds = (millis / 1_000L + if (millis % 1_000L == 0L) 0L else 1L)
            .coerceAtLeast(1L)
        formatDurationInput(seconds)
    } ?: if (incident.recoveryPending) {
        "…"
    } else {
        "?"
    }
    return "Stopped at $stoppedAt for $duration"
}

private fun formatIncidentMemory(kb: Long): String {
    if (kb < 0L) return ""
    if (kb < 1024L) return "$kb KiB"
    val mib = kb / 1024.0
    return if (mib >= 100.0) "${mib.roundToLong()} MiB" else String.format(Locale.getDefault(), "%.1f MiB", mib)
}

private fun incidentImportanceLabel(importance: Int): String? = when (importance) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Foreground service"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Service"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "Sleeping"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "Unsavable"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "Cached"
    500 -> "Empty"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "Gone"
    else -> importance.takeIf { it != Int.MIN_VALUE }?.let { "Importance $it" }
}

private fun signalLabel(signal: Int): String = when (signal) {
    1 -> "SIGHUP (1)"
    2 -> "SIGINT (2)"
    3 -> "SIGQUIT (3)"
    6 -> "SIGABRT (6)"
    9 -> "SIGKILL (9)"
    11 -> "SIGSEGV (11)"
    15 -> "SIGTERM (15)"
    else -> "signal $signal"
}

private fun incidentCauseLine(incident: RecordingIncident): String = buildList {
    if (incident.exitReason != 0) add(recordingExitReasonLabel(incident.exitReason))
    if (incident.exitStatus != Int.MIN_VALUE) {
        add(if (incident.exitReason == ApplicationExitInfo.REASON_SIGNALED) signalLabel(incident.exitStatus) else "status ${incident.exitStatus}")
    }
    if (incident.pid > 0) add("PID ${incident.pid}")
}.joinToString(" · ")

private fun incidentRuntimeLine(incident: RecordingIncident): String = buildList {
    incidentImportanceLabel(incident.importance)?.let(::add)
    if (incident.rssKb >= 0L) add("RSS ${formatIncidentMemory(incident.rssKb)}")
    if (incident.pssKb > 0L) add("PSS ${formatIncidentMemory(incident.pssKb)}")
}.joinToString(" · ")

private fun incidentAgeLine(incident: RecordingIncident): String = buildList {
    if (incident.processStartedAtMillis > 0L && incident.occurredAtMillis >= incident.processStartedAtMillis) {
        add("process ${formatDurationInput((incident.occurredAtMillis - incident.processStartedAtMillis) / 1000L)}")
    }
    if (incident.captureArmedAtMillis > 0L && incident.occurredAtMillis >= incident.captureArmedAtMillis) {
        add("capture ${formatDurationInput((incident.occurredAtMillis - incident.captureArmedAtMillis) / 1000L)}")
    }
}.joinToString(" · ")

// Prepared off the UI thread once per durable revision, not when a lazy row enters composition.
internal data class IncidentPresentation(
    val incident: RecordingIncident,
    val key: String,
    val date: String,
    val stopSummary: String,
    val cause: String,
    val runtime: String,
    val age: String,
    val description: String?,
) {
    val hasDetails: Boolean
        get() = cause.isNotEmpty() || runtime.isNotEmpty() || age.isNotEmpty() || description != null
}

internal class IncidentHistoryPresentation(val rows: List<IncidentPresentation>) {
    val hasAlert: Boolean = rows.any { !it.incident.acknowledged }
}

internal fun prepareIncidentHistory(
    incidents: List<RecordingIncident>,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): IncidentHistoryPresentation {
    val dateFormatter = incidentDateFormatter(locale)
    val clockFormatter = incidentClockFormatter(locale)
    return IncidentHistoryPresentation(incidents.asReversed().map { incident ->
        IncidentPresentation(
            incident = incident,
            key = "${incident.kind.storageCode}:${incident.occurredAtMillis}",
            date = dateFormatter.format(Instant.ofEpochMilli(incident.occurredAtMillis).atZone(zone)),
            stopSummary = formatIncidentStopSummary(incident, clockFormatter, zone),
            cause = incidentCauseLine(incident),
            runtime = incidentRuntimeLine(incident),
            age = incidentAgeLine(incident),
            description = incident.description?.takeIf { it.isNotBlank() },
        )
    })
}

@Composable
internal fun IncidentsScreen(
    history: IncidentHistoryPresentation,
    onBack: () -> Unit,
    onToggleAcknowledged: (RecordingIncident) -> Unit,
    onDelete: (RecordingIncident) -> Unit,
    modifier: Modifier = Modifier,
    openProgress: () -> Float = { 1f },
) {
    val noiseBrush = rememberAppNoiseBrush()
    val topBarNoiseBrush = rememberAppNoiseBrush(APP_NOISE_SEED_TOP_BAR)
    Surface(
        modifier = modifier.graphicsLayer {
            val progress = openProgress().coerceIn(0f, 1f)
            translationX = size.width * 0.08f * (1f - progress)
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
                        Icon(AppIcons.incidents, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                        Text(stringResource(R.string.incidents_empty), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.incidents_empty_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = history.rows,
                        key = { it.key },
                    ) { row ->
                        IncidentCard(
                            row = row,
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
    onToggleAcknowledged: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuCreated by remember { mutableStateOf(false) }
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
                    onClick = onToggleAcknowledged,
                    onLongClick = { menuCreated = true; menuExpanded = true },
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
                                onClick = onToggleAcknowledged,
                                onLongClick = { menuCreated = true; menuExpanded = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (incident.acknowledged) AppIcons.checked else AppIcons.unchecked,
                            contentDescription = stringResource(
                                if (incident.acknowledged) R.string.incident_mark_unchecked
                                else R.string.incident_mark_checked,
                            ),
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
