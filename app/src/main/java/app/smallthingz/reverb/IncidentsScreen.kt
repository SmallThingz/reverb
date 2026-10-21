package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private fun incidentDateFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)

private fun incidentClockFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm:ss a", locale)

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

internal fun formatIncidentSummary(
    incident: RecordingIncident,
    clockFormatter: DateTimeFormatter = incidentClockFormatter(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = when (incident.kind) {
    RecordingIncidentKind.UNEXPECTED_SHUTDOWN -> formatIncidentStopSummary(incident, clockFormatter, zone)
    RecordingIncidentKind.UNEXPECTED_ERROR -> {
        val occurredAt = clockFormatter.format(
            Instant.ofEpochMilli(incident.occurredAtMillis).atZone(zone),
        )
        "Unexpected error at $occurredAt"
    }
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
    if (incident.kind == RecordingIncidentKind.UNEXPECTED_ERROR) add("App error")
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

internal enum class IncidentHistoryLoadStatus { LOADING, READY, UNAVAILABLE }

internal data class IncidentHistoryUiState(
    val history: IncidentHistoryPresentation = IncidentHistoryPresentation(emptyList()),
    val status: IncidentHistoryLoadStatus = IncidentHistoryLoadStatus.LOADING,
) {
    val canMutate: Boolean get() = status == IncidentHistoryLoadStatus.READY
    val showEmptyHistory: Boolean get() = canMutate && history.rows.isEmpty()

    fun loadStarted(): IncidentHistoryUiState = copy(status = IncidentHistoryLoadStatus.LOADING)
    fun loadFailed(): IncidentHistoryUiState = copy(status = IncidentHistoryLoadStatus.UNAVAILABLE)
    fun loadSucceeded(loaded: IncidentHistoryPresentation): IncidentHistoryUiState =
        IncidentHistoryUiState(loaded, IncidentHistoryLoadStatus.READY)
}

internal fun recordingIncidentPresentationKey(incident: RecordingIncident): String {
    val kind = incident.kind.storageCode
    // Learning a missing process identity may remount a legacy row; a known identity must still
    // distinguish separate process lifetimes that reused a PID and capture-arm timestamp.
    val processStart = incident.processStartedAtMillis.takeIf { it > 0L } ?: 0L
    return if (recordingIncidentHasSessionIdentity(incident)) {
        // Occurrence can move when Android enriches a provisional capture interruption. The
        // session identity survives that update and distinguishes equal-time capture incidents.
        "$kind:session:${incident.pid}:${incident.captureArmedAtMillis}:$processStart"
    } else {
        val pid = incident.pid.takeIf { it > 0 } ?: 0
        "$kind:occurrence:${incident.occurredAtMillis}:$pid:$processStart"
    }
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
            key = recordingIncidentPresentationKey(incident),
            date = dateFormatter.format(Instant.ofEpochMilli(incident.occurredAtMillis).atZone(zone)),
            stopSummary = formatIncidentSummary(incident, clockFormatter, zone),
            cause = incidentCauseLine(incident),
            runtime = incidentRuntimeLine(incident),
            age = incidentAgeLine(incident),
            description = incident.description?.takeIf { it.isNotBlank() },
        )
    })
}

