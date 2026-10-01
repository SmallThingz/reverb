package app.smallthingz.reverb

import android.content.Context
import android.os.Process
import android.net.Uri
import android.provider.DocumentsContract
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
    prepareNativeExportBeforeProcessDeath(context)
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
    verifyNativeExportAfterProcessDeath(context)
}

private fun nativeExportTree(context: Context): Uri = DocumentsContract.buildTreeDocumentUri(
    "${context.packageName}.reliability.documents", ReliabilityDocumentsProvider.ROOT_ID,
)

private fun prepareNativeExportBeforeProcessDeath(context: Context) {
    val tree = nativeExportTree(context)
    context.contentResolver.acquireContentProviderClient(tree).use { client ->
        val provider = requireNotNull(client?.localContentProvider as? ReliabilityDocumentsProvider)
        provider.reset(ReliabilityDocumentsProvider.Mode.UNKNOWN_MODIFIED)
        val target = createOutputTargetInDirectory(
            context, tree, "crash-native-${UUID.randomUUID()}.wav", "audio/wav", System.currentTimeMillis(),
            StagingOutputKind.EXPORT_TRACKED,
        )
        val pcm = ByteArray(3200) { (it * 19 + 3).toByte() }
        val writer = WavAudioFileWriter(context, target, 16000, 1)
        writer.use { it.write(pcm, 0, pcm.size) }
        val fingerprint = verifyWavOutputTargetAndDigest(
            context, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
            writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
        )
        check(parseDocumentNativeIdentity(fingerprint.providerIdentity.orEmpty()) != null)
        requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, target, fingerprint))
        FileOutputStream(File(context.filesDir, "reliability-native-export.txt")).use {
            it.write("${target.id}\n${target.displayName}\n".toByteArray())
            it.fd.sync()
        }
        // Leave verified staging unpublished. Its durable native identity must survive restart,
        // even though the restarted provider will expose the formerly unknown metadata.
    }
}

private fun verifyNativeExportAfterProcessDeath(context: Context) {
    val details = File(context.filesDir, "reliability-native-export.txt").readLines()
    val tree = nativeExportTree(context)
    context.contentResolver.acquireContentProviderClient(tree).use { client ->
        val provider = requireNotNull(client?.localContentProvider as? ReliabilityDocumentsProvider)
        provider.restoreExistingDocument(DocumentsContract.getDocumentId(Uri.parse(details[0])))
        check(provider.mode == ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
        val saved = listOutputDirectoryRecordings(context, tree).single { it.displayName == details[1] }
        val pcm = ByteArray(3200) { (it * 19 + 3).toByte() }
        val expected = buildWavHeaderBytes(16000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
        val recovered = requireNotNull(openRecordingInputStream(context, saved)).use { it.readBytes() }
        check(recovered.contentEquals(expected)) { "Restart lost or changed the verified native-identity export" }
        check(provider.deleteCalls == 0)
    }
}
