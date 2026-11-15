package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual provider/service terminal paths, using only synthetic QA-package recordings. */
internal fun verifyProviderTerminalMatrix(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    report: StringBuilder,
    run: (String, () -> Unit) -> Unit,
) {
    requireProviderTerminalDurableQuiescence(context)
    for ((name, mode) in listOf(
        "deleted_old_document_id_reports_success" to ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID,
        "committed_delete_transport_failure_reports_success" to ReliabilityDocumentsProvider.Mode.DELETE_THEN_THROW,
        "delete_noop_stays_failure" to ReliabilityDocumentsProvider.Mode.DELETE_NOOP,
        "delete_directory_unavailable_stays_uncertain" to ReliabilityDocumentsProvider.Mode.DELETE_LISTING_UNAVAILABLE,
        "delete_unlisted_source_does_not_invent_absence" to ReliabilityDocumentsProvider.Mode.DELETE_NOT_LISTED,
    )) {
        run(name) {
            provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
            val (staging, verified) = writeTerminalFixture(context, tree)
            val output = finalizeOutputTarget(context, staging, verified)
            val recording = buildRecordingEntity(context, output, 100L, "WAV", verified.digest.byteCount)
            check(readStableOutputFingerprint(context, recording.storageType, recording.id) != null)
            provider.mode = mode
            val deleted = deleteVerifiedRecordingAsset(context, recording)
            val expected = mode == ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID ||
                mode == ReliabilityDocumentsProvider.Mode.DELETE_THEN_THROW
            check(deleted == expected) { "Document deletion terminal=$deleted expected=$expected mode=$mode" }
            check(provider.deleteCalls == 1)
            if (mode == ReliabilityDocumentsProvider.Mode.DELETE_NOOP) check(provider.retainedBytes().size == 1)
            else check(provider.retainedBytes().isEmpty())
        }
    }
    for ((name, mode) in listOf(
        "output_cleanup_retires_successful_document_deletion" to ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID,
        "output_cleanup_loading_is_not_absence" to ReliabilityDocumentsProvider.Mode.DELETE_LOADING,
        "output_cleanup_error_is_not_absence" to ReliabilityDocumentsProvider.Mode.DELETE_ERROR,
    )) {
        run(name) {
            provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
            val (target, verified) = writeTerminalFixture(context, tree)
            val before = provider.retainedBytes().single()
            provider.mode = mode
            val cleaned = suppressAndDeleteOutputTarget(context, target, expectedFingerprint = verified)
            val expected = mode == ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID
            check(cleaned == expected) { "Cleanup terminal=$cleaned expected=$expected mode=$mode" }
            check(provider.deleteCalls == 1)
            check((target.id in pendingOutputCleanupIds(context)) == !expected) {
                "Cleanup journal did not retain exactly the unresolved output"
            }
            if (!expected) {
                check(provider.retainedBytes().single().contentEquals(before))
                provider.mode = ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID
                retryPendingOutputCleanup(context)
                check(target.id !in pendingOutputCleanupIds(context)) { "Cleanup failed to resume after provider recovered" }
                check(provider.retainedBytes().isEmpty())
                check(provider.deleteCalls == 2)
            }
        }
    }
    run("document_delete_rechecks_move_authority_after_directory_io") {
        provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
        val (target, fingerprint) = writeTerminalFixture(context, tree)
        val recording = buildRecordingEntity(context, target, 100L, "WAV", fingerprint.digest.byteCount)
        var destinationStillCurrent = true
        provider.afterNextListing = { destinationStillCurrent = false }
        check(!deleteVerifiedRecordingAsset(context, recording) { destinationStillCurrent })
        check(provider.deleteCalls == 0) { "Source deleted after destination authority was revoked" }
        check(provider.retainedBytes().single().size.toLong() == fingerprint.digest.byteCount)
    }
    run("document_delete_preserves_source_replaced_during_directory_io") {
        provider.reset(ReliabilityDocumentsProvider.Mode.UNKNOWN_MODIFIED)
        val (target, fingerprint) = writeTerminalFixture(context, tree)
        val recording = buildRecordingEntity(context, target, 100L, "WAV", fingerprint.digest.byteCount)
        val originalBytes = provider.retainedBytes().single()
        provider.afterNextListing = {
            provider.replaceDocumentWithSameBytes(android.provider.DocumentsContract.getDocumentId(requireNotNull(target.uri)))
        }
        check(!deleteVerifiedRecordingAsset(context, recording))
        check(provider.deleteCalls == 0) { "Replacement source inherited original deletion authority" }
        check(provider.retainedBytes().single().contentEquals(originalBytes))
    }
    run("provider_timeout_is_failed_save_not_user_cancel") {
        verifyDurableProviderTimeout(context, tree, provider, report)
    }
}

internal fun writeTerminalFixture(context: Context, tree: Uri): Pair<RecordingOutputTarget, StableOutputFingerprint> {
    val target = createOutputTargetInDirectory(
        context, tree, "terminal-${UUID.randomUUID()}.wav", "audio/wav", 1_790_000_000_000L,
        StagingOutputKind.EXPORT_TRACKED,
    )
    val pcm = ByteArray(3200) { (it * 13 + 9).toByte() }
    val writer = WavAudioFileWriter(context, target, 16000, 1)
    writer.use { it.write(pcm, 0, pcm.size) }
    val fingerprint = verifyWavOutputTargetAndDigest(
        context, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
        writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
    )
    return target to fingerprint
}

/** Private legacy-token injection plus real public cancellation; not a queued recompression test. */
internal fun verifyLegacyExportTokenCancellation(recorder: ReverbService) {
    val main = Handler(Looper.getMainLooper())
    fun awaitMainCallbacks() {
        val fence = FutureTask { Unit }
        check(main.post(fence))
        fence.get(15, TimeUnit.SECONDS)
    }
    val cancelled = CountDownLatch(1)
    val terminals = AtomicInteger()
    val cancellationFailure = AtomicReference<Throwable>()
    val cancellationReceiver = object : ReverbService.AudioFileReceiver {
        // Main-thread callbacks record failures; the instrumentation owner reports them below.
        override fun fileReady(recording: RecordingEntity) {
            terminals.incrementAndGet()
            cancellationFailure.compareAndSet(null, IllegalStateException("Cancelled export succeeded"))
            cancelled.countDown()
        }
        override fun fileFailed(message: String, error: Throwable?) {
            terminals.incrementAndGet()
            cancellationFailure.compareAndSet(null,
                IllegalStateException("User cancellation reported as failure: $message", error))
            cancelled.countDown()
        }
        override fun fileCancelled() {
            terminals.incrementAndGet()
            cancelled.countDown()
        }
    }
    val begin = ReverbService::class.java.getDeclaredMethod("beginExport", ReverbService.AudioFileReceiver::class.java)
        .apply { isAccessible = true }
    try { requireNotNull(begin.invoke(recorder, cancellationReceiver)) }
    catch (error: InvocationTargetException) { throw error.targetException }
    check(recorder.cancelCurrentExport())
    check(cancelled.await(15, TimeUnit.SECONDS)) { "User cancellation lost its neutral terminal" }
    try {
        awaitMainCallbacks()
    } catch (fenceFailure: Throwable) {
        cancellationFailure.get()?.let { terminalFailure ->
            if (terminalFailure !== fenceFailure) terminalFailure.addSuppressed(fenceFailure)
            throw terminalFailure
        }
        throw fenceFailure
    }
    cancellationFailure.get()?.let { throw it }
    check(terminals.get() == 1) { "Legacy cancellation delivered multiple terminal callbacks" }
    check(!recorder.cancelCurrentExport())
}
