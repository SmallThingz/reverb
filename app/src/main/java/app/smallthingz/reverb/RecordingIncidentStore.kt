package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal const val EXIT_REASON_MEMORY_LIMITER = 17
internal const val EXIT_REASON_ANOMALY = 18
internal const val MAX_PENDING_INCIDENT_SESSIONS = 128
internal const val MAX_VISIBLE_RECORDING_INCIDENTS = 128
// Visible history, all bounded pending capture owners, and a legacy-history-sized reserve for
// deletion identities whose old payload lacks enough identity to prove that replay has ended.
// The four extra slots cover the active capture, two process-local retry owners, and app exit.
internal const val MAX_RECORDING_INCIDENT_HISTORY_RECORDS =
    MAX_VISIBLE_RECORDING_INCIDENTS * 2 + MAX_PENDING_INCIDENT_SESSIONS + 4

internal fun pendingIncidentQueueCanAppend(existingCount: Int): Boolean =
    existingCount in 0 until MAX_PENDING_INCIDENT_SESSIONS

internal fun atomicReadMissIsAuthoritativeAbsence(backingState: StoragePathState): Boolean =
    backingState == StoragePathState.MISSING

/** A visible unlink is not durable absence until this directory barrier succeeds. */
private fun requireDurableIncidentAtomicAbsence(
    baseFile: File,
    syncDirectory: (File) -> Unit,
) {
    fun requireAbsent() {
        when (atomicFileRegularBackingState(baseFile)) {
            StoragePathState.MISSING -> Unit
            StoragePathState.PRESENT -> throw IOException(
                "Incident state is present during removal confirmation: ${baseFile.absolutePath}",
            )
            StoragePathState.UNAVAILABLE -> throw IOException(
                "Unable to confirm incident-state absence: ${baseFile.absolutePath}",
            )
        }
    }
    requireAbsent()
    val parent = baseFile.parentFile ?: throw IOException(
        "Incident state has no durability parent: ${baseFile.absolutePath}",
    )
    // Do not convert the barrier's exception to a Boolean: keep its original failure/cause.
    syncDirectory(parent)
    // A new base, .new or .bak must be preserved, never deleted by an absence retry.
    requireAbsent()
}

internal fun deleteIncidentAtomicStateDurably(
    baseFile: File,
    removePresent: () -> Unit,
    syncDirectory: (File) -> Unit = ::forceRecordingDirectoryDurable,
) {
    when (atomicFileRegularBackingState(baseFile)) {
        StoragePathState.MISSING -> Unit // Retry a prior unlink whose directory barrier failed.
        StoragePathState.PRESENT -> removePresent()
        StoragePathState.UNAVAILABLE -> throw IOException(
            "Unable to inspect durable incident state: ${baseFile.absolutePath}",
        )
    }
    requireDurableIncidentAtomicAbsence(baseFile, syncDirectory)
}

internal fun completeMissingIncidentMarkerRetry(
    baseFile: File,
    preserveIncident: () -> Unit,
    retireRetry: () -> Unit,
    syncDirectory: (File) -> Unit = ::forceRecordingDirectoryDurable,
) {
    // Missing-marker retry owns confirmation only, never deletion of a replacement marker.
    requireDurableIncidentAtomicAbsence(baseFile, syncDirectory)
    preserveIncident()
    retireRetry()
}

internal inline fun <T> readIncidentPayloadExact(
    input: DataInputStream,
    label: String,
    decode: (DataInputStream) -> T,
): T {
    val decoded = decode(input)
    if (input.read() >= 0) throw IOException("Trailing bytes in $label")
    return decoded
}

internal enum class RecordingIncidentKind(val storageCode: Byte) {
    UNEXPECTED_SHUTDOWN(1),
    UNEXPECTED_ERROR(2),
    ;

    companion object {
        fun fromStorageCode(code: Byte): RecordingIncidentKind? = entries.firstOrNull { it.storageCode == code }
    }
}

internal data class RecordingIncident(
    val occurredAtMillis: Long,
    val resumedAtMillis: Long = -1L,
    val acknowledgedAtMillis: Long = 0L,
    val kind: RecordingIncidentKind = RecordingIncidentKind.UNEXPECTED_SHUTDOWN,
    val exitReason: Int = 0,
    val exitStatus: Int = Int.MIN_VALUE,
    val pid: Int = 0,
    val importance: Int = Int.MIN_VALUE,
    val pssKb: Long = -1L,
    val rssKb: Long = -1L,
    val processStartedAtMillis: Long = -1L,
    val captureArmedAtMillis: Long = -1L,
    val description: String? = null,
) {
    // Keep an identity tombstone so delayed exit evidence cannot resurrect user-deleted cards.
    // This sentinel uses the existing signed history field and survives format-compatible reads.
    val deleted: Boolean get() = acknowledgedAtMillis == Long.MIN_VALUE
    val acknowledged: Boolean get() = acknowledgedAtMillis > 0L
    val recoveryPending: Boolean
        get() = kind == RecordingIncidentKind.UNEXPECTED_SHUTDOWN && resumedAtMillis == 0L
}

internal fun toggleRecordingIncidentAcknowledgement(
    incident: RecordingIncident,
    acknowledgedAtMillis: Long,
): RecordingIncident = if (incident.deleted) incident else incident.copy(
    acknowledgedAtMillis = if (incident.acknowledged) 0L else acknowledgedAtMillis.coerceAtLeast(1L),
)

internal fun appendRecordingIncidentPreservingDeletions(
    existing: List<RecordingIncident>,
    incident: RecordingIncident,
    limit: Int,
): List<RecordingIncident> {
    require(limit > 0)
    val updated = (existing + incident).sortedBy { it.occurredAtMillis }.toMutableList()
    var visibleCount = updated.count { !it.deleted }
    while (visibleCount > limit) {
        updated.removeAt(updated.indexOfFirst { !it.deleted })
        visibleCount--
    }
    return updated
}

internal data class RecordingIncidentReplayOwners(
    val captureSessions: List<RecordingIncident> = emptyList(),
    val exactIncidents: List<RecordingIncident> = emptyList(),
    val appProcessPid: Int = 0,
    val appProcessStartedAtMillis: Long = -1L,
)

/** Missing owner proof never authorizes pruning; the caller supplies only a complete snapshot. */
internal fun retainRecordingIncidentDeletions(
    incidents: List<RecordingIncident>,
    owners: RecordingIncidentReplayOwners,
    incoming: RecordingIncident,
): List<RecordingIncident> = incidents.filter { incident ->
    if (!incident.deleted) true
    else if (recordingIncidentReferenceMatches(incident, incoming) ||
        owners.exactIncidents.any { recordingIncidentReferenceMatches(incident, it) }) true
    else when (incident.kind) {
        RecordingIncidentKind.UNEXPECTED_SHUTDOWN -> owners.captureSessions.any { session ->
            if (recordingIncidentHasSessionIdentity(incident)) {
                recordingIncidentsShareCaptureSession(incident, session)
            } else {
                // Legacy cards can be matched only by occurrence time. Any still-owned session
                // armed before that time could supply delayed evidence with that exact timestamp.
                incident.occurredAtMillis >= session.captureArmedAtMillis
            }
        }
        RecordingIncidentKind.UNEXPECTED_ERROR -> owners.appProcessPid > 0 &&
            (incident.pid <= 0 || incident.pid == owners.appProcessPid) && when {
                incident.pid <= 0 -> incident.occurredAtMillis >= owners.appProcessStartedAtMillis
                incident.exitReason != ApplicationExitInfo.REASON_UNKNOWN ->
                    incident.processStartedAtMillis <= 0L || owners.appProcessStartedAtMillis <= 0L ||
                        incident.processStartedAtMillis == owners.appProcessStartedAtMillis
                incident.description?.startsWith("Uncaught exception on ") == true ->
                    // The uncaught-handler timestamp uses the live clock, whereas the process
                    // marker was sampled at startup. Keep the same tolerance as exit deduplication.
                    incident.occurredAtMillis >= (owners.appProcessStartedAtMillis - 10_000L).coerceAtLeast(0L)
                else -> false
            }
    }
}

internal fun compactRecordingIncidentDeletions(
    incidents: List<RecordingIncident>,
    incoming: RecordingIncident,
    readOwners: () -> RecordingIncidentReplayOwners,
): List<RecordingIncident> {
    if (incidents.none { it.deleted }) return incidents
    val owners = try {
        readOwners()
    } catch (_: IOException) {
        // Compaction is optional. Unavailable owner metadata must preserve every identity.
        return incidents
    }
    return retainRecordingIncidentDeletions(incidents, owners, incoming)
}

