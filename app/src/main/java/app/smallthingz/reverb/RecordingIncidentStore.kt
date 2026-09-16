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

internal const val EXIT_REASON_MEMORY_LIMITER = 17
internal const val EXIT_REASON_ANOMALY = 18

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

internal enum class RecordingExitDisposition {
    INCIDENT,
    PENDING,
}

internal fun recordingExitDisposition(reason: Int?): RecordingExitDisposition =
    if (reason == null) RecordingExitDisposition.PENDING else RecordingExitDisposition.INCIDENT


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
    private const val MAX_PENDING_SESSIONS = 16
    private const val MAX_DESCRIPTION_CHARS = 384
    private const val SESSION_FILE_NAME = "recording-session.bin"
    private const val PENDING_SESSION_FILE_NAME = "recording-incident-pending.bin"
    private const val HISTORY_FILE_NAME = "recording-incidents.bin"
    private const val PENDING_MAGIC = 0x52495031 // RIP1
    private const val PENDING_FORMAT_VERSION = 1

    private val mutableHistoryRevision = MutableStateFlow(0L)
    val historyRevision: StateFlow<Long> = mutableHistoryRevision.asStateFlow()

    @Synchronized
    fun recoverPriorSessionIfNeeded(context: Context) {
        val appContext = context.applicationContext
        resolvePendingSessions(appContext)

        val markerFile = sessionFile(appContext)
        val marker = readSession(markerFile) ?: run {
            markerFile.delete()
            return
        }
        if (!marker.armed) {
            markerFile.delete()
            return
        }
        val listeningExpected = getRecorderPreferences(appContext)
            .getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
        if (!listeningExpected) {
            markerFile.delete()
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
            markerFile.delete()
            return
        }

        if (markerBelongsToCurrentProcess(marker)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendIncident(
                appContext,
                incidentWithoutExitEvidence(marker, System.currentTimeMillis()),
            )
            markerFile.delete()
            return
        }

        // Preserve the previous armed session before the restarted recorder can overwrite the
        // active marker. ExitInfo can lag process startup, so absence of evidence is not evidence
        // of a clean exit. The pending queue is retried on startup, capture start, and history read.
        if (enqueuePendingSession(appContext, marker)) {
            markerFile.delete()
            resolvePendingSessions(appContext)
        }
    }

    @Synchronized
    fun recordCaptureStarted(context: Context) {
        val appContext = context.applicationContext
        recoverPriorSessionIfNeeded(appContext)
        val resumedAtMillis = System.currentTimeMillis()
        notePendingSessionsResumed(appContext, resumedAtMillis)
        resolvePendingSessions(appContext)
        writeSession(
            sessionFile(appContext),
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
    fun recordCaptureInterrupted(context: Context, description: String) {
        val appContext = context.applicationContext
        val file = sessionFile(appContext)
        val marker = readSession(file) ?: return
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
            .onFailure { file.delete() }
    }

    @Synchronized
    fun recordKnownCaptureStop(context: Context) {
        val appContext = context.applicationContext
        val file = sessionFile(appContext)
        val existing = readSession(file) ?: return
        if (!existing.armed) return
        runCatching {
            writeSession(file, existing.copy(armed = false))
        }.onFailure {
            // Best-effort fallback. A known stop should disarm durably; if that write fails,
            // deleting the marker is safer than falsely reporting a later process death.
            file.delete()
        }
    }

    @Synchronized
    fun readIncidents(context: Context): List<RecordingIncident> {
        val appContext = context.applicationContext
        resolvePendingSessions(appContext)
        return readHistory(historyFile(appContext))
    }

    @Synchronized
    fun toggleIncidentAcknowledged(context: Context, incident: RecordingIncident): List<RecordingIncident> {
        val appContext = context.applicationContext
        val file = historyFile(appContext)
        val existing = readHistory(file)
        val index = existing.indexOfFirst {
            it.kind == incident.kind && it.occurredAtMillis == incident.occurredAtMillis
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

    private fun enqueuePendingSession(
        context: Context,
        marker: ActiveRecordingSessionMarker,
    ): Boolean = runCatching {
        val file = pendingSessionFile(context)
        val existing = readPendingSessions(file)
        if (existing.any { sameSession(it.marker, marker) }) return@runCatching true
        val updated = (existing + PendingRecordingSession(marker)).takeLast(MAX_PENDING_SESSIONS)
        writePendingSessions(file, updated)
        true
    }.getOrDefault(false)

    private fun resolvePendingSessions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val file = pendingSessionFile(context)
        val pending = readPendingSessions(file)
        if (pending.isEmpty()) {
            file.delete()
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
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, marker.pid, 32)
                .asSequence()
                .filter { info -> info.pid == marker.pid && info.timestamp >= marker.armedAtMillis }
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
        if (existing.any { it.kind == incident.kind && it.occurredAtMillis == incident.occurredAtMillis }) return
        val updated = (existing + incident)
            .sortedBy { it.occurredAtMillis }
            .takeLast(MAX_INCIDENTS)
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

    private fun readSession(file: AtomicFile): ActiveRecordingSessionMarker? = runCatching {
        DataInputStream(BufferedInputStream(file.openRead())).use { input ->
            if (input.readInt() != SESSION_MAGIC || input.readUnsignedByte() != SESSION_FORMAT_VERSION) return null
            readSessionMarker(input)
        }
    }.getOrNull()

    private fun writeSession(file: AtomicFile, marker: ActiveRecordingSessionMarker) {
        writeAtomic(file) { output ->
            output.writeInt(SESSION_MAGIC)
            output.writeByte(SESSION_FORMAT_VERSION)
            writeSessionMarker(output, marker)
        }
    }

    private fun readPendingSessions(file: AtomicFile): List<PendingRecordingSession> = runCatching {
        DataInputStream(BufferedInputStream(file.openRead())).use { input ->
            if (input.readInt() != PENDING_MAGIC || input.readUnsignedByte() != PENDING_FORMAT_VERSION) {
                return emptyList()
            }
            val count = input.readUnsignedShort().coerceAtMost(MAX_PENDING_SESSIONS)
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
        }
    }.getOrDefault(emptyList())

    private fun writePendingSessions(
        file: AtomicFile,
        sessions: List<PendingRecordingSession>,
    ) {
        if (sessions.isEmpty()) {
            file.delete()
            return
        }
        writeAtomic(file) { output ->
            output.writeInt(PENDING_MAGIC)
            output.writeByte(PENDING_FORMAT_VERSION)
            output.writeShort(sessions.size.coerceAtMost(MAX_PENDING_SESSIONS))
            sessions.takeLast(MAX_PENDING_SESSIONS).forEach { session ->
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
    )

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

    private fun readHistory(file: AtomicFile): List<RecordingIncident> = runCatching {
        DataInputStream(BufferedInputStream(file.openRead())).use { input ->
            if (input.readInt() != HISTORY_MAGIC) return emptyList()
            when (input.readUnsignedByte()) {
                LEGACY_HISTORY_FORMAT_VERSION -> readLegacyHistory(input)
                HISTORY_FORMAT_VERSION -> readCurrentHistory(input)
                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList())

    private fun readLegacyHistory(input: DataInputStream): List<RecordingIncident> {
        val count = input.readUnsignedShort().coerceAtMost(MAX_INCIDENTS)
        return buildList(count) {
            repeat(count) {
                val kindCode = input.readByte()
                val timestamp = input.readLong()
                val kind = RecordingIncidentKind.fromStorageCode(kindCode)
                if (kind != null && timestamp > 0L) {
                    add(RecordingIncident(occurredAtMillis = timestamp, kind = kind))
                }
            }
        }
    }

    private fun readCurrentHistory(input: DataInputStream): List<RecordingIncident> {
        val count = input.readUnsignedShort().coerceAtMost(MAX_INCIDENTS)
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
                val description = input.readUTF().takeIf { it.isNotBlank() }
                val kind = RecordingIncidentKind.fromStorageCode(kindCode)
                if (kind != null && occurredAtMillis > 0L) {
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

    private inline fun writeAtomic(file: AtomicFile, block: (DataOutputStream) -> Unit) {
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(BufferedOutputStream(stream))
            block(output)
            output.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }
}
