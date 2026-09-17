package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal fun pendingIncidentQueueCanAppend(existingCount: Int): Boolean =
    existingCount in 0 until MAX_PENDING_INCIDENT_SESSIONS

internal enum class RecordingIncidentKind(val storageCode: Byte) {
    UNEXPECTED_SHUTDOWN(1),
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
    val acknowledged: Boolean get() = acknowledgedAtMillis > 0L
    val recoveryPending: Boolean get() = resumedAtMillis == 0L
}

internal fun toggleRecordingIncidentAcknowledgement(
    incident: RecordingIncident,
    acknowledgedAtMillis: Long,
): RecordingIncident = incident.copy(
    acknowledgedAtMillis = if (incident.acknowledged) 0L else acknowledgedAtMillis.coerceAtLeast(1L),
)

internal fun recordingIncidentsShareCaptureSession(
    left: RecordingIncident,
    right: RecordingIncident,
): Boolean = left.pid > 0 && right.pid == left.pid &&
    left.captureArmedAtMillis > 0L && right.captureArmedAtMillis == left.captureArmedAtMillis

internal fun recordingIncidentReferenceMatches(
    candidate: RecordingIncident,
    reference: RecordingIncident,
): Boolean = candidate.kind == reference.kind &&
    (candidate.occurredAtMillis == reference.occurredAtMillis ||
        recordingIncidentsShareCaptureSession(candidate, reference))

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
        acknowledgedAtMillis = existing.acknowledgedAtMillis.takeIf { it > 0L }
            ?: incoming.acknowledgedAtMillis,
        description = base.description ?: existing.description ?: incoming.description,
    )
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

private data class PendingServiceStopIncidentRetry(
    val occurredAtMillis: Long,
    val description: String,
    val expectedPid: Int,
    val expectedProcessStartElapsedRealtimeMillis: Long,
    val incident: RecordingIncident? = null,
)

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

internal fun captureSessionStartDisposition(
    sameProcessArmedSession: Boolean,
    continuousRestart: Boolean,
): CaptureSessionStartDisposition = when {
    !sameProcessArmedSession -> CaptureSessionStartDisposition.NEW_SESSION
    continuousRestart -> CaptureSessionStartDisposition.CONTINUE_SESSION
    else -> CaptureSessionStartDisposition.RESOLVE_INTERRUPTED_SESSION
}

