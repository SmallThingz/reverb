package app.smallthingz.reverb

import android.content.Context
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

/** The real MediaStore case stays IS_PENDING throughout and deletes only its verified synthetic row. */
internal fun measureProviderFingerprintPerformance(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    val tree = DocumentsContract.buildTreeDocumentUri(
        "${context.packageName}.reliability.documents", ReliabilityDocumentsProvider.ROOT_ID,
    )
    context.contentResolver.acquireContentProviderClient(tree).use { client ->
        (client?.localContentProvider as ReliabilityDocumentsProvider).reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
        for (storage in listOf(RecordingStorageType.DOCUMENT, RecordingStorageType.MEDIASTORE)) {
            val target = createOutputTargetInDirectory(
                context, if (storage == RecordingStorageType.DOCUMENT) tree else null,
                "reverb-qa-${UUID.randomUUID()}.wav", "audio/wav", System.currentTimeMillis(),
                StagingOutputKind.EXPORT_TRACKED,
            )
            check(target.storageType == storage && target.staging)
            var verified: StableOutputFingerprint? = null
            try {
                val payload = ByteArray(64 * 1024) { (it * 13 + 7).toByte() }
                val writer = WavAudioFileWriter(context, target, 48000, 1)
                writer.use { repeat(1024) { writer.write(payload, 0, payload.size) } }
                val fingerprint = verifyWavOutputTargetAndDigest(
                    context, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
                    writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
                )
                verified = fingerprint
                requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, target, fingerprint))
                val requireCurrent = Class.forName("app.smallthingz.reverb.RecordingFilesKt").getDeclaredMethod(
                    "requireCurrentOutputFingerprint", Context::class.java,
                    RecordingOutputTarget::class.java, StableOutputFingerprint::class.java,
                ).apply { isAccessible = true }
                val reads = providerReadChars()
                val start = SystemClock.elapsedRealtimeNanos()
                repeat(3) { requireCurrent.invoke(null, context, target, fingerprint) }
                report.append("MEASURE provider storage=$storage bytes=${writer.totalFileBytesWritten}")
                    .append(" repeated_proof_ms=${(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0}")
                    .append(" repeated_proof_read_bytes=${providerReadChars()-reads}\n")
                val fullReads = providerReadChars()
                val observed = requireNotNull(readStableOutputFingerprint(context, storage, target.id, fingerprint.providerIdentity))
                check(stableOutputFingerprintMatches(storage, fingerprint, observed))
                val fullBytes = providerReadChars() - fullReads
                check(fullBytes >= writer.totalFileBytesWritten) { "Final/destructive proof silently used a cache" }
                report.append("CHECK provider storage=$storage mandatory_full_read_bytes=$fullBytes\n")
            } finally {
                val proof = verified
                if (proof != null) {
                    check(suppressAndDeleteOutputTarget(context, target, proof)) { "Owned synthetic pending row cleanup failed: ${target.id}" }
                } else {
                    report.append("UNVERIFIED_QA_STAGING_PRESERVED ").append(target.id).append('\n')
                }
            }
        }
    }
}

private fun providerReadChars(): Long = File("/proc/self/io").readLines()
    .first { it.startsWith("rchar:") }.substringAfter(':').trim().toLong()
