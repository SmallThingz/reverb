package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.res.Resources
import smallthingz.ui.reverb.ReverbIncidentGroup
import smallthingz.ui.reverb.ReverbIncidentHistory
import smallthingz.ui.reverb.ReverbIncidentLiveEnd
import smallthingz.ui.reverb.ReverbIncidentLoadStatus
import smallthingz.ui.reverb.ReverbIncidentOccurrence
import smallthingz.ui.reverb.ReverbIncidentTarget
import java.io.IOException
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
    RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION -> {
        val occurredAt = clockFormatter.format(Instant.ofEpochMilli(incident.occurredAtMillis).atZone(zone))
        val summary = "Microphone input issue at $occurredAt"
        when {
            incident.description != CaptureInputObservationReason.POLICY_SILENCED.description -> summary
            incident.ongoingPolicyDuration != null -> summary
            else -> {
                val duration = recordingIncidentDowntimeMillis(incident)
                if (duration == null) "$summary · duration unknown"
                else "$summary for ${formatDurationInput(duration / 1000L + if (duration % 1000L == 0L) 0L else 1L)}"
            }
        }
    }
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
    override val key: String,
    val date: String,
    val stopSummary: String,
    val cause: String,
    val runtime: String,
    val age: String,
    val description: String?,
    val started: String = date,
    val ended: String? = null,
    override val diagnostics: String = listOf(runtime, age).filter(String::isNotEmpty).joinToString(" · "),
    override val startLabel: String = started,
    override val endLabel: String = ended.orEmpty(),
    override val causeLabel: String = cause,
    override val liveEnd: ReverbIncidentLiveEnd? = null,
) : ReverbIncidentOccurrence, ReverbIncidentTarget {
    override val target: ReverbIncidentTarget get() = this
    override val acknowledged: Boolean get() = incident.acknowledged

    override fun matches(reference: ReverbIncidentTarget): Boolean = reference is IncidentPresentation &&
        recordingIncidentReferenceMatches(incident, reference.incident)

    val hasDetails: Boolean
        get() = cause.isNotEmpty() || runtime.isNotEmpty() || age.isNotEmpty() || description != null
}

internal fun IncidentPresentation.summaryAt(context: Context, elapsedMillis: Long): String {
    val duration = incident.ongoingPolicyDuration?.takeIf { it.isOngoing } ?: return stopSummary
    val seconds = (elapsedMillis - duration.startedAtElapsedMillis).coerceAtLeast(0L) / 1000L
    return context.getString(R.string.incident_policy_ongoing, stopSummary, formatDurationInput(seconds))
}

internal data class IncidentMessage(val description: String?, val fallbackKind: RecordingIncidentKind?) {
    fun displayText(resources: Resources): String = description ?: resources.getString(when (fallbackKind) {
        RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION -> R.string.incident_input_heading
        RecordingIncidentKind.UNEXPECTED_ERROR -> R.string.incident_error_heading
        else -> R.string.incident_shutdown_heading
    })
}

internal class IncidentHistoryPresentation(val rows: List<IncidentPresentation>, resources: Resources? = null) {
    val hasAlert: Boolean = rows.any { !it.incident.acknowledged }
    val groups: Map<IncidentMessage, List<IncidentPresentation>> = rows.groupBy { row ->
        IncidentMessage(row.description, row.incident.kind.takeIf { row.description == null })
    }
    val nativeGroups = resources?.let { labels ->
        groups.map { (message, occurrences) ->
            val key = message.description?.let { "message:$it" } ?: "kind:${message.fallbackKind?.storageCode}"
            ReverbIncidentGroup(key, message.displayText(labels), occurrences)
        }
    }.orEmpty()
}

internal enum class IncidentHistoryLoadStatus { LOADING, READY, UNAVAILABLE }