internal fun validateRecordingIncidentHistorySize(incidents: List<RecordingIncident>) {
    if (incidents.size > MAX_RECORDING_INCIDENT_HISTORY_RECORDS ||
        incidents.count { !it.deleted } > MAX_VISIBLE_RECORDING_INCIDENTS) {
        throw IOException("Recording incident history exceeds its bounded live/history capacity")
    }
}

/** Owns an exact history payload across an uncertain AtomicFile publication, never a toggle. */
internal class IncidentHistoryPublication {
    private data class Pending(val file: File, val incidents: List<RecordingIncident>)
    private var pending: Pending? = null
    private var refreshPending = false

    fun confirmPending(
        publish: (File, List<RecordingIncident>, () -> Unit) -> Unit,
    ) {
        val target = pending ?: return
        // Retain the same target and wake-up obligation through repeated failures. The history
        // reader already owns bounded backoff; another signal here would reset that backoff.
        publish(target.file, target.incidents) {}
        pending = null
    }

    fun completeHistoryRead(): Boolean {
        val refreshCompleted = refreshPending
        refreshPending = false
        return refreshCompleted
    }

    fun write(
        file: File,
        incidents: List<RecordingIncident>,
        publish: (File, List<RecordingIncident>, () -> Unit) -> Unit,
        onHistoryChanged: () -> Unit,
    ) {
        confirmPending(publish)
        val target = incidents.toList()
        var publicationStarted = false
        try {
            publish(file, target) { publicationStarted = true }
        } catch (error: Throwable) {
            // A pre-publication failure must not acquire authority to replay over rollback
            // artifacts. Wake the strict reader, but retain a target only past publication.
            if (publicationStarted) pending = Pending(file, target)
            if (!refreshPending) {
                refreshPending = true
                onHistoryChanged()
            }
            throw error
        }
    }
}

internal inline fun throwIncidentAtomicWriteFailure(
    primaryFailure: Throwable,
    committed: Boolean,
    rollback: () -> Unit,
): Nothing {
    if (!committed) throwAfterClosePreservingPrimary(primaryFailure, rollback)
    throw primaryFailure
}

internal inline fun runIncidentHistoryMutation(
    mutation: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    try {
        mutation()
    } catch (error: Exception) {
        runCatching { onFailure(error) }
    }
}

internal fun recordingIncidentsShareCaptureSession(
    left: RecordingIncident,
    right: RecordingIncident,
): Boolean {
    if (left.pid <= 0 || right.pid != left.pid) return false
    if (left.captureArmedAtMillis <= 0L || right.captureArmedAtMillis != left.captureArmedAtMillis) return false
    if (left.processStartedAtMillis > 0L && right.processStartedAtMillis > 0L &&
        left.processStartedAtMillis != right.processStartedAtMillis
    ) {
        return false
    }
    return true
}

internal fun recordingIncidentHasSessionIdentity(
    incident: RecordingIncident,
): Boolean = incident.pid > 0 && incident.captureArmedAtMillis > 0L

internal fun recordingIncidentReferenceMatches(
    candidate: RecordingIncident,
    reference: RecordingIncident,
): Boolean {
    if (candidate.kind != reference.kind) return false
    if (recordingIncidentsShareCaptureSession(candidate, reference)) return true

    // Exact interruption timestamp is retained only as a legacy identity fallback. Once both
    // records carry capture-session identity, a session mismatch is authoritative even when two
    // independent incidents happen to land on the same wall-clock millisecond.
    if (recordingIncidentHasSessionIdentity(candidate) &&
        recordingIncidentHasSessionIdentity(reference)
    ) {
        return false
    }
    return candidate.occurredAtMillis == reference.occurredAtMillis
}

internal fun exitTimestampBelongsToPriorProcess(
    exitTimestampMillis: Long,
    captureArmedAtMillis: Long,
    currentProcessStartedAtMillis: Long,
): Boolean = exitTimestampMillis >= captureArmedAtMillis &&
    (currentProcessStartedAtMillis <= 0L || exitTimestampMillis <= currentProcessStartedAtMillis)

internal fun mergeRecordingIncidentEvidence(
    existing: RecordingIncident,
    incoming: RecordingIncident,
): RecordingIncident {
    val incomingHasExitEvidence = incoming.exitReason != ApplicationExitInfo.REASON_UNKNOWN
    val existingHasExitEvidence = existing.exitReason != ApplicationExitInfo.REASON_UNKNOWN
    val base = if (incomingHasExitEvidence && !existingHasExitEvidence) incoming else existing
    return base.copy(
        // Android process death can occur after capture already stopped. Preserve the earliest
        // observed interruption boundary while enriching it with later process-exit evidence.
        occurredAtMillis = minOf(existing.occurredAtMillis, incoming.occurredAtMillis),
        resumedAtMillis = existing.resumedAtMillis.takeIf { it > 0L }
            ?: incoming.resumedAtMillis,
        acknowledgedAtMillis = existing.acknowledgedAtMillis.takeIf { it > 0L || existing.deleted }
            ?: incoming.acknowledgedAtMillis,
        description = base.description ?: existing.description ?: incoming.description,
    )
}

private data class AppProcessMarker(
    val pid: Int,
    val processStartedAtMillis: Long,
)

internal fun unexpectedAppExitShouldCreateIncident(reason: Int, status: Int): Boolean = when (reason) {
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    EXIT_REASON_ANOMALY,
    -> true
    ApplicationExitInfo.REASON_SIGNALED -> status in setOf(4, 6, 7, 8, 11) // SIGILL/ABRT/BUS/FPE/SEGV
    else -> false
}

internal fun unexpectedProcessExitAlreadyRecorded(
    incidents: List<RecordingIncident>,
    pid: Int,
    exitTimestampMillis: Long,
    toleranceMillis: Long = 10_000L,
): Boolean {
    val tolerance = toleranceMillis.coerceAtLeast(0L)
    val lowerBound = (exitTimestampMillis - tolerance).coerceAtLeast(0L)
    val upperBound = if (Long.MAX_VALUE - exitTimestampMillis < tolerance) {
        Long.MAX_VALUE
    } else {
        exitTimestampMillis + tolerance
    }
    return incidents.any { incident ->
        incident.kind == RecordingIncidentKind.UNEXPECTED_ERROR &&
            incident.pid == pid &&
            incident.description?.startsWith("Uncaught exception on ") == true &&
            incident.occurredAtMillis in lowerBound..upperBound
    }
}

internal fun confirmAlreadyRecordedAppExit(
    incidents: List<RecordingIncident>,
    pid: Int,
    exitTimestampMillis: Long,
    confirmHistory: (List<RecordingIncident>) -> Unit,
): Boolean {
    if (!unexpectedProcessExitAlreadyRecorded(incidents, pid, exitTimestampMillis)) return false
    // Visible history may come from a previous process whose publication barrier failed.
    // The old process marker remains its recovery owner until this proof succeeds.
    confirmHistory(incidents)
    return true
}

private data class ActiveRecordingSessionMarker(
    val armed: Boolean,
    val pid: Int,
    val processStartElapsedRealtimeMillis: Long,
    val armedAtMillis: Long,
    val armedElapsedRealtimeMillis: Long,
    val packageLastUpdateTimeMillis: Long,
)

private data class PendingRecordingSession(
    val marker: ActiveRecordingSessionMarker,
    val resumedAtMillis: Long = 0L,
)

private data class PendingProcessIncidentRetry(
    val occurredAtMillis: Long,
    val description: String,
    val expectedPid: Int,
    val expectedProcessStartElapsedRealtimeMillis: Long,
    val incident: RecordingIncident? = null,
) {
    fun sessionKey() = IncidentRetrySessionKey(expectedPid, expectedProcessStartElapsedRealtimeMillis)
}

internal data class IncidentRetrySessionKey(
    val pid: Int,
    val processStartElapsedRealtimeMillis: Long,
)

internal fun serviceStopRetryShouldAppend(
    existing: List<IncidentRetrySessionKey>,
    incoming: IncidentRetrySessionKey,
): Boolean = incoming !in existing

internal enum class RecordingExitDisposition {
    INCIDENT,
    PENDING,
}

internal fun recordingExitDisposition(reason: Int?): RecordingExitDisposition =
    if (reason == null) RecordingExitDisposition.PENDING else RecordingExitDisposition.INCIDENT

