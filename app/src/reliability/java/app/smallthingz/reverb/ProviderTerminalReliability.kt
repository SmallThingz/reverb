package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutorService
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
    val connected = CountDownLatch(1)
    var service: ReverbService? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ReverbService.BackgroundRecorderBinder).service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    val root = File(context.filesDir, "terminal-source-${UUID.randomUUID()}")
    val pcm = ByteArray(3200) { (it * 31 + 3).toByte() }
    var bound = false
    var treeConfigured = false
    var store: PersistentAudioChunkStore? = null
    var firstLease: PersistentAudioChunkStore.RangeLease? = null
    var retryLease: PersistentAudioChunkStore.RangeLease? = null
    var executor: ExecutorService? = null
    var exportStarted = false
    var primaryFailure: Throwable? = null
    val main = Handler(Looper.getMainLooper())
    fun awaitMainCallbacks() {
        val fence = FutureTask { Unit }
        check(main.post(fence))
        fence.get(15, TimeUnit.SECONDS)
    }
    try {
        treeConfigured = setConfiguredExportTreeUri(context, tree)
        check(treeConfigured)
        bound = context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound)
        check(connected.await(15, TimeUnit.SECONDS))
        val recorder = requireNotNull(service)
        val worker = ReverbService::class.java.getDeclaredField("exportWorkExecutor").apply { isAccessible = true }
            .get(recorder) as ExecutorService
        executor = worker
        val export = ProviderTerminalExportAdapter(recorder)
        val source = PersistentAudioChunkStore(root)
        store = source
        val initialized = FutureTask { Unit }
        val audio = ReverbService::class.java.getDeclaredField("audioHandler").apply { isAccessible = true }
            .get(recorder) as Handler
        check(audio.post(initialized))
        initialized.get(15, TimeUnit.SECONDS)
        source.configure(RetentionMode.SIZE, 1_048_576L, 16000, 1, PcmSampleFormat.PCM_16)
        check(source.append(pcm, 0, pcm.size) == pcm.size)
        source.sealActiveChunk()
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
        val token = requireNotNull(export.begin(receiver))
        exportStarted = true
        val config = recorder.getConfigurationSnapshot().copy(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleRate = 16000,
            channelMode = ChannelMode.entries.single { it.channelCount == 1 },
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        val lease = requireNotNull(source.acquireRange(0.0, source.durationSeconds())).also { firstLease = it }
        export.submit(lease, receiver, "terminal-timeout", token, config)
        check(delivered.await(15, TimeUnit.SECONDS)) { "Export never delivered its terminal result" }
        // Terminal delivery can precede worker cleanup. Wait for that owned worker before unbinding.
        worker.submit {}.get(15, TimeUnit.SECONDS)
        awaitMainCallbacks()
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
        val retryToken = requireNotNull(export.begin(retryReceiver))
        val retry = requireNotNull(source.acquireRange(0.0, source.durationSeconds())).also { retryLease = it }
        export.submit(retry, retryReceiver, "terminal-retry", retryToken, config)
        check(retried.await(15, TimeUnit.SECONDS))
        worker.submit {}.get(15, TimeUnit.SECONDS)
        awaitMainCallbacks()
        val recording = checkNotNull(saved.get()) { "A fresh export failed after the provider recovered" }
        val savedBytes = requireNotNull(openRecordingInputStream(context, recording)).use { it.readBytes() }
        check(savedBytes.contentEquals(buildWavHeaderBytes(16000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm))

        val cancelled = CountDownLatch(1)
        val cancellationReceiver = object : ReverbService.AudioFileReceiver {
            override fun fileReady(recording: RecordingEntity) = error("Cancelled export succeeded")
            override fun fileFailed(message: String, error: Throwable?) = error("User cancellation reported as failure")
            override fun fileCancelled() { cancelled.countDown() }
        }
        requireNotNull(export.begin(cancellationReceiver))
        check(recorder.cancelCurrentExport())
        check(cancelled.await(15, TimeUnit.SECONDS)) { "User cancellation lost its neutral terminal" }
        check(!recorder.cancelCurrentExport())
        val retained = ByteArrayOutputStream()
        requireNotNull(source.acquireRange(0.0, source.durationSeconds())).use { original ->
            original.readNormalized(16000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                retained.write(bytes, offset, count)
                count
            }
        }
        check(pcm.contentEquals(retained.toByteArray())) { "Failed export changed retained audio" }
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error
                else if (cleanupFailure !== error) cleanupFailure!!.addSuppressed(error)
            }
        }
        // This fixture exclusively owns the isolated QA export. Keep its store, leases and tree
        // intact if cancellation/draining cannot establish that the worker has released them.
        if (primaryFailure != null && exportStarted) cleanup { service?.cancelCurrentExport() }
        var drained = false
        cleanup {
            executor?.submit {}?.get(15, TimeUnit.SECONDS)
            awaitMainCallbacks()
            drained = true
        }
        if (drained) {
            cleanup { val closing = firstLease; firstLease = null; closing?.close() }
            cleanup { val closing = retryLease; retryLease = null; closing?.close() }
            cleanup { val closing = store; store = null; closing?.close() }
        } else cleanup { error("QA export worker/callbacks did not drain; synthetic store, leases and tree retained") }
        if (bound) cleanup { bound = false; context.unbindService(connection) }
        if (drained && treeConfigured) cleanup { check(setConfiguredExportTreeUri(context, null)) }
        cleanupFailure?.let { error ->
            if (primaryFailure != null) {
                if (primaryFailure !== error) primaryFailure!!.addSuppressed(error)
            } else throw error
        }
    }
}
