package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** One externally created exact fixture. No discovery, permission mutation, UI launch or automatic cleanup. */
internal fun verifyAllFilesAccessBoundary(
    instrumentation: Instrumentation,
    report: StringBuilder,
    manifestText: String,
    manifestSha256: String,
) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.allfiles.reliability")
    check(context.applicationContext.javaClass == ReverbApplication::class.java)
    check(Build.VERSION.SDK_INT >= 30)
    @Suppress("DEPRECATION")
    val main = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
    check(main.enabled && main.applicationInfo.uid == Process.myUid())
    require(manifestText.length <= 32768 && manifestSha256.matches(Regex("[0-9a-f]{64}")))
    check(allFilesQaSha(manifestText.toByteArray(Charsets.UTF_8)) == manifestSha256)
    val manifest = JSONObject(manifestText)
    require(manifest.getInt("schema") == 1)
    val runId = manifest.getString("run_id")
    require(UUID.fromString(runId).toString() == runId)
    val phase = manifest.getString("case_id")
    require(phase == "denied" || phase == "granted")
    val relativePath = "Music/Reverb/"
    val granted = phase == "granted"
    fun requireAccess() { check(Environment.isExternalStorageManager() == granted) { "Actual special access differs from phase" } }
    requireAccess()
    val uri = Uri.parse(manifest.getString("uri"))
    require(uri.toString().matches(Regex("content://media/external_primary/audio/media/[1-9][0-9]*")))
    val name = "Reverb-QA-AllFiles-$runId-$phase.wav"
    require(manifest.getString("display_name") == name)
    require(manifest.getString("relative_path") == relativePath)
    require(manifest.getString("native_path") == "/storage/emulated/0/$relativePath$name")
    require(manifest.getInt("creator_uid") >= 0 && manifest.getInt("creator_uid") != Process.myUid())
    require(manifest.has("owner_package_name"))
    val owner = if (manifest.isNull("owner_package_name")) null else manifest.getString("owner_package_name")
    require(owner != context.packageName)
    val expected = allFilesQaWave()
    val expectedSha = allFilesQaSha(expected)
    require(manifest.getLong("size_bytes") == expected.size.toLong() && manifest.getString("sha256") == expectedSha)
    val device = manifest.getLong("native_device")
    val inode = manifest.getLong("native_inode")
    require(device > 0 && inode > 0)
    require(manifest.getLong("date_modified") > 0 && manifest.getLong("generation_modified") > 0)
    val directory = File(context.filesDir, "all-files-$runId-$phase")
    check(directory.mkdir()) { "Evidence directory already exists; never replay this phase" }
    val result = JSONObject().put("schema", 1).put("run_id", runId).put("case_id", phase)
        .put("package", context.packageName).put("uid", Process.myUid()).put("uri", uri.toString())
        .put("manifest_sha256", manifestSha256).put("actual_all_files_access", granted)
        .put("passed", false).put("prompt_dispatch_count", 0)
        .put("scope", "Denied: actual storage rename typed boundary and repository deletion preparation; granted: actual repository rename/delete. No MainActivity/onboarding UI qualification.")
    fun publish(stage: String) {
        result.put("stage", stage).put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
        writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray(Charsets.UTF_8)) }
    }
    fun emptyJournal() {
        check(getRecorderPreferences(context).requireDurableStringSet(PrefKey.PENDING_RECORDING_DELETIONS).isEmpty()) {
            "Preserve existing pending deletion journal; admission refused"
        }
    }
    @Suppress("DEPRECATION")
    fun noCaptureService() {
        val services = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        check(services.none { it.service.packageName == context.packageName }) { "Backend probe requires no running app Service" }
    }
    fun observe(): AllFilesQaObservation {
        val before = checkNotNull(allFilesQaRow(context, uri))
        val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
        val native = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val first = checkNotNull(nativeProviderPayloadRevision(stream.fd))
            check(first.device == device && first.inode == inode && first.sizeBytes == expected.size.toLong())
            val bytes = ByteArray(expected.size)
            var count = 0
            while (count < bytes.size) {
                val read = stream.read(bytes, count, bytes.size - count)
                check(read > 0) { "Short or stalled exact fixture read" }
                count += read
            }
            check(stream.read() == -1 && bytes.contentEquals(expected) && allFilesQaSha(bytes) == expectedSha)
            check(nativeProviderPayloadRevision(stream.fd) == first) { "Provider payload changed during read" }
            first
        }
        check(allFilesQaRow(context, uri) == before) { "Provider row changed during observation" }
        return AllFilesQaObservation(before, native)
    }
    var failure: Throwable? = null
    publish("admission")
    try {
        noCaptureService(); emptyJournal()
        check(RecordingDeletionConsent.changes.value == 0L && RecordingDeletionConsent.completions.value == 0L) {
            "Requires fresh backend process with no consent history or attached UI workflow"
        }
        val original = observe()
        check(original.row == AllFilesQaRow(name, relativePath, manifest.getString("native_path"),
            expected.size.toLong(), owner, manifest.getLong("date_modified"), manifest.getLong("generation_modified"), 0))
        result.put("original", original.json())
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val priorCatalog = runBlocking { dao.listAll().sortedBy { it.id } }
        check(priorCatalog.none { it.id == uri.toString() }) { "Never replace an existing catalog row" }
        val identity = mediaStoreProviderIdentityFromMetadata(uri.toString(), true, original.row.size,
            true, original.row.modified, true, original.row.generation)
        check(identity.isNotBlank())
        val recording = runBlocking { RecordingRepository.register(context, RecordingEntity(
            id = uri.toString(), displayName = name, mimeType = "audio/wav", startedAtMillis = original.row.modified * 1000,
            durationMillis = 100, sizeBytes = original.row.size, codecSummary = "QA synthetic PCM16 8 kHz mono",
            storageType = RecordingStorageType.MEDIASTORE,
            directoryId = "mediastore:external:${relativePath.trimEnd('/')}", fileIdentity = identity,
        )) }
        val registeredCatalog = runBlocking { dao.listAll().sortedBy { it.id } }
        check(registeredCatalog.filterNot { it.id == recording.id } == priorCatalog)
        result.put("catalog_registered_exact_fixture", true)
        val renamedBase = "Reverb-QA-AllFiles-$runId-$phase-renamed"
        publish("before_mutation")
        requireAccess()
        if (!granted) {
            val consent = try {
                renameRecordingAsset(context, recording, renamedBase, allowWriteConsent = true)
                error("Denied storage rename did not throw typed consent")
            } catch (required: RecordingRenameConsentRequired) { required }
            check(consent.recording.id == recording.id && consent.recording.fileIdentity == recording.fileIdentity)
            check(consent.displayName == "$renamedBase.wav")
            check(consent.before.displayName == name && consent.before.fingerprint.digest.byteCount == expected.size.toLong())
            check(consent.before.fingerprint.digest.sha256.contentEquals(MessageDigest.getInstance("SHA-256").digest(expected)))
            check(observe() == original)
            result.put("rename_outcome", "STORAGE_TYPED_CONSENT_REQUIRED_NOT_REPOSITORY_RETURN")
            publish("rename_denied_unchanged")
            requireAccess()
            val journal = RecordingRepository.consentDeletionJournalBatch(context)
            val attempt = runBlocking { RecordingRepository.prepareDeletionConsent(context,
                RecordingDeletionConsentTarget(recording, PendingDeletionIntent(recording.id,
                    expected.size.toLong(), expectedSha, false)), journal) }
            check(attempt is RecordingDeletionAttempt.NeedsConsent) { "Denied repository delete did not return NeedsConsent: $attempt" }
            check(attempt.target.recording.id == recording.id && attempt.target.fingerprint.sha256Hex == expectedSha &&
                attempt.target.fingerprint.byteCount == expected.size.toLong() && !attempt.target.fingerprint.assetDeleted)
            runBlocking { RecordingRepository.flushConsentDeletionJournal(journal) }
            check(journal.failedEntries.isEmpty())
            check(observe() == original && runBlocking { dao.listAll().sortedBy { it.id } } == registeredCatalog)
            emptyJournal()
            result.put("delete_outcome", "REPOSITORY_TYPED_NEEDS_CONSENT_NO_ENQUEUE")
                .put("fixture_preserved_for_explicit_executor_cleanup", true)
        } else {
            val renamed = checkNotNull(runBlocking { RecordingRepository.rename(context, recording, renamedBase) })
            check(renamed.id == recording.id && renamed.displayName == "$renamedBase.wav")
            val afterRename = observe()
            check(afterRename.row.name == renamed.displayName && afterRename.row.path == relativePath &&
                afterRename.row.dataPath == "/storage/emulated/0/$relativePath${renamed.displayName}" &&
                afterRename.row.size == original.row.size && afterRename.row.owner == original.row.owner && afterRename.row.pending == 0)
            check(providerPayloadObjectMatches(original.native, afterRename.native))
            check(runBlocking { dao.findById(renamed.id) } == renamed)
            check(runBlocking { dao.listAll().filterNot { it.id == renamed.id }.sortedBy { it.id } } == priorCatalog)
            result.put("rename_outcome", "REPOSITORY_SUCCESS").put("after_rename", afterRename.json())
            publish("renamed_exact_payload")
            requireAccess()
            check(RecordingDeletionConsent.changes.value == 0L && RecordingDeletionConsent.completions.value == 0L)
            check(runBlocking { RecordingRepository.deleteExactWithoutPendingReplay(context, renamed) })
            check(allFilesQaRow(context, uri) == null && runBlocking { dao.findById(renamed.id) } == null)
            check(runBlocking { dao.listAll().sortedBy { it.id } } == priorCatalog)
            result.put("delete_outcome", "REPOSITORY_SUCCESS_EXACT_ABSENCE").put("fixture_deleted", true)
            emptyJournal()
        }
        requireAccess(); noCaptureService(); emptyJournal()
        check(RecordingDeletionConsent.changes.value == 0L && RecordingDeletionConsent.completions.value == 0L) {
            "A production consent handoff occurred; do not claim promptless success"
        }
        result.put("production_consent_changes", RecordingDeletionConsent.changes.value)
            .put("production_consent_completions", RecordingDeletionConsent.completions.value).put("passed", true)
        publish("completed")
    } catch (error: Throwable) {
        failure = error
        result.put("failure", error.stackTraceToString())
        publish("failed_preserve_all_remaining_fixture_and_metadata")
    } finally {
        report.appendLine("ALL_FILES_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS current_all_files_access $phase; exact foreign fixture; no consent UI dispatch")
}

