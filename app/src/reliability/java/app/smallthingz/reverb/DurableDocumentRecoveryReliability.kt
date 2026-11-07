package app.smallthingz.reverb

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private const val DOCUMENT_QA_RATE = 48_000
private const val DOCUMENT_QA_FIRST = 12_000
private const val DOCUMENT_QA_FRAMES = 72_000
private val documentDeathBoundaries = setOf("VERIFIED_DURABLE", "BEFORE_PUBLISH", "AFTER_RENAME_BEFORE_MANIFEST")

/** Every run has separate journals/preferences. No singleton replacement or global recovery/reconcile. */
private class DocumentFixtureContext(base: Context, private val owner: DocumentFixtureRoot, private val run: String) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = directory("state")
    override fun getFilesDir(): File = directory("files")
    override fun getCacheDir(): File = directory("cache")
    override fun getExternalFilesDir(type: String?): File? = null
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        check(name == packageName && mode == Context.MODE_PRIVATE)
        return super.getSharedPreferences("document-death-$run", mode)
    }
    private fun directory(name: String): File = File(owner.root, name).also {
        if (storagePathState(it) == StoragePathState.MISSING) {
            check(it.mkdir()); forceRecordingDirectoryDurable(owner.root)
        }
        check(OsConstants.S_ISDIR(Os.lstat(it.path).st_mode))
    }
}

private fun documentFixtureCoordinator(context: Context): DurableSaveCoordinator =
    DurableSaveCoordinator::class.java.getDeclaredConstructor(Context::class.java).let {
        it.isAccessible = true
        it.newInstance(context)
    }

private fun documentFixtureGuard(context: Context) {
    check(context.packageName == DOCUMENT_FIXTURE_PACKAGE && Looper.myLooper() != Looper.getMainLooper())
    check(!readDurableCaptureIntentPreferences(getRecorderPreferences(context)).enabled)
    check(DurableSaveFaultHooks.onBoundary == null)
}