internal enum class CaptureSessionStartDisposition { NEW_SESSION, CONTINUE_SESSION, RESOLVE_INTERRUPTED_SESSION }

internal enum class KnownCaptureStopResult {
    DURABLE,
    FAILED_REARMED,
    FAILED_UNCERTAIN,
}

/**
 * The caller holds the incident-store monitor. Resolve old-process evidence before granting a
 * known Stop authority over its marker; an unreadable marker is never authority to delete it.
 */
internal fun <Marker : Any> completeKnownCaptureStop(
    retryPendingEvidence: () -> Unit,
    readMarker: () -> Marker?,
    isArmed: (Marker) -> Boolean,
    belongsToCurrentProcess: (Marker) -> Boolean,
    recoverPriorSession: () -> Unit,
    confirmDisarmed: (Marker) -> Unit,
    retainUncertainEvidence: (Marker) -> Unit,
    disarm: (Marker) -> Unit,
    remove: () -> Unit,
    restore: (Marker) -> Unit,
): KnownCaptureStopResult {
    val existing = try {
        retryPendingEvidence()
        var marker = readMarker()
        if (marker != null && isArmed(marker) && !belongsToCurrentProcess(marker)) {
            recoverPriorSession()
            marker = readMarker()
        }
        marker
    } catch (_: Exception) {
        return KnownCaptureStopResult.FAILED_UNCERTAIN
    }
    if (existing == null) {
        // Confirm durable absence, including an earlier unlink whose directory sync failed.
        return if (runCatching(remove).isSuccess) KnownCaptureStopResult.DURABLE
        else KnownCaptureStopResult.FAILED_UNCERTAIN
    }
    if (!isArmed(existing)) {
        // A failed publication barrier can leave a readable disarmed marker. Reading it alone
        // does not turn the earlier uncertain Stop into a durability acknowledgement.
        return if (runCatching { confirmDisarmed(existing) }.isSuccess) KnownCaptureStopResult.DURABLE
        else KnownCaptureStopResult.FAILED_UNCERTAIN
    }
    // Recovery must retire the old owner before this Stop can grant any disarm/delete authority.
    if (!belongsToCurrentProcess(existing)) return KnownCaptureStopResult.FAILED_UNCERTAIN
    if (runCatching { disarm(existing) }.isSuccess) return KnownCaptureStopResult.DURABLE
    if (runCatching(remove).isSuccess) return KnownCaptureStopResult.DURABLE
    return if (runCatching { restore(existing) }.isSuccess) {
        KnownCaptureStopResult.FAILED_REARMED
    } else {
        // finishWrite may have exposed a disarmed marker before its directory barrier failed.
        // Keep the original armed identity before the caller's interrupted-session lookup can
        // see that changed marker (or an unlink whose barrier failed).
        retainUncertainEvidence(existing)
        KnownCaptureStopResult.FAILED_UNCERTAIN
    }
}

internal fun captureSessionStartDisposition(
    sameProcessArmedSession: Boolean,
    continuousRestart: Boolean,
): CaptureSessionStartDisposition = when {
    !sameProcessArmedSession -> CaptureSessionStartDisposition.NEW_SESSION
    continuousRestart -> CaptureSessionStartDisposition.CONTINUE_SESSION
    else -> CaptureSessionStartDisposition.RESOLVE_INTERRUPTED_SESSION
}

internal fun processLocalIncidentMarkerMatches(
    armed: Boolean,
    markerPid: Int,
    markerProcessStartElapsedRealtimeMillis: Long,
    markerArmedAtMillis: Long,
    expectedPid: Int,
    expectedProcessStartElapsedRealtimeMillis: Long,
    stopOccurredAtMillis: Long,
): Boolean = armed &&
    markerPid == expectedPid &&
    markerProcessStartElapsedRealtimeMillis == expectedProcessStartElapsedRealtimeMillis &&
    markerArmedAtMillis > 0L &&
    markerArmedAtMillis <= stopOccurredAtMillis

private fun ActiveRecordingSessionMarker.matches(retry: PendingProcessIncidentRetry): Boolean =
    processLocalIncidentMarkerMatches(
        armed = armed,
        markerPid = pid,
        markerProcessStartElapsedRealtimeMillis = processStartElapsedRealtimeMillis,
        markerArmedAtMillis = armedAtMillis,
        expectedPid = retry.expectedPid,
        expectedProcessStartElapsedRealtimeMillis = retry.expectedProcessStartElapsedRealtimeMillis,
        stopOccurredAtMillis = retry.occurredAtMillis,
    )


internal fun unexpectedErrorIncidentDescription(
    source: String,
    error: Throwable,
    maxChars: Int = 384,
): String {
    require(maxChars > 0)
    val normalizedSource = source.trim().ifBlank { "Unexpected app error" }
    val type = error::class.java.name
    val message = error.message?.trim().orEmpty()
    val frame = error.stackTrace.firstOrNull { it.className.startsWith("app.smallthingz.reverb") }
        ?: error.stackTrace.firstOrNull()
    val location = frame?.let { stack ->
        buildString {
            append(stack.className.substringAfterLast('.'))
            append('.')
            append(stack.methodName)
            if (stack.lineNumber > 0) append(':').append(stack.lineNumber)
        }
    }.orEmpty()
    val root = generateSequence(error.cause) { it.cause }.lastOrNull()
        ?.takeIf { it !== error }
    val rootDetail = root?.let { cause ->
        val causeMessage = cause.message?.trim().orEmpty()
        buildString {
            append("; cause ")
            append(cause::class.java.name)
            if (causeMessage.isNotEmpty()) append(": ").append(causeMessage)
        }
    }.orEmpty()
    return buildString {
        append(normalizedSource)
        append(": ")
        append(type)
        if (message.isNotEmpty()) append(": ").append(message)
        if (location.isNotEmpty()) append(" @ ").append(location)
        append(rootDetail)
    }.take(maxChars)
}

internal fun completeRecordingIncidentDowntimes(
    incidents: List<RecordingIncident>,
    resumedAtMillis: Long,
): List<RecordingIncident> {
    var changed = false
    val updated = incidents.map { incident ->
        if (
            incident.recoveryPending &&
            resumedAtMillis >= incident.occurredAtMillis
        ) {
            changed = true
            incident.copy(resumedAtMillis = resumedAtMillis)
        } else {
            incident
        }
    }
    return if (changed) updated else incidents
}

internal object RecordingIncidentStore {
    private const val SESSION_MAGIC = 0x52495331 // RIS1
    private const val PROCESS_MAGIC = 0x52495032 // RIP2
    private const val HISTORY_MAGIC = 0x52494831 // RIH1
    private const val SESSION_FORMAT_VERSION = 2
    private const val PROCESS_FORMAT_VERSION = 1
    private const val LEGACY_HISTORY_FORMAT_VERSION = 1
    private const val PREVIOUS_HISTORY_FORMAT_VERSION = 2
    private const val HISTORY_FORMAT_VERSION = 3
    private const val MAX_INCIDENTS = MAX_VISIBLE_RECORDING_INCIDENTS
    private const val MAX_DESCRIPTION_CHARS = 384
    private const val SESSION_FILE_NAME = "recording-session.bin"
    private const val PROCESS_FILE_NAME = "app-process.bin"
    private const val PENDING_SESSION_FILE_NAME = "recording-incident-pending.bin"
    private const val HISTORY_FILE_NAME = "recording-incidents.bin"
    private const val PENDING_MAGIC = 0x52495031 // RIP1
    private const val PENDING_FORMAT_VERSION = 1

    private val mutableHistoryRevision = MutableStateFlow(0L)
    val historyRevision: StateFlow<Long> = mutableHistoryRevision.asStateFlow()
    private val historyMutationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingServiceStopIncidentRetries = mutableListOf<PendingProcessIncidentRetry>()
    private val pendingCaptureInterruptionRetries = mutableListOf<PendingProcessIncidentRetry>()
    private val historyPublication = IncidentHistoryPublication()
    private var lastUnexpectedErrorAtMillis = 0L

    fun recordUnexpectedErrorInBackground(context: Context, source: String, error: Throwable) {
        val appContext = context.applicationContext
        val description = unexpectedErrorIncidentDescription(source, error, MAX_DESCRIPTION_CHARS)
        historyMutationScope.launch {
            try {
                recordUnexpectedErrorDescription(appContext, description)
            } catch (recordingFailure: Throwable) {
                // Never recurse through the incident system when incident persistence itself fails.
                Log.e("RecordingIncidentStore", "Unable to persist unexpected-error incident", recordingFailure)
            }
        }
    }

