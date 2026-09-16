package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.annotation.RequiresApi
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

private data class ActiveRecordingSessionMarker(
    val armed: Boolean,
    val pid: Int,
    val processStartElapsedRealtimeMillis: Long,
    val armedAtMillis: Long,
    val armedElapsedRealtimeMillis: Long,
    val packageLastUpdateTimeMillis: Long,
)

internal fun isSpuriousRecordingProcessExitReason(reason: Int): Boolean = when (reason) {
    EXIT_REASON_ANOMALY,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    ApplicationExitInfo.REASON_FREEZER,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    EXIT_REASON_MEMORY_LIMITER,
    ApplicationExitInfo.REASON_SIGNALED,
    -> true

    ApplicationExitInfo.REASON_EXIT_SELF,
    ApplicationExitInfo.REASON_OTHER,
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
    ApplicationExitInfo.REASON_PACKAGE_UPDATED,
    ApplicationExitInfo.REASON_PERMISSION_CHANGE,
    ApplicationExitInfo.REASON_UNKNOWN,
    ApplicationExitInfo.REASON_USER_REQUESTED,
    ApplicationExitInfo.REASON_USER_STOPPED,
    -> false

    else -> false
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
    private const val HISTORY_FILE_NAME = "recording-incidents.bin"

    @Synchronized
    fun recoverPriorSessionIfNeeded(context: Context) {
        val appContext = context.applicationContext
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
        val currentPackageUpdate = packageLastUpdateTime(appContext)
        if (
            marker.armedElapsedRealtimeMillis > currentElapsed ||
            (marker.packageLastUpdateTimeMillis > 0L &&
                currentPackageUpdate > 0L &&
                marker.packageLastUpdateTimeMillis != currentPackageUpdate)
        ) {
            markerFile.delete()
            return
        }

        val sameProcess = marker.pid == Process.myPid() &&
            marker.processStartElapsedRealtimeMillis == Process.getStartElapsedRealtime()
        if (!sameProcess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            historicalSpuriousExit(appContext, marker)?.let { exit ->
                appendIncident(appContext, incidentFromExit(marker, exit))
            }
        }
        if (!sameProcess) markerFile.delete()
    }

    @Synchronized
    fun markCaptureRunning(context: Context) {
        val appContext = context.applicationContext
        val resumedAtMillis = System.currentTimeMillis()
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
    fun markCaptureStopped(context: Context) {
        val appContext = context.applicationContext
        val file = sessionFile(appContext)
        val existing = readSession(file) ?: return
        if (!existing.armed) return
        runCatching {
            writeSession(file, existing.copy(armed = false))
        }.onFailure {
            // Best-effort fallback. Recovery also requires a matching durable listening intent
            // and an Android-classified unplanned process exit before it can create an incident.
            file.delete()
        }
    }

    @Synchronized
    fun readIncidents(context: Context): List<RecordingIncident> =
        readHistory(historyFile(context.applicationContext))

    @Synchronized
    fun acknowledgeIncident(context: Context, incident: RecordingIncident): List<RecordingIncident> {
        val appContext = context.applicationContext
        val file = historyFile(appContext)
        val existing = readHistory(file)
        val index = existing.indexOfFirst {
            it.kind == incident.kind && it.occurredAtMillis == incident.occurredAtMillis
        }
        if (index < 0 || existing[index].acknowledged) return existing
        val updated = existing.toMutableList().apply {
            this[index] = this[index].copy(acknowledgedAtMillis = System.currentTimeMillis())
        }
        writeHistory(file, updated)
        return updated
    }

    @Synchronized
    internal fun clearForTests(context: Context) {
        sessionFile(context.applicationContext).delete()
        historyFile(context.applicationContext).delete()
    }

    private fun historicalSpuriousExit(
        context: Context,
        marker: ActiveRecordingSessionMarker,
    ): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, marker.pid, 8)
                .firstOrNull { info ->
                    info.pid == marker.pid && info.timestamp >= marker.armedAtMillis
                }
        }.getOrNull() ?: return null
        return exit.takeIf { isSpuriousRecordingProcessExitReason(it.reason) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun incidentFromExit(
        marker: ActiveRecordingSessionMarker,
        exit: ApplicationExitInfo,
    ): RecordingIncident {
        val elapsedBeforeArmed = marker.armedElapsedRealtimeMillis - marker.processStartElapsedRealtimeMillis
        val processStartedAtMillis = if (elapsedBeforeArmed >= 0L && elapsedBeforeArmed <= marker.armedAtMillis) {
            marker.armedAtMillis - elapsedBeforeArmed
        } else {
            -1L
        }
        val description = exit.description
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_DESCRIPTION_CHARS)
        return RecordingIncident(
            occurredAtMillis = exit.timestamp,
            resumedAtMillis = 0L,
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
        val index = existing.indexOfLast { it.recoveryPending }
        if (index < 0) return
        val incident = existing[index]
        if (resumedAtMillis < incident.occurredAtMillis) return
        val updated = existing.toMutableList().apply {
            this[index] = incident.copy(resumedAtMillis = resumedAtMillis)
        }
        writeHistory(file, updated)
    }

    private fun appendIncident(context: Context, incident: RecordingIncident) {
        val file = historyFile(context)
        val existing = readHistory(file)
        if (existing.any { it.kind == incident.kind && it.occurredAtMillis == incident.occurredAtMillis }) return
        val updated = (existing + incident)
            .sortedBy { it.occurredAtMillis }
            .takeLast(MAX_INCIDENTS)
        writeHistory(file, updated)
    }

    private fun packageLastUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    private fun sessionFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, SESSION_FILE_NAME))
    private fun historyFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, HISTORY_FILE_NAME))

    private fun readSession(file: AtomicFile): ActiveRecordingSessionMarker? = runCatching {
        DataInputStream(BufferedInputStream(file.openRead())).use { input ->
            if (input.readInt() != SESSION_MAGIC || input.readUnsignedByte() != SESSION_FORMAT_VERSION) return null
            ActiveRecordingSessionMarker(
                armed = input.readBoolean(),
                pid = input.readInt(),
                processStartElapsedRealtimeMillis = input.readLong(),
                armedAtMillis = input.readLong(),
                armedElapsedRealtimeMillis = input.readLong(),
                packageLastUpdateTimeMillis = input.readLong(),
            )
        }
    }.getOrNull()

    private fun writeSession(file: AtomicFile, marker: ActiveRecordingSessionMarker) {
        writeAtomic(file) { output ->
            output.writeInt(SESSION_MAGIC)
            output.writeByte(SESSION_FORMAT_VERSION)
            output.writeBoolean(marker.armed)
            output.writeInt(marker.pid)
            output.writeLong(marker.processStartElapsedRealtimeMillis)
            output.writeLong(marker.armedAtMillis)
            output.writeLong(marker.armedElapsedRealtimeMillis)
            output.writeLong(marker.packageLastUpdateTimeMillis)
        }
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
