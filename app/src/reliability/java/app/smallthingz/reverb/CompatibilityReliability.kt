package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Normal platform behavior, not only adversarial providers. All bytes belong to the QA package. */
internal fun verifyCompatibilityMatrix(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    run: (String, () -> Unit) -> Unit,
) {
    check(context.packageName.endsWith(".reliability"))
    run("directory_identity_survives_children_but_not_replacement") {
        val root = File(context.filesDir, "directory-compat-${UUID.randomUUID()}")
        check(root.mkdir())
        val before = resolveDirectoryIdentity(root)
        check(before.isNotBlank())
        Thread.sleep(20)
        File(root, "child").writeText("owned sibling")
        check(fileIdentityMatches(before, resolveDirectoryIdentity(root))) {
            "Adding an ordinary child falsely invalidates directory identity"
        }
        val moved = File(root.parentFile, root.name + "-moved")
        check(root.renameTo(moved))
        check(root.mkdir())
        check(!fileIdentityMatches(before, resolveDirectoryIdentity(root))) {
            "Replacement directory inherited old directory authority"
        }
    }
    run("database_preservation_with_sidecars") {
        val root = File(context.filesDir, "database-compat-${UUID.randomUUID()}")
        check(root.mkdir())
        val database = File(root, "catalog.db")
        val sources = listOf(database, File(root, "catalog.db-wal"), File(root, "catalog.db-shm"))
        val bytes = sources.mapIndexed { index, file ->
            ByteArray(1024 + index * 16) { (it * 29 + index).toByte() }.also(file::writeBytes)
        }
        val preserved = preserveCorruptRecordingDatabase(database, File(root, "recovery"))
        check(preserved != null) { "Ordinary database preservation rejected its own directory changes" }
        sources.forEachIndexed { index, source ->
            check(source.readBytes().contentEquals(bytes[index])) { "Preservation mutated original bytes" }
            check(File(preserved, source.name).readBytes().contentEquals(bytes[index])) { "Preserved bytes differ" }
        }
    }
    for ((name, mode) in listOf(
        "saf_unknown_size" to ReliabilityDocumentsProvider.Mode.UNKNOWN_SIZE,
        "saf_unknown_modified" to ReliabilityDocumentsProvider.Mode.UNKNOWN_MODIFIED,
        "saf_zero_modified" to ReliabilityDocumentsProvider.Mode.ZERO_MODIFIED,
        "saf_metadata_appears_after_write" to ReliabilityDocumentsProvider.Mode.METADATA_APPEARS_AFTER_WRITE,
    )) {
        run(name) {
            provider.reset(mode)
            verifyOrdinaryDocumentExport(context, tree)
            check(provider.deleteCalls == 0)
        }
    }
    run("saf_metadata_hydration_preserves_known_recording_identity") {
        provider.reset(ReliabilityDocumentsProvider.Mode.METADATA_APPEARS_AFTER_WRITE)
        val saved = verifyOrdinaryDocumentExport(context, tree)
        check(parseDocumentNativeIdentity(saved.fileIdentity) != null)
        val refreshed = listOutputDirectoryRecordings(context, tree, mapOf(saved.id to saved))
            .single { it.id == saved.id }
        check(providerRecordingIdentityMatches(saved.fileIdentity, refreshed.fileIdentity)) {
            "Optional metadata appearing changed the identity of an unchanged saved recording"
        }
        check(refreshed === saved) { "Refresh unnecessarily rebuilt unchanged recording metadata" }
        provider.replaceDocumentWithSameBytes(DocumentsContract.getDocumentId(Uri.parse(saved.id)))
        val replaced = listOutputDirectoryRecordings(context, tree, mapOf(saved.id to saved))
            .single { it.id == saved.id }
        check(!providerRecordingIdentityMatches(saved.fileIdentity, replaced.fileIdentity)) {
            "Preserving a native identity concealed an actual replacement recording"
        }
        check(replaced !== saved && replaced.fileIdentity.isNotBlank())
    }
    for ((name, mode) in listOf(
        "saf_unknown_size_nonempty_is_not_overwritten" to ReliabilityDocumentsProvider.Mode.UNKNOWN_SIZE_NONEMPTY,
        "saf_unknown_identity_pipe_is_not_writable_authority" to ReliabilityDocumentsProvider.Mode.UNKNOWN_METADATA_PIPE,
    )) {
        run(name) {
            provider.reset(mode)
            val failure = runCatching { verifyOrdinaryDocumentExport(context, tree) }
            check(failure.isFailure)
            check(provider.writeOpenCalls == 0) { "Unverified object was opened for writing" }
            check(provider.deleteCalls == 0)
            if (mode == ReliabilityDocumentsProvider.Mode.UNKNOWN_SIZE_NONEMPTY) {
                check(provider.retainedBytes().single().contentEquals("Existing nonempty fixture bytes".toByteArray()))
            }
        }
    }
    run("saf_equivalent_tree_encoding") {
        provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
        val equivalent = Uri.parse(tree.toString().replace("/tree/root", "/tree/%72oot"))
        check(equivalent != tree)
        verifyOrdinaryDocumentExport(context, equivalent)
    }
    run("recorder_snapshot_is_coherent_across_settings_change") {
        verifyRecorderSnapshotHandoff(context, changeCommandGeneration = false)
    }
    run("recorder_snapshot_cannot_replay_before_newer_command") {
        verifyRecorderSnapshotHandoff(context, changeCommandGeneration = true)
    }
    run("recorder_snapshot_cannot_clear_new_export_busy_state") {
        verifyRecorderSnapshotHandoff(context, changeCommandGeneration = false, changeExport = true)
    }
    run("recorder_snapshot_cannot_resurrect_finished_export") {
        verifyRecorderSnapshotHandoff(context, changeCommandGeneration = false, changeExport = true, exportFinishes = true)
    }
    run("native_provider_identity_binds_actual_read_descriptor") {
        provider.reset(ReliabilityDocumentsProvider.Mode.UNKNOWN_MODIFIED)
        val saved = verifyOrdinaryDocumentExport(context, tree)
        // One identity probe then the actual payload open: only that payload gets a wrong inode.
        provider.substituteReadAfter(2)
        check(openRecordingInputStream(context, saved) == null) {
            "Read accepted a substituted descriptor while URI queries still described the original"
        }
        provider.substituteReadAfter(2)
        check(readStableOutputFingerprint(context, saved.storageType, saved.id, saved.fileIdentity) == null) {
            "Verification accepted a substituted same-byte descriptor"
        }
        val before = requireNotNull(openRecordingInputStream(context, saved)).use { it.readBytes() }
        val shared = buildRecordingUri(context, saved)
        provider.replaceDocumentWithSameBytes(DocumentsContract.getDocumentId(Uri.parse(saved.id)))
        check(!recordingContentIdentityMatches(context, saved)) { "Same-byte replacement inherited native identity" }
        check(openRecordingInputStream(context, saved) == null)
        check(context.contentResolver.getType(shared) == null)
        check(runCatching { context.contentResolver.openInputStream(shared)?.use { it.readBytes() } }.isFailure)
        check(provider.retainedBytes().single().contentEquals(before))
    }
}