    fun recordUnexpectedError(context: Context, source: String, error: Throwable) {
        recordUnexpectedErrorDescription(
            context = context.applicationContext,
            description = unexpectedErrorIncidentDescription(source, error, MAX_DESCRIPTION_CHARS),
        )
    }

    @Synchronized
    private fun recordUnexpectedErrorDescription(context: Context, description: String) {
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val occurredAtMillis = if (lastUnexpectedErrorAtMillis >= now && lastUnexpectedErrorAtMillis < Long.MAX_VALUE) {
            lastUnexpectedErrorAtMillis + 1L
        } else {
            now
        }
        lastUnexpectedErrorAtMillis = occurredAtMillis
        appendIncident(
            context.applicationContext,
            RecordingIncident(
                occurredAtMillis = occurredAtMillis,
                kind = RecordingIncidentKind.UNEXPECTED_ERROR,
                pid = Process.myPid(),
                processStartedAtMillis = currentProcessStartedAtWallClockMillis(),
                description = description,
            ),
        )
    }

    @Synchronized
    fun recoverUnexpectedProcessExitAndMarkCurrent(context: Context) {
        val appContext = context.applicationContext
        val currentMarker = AppProcessMarker(
            pid = Process.myPid(),
            processStartedAtMillis = currentProcessStartedAtWallClockMillis(),
        )
        val file = AtomicFile(File(appContext.noBackupFilesDir, PROCESS_FILE_NAME))
        val previous = readProcessMarker(file)
        if (previous != null && previous != currentMarker && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            historicalUnexpectedAppExit(appContext, previous)?.let { exit ->
                val existing = readHistory(historyFile(appContext))
                if (!confirmAlreadyRecordedAppExit(existing, previous.pid, exit.timestamp) {
                        writeHistory(historyFile(appContext), it)
                    }
                ) {
                    val description = exit.description
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(MAX_DESCRIPTION_CHARS)
                        ?: "Previous app process ended unexpectedly"
                    appendIncident(
                        appContext,
                        RecordingIncident(
                            occurredAtMillis = exit.timestamp.coerceAtLeast(previous.processStartedAtMillis),
                            kind = RecordingIncidentKind.UNEXPECTED_ERROR,
                            exitReason = exit.reason,
                            exitStatus = exit.status,
                            pid = exit.pid,
                            importance = exit.importance,
                            pssKb = exit.pss,
                            rssKb = exit.rss,
                            processStartedAtMillis = previous.processStartedAtMillis,
                            description = description,
                        ),
                    )
                }
            }
        }
        writeProcessMarker(file, currentMarker)
    }

    @Synchronized
    fun recoverPriorSessionIfNeeded(context: Context) {
        val appContext = context.applicationContext
        resolvePendingSessions(appContext)

        val markerFile = sessionFile(appContext)
        val marker = readSession(markerFile) ?: run {
            deleteAtomicDurablyIfPresent(markerFile)
            return
        }
        if (!marker.armed) {
            deleteAtomicDurablyIfPresent(markerFile)
            return
        }
        val currentElapsed = SystemClock.elapsedRealtime()
        if (marker.armedElapsedRealtimeMillis > currentElapsed) {
            // elapsedRealtime resets only across a device restart. Capture was durably armed,
            // so a reboot is itself an interruption even though ApplicationExitInfo may be gone.
            appendIncident(
                appContext,
                incidentFromDeviceRestart(
                    marker = marker,
                    currentWallClockMillis = System.currentTimeMillis(),
                    currentElapsedRealtimeMillis = currentElapsed,
                ),
            )
            deleteAtomicDurablyIfPresent(markerFile)
            return
        }

        if (markerBelongsToCurrentProcess(marker)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendIncident(
                appContext,
                incidentWithoutExitEvidence(marker, System.currentTimeMillis()),
            )
            deleteAtomicDurablyIfPresent(markerFile)
            return
        }

        // Preserve the previous armed session before the restarted recorder can overwrite the
        // active marker. ExitInfo can lag process startup, so absence of evidence is not evidence
        // of a clean exit. The pending queue is retried on startup, capture start, and history read.
        // Do not allow the next capture start to overwrite the only durable evidence of the
        // previous armed session. Any read/write failure propagates so capture fails closed.
        enqueuePendingSession(appContext, marker)
        // This marker belongs to a process that is already gone, so the interruption itself is
        // certain even if Android has not published ApplicationExitInfo yet. Surface a
        // provisional incident immediately; the pending queue remains the durable authority for
        // later reason/timestamp enrichment, and appendIncident merges by capture-session ID.
        appendIncident(
            appContext,
            incidentWithoutExitEvidence(
                marker = marker,
                occurredAtMillis = System.currentTimeMillis(),
                description = "Recording process ended while capture was running; exit evidence pending",
            ),
        )
        deleteAtomicDurablyIfPresent(markerFile)
        resolvePendingSessions(appContext)
    }

    @Synchronized
    fun recordCaptureStarted(
        context: Context,
        continuousRestart: Boolean = false,
    ) {
        val appContext = context.applicationContext
        // Process-local interruption publication/disarm failures have no process-exit evidence
        // to recover while this process remains alive. Resolve them before a fresh capture can
        // replace the armed marker they belong to; failure keeps capture start fail-closed.
        retryPendingCaptureInterruptions(appContext)
        // A prior same-process Service teardown may have failed to publish its provisional
        // incident. Resolve that process-local evidence before a fresh capture can replace the
        // armed marker it belongs to. Failure propagates so capture start remains fail-closed.
        retryPendingServiceStopIncidents(appContext)
        val markerFile = sessionFile(appContext)
        val existing = readSession(markerFile)
        val sameProcessArmed = existing?.armed == true && markerBelongsToCurrentProcess(existing)
        when (captureSessionStartDisposition(sameProcessArmed, continuousRestart)) {
            CaptureSessionStartDisposition.CONTINUE_SESSION -> {
                // A transparent AudioRecord/config restart is still the same logical capture
                // session. Do not reset its arm timestamp or split later incident identity.
                resolvePendingSessions(appContext)
                return
            }
            CaptureSessionStartDisposition.RESOLVE_INTERRUPTED_SESSION -> {
                val marker = requireNotNull(existing)
                appendIncident(
                    appContext,
                    incidentWithoutExitEvidence(
                        marker = marker,
                        occurredAtMillis = System.currentTimeMillis(),
                        description = "Capture restarted after an unresolved interruption",
                    ),
                )
            }
            CaptureSessionStartDisposition.NEW_SESSION -> recoverPriorSessionIfNeeded(appContext)
        }

        val resumedAtMillis = System.currentTimeMillis()
        notePendingSessionsResumed(appContext, resumedAtMillis)
        resolvePendingSessions(appContext)
        writeSession(
            markerFile,
            ActiveRecordingSessionMarker(
                armed = true,
                pid = Process.myPid(),
                processStartElapsedRealtimeMillis = Process.getStartElapsedRealtime(),
                armedAtMillis = resumedAtMillis,
                armedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                packageLastUpdateTimeMillis = packageLastUpdateTime(appContext),
            ),
        )
        completePendingDowntime(appContext, resumedAtMillis)
    }

    @Synchronized
    fun recordCaptureServiceStopped(context: Context, description: String) {
        val appContext = context.applicationContext
        val stoppedAtMillis = System.currentTimeMillis()
        val retry = PendingProcessIncidentRetry(
            occurredAtMillis = stoppedAtMillis,
            description = description,
            expectedPid = Process.myPid(),
            expectedProcessStartElapsedRealtimeMillis = Process.getStartElapsedRealtime(),
        )
        val markerFile = sessionFile(appContext)
        val marker = try {
            readSession(markerFile)
        } catch (_: IOException) {
            // The marker itself remains durable evidence. Remember that this live process did
            // cross a Service-stop boundary and retry once the marker becomes readable.
            enqueueServiceStopIncidentRetry(retry)
            return
        } ?: return
        if (!marker.matches(retry)
        ) {
            // A prior-process armed marker belongs to startup recovery, not this Service lifetime.
            return
        }

        // Service-only teardown may not produce ApplicationExitInfo. Record the outage now but
        // keep the marker armed so a subsequent process death can enrich this same session.
        val incident = incidentWithoutExitEvidence(
            marker = marker,
            occurredAtMillis = stoppedAtMillis,
            description = description,
        )
        if (runCatching { appendIncident(appContext, incident) }.isFailure) {
            enqueueServiceStopIncidentRetry(retry.copy(incident = incident))
        }
    }