/** Called by the instrumentation controller, which kills this process only after receipt durability. */
internal fun prepareDurableDocumentProcessDeath(context: Context, format: ExportFormat, boundary: String, report: StringBuilder): String {
    documentFixtureGuard(context)
    check(boundary in documentDeathBoundaries)
    val run = UUID.randomUUID().toString()
    val owner = documentFixtureCreate(context, run)
    val scoped = DocumentFixtureContext(context, owner, run)
    val tree = DocumentsContract.buildTreeDocumentUri(DOCUMENT_FIXTURE_AUTHORITY, run)
    documentFixtureVerifyAncestry(context, run)
    // Ordinary provider operations use our own UID; no global persisted grant/preference is changed.
    check(setConfiguredExportTreeUri(scoped, tree))
    val priorCatalog = documentFixtureCatalogSnapshot(context)
    val controls = JSONArray()
    for ((name, first) in listOf("identical" to DOCUMENT_QA_FIRST, "unrelated" to 0)) {
        val target = createOutputTargetInDirectory(scoped, tree, "$name.${format.extension}", format.outputMimeType,
            System.currentTimeMillis())
        val writer = createRecordingAudioWriter(scoped, target, format, DOCUMENT_QA_RATE, 1,
            PcmSampleFormat.PCM_16, DEFAULT_FLAC_COMPRESSION)
        writer.use { val pcm = documentFixturePcm(first); it.write(pcm, 0, pcm.size) }
        val fingerprint = writer.verify(scoped)
        val published = finalizeOutputTarget(scoped, target, fingerprint)
        val proof = checkNotNull(readStableOutputFingerprint(scoped, published.storageType, published.id,
            published.publishedIdentity))
        controls.put(JSONObject().put("id", published.id).put("name", published.displayName)
            .put("identity", proof.providerIdentity).put("bytes", proof.digest.byteCount)
            .put("sha256", documentFixtureHex(proof.digest.sha256)))
    }
    val baselineRenameCalls = owner.renameCalls()
    check(baselineRenameCalls == 2)
    documentFixtureVerifyAncestry(context, run, DocumentsContract.getDocumentId(Uri.parse(controls.getJSONObject(0).getString("id"))))
    val sourceRoot = File(scoped.noBackupFilesDir, "source")
    check(sourceRoot.mkdir()); forceRecordingDirectoryDurable(scoped.noBackupFilesDir)
    val store = PersistentAudioChunkStore(sourceRoot)
    val coordinator = documentFixtureCoordinator(scoped)
    val handle = try {
        store.configure(RetentionMode.SIZE, 4_194_304L, DOCUMENT_QA_RATE, 1, PcmSampleFormat.PCM_16)
        for (first in 0 until 96_000 step 24_000) {
            val pcm = documentFixturePcm(first, 24_000)
            check(store.append(pcm, 0, pcm.size) == pcm.size)
            store.sealActiveChunk()
        }
        val lease = checkNotNull(store.acquireRange(0.25, 1.75))
        val admitted = lease.use {
            coordinator.admit(it, DurableSaveRequest("saved-$run", tree.toString(), format,
                ExportCodec.PCM_16, DOCUMENT_QA_RATE, 1, PcmSampleFormat.PCM_16, DEFAULT_FLAC_COMPRESSION))
        }
        store.clear() // The durable accepted references must preserve all selected source bytes.
        admitted
    } finally {
        store.close()
        check(!store.hasRangeLeaseCloseFailure())
    }
    val journal = DurableSaveJournal(durableSaveJournalRoot(scoped))
    val ready = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workerFailure = AtomicReference<Throwable?>()
    val hook: (String, String) -> Unit = { id, point ->
        if (id == handle.id && point == boundary) {
            val record = journal.read(id)
            val stagedTarget = checkNotNull(record.output)
            val expectedStage = if (boundary == "VERIFIED_DURABLE") DurableSaveStage.VERIFIED else DurableSaveStage.PUBLISHING
            check(record.stage == expectedStage && record.output?.storageType == RecordingStorageType.DOCUMENT)
            documentFixtureVerifyPins(journal, record)
            val verified = checkNotNull(record.fingerprint)
            check(documentFixtureHex(verified.digest.sha256) == controls.getJSONObject(0).getString("sha256")) {
                "Existing identical control must contain the same complete encoded output"
            }
            check(controls.getJSONObject(0).getString("sha256") != controls.getJSONObject(1).getString("sha256"))
            documentFixtureVerifyControls(scoped, controls)
            check(documentFixtureCatalogSnapshot(context).toString() == priorCatalog.toString())
            val renameCalls = owner.renameCalls()
            check(renameCalls == baselineRenameCalls + if (boundary == "AFTER_RENAME_BEFORE_MANIFEST") 1 else 0)
            val receipt = JSONObject().put("version", 1).put("run", run).put("job", id)
                .put("pid", Process.myPid()).put("processBirthElapsed", Process.getStartElapsedRealtime())
                .put("format", format.storageCode.toInt()).put("boundary", boundary).put("stage", record.stage.code)
                .put("tree", tree.toString()).put("rootDev", owner.admission.getLong("rootDev"))
                .put("rootIno", owner.admission.getLong("rootIno")).put("renameCalls", renameCalls)
                .put("baselineRenameCalls", baselineRenameCalls).put("controls", controls)
                .put("priorCatalog", priorCatalog).put("sourceProofs", documentFixtureProofs(record.source.objects))
                .put("stagingId", stagedTarget.id).put("stagingName", stagedTarget.stagingDisplayName)
                .put("stagingIdentity", stagedTarget.stagingIdentity)
                .put("verifiedIdentity", verified.providerIdentity)
                .put("verifiedBytes", verified.digest.byteCount)
                .put("verifiedSha256", documentFixtureHex(verified.digest.sha256))
                .put("mode", "explicit_scoped_coordinator_recovery")
            documentFixtureWriteNew(File(owner.root, "receipt.json"), receipt.toString().toByteArray())
            ready.countDown()
            check(release.await(120, TimeUnit.SECONDS)) { "Controller did not terminate prepared fixture; preserve evidence" }
            throw IOException("Prepared fixture released without process death")
        }
    }
    check(DurableSaveFaultHooks.onBoundary == null)
    DurableSaveFaultHooks.onBoundary = hook
    val worker = Thread({
        try { coordinator.materialize(handle.id) {} } catch (error: Throwable) { workerFailure.set(error) }
        finally { if (DurableSaveFaultHooks.onBoundary === hook) DurableSaveFaultHooks.onBoundary = null }
    }, "document-death-$run")
    try {
        worker.start()
        check(ready.await(60, TimeUnit.SECONDS)) { "Boundary not reached: ${workerFailure.get()}" }
        check(workerFailure.get() == null)
        report.appendLine("DOCUMENT_PREPARED run=$run boundary=$boundary receipt=${File(owner.root, "receipt.json")} mode=explicit_scoped_coordinator_recovery")
        return run
    } catch (failure: Throwable) {
        release.countDown()
        worker.join(5_000)
        if (DurableSaveFaultHooks.onBoundary === hook) DurableSaveFaultHooks.onBoundary = null
        throw failure
    }
}

