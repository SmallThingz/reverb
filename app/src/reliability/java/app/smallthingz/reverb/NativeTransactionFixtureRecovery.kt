package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val RECOVERY_PACKAGE = "app.smallthingz.reverb.nativeparity.reliability"
private const val RECOVERY_URI = "content://media/external/audio/media/9097"
private const val RECOVERY_NAME = "bad name.wav"
private const val RECOVERY_BYTES = 48_044L
private const val RECOVERY_LEDGER = "7c4199f06055937856b8c516410b22b2186fc09f84ba3f16359916d2f784f157"
private const val RECOVERY_RESULT = "1dc99d7939f9646473b40f96305ef261b8a3981f76e4bbae325d1c14ac739653"
private const val RECOVERY_WAV = "029a9cba84693987917c7be5ee161543474f76ab9172ce4d5408c5cdf03c503a"
private const val RECOVERY_PCM = "be65ea7c0372abbf61923127f77130fe104e08808474e76b7589fbe7b5239c7a"

/** One explicitly selected retained QA output; never scans for, adopts, or cleans other recordings. */
internal fun recoverExactNativeTransactionFixture(
    instrumentation: Instrumentation,
    report: StringBuilder,
    nonce: String?,
    expectedLedgerSha256: String?,
) {
    val context = instrumentation.targetContext
    check(context.packageName == RECOVERY_PACKAGE && context.applicationContext.javaClass == Application::class.java)
    check(context.applicationInfo.uid == Process.myUid() && Build.VERSION.SDK_INT >= 30)
    check(nonce != null && nonce.matches(Regex("[a-f0-9]{32,64}")))
    check(expectedLedgerSha256 == RECOVERY_LEDGER) { "Exact failed-run creation ledger must be explicitly bound" }
    val directory = File(context.filesDir, "native-transaction-fixture-recovery-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val result = JSONObject().put("package", context.packageName).put("uid", Process.myUid())
        .put("session_nonce", nonce).put("creation_ledger_sha256", RECOVERY_LEDGER)
        .put("retained_failed_result_sha256", RECOVERY_RESULT).put("exact_uri", RECOVERY_URI)
        .put("observations_verified", false).put("cleanup_successful", false)
        .put("normal_package_accessed", false).put("delete_attempted", false)
        .put("scope", "Explicitly authorized cleanup of one created QA fixture after fresh owner/identity/content proof")
    var failure: Throwable? = null
    try {
        publishRecovery(directory, result)
        val environment = recoveryEnvironment(context)
        result.put("qa_environment", environment)
        val originalDirectory = File(context.filesDir, "native-library-transactions-1792631838515")
        val ledgerBytes = File(originalDirectory, "compose-fixtures.json").readBytes()
        val failedBytes = File(originalDirectory, "result.json").readBytes()
        check(recoveryDigest(ledgerBytes) == RECOVERY_LEDGER && recoveryDigest(failedBytes) == RECOVERY_RESULT)
        val ledger = JSONObject(String(ledgerBytes, Charsets.UTF_8))
        check(ledger.getString("package") == RECOVERY_PACKAGE && ledger.getString("renderer") == "compose")
        check(ledger.getBoolean("baseline_empty") && ledger.getInt("creation_attempts") == 2)
        val entries = ledger.getJSONArray("fixtures")
        check(entries.length() == 2)
        val original = entries.getJSONObject(0)
        val retired = entries.getJSONObject(1)
        check(original.getBoolean("complete") && original.getString("id") == RECOVERY_URI &&
            original.getString("uri") == RECOVERY_URI && original.getString("published_id") == RECOVERY_URI &&
            original.getString("published_uri") == RECOVERY_URI && original.getString("storage_type") == "MEDIASTORE" &&
            original.getString("directory_id") == MEDIA_STORE_DIRECTORY_ID)
        check(original.getString("expected_final_name") == "Library transaction A.wav" &&
            original.getLong("bytes") == RECOVERY_BYTES && original.getLong("verified_bytes") == RECOVERY_BYTES &&
            original.getInt("payload_offset") == 44 && original.getString("wav_sha256") == RECOVERY_WAV &&
            original.getString("verified_wav_sha256") == RECOVERY_WAV && original.getString("pcm_sha256") == RECOVERY_PCM)
        val publishedIdentity = original.getString("published_identity")
        check(original.getString("identity") == publishedIdentity &&
            sameProviderObjectAcrossMutation(original.getString("staging_creation_identity"), publishedIdentity) &&
            sameProviderObjectAcrossMutation(original.getString("verified_staging_identity"), publishedIdentity))
        check(!original.optBoolean("deleted_by_cleanup") && retired.getBoolean("deleted_by_cleanup") &&
            retired.getString("id") == "content://media/external/audio/media/9098")
        requireRecoveryUriAbsent(context, Uri.parse(retired.getString("id")))
        val records = readRecoveryCatalog(context)
        check(records.size == 1) { "Unexpected QA catalog membership; preserve everything" }
        val recording = records.single()
        check(recording.id == RECOVERY_URI && recording.displayName == RECOVERY_NAME &&
            recording.storageType == RecordingStorageType.MEDIASTORE && recording.directoryId == MEDIA_STORE_DIRECTORY_ID &&
            recording.sizeBytes == RECOVERY_BYTES && recording.durationMillis == 3_000L &&
            recording.mimeType == "audio/wav" && recording.codecSummary == "PCM")
        check(sameProviderObjectAcrossMutation(publishedIdentity, recording.fileIdentity))
        val observation = proveRecoveryRecording(context, recording)
        result.put("recording", JSONObject().put("id", recording.id).put("display_name", recording.displayName)
            .put("original_identity", publishedIdentity).put("current_identity", recording.fileIdentity)
            .put("bytes", recording.sizeBytes))
        result.put("observation", observation).put("observations_verified", true)
        // Complete fresh proof is durably recorded before an operation can possibly commit.
        publishRecovery(directory, result)
        check(recoveryEnvironment(context).toString() == environment.toString())
        check(readRecoveryCatalog(context) == records)
        val immediate = proveRecoveryRecording(context, recording)
        check(immediate.toString() == observation.toString()) { "Verified fixture changed before cleanup; preserved" }
        result.put("pre_delete_observation", immediate).put("delete_attempted", true)
            .put("delete_outcome", "UNKNOWN until backend return and terminal observations")
        publishRecovery(directory, result)
        // Existing backend rechecks this exact current identity; no stale-token or name-based deletion.
        val removed = runBlocking { withTimeout(15_000L) { RecordingRepository.delete(context, recording) } }
        result.put("delete_returned", removed)
        check(removed)
        check(recordingAssetState(context, recording) == RecordingAssetState.MISSING)
        requireRecoveryUriAbsent(context, Uri.parse(RECOVERY_URI))
        check(readRecoveryCatalog(context).isEmpty())
        check(recoveryEnvironment(context).toString() == environment.toString())
        check(recoveryDigest(File(originalDirectory, "compose-fixtures.json").readBytes()) == RECOVERY_LEDGER &&
            recoveryDigest(File(originalDirectory, "result.json").readBytes()) == RECOVERY_RESULT)
        result.put("delete_outcome", "Exact owned URI absent and own catalog retired")
            .put("cleanup_successful", true).put("original_failure_and_creation_ledger_unchanged", true)
    } catch (error: Throwable) {
        failure = error
        result.put("failure", error.stackTraceToString())
    } finally {
        var published = false
        try { publishRecovery(directory, result); published = true }
        catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        report.append(if (published) "PARITY_EVIDENCE " else "PARITY_EVIDENCE_WRITE_FAILED ")
            .append(directory.name).append("/result.json\n")
    }
    failure?.let { throw it }
    report.append("PASS native_transaction_fixture_recovery exact9097 owner/identity/WAV/PCM proof and terminal absence\n")
}

private fun recoveryDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

private fun publishRecovery(directory: File, result: JSONObject) {
    val bytes = result.toString(2).toByteArray(Charsets.UTF_8)
    val file = AtomicFile(File(directory, "result.json"))
    var output: FileOutputStream? = null
    try {
        output = file.startWrite()
        output.write(bytes); output.flush(); output.fd.sync()
        file.finishWrite(output); output = null
        check(file.baseFile.readBytes().contentEquals(bytes)) { "Recovery evidence publication failed" }
    } catch (error: Throwable) {
        output?.let(file::failWrite)
        throw error
    }
}

private fun recoveryEnvironment(context: Context): JSONObject {
    check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
    check(context.contentResolver.persistedUriPermissions.isEmpty() && getConfiguredExportTreeUri(context) == null)
    check(recordingMutations.activeIds.value.isEmpty()) { "QA mutation is still active" }
    val preferences = getRecorderPreferences(context)
    check(preferences.requireDurableStringSet(PrefKey.PENDING_RECORDING_DELETIONS).isEmpty())
    val values = JSONObject()
    for ((key, value) in preferences.all.toSortedMap()) {
        // Existing delete may retire an explicitly empty journal key; no other preference normalization.
        if (key == PrefKey.PENDING_RECORDING_DELETIONS.name) continue
        values.put(key, JSONObject().put("type", value?.javaClass?.name ?: "null")
            .put("value", if (value is Set<*>) JSONArray(value.map { check(it is String); it }.sorted()) else value ?: JSONObject.NULL))
    }
    val folder = getSavedRecordingsDirectory(context).canonicalFile
    val external = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)?.canonicalFile
    check(folder.parentFile == external || folder.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
    check(folder.isDirectory && folder.listFiles()?.isEmpty() == true) { "Unrelated QA files present; preserve them" }
    val permissions = JSONObject()
    for (name in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE)) permissions.put(name, context.checkSelfPermission(name))
    return JSONObject().put("preferences", values).put("permissions", permissions)
        .put("ui_mode", context.resources.configuration.uiMode).put("font_scale", context.resources.configuration.fontScale.toDouble())
}