internal data class IncidentHistoryUiState(
    val history: IncidentHistoryPresentation = IncidentHistoryPresentation(emptyList()),
    val status: IncidentHistoryLoadStatus = IncidentHistoryLoadStatus.LOADING,
) {
    val canMutate: Boolean get() = status == IncidentHistoryLoadStatus.READY
    val showEmptyHistory: Boolean get() = canMutate && history.rows.isEmpty()
    val nativeHistory = ReverbIncidentHistory(history.nativeGroups, when (status) {
        IncidentHistoryLoadStatus.LOADING -> ReverbIncidentLoadStatus.LOADING
        IncidentHistoryLoadStatus.READY -> ReverbIncidentLoadStatus.READY
        IncidentHistoryLoadStatus.UNAVAILABLE -> ReverbIncidentLoadStatus.UNAVAILABLE
    }, history.rows)

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
    if (incident.kind == RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION) {
        return "$kind:observation:${incident.pid}:$processStart:${incident.observationId}"
    }
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
    resources: Resources? = null,
): IncidentHistoryPresentation {
    // Reject ambiguous visible rows on the history-read path, before a keyed adapter can
    // redirect their actions. Tombstones remain store-owned and never enter presentation.
    val visible = ArrayList<RecordingIncident>(minOf(incidents.size, MAX_VISIBLE_RECORDING_INCIDENTS))
    val keys = HashSet<String>()
    for (incident in incidents) {
        if (incident.deleted) continue
        if (visible.size >= MAX_VISIBLE_RECORDING_INCIDENTS) {
            throw IOException("Recording incident presentation exceeds its visible capacity")
        }
        if (!keys.add(recordingIncidentPresentationKey(incident))) {
            throw IOException("Duplicate recording incident presentation identity")
        }
        visible.add(incident)
    }
    val dateFormatter = incidentDateFormatter(locale)
    val clockFormatter = incidentClockFormatter(locale)
    fun formatDateTime(millis: Long): String {
        val instant = Instant.ofEpochMilli(millis).atZone(zone)
        return "${dateFormatter.format(instant)} · ${clockFormatter.format(instant)}"
    }
    val newestFirst = visible.sortedWith(compareByDescending<RecordingIncident> { it.occurredAtMillis }
        .thenBy(::recordingIncidentPresentationKey))
    val ongoingPattern = resources?.getString(R.string.incident_occurrence_ongoing)
    return IncidentHistoryPresentation(newestFirst.map { incident ->
        val started = formatDateTime(incident.occurredAtMillis)
        val ended = incident.resumedAtMillis.takeIf { it > 0L }?.let(::formatDateTime)
        val cause = incidentCauseLine(incident)
        IncidentPresentation(
            incident = incident,
            key = recordingIncidentPresentationKey(incident),
            date = dateFormatter.format(Instant.ofEpochMilli(incident.occurredAtMillis).atZone(zone)),
            stopSummary = formatIncidentSummary(incident, clockFormatter, zone),
            cause = cause,
            runtime = incidentRuntimeLine(incident),
            age = incidentAgeLine(incident),
            description = incident.description?.takeIf { it.isNotBlank() },
            started = started,
            ended = ended,
            startLabel = resources?.getString(R.string.incident_occurrence_start, started) ?: started,
            endLabel = when {
                resources == null -> ended.orEmpty()
                ended != null -> resources.getString(R.string.incident_occurrence_end, ended)
                incident.kind == RecordingIncidentKind.UNEXPECTED_ERROR -> resources.getString(R.string.incident_occurrence_instant)
                incident.recoveryPending -> resources.getString(R.string.incident_occurrence_pending_recovery)
                else -> resources.getString(R.string.incident_occurrence_unknown_end)
            },
            causeLabel = when {
                resources == null || incident.pid > 0 -> cause
                cause.isEmpty() -> resources.getString(R.string.incident_pid_unknown)
                else -> resources.getString(R.string.incident_cause_without_pid, cause)
            },
            liveEnd = if (ongoingPattern != null) incident.ongoingPolicyDuration?.let {
                LiveIncidentEnd(it, ongoingPattern, locale)
            } else null,
        )
    }, resources)
}

private data class LiveIncidentEnd(
    val duration: CaptureObservationDuration,
    val pattern: String,
    val locale: Locale,
) : ReverbIncidentLiveEnd {
    override val ongoing get() = duration.isOngoing
    override fun labelAt(elapsedMillis: Long): String {
        val seconds = (elapsedMillis - duration.startedAtElapsedMillis).coerceAtLeast(0L) / 1000L
        return String.format(locale, pattern, formatDurationInput(seconds))
    }
}

