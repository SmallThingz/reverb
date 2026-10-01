package app.smallthingz.reverb

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Same APIs on baseline and candidate. All bytes are synthetic and app-private. */
internal fun measureSavedFilePerformance(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    val directory = getSavedRecordingsDirectory(context)
    check(directory.mkdirs() || directory.isDirectory)
    repeat(3) { iteration ->
        val displayName = "performance-${UUID.randomUUID()}.wav"
        val name = stagingOutputName(displayName, UUID.randomUUID().toString(), kind = StagingOutputKind.EXPORT_TRACKED)
        val file = File(directory, name)
        check(file.createNewFile())
        val target = RecordingOutputTarget(file.absolutePath, displayName, "audio/wav", RecordingStorageType.FILE,
            directory.absolutePath, 1_790_000_000_000L, file = file, staging = true,
            stagingDisplayName = name, stagingIdentity = resolveFileIdentity(file))
        val payload = ByteArray(64 * 1024) { (it * 23 + 7).toByte() }
        val writer = WavAudioFileWriter(context, target, 48_000, 1)
        writer.use { repeat(1024) { writer.write(payload, 0, payload.size) } }
        val verifyStart = SystemClock.elapsedRealtimeNanos()
        val verified = verifyWavOutputTargetAndDigest(context, target, writer.totalFileBytesWritten,
            writer.expectedHeaderBytes, writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256)
        val verifyEnd = SystemClock.elapsedRealtimeNanos()
        requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, target, verified))
        val publishReads = currentProcessReadChars()
        val publishStart = SystemClock.elapsedRealtimeNanos()
        val output = finalizeOutputTarget(context, target, verified)
        val publishEnd = SystemClock.elapsedRealtimeNanos()
        val publishBytesRead = currentProcessReadChars() - publishReads
        check(output.file?.parentFile == directory && !file.exists())
        check(sameFileObjectAcrossRename(verified.fileKey!!, output.publishedIdentity)) {
            "Publication copied or replaced the original staged file"
        }
        check(removeVerifiedExportStaging(context, target.storageType, target.id, verified))
        val recording = buildRecordingEntity(context, output, writer.totalSampleBytesWritten * 1000L / 96000L,
            "PCM", writer.totalFileBytesWritten)
        val scanReads = currentProcessReadChars()
        val scanStart = SystemClock.elapsedRealtimeNanos()
        val listed = listLegacyAppStorageRecordings(context).single { it.id == recording.id }
        val scanEnd = SystemClock.elapsedRealtimeNanos()
        val scanBytesRead = currentProcessReadChars() - scanReads
        check(listed.sizeBytes == recording.sizeBytes && listed.durationMillis == recording.durationMillis)
        val repeatReads = currentProcessReadChars()
        val repeatStart = SystemClock.elapsedRealtimeNanos()
        repeat(3) { check(copyDigestMatches(verified.digest, requireNotNull(sha256StableRecording(context, recording)))) }
        val repeatEnd = SystemClock.elapsedRealtimeNanos()
        val repeatBytesRead = currentProcessReadChars() - repeatReads
        report.append("MEASURE file iteration=$iteration bytes=${writer.totalFileBytesWritten}")
            .append(" verify_ms=${(verifyEnd-verifyStart)/1_000_000.0}")
            .append(" publish_ms=${(publishEnd-publishStart)/1_000_000.0} publish_read_bytes=$publishBytesRead")
            .append(" scan_ms=${(scanEnd-scanStart)/1_000_000.0} scan_read_bytes=$scanBytesRead")
            .append(" repeated_proof_ms=${(repeatEnd-repeatStart)/1_000_000.0} repeated_proof_read_bytes=$repeatBytesRead\n")
        val published = requireNotNull(output.file)
        RandomAccessFile(published, "rw").use { changed ->
            changed.seek(writer.payloadOffsetBytes); changed.write(17); changed.fd.sync()
        }
        check(sha256StableRecording(context, recording) == null) { "Stale digest survived a same-size content mutation" }
        check(published.delete()) // Only this exact synthetic fixture.
    }
}

private fun currentProcessReadChars(): Long = File("/proc/self/io").readLines()
    .first { it.startsWith("rchar:") }.substringAfter(':').trim().toLong()
