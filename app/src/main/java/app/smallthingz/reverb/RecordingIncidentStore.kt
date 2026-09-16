package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

internal enum class RecordingIncidentKind(val storageCode: Byte) {
    UNEXPECTED_SHUTDOWN(1),
    ;

    companion object {
        fun fromStorageCode(code: Byte): RecordingIncidentKind? = entries.firstOrNull { it.storageCode == code }
    }
}

internal data class RecordingIncident(
    val occurredAtMillis: Long,
    val kind: RecordingIncidentKind = RecordingIncidentKind.UNEXPECTED_SHUTDOWN,
)

private data class ActiveRecordingSessionMarker(
    val armed: Boolean,
    val pid: Int,
    val processStartElapsedRealtimeMillis: Long,
    val armedAtMillis: Long,
    val armedElapsedRealtimeMillis: Long,
    val packageLastUpdateTimeMillis: Long,
)

internal fun isSpuriousRecordingProcessExitReason(reason: Int): Boolean = when (reason) {
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    ApplicationExitInfo.REASON_FREEZER,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_OTHER,
    ApplicationExitInfo.REASON_SIGNALED,
    -> true

    ApplicationExitInfo.REASON_EXIT_SELF,
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
    private const val HISTORY_FORMAT_VERSION = 1
    private const val MAX_INCIDENTS = 128
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
        val incidentAt = when {
            sameProcess -> null
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> null
            else -> historicalSpuriousExitTimestamp(appContext, marker)
        }

        if (incidentAt != null) {
            appendIncident(
                appContext,
                RecordingIncident(incidentAt, RecordingIncidentKind.UNEXPECTED_SHUTDOWN),
            )
        }
        if (!sameProcess) markerFile.delete()
    }

    @Synchronized
    fun markCaptureRunning(context: Context) {
        val appContext = context.applicationContext
        writeSession(
            sessionFile(appContext),
            ActiveRecordingSessionMarker(
                armed = true,
                pid = Process.myPid(),
                processStartElapsedRealtimeMillis = Process.getStartElapsedRealtime(),
                armedAtMillis = System.currentTimeMillis(),
                armedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                packageLastUpdateTimeMillis = packageLastUpdateTime(appContext),
            ),
        )
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
    internal fun clearForTests(context: Context) {
        sessionFile(context.applicationContext).delete()
        historyFile(context.applicationContext).delete()
    }

    private fun historicalSpuriousExitTimestamp(
        context: Context,
        marker: ActiveRecordingSessionMarker,
    ): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, marker.pid, 8)
                .firstOrNull { info ->
                    info.pid == marker.pid && info.timestamp >= marker.armedAtMillis
                }
        }.getOrNull() ?: return null
        return exit.timestamp.takeIf { isSpuriousRecordingProcessExitReason(exit.reason) }
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
            if (input.readInt() != HISTORY_MAGIC || input.readUnsignedByte() != HISTORY_FORMAT_VERSION) return emptyList()
            val count = input.readUnsignedShort().coerceAtMost(MAX_INCIDENTS)
            buildList(count) {
                repeat(count) {
                    val kindCode = input.readByte()
                    val timestamp = input.readLong()
                    val kind = RecordingIncidentKind.fromStorageCode(kindCode)
                    if (kind != null && timestamp > 0L) add(RecordingIncident(timestamp, kind))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun writeHistory(file: AtomicFile, incidents: List<RecordingIncident>) {
        writeAtomic(file) { output ->
            output.writeInt(HISTORY_MAGIC)
            output.writeByte(HISTORY_FORMAT_VERSION)
            output.writeShort(incidents.size)
            incidents.forEach { incident ->
                output.writeByte(incident.kind.storageCode.toInt())
                output.writeLong(incident.occurredAtMillis)
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