/** Explicitly resumes this retained private journal; it does not assert normal Application discovery. */
internal fun resumeDurableDocumentProcessDeath(context: Context, run: String, report: StringBuilder) {
    documentFixtureGuard(context)
    val owner = documentFixtureRoot(context, run)
    val receipt = documentFixtureReadJson(File(owner.root, "receipt.json"), 32768)
    check(receipt.getInt("version") == 1 && receipt.getString("run") == run)
    check(receipt.getInt("pid") != Process.myPid() || receipt.getLong("processBirthElapsed") != Process.getStartElapsedRealtime())
    check(receipt.getLong("rootDev") == owner.admission.getLong("rootDev") && receipt.getLong("rootIno") == owner.admission.getLong("rootIno"))
    val scoped = DocumentFixtureContext(context, owner, run)
    val tree = DocumentsContract.buildTreeDocumentUri(DOCUMENT_FIXTURE_AUTHORITY, run)
    documentFixtureVerifyAncestry(context, run)
    check(receipt.getString("tree") == tree.toString())
    val boundary = receipt.getString("boundary")
    check(boundary in documentDeathBoundaries)
    val format = checkNotNull(ExportFormat.fromStorageCode(receipt.getInt("format")))
    val id = receipt.getString("job")
    check(UUID.fromString(id).toString() == id)
    val journal = DurableSaveJournal(durableSaveJournalRoot(scoped))
    val before = journal.read(id)
    check(before.stage.code == receipt.getInt("stage") && before.request.directoryId == tree.toString())
    check(before.request.format == format && before.output?.id == receipt.getString("stagingId"))
    check(documentFixtureProofs(before.source.objects).toString() == receipt.getJSONArray("sourceProofs").toString())
    val beforeFingerprint = checkNotNull(before.fingerprint)
    check(beforeFingerprint.providerIdentity == receipt.getString("verifiedIdentity") &&
        beforeFingerprint.digest.byteCount == receipt.getLong("verifiedBytes") &&
        documentFixtureHex(beforeFingerprint.digest.sha256) == receipt.getString("verifiedSha256"))
    documentFixtureVerifyPins(journal, before)
    val controls = receipt.getJSONArray("controls")
    documentFixtureVerifyControls(scoped, controls)
    val baselineCatalog = receipt.getJSONArray("priorCatalog")
    check(documentFixtureCatalogSnapshot(context).toString() == baselineCatalog.toString())
    check(owner.renameCalls() == receipt.getInt("renameCalls"))
    val coordinator = documentFixtureCoordinator(scoped)
    if (boundary == "BEFORE_PUBLISH") {
        var failure: Exception? = null
        try { coordinator.materialize(id) {} } catch (error: Exception) { failure = error }
        check(failure is IOException) { "Ambiguous publication must remain failed/preserved: $failure" }
        val retained = journal.read(id)
        check(retained.stage == DurableSaveStage.PUBLISHING && retained.error.isNotEmpty())
        check(retained.output == before.output && stableOutputFingerprintMatches(RecordingStorageType.DOCUMENT,
            checkNotNull(before.fingerprint), checkNotNull(retained.fingerprint)))
        documentFixtureVerifyPins(journal, retained)
        val staged = checkNotNull(retained.output)
        val retainedFingerprint = checkNotNull(retained.fingerprint)
        val current = checkNotNull(readStableOutputFingerprint(scoped, staged.storageType, staged.id,
            retainedFingerprint.providerIdentity))
        check(stableOutputFingerprintMatches(staged.storageType, retainedFingerprint, current))
        check(owner.renameCalls() == receipt.getInt("renameCalls"))
        check(documentFixtureCatalogSnapshot(context).toString() == baselineCatalog.toString())
        documentFixtureVerifyControls(scoped, controls)
        documentFixtureWriteNew(File(owner.root, "preserved.json"), JSONObject().put("run", run).put("job", id)
            .put("stage", retained.stage.code).put("error", retained.error).put("renameCalls", owner.renameCalls())
            .put("pinsPreserved", true).put("noRetryDispatch", true).put("mode", "explicit_scoped_coordinator_recovery")
            .toString().toByteArray())
        report.appendLine("DOCUMENT_PRESERVED run=$run job=$id pins=true no_retry_dispatch=true mode=explicit_scoped_coordinator_recovery")
        return // Deliberately keep all journal/pins/provider files; later cases have separate run roots.
    }
    val saved = coordinator.materialize(id) {}
    check(saved.storageType == RecordingStorageType.DOCUMENT && saved.directoryId == tree.toString())
    check(owner.renameCalls() == receipt.getInt("baselineRenameCalls") + 1)
    val rows = runBlocking { RecordingDatabase.getInstance(context).recordingDao().listAll() }
    check(rows.count { it.id == saved.id } == 1 && rows.count { it.directoryId == tree.toString() } == 1)
    check(documentFixtureCatalogSnapshot(context, saved.id).toString() == baselineCatalog.toString())
    check(storagePathState(journal.directory(id)) == StoragePathState.MISSING)
    before.source.objects.forEach { proof -> check(!DurablePcmPins.hasReference(journal.directory(id), proof)) }
    check(queryUriAssetState(scoped, Uri.parse(receipt.getString("stagingId")),
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), id) != RecordingAssetState.PRESENT)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, run)
    checkNotNull(scoped.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)).use { cursor ->
        requireCompleteDocumentListing(cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false),
            cursor.extras.getString(DocumentsContract.EXTRA_ERROR))
        val ids = mutableListOf<String>()
        while (cursor.moveToNext()) ids += cursor.getString(0)
        check(ids.size == 3 && "$run/${receipt.getString("stagingName")}" !in ids)
        check(DocumentsContract.getDocumentId(Uri.parse(saved.id)) in ids)
        requireCompleteDocumentListing(cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false),
            cursor.extras.getString(DocumentsContract.EXTRA_ERROR))
    }
    documentFixtureVerifyControls(scoped, controls)
    documentFixtureVerifyOutput(scoped, saved, format)
    val fingerprint = checkNotNull(readStableOutputFingerprint(scoped, saved.storageType, saved.id, saved.fileIdentity))
    val archive = File(owner.root, "output.${format.extension}")
    check(archive.createNewFile())
    checkNotNull(openRecordingInputStream(scoped, saved)).use { input ->
        java.io.FileOutputStream(archive).use { output ->
            val scratch = ByteArray(32768)
            var total = 0L
            while (true) {
                val count = input.read(scratch)
                if (count < 0) break
                check(count > 0 && total + count <= 1_048_576)
                output.write(scratch, 0, count); total += count
            }
            check(total == fingerprint.digest.byteCount); output.fd.sync()
        }
    }
    forceRecordingDirectoryDurable(owner.root)
    check(documentFixtureHex(MessageDigest.getInstance("SHA-256").digest(archive.readBytes())) == documentFixtureHex(fingerprint.digest.sha256))
    val after = checkNotNull(readStableOutputFingerprint(scoped, saved.storageType, saved.id, saved.fileIdentity))
    check(stableOutputFingerprintMatches(saved.storageType, fingerprint, after))
    documentFixtureWriteNew(File(owner.root, "complete.json"), JSONObject().put("run", run).put("job", id)
        .put("id", saved.id).put("name", saved.displayName).put("identity", saved.fileIdentity)
        .put("format", format.storageCode.toInt()).put("frames", DOCUMENT_QA_FRAMES).put("duration", saved.durationMillis)
        .put("bytes", fingerprint.digest.byteCount).put("sha256", documentFixtureHex(fingerprint.digest.sha256))
        .put("pcmSha256", documentFixtureHex(MessageDigest.getInstance("SHA-256").digest(documentFixturePcm(DOCUMENT_QA_FIRST))))
        .put("renameCalls", owner.renameCalls()).put("pinsReleased", true).put("controlsUnchanged", true)
        .put("mode", "explicit_scoped_coordinator_recovery").toString().toByteArray())
    report.appendLine("DOCUMENT_RECOVERED run=$run job=$id boundary=$boundary rows=1 pins_released=true retained_output=${archive.path} mode=explicit_scoped_coordinator_recovery")
}