    @Synchronized
    fun recordCaptureInterrupted(context: Context, description: String) {
        val appContext = context.applicationContext
        val interruptedAtMillis = System.currentTimeMillis()
        val retry = PendingProcessIncidentRetry(
            occurredAtMillis = interruptedAtMillis,
            description = description,
            expectedPid = Process.myPid(),
            expectedProcessStartElapsedRealtimeMillis = Process.getStartElapsedRealtime(),
        )
        val file = sessionFile(appContext)
        val marker = try {
            readSession(file)
        } catch (_: IOException) {
            // The marker itself remains durable evidence, but a same-process outage has no
            // ApplicationExitInfo to make this incident visible later. Keep a process-local retry
            // bound to this process lifetime and wake the history reader so transient I/O can
            // recover without waiting for capture to restart or the process to die.
            enqueueCaptureInterruptionRetry(retry)
            return
        } ?: return
        if (!marker.matches(retry)
        ) return

        val incident = incidentWithoutExitEvidence(
            marker = marker,
            occurredAtMillis = interruptedAtMillis,
            description = description,
        )
        // Persist the incident before disarming the session. If either history publication or
        // marker retirement is transiently unavailable, retain a process-local retry. Leaving an
        // armed marker forever after the incident is already durable can otherwise misattribute a
        // much later process exit to capture that had already stopped.
        if (runCatching { appendIncident(appContext, incident) }.isFailure) {
            enqueueCaptureInterruptionRetry(retry.copy(incident = incident))
            return
        }
        if (!disarmInterruptedSession(file, marker)) {
            enqueueCaptureInterruptionRetry(retry.copy(incident = incident))
        }
    }

    @Synchronized
    fun recordKnownCaptureStop(context: Context): KnownCaptureStopResult {
        val appContext = context.applicationContext
        val file = sessionFile(appContext)
        return completeKnownCaptureStop(
            retryPendingEvidence = {
                retryPendingCaptureInterruptions(appContext)
                retryPendingServiceStopIncidents(appContext)
            },
            readMarker = { readSession(file) },
            isArmed = { it.armed },
            belongsToCurrentProcess = ::markerBelongsToCurrentProcess,
            recoverPriorSession = { recoverPriorSessionIfNeeded(appContext) },
            confirmDisarmed = { marker ->
                if (readSession(file) != marker) {
                    throw IOException("Disarmed recording session changed before durable confirmation")
                }
                // Reuse the descriptor-bound payload/publication/directory proof; an earlier
                // readable false marker may have survived a failed publication barrier.
                writeSession(file, marker)
            },
            retainUncertainEvidence = { marker ->
                val interruptedAtMillis = System.currentTimeMillis()
                val description = "Capture stopped because known-Stop incident state could not be persisted"
                enqueueCaptureInterruptionRetry(
                    PendingProcessIncidentRetry(
                        occurredAtMillis = interruptedAtMillis,
                        description = description,
                        expectedPid = marker.pid,
                        expectedProcessStartElapsedRealtimeMillis = marker.processStartElapsedRealtimeMillis,
                        incident = incidentWithoutExitEvidence(marker, interruptedAtMillis, description),
                    ),
                )
            },
            disarm = { marker -> writeSession(file, marker.copy(armed = false)) },
            remove = { deleteAtomicDurablyIfPresent(file) },
            restore = { marker -> writeSession(file, marker) },
        )
    }

    @Synchronized
    fun readIncidents(context: Context): List<RecordingIncident> {
        val appContext = context.applicationContext
        // Same-process capture interruptions can fail while reading/writing the session/history
        // files. Retry both publication and exact-session disarm on every bounded UI history read.
        retryPendingCaptureInterruptions(appContext)
        // Same-process Service-stop publication failures have no ApplicationExitInfo to recover
        // while this process remains alive. Retry that process-local evidence first; exceptions
        // intentionally feed the UI's bounded history-read backoff.
        retryPendingServiceStopIncidents(appContext)
        // Application startup recovery is best-effort. Every history read is retried by the UI,
        // so retry the complete prior-session recovery here too; otherwise one transient startup
        // failure can leave an armed previous-process marker invisible until capture starts again.
        recoverPriorSessionIfNeeded(appContext)
        val incidents = readHistory(historyFile(appContext)).filterNot { it.deleted }
        // Only a complete authoritative history read ends the failure episode. A successful
        // internal read/write during still-failing recovery must not reset its wake-up budget.
        if (historyPublication.completeHistoryRead()) signalHistoryChanged()
        return incidents
    }

    fun deleteIncidentInBackground(context: Context, incident: RecordingIncident) {
        val appContext = context.applicationContext
        historyMutationScope.launch {
            runIncidentHistoryMutation(
                mutation = { deleteIncident(appContext, incident) },
                onFailure = {
                    AppFeedbackCenter.post(
                        appContext.getString(R.string.incident_update_failed),
                        FeedbackTone.ERROR,
                    )
                },
            )
        }
    }

    @Synchronized
    private fun deleteIncident(context: Context, incident: RecordingIncident) {
        val file = historyFile(context)
        val existing = readHistory(file)
        val index = existing.indexOfFirst { recordingIncidentReferenceMatches(it, incident) }
        if (index < 0) return
        if (existing[index].deleted) {
            // A previous publication can be visible without its directory barrier succeeding.
            // Explicit retry confirms the tombstone and refreshes the still-visible UI row.
            writeHistory(file, existing)
            signalHistoryChanged()
            return
        }
        val updated = existing.toMutableList().apply {
            this[index] = this[index].copy(acknowledgedAtMillis = Long.MIN_VALUE)
        }
        writeHistory(file, updated)
        signalHistoryChanged()
    }

    fun toggleIncidentAcknowledgedInBackground(context: Context, incident: RecordingIncident) {
        val appContext = context.applicationContext
        // The card tap has already committed a durable-history mutation from the user's
        // perspective. Transfer it to process lifetime before returning to the UI; Activity or
        // Compose disposal may cancel presentation work but must not revoke this accepted toggle.
        historyMutationScope.launch {
            runIncidentHistoryMutation(
                mutation = { toggleIncidentAcknowledged(appContext, incident) },
                onFailure = {
                    AppFeedbackCenter.post(
                        appContext.getString(R.string.incident_update_failed),
                        FeedbackTone.ERROR,
                    )
                },
            )
        }
    }

    @Synchronized
    fun toggleIncidentAcknowledged(context: Context, incident: RecordingIncident): List<RecordingIncident> {
        val appContext = context.applicationContext
        val file = historyFile(appContext)
        val existing = readHistory(file)
        val index = existing.indexOfFirst { candidate ->
            recordingIncidentReferenceMatches(candidate, incident)
        }
        if (index < 0) return existing
        val updated = existing.toMutableList().apply {
            this[index] = toggleRecordingIncidentAcknowledgement(
                incident = this[index],
                acknowledgedAtMillis = System.currentTimeMillis(),
            )
        }
        writeHistory(file, updated)
        signalHistoryChanged()
        return updated
    }

    private fun markerBelongsToCurrentProcess(marker: ActiveRecordingSessionMarker): Boolean =
        marker.pid == Process.myPid() &&
            marker.processStartElapsedRealtimeMillis == Process.getStartElapsedRealtime()

    private fun enqueueServiceStopIncidentRetry(retry: PendingProcessIncidentRetry) {
        if (serviceStopRetryShouldAppend(
                existing = pendingServiceStopIncidentRetries.map(PendingProcessIncidentRetry::sessionKey),
                incoming = retry.sessionKey(),
            )
        ) {
            pendingServiceStopIncidentRetries += retry
        }
        // StateFlow retains the latest revision even with no active UI. A live UI immediately
        // enters its existing read/backoff loop; a later UI load also sees the incremented value.
        signalHistoryChanged()
    }

    private fun enqueueCaptureInterruptionRetry(retry: PendingProcessIncidentRetry) {
        // A pending interruption blocks fresh capture before marker replacement, so another retry
        // from the same process lifetime still refers to that unresolved armed session. Keep the
        // first outage boundary instead of growing an unbounded in-memory queue while I/O is sick.
        if (pendingCaptureInterruptionRetries.any { pending ->
                pending.expectedPid == retry.expectedPid &&
                    pending.expectedProcessStartElapsedRealtimeMillis ==
                    retry.expectedProcessStartElapsedRealtimeMillis
            }
        ) {
            signalHistoryChanged()
            return
        }
        pendingCaptureInterruptionRetries += retry
        // Reuse the history revision as a process-local retry trigger. The UI collector already
        // performs bounded backoff, and recordCaptureStarted retries again before marker replacement.
        signalHistoryChanged()
    }