private fun readRecoveryCatalog(context: Context): List<RecordingEntity> {
    val file = context.getDatabasePath(DATABASE_FILE_NAME)
    check(file.isFile) { "Existing QA catalog missing; no database creation" }
    val corruption = DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Preserve corrupt read-only QA catalog") }
    return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, corruption).use { database ->
        check(database.isReadOnly && database.version == RecordingDatabase.DATABASE_VERSION)
        database.query(RecordingDatabase.TABLE_RECORDINGS, arrayOf(
            RecordingDatabase.COLUMN_ID, RecordingDatabase.COLUMN_DISPLAY_NAME, RecordingDatabase.COLUMN_MIME_TYPE,
            RecordingDatabase.COLUMN_STARTED_AT_MILLIS, RecordingDatabase.COLUMN_DURATION_MILLIS, RecordingDatabase.COLUMN_SIZE_BYTES,
            RecordingDatabase.COLUMN_CODEC_SUMMARY, RecordingDatabase.COLUMN_STORAGE_TYPE_CODE,
            RecordingDatabase.COLUMN_DIRECTORY_ID, RecordingDatabase.COLUMN_FILE_IDENTITY,
        ), null, null, null, null, RecordingDatabase.COLUMN_ID, "2").use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    fun text(index: Int): String { check(rows.getType(index) == Cursor.FIELD_TYPE_STRING); return rows.getString(index).also { check(it.length <= 4096) } }
                    fun number(index: Int): Long { check(rows.getType(index) == Cursor.FIELD_TYPE_INTEGER); return rows.getLong(index) }
                    check(number(7) == RecordingStorageType.MEDIASTORE.storageCode.toLong())
                    add(RecordingEntity(text(0), text(1), text(2), number(3), number(4), number(5), text(6),
                        RecordingStorageType.MEDIASTORE, text(8), text(9), createdAtMillis = 0L, lastSeenAtMillis = 0L))
                }
            }
        }
    }
}