private fun documentFixtureVerifyPins(journal: DurableSaveJournal, record: DurableSaveRecord) {
    check(record.source.objects.size in 1..8)
    for (proof in record.source.objects) {
        check(proof.sourcePath.isNotEmpty() && DurablePcmPins.hasReference(journal.directory(record.id), proof))
        check(DurablePcmPins.isProtected(File(proof.sourcePath)))
        DurablePcmPins.verify(journal.directory(record.id), proof)
    }
    val expected = documentFixturePcm(DOCUMENT_QA_FIRST)
    var position = 0
    record.source.range.readNormalized(DOCUMENT_QA_RATE, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, count ->
        check(count <= expected.size - position)
        for (index in 0 until count) check(bytes[offset + index] == expected[position + index])
        position += count
        count
    }
    check(position == expected.size)
}

private fun documentFixtureVerifyAncestry(context: Context, run: String, existingId: String? = null) {
    checkNotNull(context.contentResolver.acquireContentProviderClient(DOCUMENT_FIXTURE_AUTHORITY)).use { client ->
        val provider = client.localContentProvider as DurableDocumentFixtureProvider
        check(provider.isChildDocument(run, "$run/missing.wav"))
        var missing = false
        try { provider.queryDocument("$run/missing.wav", null).close() }
        catch (_: java.io.FileNotFoundException) { missing = true }
        check(missing) { "Scoped absent child must report missing, not a grant failure" }
        existingId?.let { check(provider.isChildDocument(run, it)) }
        for (candidate in listOf(run, "$run/", "$run/../escape.wav", "$run/folder/child.wav",
            "$run/folder\\child.wav", "$run/bad\u0000.wav", "$run/bad\uD800.wav", "$run/unknown.bin",
            "${UUID.randomUUID()}/child.wav")) {
            check(!provider.isChildDocument(run, candidate)) { "Fixture accepted out-of-scope child: $candidate" }
        }
        check(!provider.isChildDocument("$run/not-a-tree.wav", "$run/missing.wav"))
    }
}