    private fun disarmInterruptedSession(
        file: AtomicFile,
        marker: ActiveRecordingSessionMarker,
    ): Boolean {
        if (runCatching { writeSession(file, marker.copy(armed = false)) }.isSuccess) return true
        return runCatching { deleteAtomicDurablyIfPresent(file) }.isSuccess
    }

    private fun retryPendingCaptureInterruptions(context: Context) {
        val iterator = pendingCaptureInterruptionRetries.iterator()
        while (iterator.hasNext()) {
            val retry = iterator.next()
            val markerFile = sessionFile(context)
            val marker = readSession(markerFile)
            if (marker == null) {
                when (atomicFileRegularBackingState(markerFile.baseFile)) {
                    StoragePathState.MISSING -> {
                        // If history was already built before marker retirement failed, keep that
                        // incident. With no marker left there is no exact session identity from
                        // which an earlier read failure can safely synthesize one.
                        completeMissingIncidentMarkerRetry(
                            baseFile = markerFile.baseFile,
                            preserveIncident = { retry.incident?.let { appendIncident(context, it) } },
                            retireRetry = { iterator.remove() },
                        )
                        continue
                    }
                    StoragePathState.PRESENT,
                    StoragePathState.UNAVAILABLE,
                    -> throw IOException(
                        "Unable to resolve armed session for pending capture interruption",
                    )
                }
            }
            val resolvedMarker = requireNotNull(marker)
            val markerMatches = resolvedMarker.matches(retry)
            val incident = retry.incident ?: if (markerMatches) {
                incidentWithoutExitEvidence(
                    marker = resolvedMarker,
                    occurredAtMillis = retry.occurredAtMillis,
                    description = retry.description,
                )
            } else {
                // Never retarget a retry whose session identity could not be read originally at a
                // newer/prior marker from the same process lifetime.
                iterator.remove()
                continue
            }
            appendIncident(context, incident)
            if (markerMatches && !disarmInterruptedSession(markerFile, resolvedMarker)) {
                throw IOException("Unable to retire session after pending capture interruption")
            }
            iterator.remove()
        }
    }

    private fun retryPendingServiceStopIncidents(context: Context) {
        val iterator = pendingServiceStopIncidentRetries.iterator()
        while (iterator.hasNext()) {
            val retry = iterator.next()
            var incident = retry.incident
            if (incident == null) {
                val markerFile = sessionFile(context)
                val marker = readSession(markerFile)
                if (marker == null) {
                    when (atomicFileRegularBackingState(markerFile.baseFile)) {
                        StoragePathState.MISSING -> {
                            iterator.remove()
                            continue
                        }
                        StoragePathState.PRESENT,
                        StoragePathState.UNAVAILABLE,
                        -> throw IOException(
                            "Unable to resolve armed session for pending Service-stop incident",
                        )
                    }
                }
                val resolvedMarker = requireNotNull(marker)
                if (!resolvedMarker.matches(retry)
                ) {
                    // The only matching durable marker is gone or no longer armed. Never retarget
                    // this old stop at a newer/prior capture session.
                    iterator.remove()
                    continue
                }
                incident = incidentWithoutExitEvidence(
                    marker = resolvedMarker,
                    occurredAtMillis = retry.occurredAtMillis,
                    description = retry.description,
                )
            }
            appendIncident(context, requireNotNull(incident))
            iterator.remove()
        }
    }

    private fun enqueuePendingSession(
        context: Context,
        marker: ActiveRecordingSessionMarker,
    ) {
        val file = pendingSessionFile(context)
        val existing = readPendingSessions(file)
        if (existing.any { sameSession(it.marker, marker) }) {
            // Recovery must not retire the old armed marker on merely visible pending evidence.
            writePendingSessions(file, existing)
            return
        }
        if (!pendingIncidentQueueCanAppend(existing.size)) {
            throw IOException(
                "Pending recording incident queue is full; refusing to discard interruption evidence",
            )
        }
        writePendingSessions(file, existing + PendingRecordingSession(marker))
    }

    private fun resolvePendingSessions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val file = pendingSessionFile(context)
        val pending = readPendingSessions(file)
        if (pending.isEmpty()) {
            deleteAtomicDurablyIfPresent(file)
            return
        }