internal fun serviceStopIncidentMarkerMatches(
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
    private const val HISTORY_MAGIC = 0x52494831 // RIH1
    private const val SESSION_FORMAT_VERSION = 2
    private const val LEGACY_HISTORY_FORMAT_VERSION = 1
    private const val HISTORY_FORMAT_VERSION = 2
    private const val MAX_INCIDENTS = 128
    private const val MAX_DESCRIPTION_CHARS = 384
    private const val SESSION_FILE_NAME = "recording-session.bin"
    private const val PENDING_SESSION_FILE_NAME = "recording-incident-pending.bin"
    private const val HISTORY_FILE_NAME = "recording-incidents.bin"
    private const val PENDING_MAGIC = 0x52495031 // RIP1
    private const val PENDING_FORMAT_VERSION = 1

    private val mutableHistoryRevision = MutableStateFlow(0L)
    val historyRevision: StateFlow<Long> = mutableHistoryRevision.asStateFlow()
    private val pendingServiceStopIncidentRetries = mutableListOf<PendingServiceStopIncidentRetry>()

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
        val retry = PendingServiceStopIncidentRetry(
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
        if (!serviceStopIncidentMarkerMatches(
                armed = marker.armed,
                markerPid = marker.pid,
                markerProcessStartElapsedRealtimeMillis = marker.processStartElapsedRealtimeMillis,
                markerArmedAtMillis = marker.armedAtMillis,
                expectedPid = retry.expectedPid,
                expectedProcessStartElapsedRealtimeMillis = retry.expectedProcessStartElapsedRealtimeMillis,
                stopOccurredAtMillis = retry.occurredAtMillis,
            )
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
        val file = sessionFile(appContext)
        val marker = try {
            readSession(file)
        } catch (_: IOException) {
            // Preserve unreadable evidence for startup recovery; a framework timeout must not
            // turn bookkeeping corruption into a second process crash.
            return
        } ?: return
        if (!marker.armed) return

        // Persist the incident before disarming the session. If history persistence fails,
        // leave the marker armed so restart recovery still has a chance to report the outage.
        val persisted = runCatching {
            appendIncident(
                appContext,
                incidentWithoutExitEvidence(
                    marker = marker,
                    occurredAtMillis = System.currentTimeMillis(),
                    description = description,
                ),
            )
        }.isSuccess
        if (!persisted) return
        runCatching { writeSession(file, marker.copy(armed = false)) }
            .onFailure { runCatching { deleteAtomicDurablyIfPresent(file) } }
    }

    @Synchronized
    fun recordKnownCaptureStop(context: Context): KnownCaptureStopResult {
        val appContext = context.applicationContext
        val file = sessionFile(appContext)
        val existing = try {
            readSession(file)
        } catch (_: IOException) {
            // A known Stop may retire unreadable incident metadata only if that removal is
            // durable. Otherwise the prior armed state is unknown and the caller must fail closed.
            return if (runCatching { deleteAtomicDurablyIfPresent(file) }.isSuccess) {
                KnownCaptureStopResult.DURABLE
            } else {
                KnownCaptureStopResult.FAILED_UNCERTAIN
            }
        }
        if (existing == null) {
            // AtomicFile.openRead can report no readable base while backup/new state is still
            // ambiguous. The durable delete helper distinguishes real absence from uncertainty.
            return if (runCatching { deleteAtomicDurablyIfPresent(file) }.isSuccess) {
                KnownCaptureStopResult.DURABLE
            } else {
                KnownCaptureStopResult.FAILED_UNCERTAIN
            }
        }
        if (!existing.armed) return KnownCaptureStopResult.DURABLE
        if (runCatching { writeSession(file, existing.copy(armed = false)) }.isSuccess) {
            return KnownCaptureStopResult.DURABLE
        }
        // Durable deletion is a valid known-Stop fallback. If deletion cannot be proven, restore
        // the exact original armed marker before allowing capture to continue. A failed re-arm
        // means marker state is uncertain, so the caller must stop/reclassify rather than record
        // with potentially disabled incident tracking.
        if (runCatching { deleteAtomicDurablyIfPresent(file) }.isSuccess) {
            return KnownCaptureStopResult.DURABLE
        }
        return if (runCatching { writeSession(file, existing) }.isSuccess) {
            KnownCaptureStopResult.FAILED_REARMED
        } else {
            KnownCaptureStopResult.FAILED_UNCERTAIN
        }
    }

    @Synchronized
    fun readIncidents(context: Context): List<RecordingIncident> {
        val appContext = context.applicationContext
        // Same-process Service-stop publication failures have no ApplicationExitInfo to recover
        // while this process remains alive. Retry that process-local evidence first; exceptions
        // intentionally feed the UI's bounded history-read backoff.
        retryPendingServiceStopIncidents(appContext)
        // Application startup recovery is best-effort. Every history read is retried by the UI,
        // so retry the complete prior-session recovery here too; otherwise one transient startup
        // failure can leave an armed previous-process marker invisible until capture starts again.
        recoverPriorSessionIfNeeded(appContext)
        return readHistory(historyFile(appContext))
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

    private fun enqueueServiceStopIncidentRetry(retry: PendingServiceStopIncidentRetry) {
        pendingServiceStopIncidentRetries += retry
        // StateFlow retains the latest revision even with no active UI. A live UI immediately
        // enters its existing read/backoff loop; a later UI load also sees the incremented value.
        signalHistoryChanged()
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
                    when (atomicFileBackingState(markerFile.baseFile)) {
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
                if (!serviceStopIncidentMarkerMatches(
                        armed = resolvedMarker.armed,
                        markerPid = resolvedMarker.pid,
                        markerProcessStartElapsedRealtimeMillis = resolvedMarker.processStartElapsedRealtimeMillis,
                        markerArmedAtMillis = resolvedMarker.armedAtMillis,
                        expectedPid = retry.expectedPid,
                        expectedProcessStartElapsedRealtimeMillis = retry.expectedProcessStartElapsedRealtimeMillis,
                        stopOccurredAtMillis = retry.occurredAtMillis,
                    )
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
        if (existing.any { sameSession(it.marker, marker) }) return
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
                RecordingExitDisposition.PENDING -> remaining += pendingSession
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

    private fun appendIncident(context: Context, incident: RecordingIncident) {
        val file = historyFile(context)
        val existing = readHistory(file)
        val sessionIndex = existing.indexOfFirst { candidate ->
            candidate.kind == incident.kind &&
                recordingIncidentsShareCaptureSession(candidate, incident)
        }
        val exactIndex = existing.indexOfFirst { candidate ->
            candidate.kind == incident.kind && candidate.occurredAtMillis == incident.occurredAtMillis
        }
        val index = sessionIndex.takeIf { it >= 0 } ?: exactIndex
        val updated = if (index >= 0) {
            existing.toMutableList().apply {
                this[index] = mergeRecordingIncidentEvidence(this[index], incident)
            }.sortedBy { it.occurredAtMillis }
        } else {
            (existing + incident).sortedBy { it.occurredAtMillis }.takeLast(MAX_INCIDENTS)
        }
        if (updated == existing) return
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
        when (atomicFileBackingState(file.baseFile)) {
            StoragePathState.MISSING -> return
            StoragePathState.UNAVAILABLE -> throw IOException(
                "Unable to inspect durable incident state: ${file.baseFile.absolutePath}",
            )
            StoragePathState.PRESENT -> Unit
        }
        file.delete()
        when (atomicFileBackingState(file.baseFile)) {
            StoragePathState.MISSING -> Unit
            StoragePathState.PRESENT -> throw IOException(
                "Unable to remove durable incident state: ${file.baseFile.absolutePath}",
            )
            StoragePathState.UNAVAILABLE -> throw IOException(
                "Unable to confirm durable incident removal: ${file.baseFile.absolutePath}",
            )
        }
        if (!confirmFileDirectoryStateDurable(file.baseFile)) {
            throw IOException("Unable to persist incident-state removal: ${file.baseFile.absolutePath}")
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

    private fun readHistory(file: AtomicFile): List<RecordingIncident> =
        readAtomic(file, "recording incident history") { input ->
            if (input.readInt() != HISTORY_MAGIC) throw IOException("Invalid recording incident history magic")
            when (val version = input.readUnsignedByte()) {
                LEGACY_HISTORY_FORMAT_VERSION -> readLegacyHistory(input)
                HISTORY_FORMAT_VERSION -> readCurrentHistory(input)
                else -> throw IOException("Unsupported recording incident history version $version")
            }
        } ?: emptyList()

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

    private fun readCurrentHistory(input: DataInputStream): List<RecordingIncident> {
        val count = input.readUnsignedShort()
        if (count > MAX_INCIDENTS) throw IOException("Incident count $count exceeds $MAX_INCIDENTS")
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
        writeAtomic(file) { output ->
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
    }

    private inline fun <T> readAtomic(
        file: AtomicFile,
        label: String,
        block: (DataInputStream) -> T,
    ): T? {
        val stream = try {
            file.openRead()
        } catch (_: FileNotFoundException) {
            return null
        }
        return try {
            DataInputStream(BufferedInputStream(stream)).use(block)
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

    private inline fun writeAtomic(file: AtomicFile, block: (DataOutputStream) -> Unit) {
        val stream = file.startWrite()
        var committed = false
        try {
            val output = DataOutputStream(BufferedOutputStream(stream))
            block(output)
            output.flush()
            file.finishWrite(stream)
            committed = true
            if (!confirmFileDirectoryStateDurable(file.baseFile)) {
                throw IOException("Unable to persist incident-state publication: ${file.baseFile.absolutePath}")
            }
        } catch (error: Throwable) {
            if (!committed) file.failWrite(stream)
            throw error
        }
    }
}