private fun documentFixtureProofs(proofs: List<DurablePcmPins.ObjectProof>) = JSONArray().also { array ->
    proofs.forEach { proof -> array.put(JSONObject().put("name", proof.name).put("identity", proof.identity)
        .put("source", proof.sourcePath).put("offset", proof.payloadOffset).put("bytes", proof.payloadBytes)
        .put("checksum", proof.checksum)) }
}

private fun documentFixtureVerifyControls(context: Context, controls: JSONArray) {
    check(controls.length() == 2)
    for (index in 0 until controls.length()) {
        val expected = controls.getJSONObject(index)
        val current = checkNotNull(readStableOutputFingerprint(context, RecordingStorageType.DOCUMENT,
            expected.getString("id"), expected.getString("identity")))
        check(current.providerIdentity == expected.getString("identity") && current.digest.byteCount == expected.getLong("bytes") &&
            documentFixtureHex(current.digest.sha256) == expected.getString("sha256"))
    }
}

internal data class DocumentFixtureCatalogObservation(
    val row: String,
    val verifiedBytes: Long?,
    val verifiedSha256: String?,
    val verifiedIdentity: String?,
)

internal fun observeDocumentFixtureCatalog(
    rows: List<RecordingEntity>,
    excludedId: String? = null,
    fingerprint: (RecordingEntity) -> StableOutputFingerprint?,
): List<DocumentFixtureCatalogObservation> {
    val selected = rows.filter { it.id != excludedId }.sortedBy { it.id }
    check(selected.size <= 128 && selected.sumOf { it.sizeBytes } <= 256L * 1024 * 1024)
    return selected.map { row ->
        // Prior synthetic providers may intentionally lose their in-memory registry on death.
        // Retain every catalog row and the absence of a verified payload proof; never infer
        // missing bytes or silently import/reconstruct a provider entry to make this pass.
        val proof = fingerprint(row)
        DocumentFixtureCatalogObservation(row.toString(), proof?.digest?.byteCount,
            proof?.digest?.sha256?.let(::documentFixtureHex), proof?.let { it.providerIdentity ?: it.fileKey })
    }
}