        val remaining = ArrayList<PendingRecordingSession>(pending.size)
        var resolvedAny = false
        pending.forEach { pendingSession ->
            val marker = pendingSession.marker
            val exit = historicalExit(context, marker)
            when (recordingExitDisposition(exit?.reason)) {
                RecordingExitDisposition.PENDING -> {
                    remaining += pendingSession
                }
                RecordingExitDisposition.INCIDENT -> {
                    appendIncident(
                        context,
                        incidentFromExit(
                            marker = marker,
                            exit = requireNotNull(exit),
                            resumedAtMillis = pendingSession.resumedAtMillis,
                        ),
                    )
                    resolvedAny = true
                }
            }
        }
        if (resolvedAny || remaining.size != pending.size) {
            writePendingSessions(file, remaining)
        }
    }

    private fun notePendingSessionsResumed(context: Context, resumedAtMillis: Long) {
        val file = pendingSessionFile(context)
        val pending = readPendingSessions(file)
        if (pending.none { it.resumedAtMillis <= 0L }) return
        writePendingSessions(
            file,
            pending.map { session ->
                if (session.resumedAtMillis > 0L) session
                else session.copy(resumedAtMillis = resumedAtMillis)
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun historicalUnexpectedAppExit(
        context: Context,
        marker: AppProcessMarker,
    ): ApplicationExitInfo? {
        if (marker.pid <= 0 || marker.processStartedAtMillis <= 0L) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val currentStartedAtMillis = currentProcessStartedAtWallClockMillis()
        return manager.getHistoricalProcessExitReasons(context.packageName, marker.pid, 32)
            .asSequence()
            .filter { info ->
                info.pid == marker.pid &&
                    info.timestamp >= marker.processStartedAtMillis &&
                    (currentStartedAtMillis <= 0L || info.timestamp <= currentStartedAtMillis) &&
                    unexpectedAppExitShouldCreateIncident(info.reason, info.status)
            }
            .minByOrNull(ApplicationExitInfo::getTimestamp)
    }

    private fun historicalExit(
        context: Context,
        marker: ActiveRecordingSessionMarker,
    ): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val currentProcessStartedAtMillis = currentProcessStartedAtWallClockMillis()
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, marker.pid, 32)
                .asSequence()
                .filter { info ->
                    info.pid == marker.pid && exitTimestampBelongsToPriorProcess(
                        exitTimestampMillis = info.timestamp,
                        captureArmedAtMillis = marker.armedAtMillis,
                        currentProcessStartedAtMillis = currentProcessStartedAtMillis,
                    )
                }
                .minByOrNull(ApplicationExitInfo::getTimestamp)
        }.getOrNull()
    }

    private fun sameSession(
        left: ActiveRecordingSessionMarker,
        right: ActiveRecordingSessionMarker,
    ): Boolean = left.pid == right.pid &&
        left.processStartElapsedRealtimeMillis == right.processStartElapsedRealtimeMillis &&
        left.armedAtMillis == right.armedAtMillis

    private fun incidentFromDeviceRestart(
        marker: ActiveRecordingSessionMarker,
        currentWallClockMillis: Long,
        currentElapsedRealtimeMillis: Long,
    ): RecordingIncident {
        val estimatedBootMillis =
            (currentWallClockMillis - currentElapsedRealtimeMillis).coerceAtLeast(marker.armedAtMillis)
        return incidentWithoutExitEvidence(
            marker = marker,
            occurredAtMillis = estimatedBootMillis,
            description = "Device restarted while capture was running",
        )
    }

    private fun incidentWithoutExitEvidence(
        marker: ActiveRecordingSessionMarker,
        occurredAtMillis: Long,
        description: String = "Recording process ended while capture was running",
    ): RecordingIncident = RecordingIncident(
        occurredAtMillis = occurredAtMillis.coerceAtLeast(marker.armedAtMillis),
        resumedAtMillis = 0L,
        kind = RecordingIncidentKind.UNEXPECTED_SHUTDOWN,
        exitReason = ApplicationExitInfo.REASON_UNKNOWN,
        pid = marker.pid,
        processStartedAtMillis = processStartedAtWallClockMillis(marker),
        captureArmedAtMillis = marker.armedAtMillis,
        description = description,
    )

    private fun processStartedAtWallClockMillis(marker: ActiveRecordingSessionMarker): Long {
        val elapsedBeforeArmed =
            marker.armedElapsedRealtimeMillis - marker.processStartElapsedRealtimeMillis
        return if (elapsedBeforeArmed >= 0L && elapsedBeforeArmed <= marker.armedAtMillis) {
            marker.armedAtMillis - elapsedBeforeArmed
        } else {
            -1L
        }
    }

    private fun currentProcessStartedAtWallClockMillis(): Long {
        val elapsedSinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        if (elapsedSinceStart < 0L) return -1L
        return (System.currentTimeMillis() - elapsedSinceStart).coerceAtLeast(1L)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun incidentFromExit(
        marker: ActiveRecordingSessionMarker,
        exit: ApplicationExitInfo,
        resumedAtMillis: Long,
    ): RecordingIncident {
        val processStartedAtMillis = processStartedAtWallClockMillis(marker)
        val description = exit.description
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_DESCRIPTION_CHARS)
        return RecordingIncident(
            occurredAtMillis = exit.timestamp,
            resumedAtMillis = resumedAtMillis,
            kind = RecordingIncidentKind.UNEXPECTED_SHUTDOWN,
            exitReason = exit.reason,
            exitStatus = exit.status,
            pid = exit.pid,
            importance = exit.importance,
            pssKb = exit.pss,
            rssKb = exit.rss,
            processStartedAtMillis = processStartedAtMillis,
            captureArmedAtMillis = marker.armedAtMillis,
            description = description,
        )
    }

    private fun completePendingDowntime(context: Context, resumedAtMillis: Long) {
        val file = historyFile(context)
        val existing = readHistory(file)
        val updated = completeRecordingIncidentDowntimes(existing, resumedAtMillis)
        if (updated === existing) return
        writeHistory(file, updated)
        signalHistoryChanged()
    }

    private fun historyRetainingReplayableDeletions(
        context: Context,
        existing: List<RecordingIncident>,
        incoming: RecordingIncident,
    ): List<RecordingIncident> = compactRecordingIncidentDeletions(existing, incoming) {
            // Every producer of delayed evidence is serialized by the store's monitor. Read all
            // durable owners before pruning; a failed read is not proof that an owner is absent.
            val active = readSession(sessionFile(context))?.takeIf { it.armed }
            val pending = readPendingSessions(pendingSessionFile(context))
            val process = readProcessMarker(AtomicFile(File(context.noBackupFilesDir, PROCESS_FILE_NAME)))
            RecordingIncidentReplayOwners(
                captureSessions = (pending.map { it.marker } + listOfNotNull(active)).map { marker ->
                    incidentWithoutExitEvidence(marker, marker.armedAtMillis)
                },
                exactIncidents = (pendingCaptureInterruptionRetries + pendingServiceStopIncidentRetries)
                    .mapNotNull { it.incident },
                appProcessPid = process?.pid ?: 0,
                appProcessStartedAtMillis = process?.processStartedAtMillis ?: -1L,
            )
    }

    private fun appendIncident(context: Context, incident: RecordingIncident) {
        val file = historyFile(context)
        val previous = readHistory(file)
        val existing = historyRetainingReplayableDeletions(context, previous, incident)
        val sessionIndex = existing.indexOfFirst { candidate ->
            candidate.kind == incident.kind &&
                recordingIncidentsShareCaptureSession(candidate, incident)
        }
        val exactIndex = existing.indexOfFirst { candidate ->
            recordingIncidentReferenceMatches(candidate, incident)
        }
        val index = sessionIndex.takeIf { it >= 0 } ?: exactIndex
        val updated = if (index >= 0) {
            existing.toMutableList().apply {
                this[index] = mergeRecordingIncidentEvidence(this[index], incident)
            }.sortedBy { it.occurredAtMillis }
        } else {
            appendRecordingIncidentPreservingDeletions(existing, incident, MAX_INCIDENTS)
        }
        if (updated == previous) {
            // A retried append may find bytes published before a failed directory barrier,
            // including after process replacement. Re-establish write-ahead proof before its
            // caller is allowed to retire the armed/pending capture owner.
            writeHistory(file, previous)
            return
        }
        writeHistory(file, updated)
        signalHistoryChanged()
    }

    private fun signalHistoryChanged() {
        mutableHistoryRevision.value = mutableHistoryRevision.value + 1L
    }

    private fun packageLastUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    private fun sessionFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, SESSION_FILE_NAME))
    private fun pendingSessionFile(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, PENDING_SESSION_FILE_NAME))
    private fun historyFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, HISTORY_FILE_NAME))

    private fun deleteAtomicDurablyIfPresent(file: AtomicFile) {
        deleteIncidentAtomicStateDurably(file.baseFile, removePresent = file::delete)
    }

    private fun readProcessMarker(file: AtomicFile): AppProcessMarker? =
        readAtomic(file, "app process marker") { input ->
            requireFileHeader(input, PROCESS_MAGIC, PROCESS_FORMAT_VERSION, "app process marker")
            val pid = input.readInt()
            val processStartedAtMillis = input.readLong()
            if (pid <= 0 || processStartedAtMillis <= 0L) {
                throw IOException("Invalid app process marker")
            }
            AppProcessMarker(pid = pid, processStartedAtMillis = processStartedAtMillis)
        }

    private fun writeProcessMarker(file: AtomicFile, marker: AppProcessMarker) {
        writeAtomic(file) { output ->
            output.writeInt(PROCESS_MAGIC)
            output.writeByte(PROCESS_FORMAT_VERSION)
            output.writeInt(marker.pid)
            output.writeLong(marker.processStartedAtMillis)
        }
    }

    private fun readSession(file: AtomicFile): ActiveRecordingSessionMarker? =
        readAtomic(file, "recording session") { input ->
            requireFileHeader(input, SESSION_MAGIC, SESSION_FORMAT_VERSION, "recording session")
            readSessionMarker(input)
        }

    private fun writeSession(file: AtomicFile, marker: ActiveRecordingSessionMarker) {
        writeAtomic(file) { output ->
            output.writeInt(SESSION_MAGIC)
            output.writeByte(SESSION_FORMAT_VERSION)
            writeSessionMarker(output, marker)
        }
    }

    private fun readPendingSessions(file: AtomicFile): List<PendingRecordingSession> =
        readAtomic(file, "pending recording incidents") { input ->
            requireFileHeader(input, PENDING_MAGIC, PENDING_FORMAT_VERSION, "pending recording incidents")
            val count = input.readUnsignedShort()
            if (count > MAX_PENDING_INCIDENT_SESSIONS) {
                throw IOException("Pending recording incident count $count exceeds $MAX_PENDING_INCIDENT_SESSIONS")
            }
            buildList(count) {
                repeat(count) {
                    add(
                        PendingRecordingSession(
                            marker = readSessionMarker(input),
                            resumedAtMillis = input.readLong(),
                        ),
                    )
                }
            }
        } ?: emptyList()

    private fun writePendingSessions(
        file: AtomicFile,
        sessions: List<PendingRecordingSession>,
    ) {
        if (sessions.isEmpty()) {
            deleteAtomicDurablyIfPresent(file)
            return
        }
        if (sessions.size > MAX_PENDING_INCIDENT_SESSIONS) {
            throw IOException(
                "Pending recording incident count ${sessions.size} exceeds $MAX_PENDING_INCIDENT_SESSIONS",
            )
        }
        writeAtomic(file) { output ->
            output.writeInt(PENDING_MAGIC)
            output.writeByte(PENDING_FORMAT_VERSION)
            output.writeShort(sessions.size)
            sessions.forEach { session ->
                writeSessionMarker(output, session.marker)
                output.writeLong(session.resumedAtMillis)
            }
        }
    }

    private fun readSessionMarker(input: DataInputStream) = ActiveRecordingSessionMarker(
        armed = input.readBoolean(),
        pid = input.readInt(),
        processStartElapsedRealtimeMillis = input.readLong(),
        armedAtMillis = input.readLong(),
        armedElapsedRealtimeMillis = input.readLong(),
        packageLastUpdateTimeMillis = input.readLong(),
    ).also { marker ->
        if (marker.pid <= 0 || marker.processStartElapsedRealtimeMillis < 0L ||
            marker.armedAtMillis <= 0L || marker.armedElapsedRealtimeMillis < 0L
        ) {
            throw IOException("Invalid recording session marker")
        }
    }

    private fun writeSessionMarker(
        output: DataOutputStream,
        marker: ActiveRecordingSessionMarker,
    ) {
        output.writeBoolean(marker.armed)
        output.writeInt(marker.pid)
        output.writeLong(marker.processStartElapsedRealtimeMillis)
        output.writeLong(marker.armedAtMillis)
        output.writeLong(marker.armedElapsedRealtimeMillis)
        output.writeLong(marker.packageLastUpdateTimeMillis)
    }

    private fun readHistory(file: AtomicFile): List<RecordingIncident> {
        historyPublication.confirmPending(::publishHistory)
        return readAtomic(file, "recording incident history", ::readHistoryPayload) ?: emptyList()
    }

    internal fun readHistoryPayload(input: DataInputStream): List<RecordingIncident> {
        if (input.readInt() != HISTORY_MAGIC) throw IOException("Invalid recording incident history magic")
        val incidents = when (val version = input.readUnsignedByte()) {
            LEGACY_HISTORY_FORMAT_VERSION -> readLegacyHistory(input)
            PREVIOUS_HISTORY_FORMAT_VERSION -> readCurrentHistory(input, MAX_INCIDENTS)
            HISTORY_FORMAT_VERSION -> readCurrentHistory(input, MAX_RECORDING_INCIDENT_HISTORY_RECORDS)
            else -> throw IOException("Unsupported recording incident history version $version")
        }
        validateRecordingIncidentHistorySize(incidents)
        return incidents
    }

    private fun readLegacyHistory(input: DataInputStream): List<RecordingIncident> {
        val count = input.readUnsignedShort()
        if (count > MAX_INCIDENTS) throw IOException("Incident count $count exceeds $MAX_INCIDENTS")
        return buildList(count) {
            repeat(count) {
                val kindCode = input.readByte()
                val timestamp = input.readLong()
                val kind = RecordingIncidentKind.fromStorageCode(kindCode)
                    ?: throw IOException("Unknown recording incident kind $kindCode")
                if (timestamp <= 0L) throw IOException("Invalid recording incident timestamp $timestamp")
                add(RecordingIncident(occurredAtMillis = timestamp, kind = kind))
            }
        }
    }

    private fun readCurrentHistory(input: DataInputStream, maximumRecords: Int): List<RecordingIncident> {
        val count = input.readUnsignedShort()
        if (count > maximumRecords) throw IOException("Incident count $count exceeds $maximumRecords")
        return buildList(count) {
            repeat(count) {
                val kindCode = input.readByte()
                val occurredAtMillis = input.readLong()
                val resumedAtMillis = input.readLong()
                val acknowledgedAtMillis = input.readLong()
                val exitReason = input.readInt()
                val exitStatus = input.readInt()
                val pid = input.readInt()
                val importance = input.readInt()
                val pssKb = input.readLong()
                val rssKb = input.readLong()
                val processStartedAtMillis = input.readLong()
                val captureArmedAtMillis = input.readLong()
                val description = input.readUTF().takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION_CHARS)
                val kind = RecordingIncidentKind.fromStorageCode(kindCode)
                    ?: throw IOException("Unknown recording incident kind $kindCode")
                if (occurredAtMillis <= 0L) {
                    throw IOException("Invalid recording incident timestamp $occurredAtMillis")
                }
                add(
                    RecordingIncident(
                        occurredAtMillis = occurredAtMillis,
                        resumedAtMillis = resumedAtMillis,
                        acknowledgedAtMillis = acknowledgedAtMillis,
                        kind = kind,
                        exitReason = exitReason,
                        exitStatus = exitStatus,
                        pid = pid,
                        importance = importance,
                        pssKb = pssKb,
                        rssKb = rssKb,
                        processStartedAtMillis = processStartedAtMillis,
                        captureArmedAtMillis = captureArmedAtMillis,
                        description = description,
                    ),
                )
            }
        }
    }

    private fun writeHistory(file: AtomicFile, incidents: List<RecordingIncident>) {
        validateRecordingIncidentHistorySize(incidents)
        historyPublication.write(file.baseFile, incidents, ::publishHistory, ::signalHistoryChanged)
    }

    private fun publishHistory(
        file: File,
        incidents: List<RecordingIncident>,
        onPublicationStarted: () -> Unit,
    ) {
        validateRecordingIncidentHistorySize(incidents)
        writeAtomic(AtomicFile(file), onPublicationStarted) { output -> writeHistoryPayload(output, incidents) }
    }

    internal fun writeHistoryPayload(output: DataOutputStream, incidents: List<RecordingIncident>) {
        validateRecordingIncidentHistorySize(incidents)
        output.writeInt(HISTORY_MAGIC)
        output.writeByte(HISTORY_FORMAT_VERSION)
        output.writeShort(incidents.size)
        incidents.forEach { incident ->
            output.writeByte(incident.kind.storageCode.toInt())
            output.writeLong(incident.occurredAtMillis)
            output.writeLong(incident.resumedAtMillis)
            output.writeLong(incident.acknowledgedAtMillis)
            output.writeInt(incident.exitReason)
            output.writeInt(incident.exitStatus)
            output.writeInt(incident.pid)
            output.writeInt(incident.importance)
            output.writeLong(incident.pssKb)
            output.writeLong(incident.rssKb)
            output.writeLong(incident.processStartedAtMillis)
            output.writeLong(incident.captureArmedAtMillis)
            output.writeUTF(incident.description.orEmpty().take(MAX_DESCRIPTION_CHARS))
        }
    }

    private inline fun <T> readAtomic(
        file: AtomicFile,
        label: String,
        block: (DataInputStream) -> T,
    ): T? {
        when (val backingState = atomicFileRegularBackingState(file.baseFile)) {
            StoragePathState.MISSING -> return null
            StoragePathState.UNAVAILABLE -> throw IOException(
                "Unable to read $label while AtomicFile backing state is $backingState",
            )
            StoragePathState.PRESENT -> Unit
        }
        val stream = try {
            file.openRead()
        } catch (error: FileNotFoundException) {
            val backingState = atomicFileRegularBackingState(file.baseFile)
            if (atomicReadMissIsAuthoritativeAbsence(backingState)) return null
            throw IOException("Unable to read $label while AtomicFile backing state is $backingState", error)
        }
        return try {
            // Descriptor binding can fail before the buffered input takes ownership.
            stream.use {
                val descriptorIdentity = bindAtomicFileReadDescriptor(file.baseFile, stream.fd)
                    ?: throw IOException("Unable to bind $label to its AtomicFile backing object")
                val result = DataInputStream(BufferedInputStream(stream)).use { input ->
                    readIncidentPayloadExact(input, label, block)
                }
                if (!atomicFileReadDescriptorRemainsCurrent(file.baseFile, descriptorIdentity)) {
                    throw IOException("AtomicFile backing object changed while reading $label")
                }
                result
            }
        } catch (error: IOException) {
            throw IOException("Unable to read $label", error)
        } catch (error: RuntimeException) {
            throw IOException("Unable to decode $label", error)
        }
    }

    private fun requireFileHeader(
        input: DataInputStream,
        expectedMagic: Int,
        expectedVersion: Int,
        label: String,
    ) {
        if (input.readInt() != expectedMagic) throw IOException("Invalid $label magic")
        val version = input.readUnsignedByte()
        if (version != expectedVersion) throw IOException("Unsupported $label version $version")
    }

    private inline fun writeAtomic(
        file: AtomicFile,
        crossinline onPublicationStarted: () -> Unit = {},
        block: (DataOutputStream) -> Unit,
    ) {
        if (atomicFileRegularBackingState(file.baseFile) == StoragePathState.UNAVAILABLE) {
            throw IOException("AtomicFile backing state is unsafe for write: ${file.baseFile.absolutePath}")
        }
        val stream = file.startWrite()
        var publicationAttempted = false
        try {
            val output = DataOutputStream(BufferedOutputStream(stream))
            block(output)
            output.flush()
            finishDurableAtomicWrite(
                baseFile = file.baseFile,
                output = stream,
                finishWrite = file::finishWrite,
                syncDirectory = { confirmFileDirectoryStateDurable(file.baseFile) },
                onPublicationStarted = {
                    publicationAttempted = true
                    onPublicationStarted()
                },
            )
        } catch (error: Throwable) {
            throwIncidentAtomicWriteFailure(
                primaryFailure = error,
                committed = publicationAttempted,
                rollback = { file.failWrite(stream) },
            )
        }
    }
}
