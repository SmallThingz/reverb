package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual provider/service terminal paths, using only synthetic QA-package recordings. */
internal fun verifyProviderTerminalMatrix(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    run: (String, () -> Unit) -> Unit,
) {
    check(context.packageName.endsWith(".reliability"))
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
        provider.reset(ReliabilityDocumentsProvider.Mode.WRITE_TIMEOUT)
        verifyUnrequestedExportTimeout(context, tree, provider)
        check(provider.writeOpenCalls == 2)
        check(provider.deleteCalls == 0)
    }
}

private fun writeTerminalFixture(context: Context, tree: Uri): Pair<RecordingOutputTarget, StableOutputFingerprint> {
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

private fun verifyUnrequestedExportTimeout(context: Context, tree: Uri, provider: ReliabilityDocumentsProvider) {
    val prefs = getRecorderPreferences(context)
    check(prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false).commit())
    check(setConfiguredExportTreeUri(context, tree))
    val connected = CountDownLatch(1)
    var service: ReverbService? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ReverbService.BackgroundRecorderBinder).service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    check(context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE))
    val root = File(context.filesDir, "terminal-source-${UUID.randomUUID()}")
    val pcm = ByteArray(3200) { (it * 31 + 3).toByte() }
    val store = PersistentAudioChunkStore(root)
    try {
        check(connected.await(15, TimeUnit.SECONDS))
        val recorder = requireNotNull(service)
        val initialized = FutureTask { Unit }
        val audio = ReverbService::class.java.getDeclaredField("audioHandler").apply { isAccessible = true }
            .get(recorder) as Handler
        check(audio.post(initialized))
        initialized.get(15, TimeUnit.SECONDS)
        store.configure(RetentionMode.SIZE, 1_048_576L, 16000, 1, PcmSampleFormat.PCM_16)
        check(store.append(pcm, 0, pcm.size) == pcm.size)
        store.sealActiveChunk()
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        val terminal = AtomicReference<String>()
        val originalError = AtomicReference<Throwable>()
        val delivered = CountDownLatch(1)
        val receiver = object : ReverbService.AudioFileReceiver {
            override fun fileReady(recording: RecordingEntity) { terminal.set("success"); delivered.countDown() }
            override fun fileFailed(message: String, error: Throwable?) {
                terminal.set("failure")
                originalError.set(error)
                delivered.countDown()
            }
            override fun fileCancelled() { terminal.set("cancelled"); delivered.countDown() }
        }
        val begin = ReverbService::class.java.getDeclaredMethod("beginExport", ReverbService.AudioFileReceiver::class.java)
            .apply { isAccessible = true }
        val token = requireNotNull(begin.invoke(recorder, receiver))
        val config = recorder.getConfigurationSnapshot().copy(
            sampleRate = 16000,
            channelMode = ChannelMode.entries.single { it.channelCount == 1 },
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        val export = ReverbService::class.java.declaredMethods.single {
            it.name == "exportBufferedRange" && it.parameterCount == 5
        }.apply { isAccessible = true }
        export.invoke(recorder, lease, receiver, "terminal-timeout", token, config)
        check(delivered.await(15, TimeUnit.SECONDS)) { "Export never delivered its terminal result" }
        // Terminal delivery can precede worker cleanup. Wait for that owned worker before unbinding.
        val executor = ReverbService::class.java.getDeclaredField("exportWorkExecutor").apply { isAccessible = true }
            .get(recorder) as java.util.concurrent.ExecutorService
        executor.submit {}.get(15, TimeUnit.SECONDS)
        check(terminal.get() == "failure") { "Unrequested provider timeout was reported as ${terminal.get()}" }
        check(originalError.get() is java.net.SocketTimeoutException) { "Original timeout failure was not preserved" }
        check(!recorder.cancelCurrentExport()) { "Failed export retained its busy token" }

        // Ordinary recovery must allow a fresh export, not leave the recorder permanently busy.
        provider.mode = ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID
        val retried = CountDownLatch(1)
        val saved = AtomicReference<RecordingEntity>()
        val retryReceiver = object : ReverbService.AudioFileReceiver {
            override fun fileReady(recording: RecordingEntity) { saved.set(recording); retried.countDown() }
            override fun fileFailed(message: String, error: Throwable?) { retried.countDown() }
            override fun fileCancelled() { retried.countDown() }
        }
        val retryToken = requireNotNull(begin.invoke(recorder, retryReceiver))
        val retryLease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        export.invoke(recorder, retryLease, retryReceiver, "terminal-retry", retryToken, config)
        check(retried.await(15, TimeUnit.SECONDS))
        executor.submit {}.get(15, TimeUnit.SECONDS)
        val recording = checkNotNull(saved.get()) { "A fresh export failed after the provider recovered" }
        val savedBytes = requireNotNull(openRecordingInputStream(context, recording)).use { it.readBytes() }
        check(savedBytes.contentEquals(buildWavHeaderBytes(16000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm))

        val cancelled = CountDownLatch(1)
        val cancellationReceiver = object : ReverbService.AudioFileReceiver {
            override fun fileReady(recording: RecordingEntity) = error("Cancelled export succeeded")
            override fun fileFailed(message: String, error: Throwable?) = error("User cancellation reported as failure")
            override fun fileCancelled() { cancelled.countDown() }
        }
        requireNotNull(begin.invoke(recorder, cancellationReceiver))
        check(recorder.cancelCurrentExport())
        check(cancelled.await(15, TimeUnit.SECONDS)) { "User cancellation lost its neutral terminal" }
        check(!recorder.cancelCurrentExport())
        val retained = ByteArrayOutputStream()
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { original ->
            original.readNormalized(16000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                retained.write(bytes, offset, count)
                count
            }
        }
        check(pcm.contentEquals(retained.toByteArray())) { "Failed export changed retained audio" }
    } finally {
        store.close()
        context.unbindService(connection)
        check(setConfiguredExportTreeUri(context, null))
    }
}