private fun documentFixtureCatalogSnapshot(context: Context, excludedId: String? = null): JSONArray {
    val rows = runBlocking { RecordingDatabase.getInstance(context).recordingDao().listAll() }
    val observations = observeDocumentFixtureCatalog(rows, excludedId) { row ->
        readStableOutputFingerprint(context, row.storageType, row.id, row.fileIdentity)
    }
    return JSONArray().also { result -> observations.forEach { observed ->
        val entry = JSONObject().put("row", observed.row)
            .put("payloadObservation", if (observed.verifiedBytes == null) "UNVERIFIED" else "VERIFIED")
        if (observed.verifiedBytes != null) {
            entry.put("bytes", observed.verifiedBytes).put("sha256", observed.verifiedSha256)
                .put("identity", observed.verifiedIdentity)
        }
        result.put(entry)
    } }
}

private fun documentFixtureVerifyOutput(context: Context, saved: RecordingEntity, format: ExportFormat) {
    check(saved.durationMillis == 1500L)
    val expected = documentFixturePcm(DOCUMENT_QA_FIRST)
    withRecordingWavChannelIdentityGuard(context, saved, RecordingReadCleanup.Propagate) { channel, stillCurrent ->
        if (format == ExportFormat.WAV) {
            val layout = readWavPcmLayout(channel)
            check(layout.sampleRate == DOCUMENT_QA_RATE && layout.channelCount == 1 && layout.sampleFormat == PcmSampleFormat.PCM_16)
            check(layout.frameCount == DOCUMENT_QA_FRAMES.toLong() && layout.dataBytes == expected.size.toLong())
            val actual = ByteArray(expected.size)
            val buffer = ByteBuffer.wrap(actual)
            var position = layout.dataOffsetBytes
            while (buffer.hasRemaining()) { val n = channel.read(buffer, position); check(n > 0); position += n }
            check(actual.contentEquals(expected))
        } else FlacPcmDecoder(channel, RecordingReadCleanup.Propagate).use { decoder ->
            check(decoder.info.sampleRate == DOCUMENT_QA_RATE && decoder.info.channelCount == 1 &&
                decoder.info.bitsPerSample == 16 && decoder.info.frameCount == DOCUMENT_QA_FRAMES.toLong())
            var position = 0
            decoder.readFrames(0, DOCUMENT_QA_FRAMES.toLong()) { bytes, offset, count ->
                check(count <= expected.size - position)
                for (index in 0 until count) check(bytes[offset + index] == expected[position + index])
                position += count
            }
            check(position == expected.size)
        }
        check(stillCurrent())
    }
}

private fun documentFixturePcm(first: Int, frames: Int = DOCUMENT_QA_FRAMES): ByteArray = ByteArray(frames * 2).also { bytes ->
    for (frame in 0 until frames) {
        val value = (((first + frame) * 73 + (first + frame) / 17) % 60_001) - 30_000
        bytes[frame * 2] = value.toByte(); bytes[frame * 2 + 1] = (value shr 8).toByte()
    }
}

internal fun documentFixtureHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