private fun verifyOrdinaryDocumentExport(context: Context, tree: Uri): RecordingEntity {
    val source = ByteArray(3200) { (it * 17 + 5).toByte() }
    val target = createOutputTargetInDirectory(
        context, tree, "compatibility-${UUID.randomUUID()}.wav", "audio/wav", 1_790_000_000_000L,
        StagingOutputKind.EXPORT_TRACKED,
    )
    val writer = WavAudioFileWriter(context, target, 16000, 1)
    writer.use { it.write(source, 0, source.size) }
    val verified = verifyWavOutputTargetAndDigest(
        context, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
        writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
    )
    requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, target, verified))
    val output = finalizeOutputTarget(context, target, verified)
    val bytes = context.contentResolver.openInputStream(requireNotNull(output.uri))!!.use { it.readBytes() }
    check(bytes.contentEquals(writer.expectedHeaderBytes + source))
    check(removeVerifiedExportStaging(context, target.storageType, target.id, verified))
    val saved = buildRecordingEntity(context, output, 100L, "WAV", writer.totalFileBytesWritten)
    check(recordingContentIdentityMatches(context, saved))
    val observation = recordingCatalogPostconditionObservation(context, saved)
    check(observation.identityMatches && observation.displayNameMatches)
    val directBytes = requireNotNull(openRecordingInputStream(context, saved)).use { it.readBytes() }
    check(directBytes.contentEquals(bytes))
    val shared = buildRecordingUri(context, saved)
    val sharedBytes = context.contentResolver.openInputStream(shared)!!.use { it.readBytes() }
    check(sharedBytes.contentEquals(bytes))
    withRecordingWavChannel(context, saved) { channel ->
        check(readWavPcmLayout(channel).dataBytes == source.size.toLong())
    }
    RecordingPcm16MonoReader.open(context, saved).use { reader ->
        check(reader.layout.sampleRate == 16000)
    }
    val listed = listOutputDirectoryRecordings(context, tree).single { it.id == saved.id }
    check(listed.sizeBytes == writer.totalFileBytesWritten && listed.fileIdentity.isNotBlank())
    return saved
}

