package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.util.UUID

/** Real provider + production recovery, restricted to the synthetic reliability package. */
internal fun verifyStagedRecoverySuppression(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    run: (String, () -> Unit) -> Unit,
) {
    check(context.packageName.endsWith(".reliability"))
    val copy = ReliabilityDocumentsProvider.Mode.COPY_INSTEAD_OF_RENAME
    val rename = ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID
    val cases = listOf(
        "recovery_failed_copy_then_success_stays_suppressed" to listOf(copy, rename),
        "recovery_success_then_failed_copy_stays_suppressed" to listOf(rename, copy),
        "recovery_all_successful_outputs_remain_visible" to listOf(rename, rename),
        "recovery_only_failed_copy_remains_suppressed" to listOf(copy),
    )
    for ((name, modes) in cases) {
        var completed = false
        run(name) {
            val preferences = getRecorderPreferences(context)
            val cleanupBefore = preferences.snapshotDurablePreferenceValue(PrefKey.PENDING_OUTPUT_CLEANUP)
            val recoveryBefore = preferences.snapshotDurablePreferenceValue(PrefKey.VERIFIED_EXPORT_STAGING)
            check(durablePreferenceSnapshotIsExactlyRestorable(cleanupBefore))
            check(durablePreferenceSnapshotIsExactlyRestorable(recoveryBefore))
            var failure: Throwable? = null
            try {
                provider.reset(rename)
                val fixtures = modes.indices.map { index ->
                    writeRecoverySuppressionFixture(context, tree, provider, index)
                }
                val initialSuppression = pendingOutputCleanupIds(context)
                check(fixtures.none { it.target.id in initialSuppression || it.publishedId in initialSuppression })
                provider.mode = modes.first()
                var switched = false
                if (modes.size > 1 && modes[0] != modes[1]) {
                    fun switchAfterFirstRename() {
                        if (provider.renamed) {
                            provider.mode = modes[1]
                            switched = true
                        } else {
                            provider.afterNextListing = ::switchAfterFirstRename
                        }
                    }
                    provider.afterNextListing = ::switchAfterFirstRename
                }

                // This call must execute recovery itself; invoking finalizeOutputTarget beforehand
                // would miss the stale snapshot taken by the real listing entry point.
                val listed = listOutputDirectoryRecordings(context, tree)
                check(provider.renamed) { "Recovery did not reach the actual rename boundary" }
                if (modes.size > 1 && modes[0] != modes[1]) check(switched)
                val expectedVisible = fixtures.indices.filter { modes[it] == rename }
                    .mapTo(mutableSetOf()) { fixtures[it].publishedId }
                val observedVisible = listed.mapTo(mutableSetOf()) { it.id }
                check(observedVisible == expectedVisible && listed.size == expectedVisible.size) {
                    "Recovery admitted the wrong outputs: expected=$expectedVisible observed=$observedVisible"
                }
                val suppressedAfter = pendingOutputCleanupIds(context)
                fixtures.forEachIndexed { index, fixture ->
                    if (modes[index] == copy) {
                        check(fixture.target.id in suppressedAfter && fixture.publishedId in suppressedAfter) {
                            "Rejected copy/source did not retain durable suppression"
                        }
                    } else {
                        check(fixture.publishedId !in suppressedAfter)
                    }
                }
                check(provider.deleteCalls == 0) { "Recovery deleted an uncertain provider object" }
                val retained = provider.retainedBytes()
                check(retained.size == modes.sumOf { if (it == copy) 2 else 1 })
                fixtures.forEachIndexed { index, fixture ->
                    val expectedCopies = if (modes[index] == copy) 2 else 1
                    check(retained.count { it.contentEquals(fixture.bytes) } == expectedCopies) {
                        "Recovery changed or lost fixture bytes at index $index"
                    }
                }
                // A second listing must neither re-publish suppressed staging nor admit its copy.
                val listedAgain = listOutputDirectoryRecordings(context, tree)
                check(listedAgain.mapTo(mutableSetOf()) { it.id } == expectedVisible)
                check(listedAgain.size == expectedVisible.size)
                check(provider.retainedBytes().size == retained.size)
                check(provider.deleteCalls == 0)
            } catch (error: Throwable) {
                failure = error
            } finally {
                // A failed assertion owns evidence: preserve its provider bytes and journals.
                // Disable only our callback, and never let a later case reset that evidence.
                provider.afterNextListing = null
                if (failure == null) {
                    failure = captureNativeParityCleanup(null) {
                        check(preferences.edit()
                            .restoreDurablePreferenceValue(PrefKey.PENDING_OUTPUT_CLEANUP, cleanupBefore)
                            .restoreDurablePreferenceValue(PrefKey.VERIFIED_EXPORT_STAGING, recoveryBefore)
                            .commit()) { "Unable to restore fixture journal preferences" }
                    }
                    if (failure == null) {
                        failure = captureNativeParityCleanup(null) { provider.reset(rename) }
                    }
                }
            }
            failure?.let { throw it }
            completed = true
        }
        if (!completed) return
    }
}

private data class RecoverySuppressionFixture(
    val target: RecordingOutputTarget,
    val publishedId: String,
    val bytes: ByteArray,
)

private fun writeRecoverySuppressionFixture(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    index: Int,
): RecoverySuppressionFixture {
    val finalName = "recovery-suppression-${UUID.randomUUID()}.wav"
    val stagingName = stagingOutputName(
        finalName, UUID.randomUUID().toString(), sessionId = "previous-reliability-session",
        kind = StagingOutputKind.EXPORT_TRACKED,
    )
    check(shouldRecoverStagingOutput(resolveStagingOutputRecovery(stagingName) { null }?.metadata, verifiedTrackedExport = true))
    val documentId = provider.createDocument(ReliabilityDocumentsProvider.ROOT_ID, "audio/wav", stagingName)
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    val target = RecordingOutputTarget(
        id = uri.toString(), displayName = finalName, mimeType = "audio/wav",
        storageType = RecordingStorageType.DOCUMENT, directoryId = tree.toString(),
        startedAtMillis = 1_790_000_000_000L, uri = uri, staging = true,
        stagingDisplayName = stagingName,
    )
    val pcm = ByteArray(3_200) { (it * 13 + index * 31 + 9).toByte() }
    val bytes = buildWavHeaderBytes(16_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
    val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "w"))
    val stream = try {
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    } catch (error: Throwable) {
        throw requireNotNull(closePreservingPrimaryFailure(error) { descriptor.close() })
    }
    stream.use { output ->
        output.write(bytes)
        output.flush()
        output.fd.sync()
    }
    val fingerprint = requireNotNull(readStableOutputFingerprint(context, target.storageType, target.id))
    requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, target, fingerprint), target.id)
    val published = DocumentsContract.buildDocumentUriUsingTree(
        tree, "${ReliabilityDocumentsProvider.ROOT_ID}/$finalName",
    )
    return RecoverySuppressionFixture(target, published.toString(), bytes)
}