private fun observeRecoveryProvider(context: Context, recording: RecordingEntity): JSONObject {
    check(recording.id == RECOVERY_URI)
    val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_ADDED,
        MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.VOLUME_NAME)
    val observed = checkNotNull(context.contentResolver.query(Uri.parse(RECOVERY_URI), columns, null, null, null)).use { rows ->
        check(rows.count == 1 && rows.moveToFirst())
        JSONObject().also { out ->
            for (column in columns) {
                val index = rows.getColumnIndexOrThrow(column)
                check(!rows.isNull(index)) { "Unknown current provider field $column" }
                when (rows.getType(index)) {
                    Cursor.FIELD_TYPE_STRING -> out.put(column, rows.getString(index))
                    Cursor.FIELD_TYPE_INTEGER -> out.put(column, rows.getLong(index))
                    else -> error("Unexpected provider field type $column")
                }
            }
        }
    }
    check(observed.getLong(MediaStore.MediaColumns._ID) == 9097L &&
        observed.getString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) == RECOVERY_PACKAGE &&
        observed.getString(MediaStore.MediaColumns.DISPLAY_NAME) == RECOVERY_NAME &&
        observed.getString(MediaStore.MediaColumns.RELATIVE_PATH) == "Music/Reverb/" &&
        observed.getLong(MediaStore.MediaColumns.IS_PENDING) == 0L &&
        observed.getLong(MediaStore.MediaColumns.SIZE) == RECOVERY_BYTES)
    check(observed.getLong(MediaStore.MediaColumns.GENERATION_ADDED) > 0L &&
        observed.getLong(MediaStore.MediaColumns.GENERATION_MODIFIED) >= observed.getLong(MediaStore.MediaColumns.GENERATION_ADDED))
    val identity = resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, Uri.parse(RECOVERY_URI), recording.fileIdentity)
    check(providerRecordingIdentityMatches(recording.fileIdentity, identity))
    return observed.put("current_identity", identity)
}

