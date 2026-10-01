package app.smallthingz.reverb

import android.content.Context
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** The only kill in this suite targets the isolated instrumentation process, never real Reverb. */
internal fun prepareDurableCaptureForProcessDeath(context: Context): Int {
    check(context.packageName.endsWith(".reliability"))
    val rootName = "reliability-crash-${UUID.randomUUID()}"
    val root = File(context.noBackupFilesDir, rootName)
    val store = PersistentAudioChunkStore(root)
    store.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
    val prefix = ByteArray(32_768) { (it * 23 + 11).toByte() }
    val tail = ByteArray(8_192) { (it * 31 + 17).toByte() }
    check(store.append(prefix, 0, prefix.size) == prefix.size)
    store.checkpoint()
    check(store.append(tail, 0, tail.size) == tail.size)
    check(store.syncActivePayloadToDisk() > 0L)
    // Tail is durable but not in a newer header/index checkpoint, as during background capture.
    FileOutputStream(File(context.filesDir, "reliability-crash-expected.pcm")).use { output ->
        output.write(prefix + tail)
        output.fd.sync()
    }
    val pid = Process.myPid()
    FileOutputStream(File(context.filesDir, "reliability-crash-case.txt")).use { output ->
        output.write("$rootName\n$pid\n".toByteArray())
        output.fd.sync()
    }
    RecordingIncidentStore.recordCaptureStarted(context)
    // Intentionally no store.close(): the next instrumentation process must perform recovery.
    return pid
}

internal fun verifyDurableCaptureAfterProcessDeath(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val details = File(context.filesDir, "reliability-crash-case.txt").readLines()
    val rootName = details[0]
    check(rootName.startsWith("reliability-crash-") && File(rootName).name == rootName)
    val previousPid = details[1].toInt()
    check(previousPid != Process.myPid()) { "Crash recovery did not run in a fresh process" }
    val expected = File(context.filesDir, "reliability-crash-expected.pcm").readBytes()
    check(expected.size == 40_960)
    PersistentAudioChunkStore(File(context.noBackupFilesDir, rootName)).use { recovered ->
        recovered.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
        val bytes = ByteArrayOutputStream()
        requireNotNull(recovered.acquireRange(0.0, recovered.durationSeconds())).use { lease ->
            lease.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { array, offset, count ->
                bytes.write(array, offset, count)
                count
            }
        }
        check(expected.contentEquals(bytes.toByteArray())) { "Process death lost or changed durable PCM" }
    }
    val incidents = RecordingIncidentStore.readIncidents(context)
        .filter { it.kind == RecordingIncidentKind.UNEXPECTED_SHUTDOWN && it.pid == previousPid }
    check(incidents.size == 1) { "Armed process death did not produce exactly one interruption incident: $incidents" }
}
