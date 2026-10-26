package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.UUID

/** Actual local ContentResolver transport and production rename; only QA-owned provider files. */
internal fun verifyDocumentRenameNullResponse(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    runCase: (String, () -> Unit) -> Unit,
) {
    check(context.packageName.endsWith(".reliability"))
    runCase("document_contract_returns_null_after_committed_checked_failure") {
        val original = prepareRenameFixture(context, tree, provider)
        val bytes = provider.retainedBytes().single()
        provider.prepareRenameResponse(ReliabilityDocumentsProvider.Mode.RENAME_THEN_IO_FAILURE)
        val returned = DocumentsContract.renameDocument(
            context.contentResolver, Uri.parse(original.id), "contract-null-${UUID.randomUUID()}.wav",
        )
        check(provider.renamed) { "Fixture did not cross the rename boundary" }
        check(returned == null) { "This platform did not exercise DocumentsContract's null-result branch" }
        check(provider.deleteCalls == 0)
        check(provider.retainedBytes().single().contentEquals(bytes))
    }
    runCase("document_null_rename_recovers_verified_final_document") {
        val original = prepareRenameFixture(context, tree, provider)
        val bytes = provider.retainedBytes().single()
        provider.prepareRenameResponse(ReliabilityDocumentsProvider.Mode.RENAME_THEN_IO_FAILURE)
        val requestedName = "recovered-null-${UUID.randomUUID()}.wav"
        val renamed = requireNotNull(renameRecordingAsset(context, original, requestedName))
        check(provider.renamed)
        check(renamed.id != original.id && renamed.displayName == requestedName)
        check(provider.oldIdQueries > 0)
        val actual = requireNotNull(openRecordingInputStream(context, renamed)).use { it.readBytes() }
        check(actual.contentEquals(bytes))
        check(provider.deleteCalls == 0)
        check(provider.retainedBytes().single().contentEquals(bytes))
    }
    runCase("document_null_rename_with_unavailable_listing_remains_uncertain") {
        val original = prepareRenameFixture(context, tree, provider)
        val bytes = provider.retainedBytes().single()
        provider.prepareRenameResponse(ReliabilityDocumentsProvider.Mode.RENAME_THEN_IO_FAILURE_UNAVAILABLE)
        val failure = runCatching {
            renameRecordingAsset(context, original, "uncertain-null-${UUID.randomUUID()}.wav")
        }.exceptionOrNull()
        check(provider.renamed)
        check(failure is RecordingRenameStateUncertainException) {
            "A committed rename without observable result was reported as a definite rejection: $failure"
        }
        check(failure.cause?.message == "Document rename returned no result") {
            "The test did not reach the production null-result classification: $failure"
        }
        check(provider.deleteCalls == 0)
        check(provider.retainedBytes().single().contentEquals(bytes))
    }
}

private fun prepareRenameFixture(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
): RecordingEntity {
    provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
    val pcm = ByteArray(3200) { (it * 13 + 9).toByte() }
    val target = createOutputTargetInDirectory(
        context, tree, "rename-source-${UUID.randomUUID()}.wav", "audio/wav", 1_790_000_000_000L,
        StagingOutputKind.EXPORT_TRACKED,
    )
    val writer = WavAudioFileWriter(context, target, 16_000, 1)
    writer.use { it.write(pcm, 0, pcm.size) }
    val fingerprint = verifyWavOutputTargetAndDigest(
        context, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
        writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
    )
    requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, target, fingerprint))
    val published = finalizeOutputTarget(context, target, fingerprint)
    check(removeVerifiedExportStaging(context, target.storageType, target.id, fingerprint))
    return buildRecordingEntity(context, published, 100L, "WAV", fingerprint.digest.byteCount)
}