private fun proveRecoveryRecording(context: Context, recording: RecordingEntity): JSONObject {
    val before = observeRecoveryProvider(context, recording)
    val postcondition = recordingCatalogPostconditionObservation(context, recording)
    check(postcondition.identityMatches && postcondition.displayNameMatches && recordingDestructiveIdentityMatches(context, recording))
    val bytes = checkNotNull(openRecordingInputStream(context, recording)).use { input ->
        val expected = ByteArray(RECOVERY_BYTES.toInt())
        var offset = 0
        while (offset < expected.size) {
            val count = input.read(expected, offset, expected.size - offset)
            check(count > 0) { "Owned fixture read incomplete" }
            offset += count
        }
        check(input.read() == -1) { "Owned fixture has additional bytes" }
        expected
    }
    check(recoveryDigest(bytes) == RECOVERY_WAV && recoveryDigest(bytes.copyOfRange(44, bytes.size)) == RECOVERY_PCM)
    check(recordingDestructiveIdentityMatches(context, recording))
    val after = observeRecoveryProvider(context, recording)
    check(after.toString() == before.toString()) { "Provider identity/metadata changed during stable read" }
    return JSONObject().put("provider_before", before).put("provider_after", after)
        .put("bytes", bytes.size).put("wav_sha256", RECOVERY_WAV).put("pcm_sha256", RECOVERY_PCM)
}

private fun requireRecoveryUriAbsent(context: Context, uri: Uri) {
    check(uri.toString() in setOf(RECOVERY_URI, "content://media/external/audio/media/9098"))
    checkNotNull(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)).use {
        check(it.count == 0) { "Expected retired fixture is still present or unknown" }
    }
}