private fun verifyRecorderSnapshotHandoff(
    context: Context,
    changeCommandGeneration: Boolean,
    changeExport: Boolean = false,
    exportFinishes: Boolean = false,
) {
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
    check(context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE))
    val mainRelease = CountDownLatch(1)
    try {
        check(connected.await(15, TimeUnit.SECONDS))
        val recorder = requireNotNull(service)
        val audio = recorder.compatField("audioHandler") as Handler
        val generation = recorder.compatField("listeningCommandGeneration") as AtomicLong
        val initialize = FutureTask {
            recorder.compatSet("durableCaptureIntentAuthorityValid", true)
            recorder.compatSet("durableListeningIntentEnabled", false)
            recorder.compatSet("state", ReverbService.STATE_READY)
            recorder.compatSet("oneShotBufferEnabled", true)
            recorder.compatSet("loopingBufferEnabled", false)
            recorder.compatSet("configuredRetentionMode", RetentionMode.TIME)
            if (exportFinishes) {
                synchronized(requireNotNull(recorder.compatField("exportStateLock"))) {
                    recorder.compatSet("activeExportToken", fixtureExportToken())
                }
            }
        }
        check(audio.post(initialize))
        initialize.get(15, TimeUnit.SECONDS)
        val mainBlocked = CountDownLatch(1)
        check(Handler(Looper.getMainLooper()).post {
            mainBlocked.countDown()
            check(mainRelease.await(10, TimeUnit.SECONDS))
        })
        check(mainBlocked.await(5, TimeUnit.SECONDS))
        val delivered = CountDownLatch(1)
        var observedMode: RetentionMode? = null
        var observedOneShot = false
        var observedGeneration = Long.MIN_VALUE
        var observedExport = false
        recorder.getState(object : ReverbService.StateCallback {
            override fun state(
                commandGeneration: Long, listeningEnabled: Boolean, activeBufferSlot: ReverbService.BufferSlot?,
                oneShotSeconds: Float, oneShotBytes: Long, loopingSeconds: Float, loopingBytes: Long,
                oneShotIsEnabled: Boolean, oneShotIsFull: Boolean, loopingIsEnabled: Boolean,
                retentionMode: RetentionMode?, exporting: Boolean,
            ) {
                observedMode = retentionMode
                observedOneShot = oneShotIsEnabled
                observedGeneration = commandGeneration
                observedExport = exporting
                delivered.countDown()
            }
        })
        val mutate = FutureTask {
            recorder.compatSet("oneShotBufferEnabled", false)
            recorder.compatSet("loopingBufferEnabled", true)
            recorder.compatSet("configuredRetentionMode", RetentionMode.SIZE)
            if (changeCommandGeneration) generation.incrementAndGet()
            if (changeExport) {
                synchronized(requireNotNull(recorder.compatField("exportStateLock"))) {
                    recorder.compatSet("activeExportToken", if (exportFinishes) null else fixtureExportToken())
                }
            }
        }
        check(audio.post(mutate))
        mutate.get(5, TimeUnit.SECONDS)
        mainRelease.countDown()
        check(delivered.await(10, TimeUnit.SECONDS))
        if (changeExport) {
            check(observedExport == !exportFinishes) { "Old recorder snapshot replayed stale export busy state" }
        }
        if (changeCommandGeneration || changeExport) {
            check(observedGeneration == generation.get()) { "Recorder delivered a snapshot superseded by a newer command" }
            check(observedMode == RetentionMode.SIZE && !observedOneShot)
        } else {
            check(observedMode == RetentionMode.TIME && observedOneShot) {
                "Recorder mixed sampled counts/state with later mutable settings: mode=$observedMode oneShot=$observedOneShot"
            }
        }
    } finally {
        mainRelease.countDown()
        service?.let { recorder ->
            synchronized(requireNotNull(recorder.compatField("exportStateLock"))) {
                recorder.compatSet("activeExportToken", null)
            }
        }
        context.unbindService(connection)
    }
}

private fun ReverbService.compatField(name: String): Any? =
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

private fun ReverbService.compatSet(name: String, value: Any?) {
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun fixtureExportToken(): Any {
    val type = ReverbService::class.java.declaredClasses.single { it.simpleName == "ExportCancellationToken" }
    val constructor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
    return constructor.newInstance(1L, *Array(7) { AtomicBoolean(false) })
}