private data class AllFilesQaRow(val name: String, val path: String, val dataPath: String, val size: Long,
    val owner: String?, val modified: Long, val generation: Long, val pending: Int)
private data class AllFilesQaObservation(val row: AllFilesQaRow, val native: ProviderPayloadRevision) {
    fun json() = JSONObject().put("display_name", row.name).put("relative_path", row.path).put("native_path", row.dataPath)
        .put("size_bytes", row.size).put("owner_package_name", row.owner ?: JSONObject.NULL)
        .put("date_modified", row.modified).put("generation_modified", row.generation).put("is_pending", row.pending)
        .put("native_device", native.device).put("native_inode", native.inode)
        .put("native_changed_seconds", native.changedSeconds).put("native_changed_nanos", native.changedNanos)
}

@Suppress("DEPRECATION")
private fun allFilesQaRow(context: Context, uri: Uri): AllFilesQaRow? {
    val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.IS_PENDING)
    return checkNotNull(context.contentResolver.query(uri, columns, null, null, null)).use { cursor ->
        if (!cursor.moveToFirst()) null else {
            check(columns.indices.filter { it != 4 }.all { !cursor.isNull(it) })
            val row = AllFilesQaRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                if (cursor.isNull(4)) null else cursor.getString(4), cursor.getLong(5), cursor.getLong(6), cursor.getInt(7))
            check(!cursor.moveToNext()) { "Exact URI returned duplicate rows" }
            row
        }
    }
}
private fun allFilesQaSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun allFilesQaWave(): ByteArray = ByteBuffer.allocate(1644).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(1636); put("WAVEfmt ".toByteArray()); putInt(16)
    putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
    put("data".toByteArray()); putInt(1600)
}.array()
