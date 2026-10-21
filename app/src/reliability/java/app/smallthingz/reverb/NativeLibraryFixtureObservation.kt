package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.Manifest
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Read-only observation of the four outputs from the exact failed18 fixture; never recovery/deletion. */
internal fun observeNativeLibraryMigratedFixtures(instrumentation: Instrumentation, report: StringBuilder, nonce: String?) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java && Build.VERSION.SDK_INT >= 30)
    check(context.applicationInfo.uid == Process.myUid())
    check(nonce != null && nonce.matches(Regex("[a-f0-9]{32,64}"))) { "An explicit operator session nonce is required" }
    val directory = File(context.filesDir, "native-library-fixture-observation-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val result = JSONObject().put("observations_verified", false).put("cleanup_authorized", false)
        .put("package", context.packageName).put("uid", Process.myUid())
        .put("session_nonce", nonce)
        .put("catalog_read", "existing own QA SQLite OPEN_READONLY; no repository refresh/recovery")
        .put("host_new_membership_receipt_sha256", "0aca023ad3f7f202dd003253992848de55b571b261336a122c2fd8a68cf050db")
        .put("host_new_membership_verification", "separate retained host evidence; not re-observed by this probe")
        .put("source_to_target_mapping", "not persisted by the ordinary migration; no cleanup authority inferred")
        .put("observations", JSONArray())
    var failure: Throwable? = null
    try {
        publishFixtureObservation(directory, result)
        result.put("qa_environment", fixtureEnvironment(context))
        val ledger = File(context.filesDir, "native-library-parity-1792613742280/fixtures.json")
        val ledgerBytes = ledger.readBytes()
        check(fixtureHex(MessageDigest.getInstance("SHA-256").digest(ledgerBytes)) ==
            "68ad8e3496b67419881ce4fb1eebb2f1f0dcf7e13eef50a602c2e2f64254015f") {
            "Original fixture ledger is missing or changed; preserve all outputs"
        }
        val original = JSONObject(String(ledgerBytes, Charsets.UTF_8))
        check(original.getString("package") == context.packageName && original.getBoolean("baseline_empty"))
        val expected = original.getJSONArray("fixtures")
        check(expected.length() == 4)
        val records = readExistingFixtureCatalog(context)
        result.put("catalog_count", records.size)
        check(records.size == 4 && records.map { it.displayName }.toSet() ==
            (1..4).map { "Library parity ${it}.wav" }.toSet()) { "QA catalog membership changed" }
        for (index in 0 until expected.length()) {
            val item = expected.getJSONObject(index)
            check(item.getBoolean("complete"))
            val name = "Library parity ${index + 1}.wav"
            check(File(item.getString("path")).name == name)
            val recording = records.single { it.displayName == name }
            check(recording.storageType == RecordingStorageType.MEDIASTORE &&
                recordingCatalogLocationIsValid(recording.storageType, recording.id, recording.directoryId))
            check(recording.fileIdentity.isNotBlank() && recording.sizeBytes == item.getLong("bytes"))
            val observed = JSONObject().put("id", recording.id).put("display_name", name)
                .put("catalog_identity", recording.fileIdentity).put("catalog_bytes", recording.sizeBytes)
            result.getJSONArray("observations").put(observed)
            val before = observeFixtureMediaRow(context, recording)
            observed.put("provider_before", before)
            requireFixtureMediaRow(context, recording, name, before)
            val postcondition = recordingCatalogPostconditionObservation(context, recording)
            check(postcondition.identityMatches && postcondition.displayNameMatches)
            // Existing read helper pins the provider identity before/after descriptor acquisition
            // and after the full read; this never substitutes a same-name URI.
            val digest = checkNotNull(sha256StableRecording(context, recording)) { "Provider read identity changed" }
            val hash = fixtureHex(digest.sha256)
            observed.put("bytes_read", digest.byteCount).put("wav_sha256", hash)
            check(digest.byteCount == item.getLong("bytes") && hash == item.getString("wav_sha256"))
            val after = observeFixtureMediaRow(context, recording)
            observed.put("provider_after", after)
            requireFixtureMediaRow(context, recording, name, after)
            check(before.toString() == after.toString()) { "Provider metadata changed during read" }
            observed.put("current_owner_identity_and_content_verified", true)
        }
        check(readExistingFixtureCatalog(context) == records) { "QA catalog changed during observation" }
        check(fixtureEnvironment(context).toString() == result.getJSONObject("qa_environment").toString())
        result.put("observations_verified", true)
    } catch (error: Throwable) {
        failure = error
        result.put("failure", error.stackTraceToString())
    } finally {
        var published = false
        try {
            publishFixtureObservation(directory, result)
            published = true
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
        report.append(if (published) "PARITY_EVIDENCE " else "PARITY_EVIDENCE_WRITE_FAILED ")
            .append(directory.name).append("/result.json\n")
    }
    failure?.let { throw it }
    report.append("PASS native_library_fixture_observation read-only current ownership/content observations; cleanup remains unauthorized\n")
}

private fun publishFixtureObservation(directory: File, result: JSONObject) {
    val pending = File(directory, "result.pending.json")
    pending.writeText(result.toString(2))
    check(pending.renameTo(File(directory, "result.json"))) { "Cannot publish fixture observation" }
}

private fun readExistingFixtureCatalog(context: Context): List<RecordingEntity> {
    val file = context.getDatabasePath(DATABASE_FILE_NAME)
    check(file.isFile) { "Existing QA catalog unavailable; do not create it" }
    val corruption = DatabaseErrorHandler {
        // Android's default corruption handler can delete even a read-only opened database.
        throw SQLiteDatabaseCorruptException("Read-only QA catalog is corrupt; preserve it")
    }
    return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, corruption).use { database ->
        check(database.isReadOnly && database.version == RecordingDatabase.DATABASE_VERSION)
        database.query(RecordingDatabase.TABLE_RECORDINGS, arrayOf(
            RecordingDatabase.COLUMN_ID, RecordingDatabase.COLUMN_DISPLAY_NAME,
            RecordingDatabase.COLUMN_MIME_TYPE, RecordingDatabase.COLUMN_STARTED_AT_MILLIS,
            RecordingDatabase.COLUMN_DURATION_MILLIS, RecordingDatabase.COLUMN_SIZE_BYTES,
            RecordingDatabase.COLUMN_CODEC_SUMMARY, RecordingDatabase.COLUMN_STORAGE_TYPE_CODE,
            RecordingDatabase.COLUMN_DIRECTORY_ID, RecordingDatabase.COLUMN_FILE_IDENTITY,
        ), null, null, null, null, RecordingDatabase.COLUMN_ID, "5").use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    fun text(index: Int): String {
                        check(rows.getType(index) == Cursor.FIELD_TYPE_STRING)
                        return rows.getString(index).also { check(it.length <= 4096) }
                    }
                    fun number(index: Int): Long {
                        check(rows.getType(index) == Cursor.FIELD_TYPE_INTEGER)
                        return rows.getLong(index)
                    }
                    check(number(7) == RecordingStorageType.MEDIASTORE.storageCode.toLong())
                    add(RecordingEntity(text(0), text(1), text(2), number(3), number(4), number(5),
                        text(6), RecordingStorageType.MEDIASTORE, text(8), text(9), createdAtMillis = 0L,
                        lastSeenAtMillis = 0L))
                }
            }
        }
    }
}

private fun observeFixtureMediaRow(context: Context, recording: RecordingEntity): JSONObject {
    val uri = Uri.parse(recording.id)
    check(recordingStorageIdIsValid(RecordingStorageType.MEDIASTORE, uri.toString()))
    val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_ADDED,
        MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.VOLUME_NAME)
    return checkNotNull(context.contentResolver.query(uri, columns, null, null, null)).use { rows ->
        check(rows.count == 1 && rows.moveToFirst())
        val observed = JSONObject()
        for (column in columns) {
            val index = rows.getColumnIndexOrThrow(column)
            check(!rows.isNull(index)) { "Unknown provider field $column" }
            when (rows.getType(index)) {
                Cursor.FIELD_TYPE_STRING -> observed.put(column, rows.getString(index))
                Cursor.FIELD_TYPE_INTEGER -> observed.put(column, rows.getLong(index))
                else -> error("Unexpected provider field type $column")
            }
        }
        val identity = resolveProviderRecordingIdentity(context, recording.storageType, uri, recording.fileIdentity)
        observed.put("observed_identity", identity)
    }
}

private fun requireFixtureMediaRow(context: Context, recording: RecordingEntity, name: String, observed: JSONObject) {
    check(observed.getLong(MediaStore.MediaColumns._ID).toString() == Uri.parse(recording.id).lastPathSegment)
    check(observed.getString(MediaStore.MediaColumns.DISPLAY_NAME) == name)
    check(observed.getString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) == context.packageName)
    check(observed.getString(MediaStore.MediaColumns.RELATIVE_PATH) == "Music/Reverb/")
    check(observed.getLong(MediaStore.MediaColumns.IS_PENDING) == 0L)
    check(observed.getLong(MediaStore.MediaColumns.SIZE) == recording.sizeBytes)
    // Exact retained fixture execution, 2026-10-21T20:15:42Z..20:16:02Z.
    check(observed.getLong(MediaStore.MediaColumns.DATE_ADDED) in 1_792_613_742L..1_792_613_762L)
    check(observed.getLong(MediaStore.MediaColumns.GENERATION_ADDED) > 0L)
    check(observed.getLong(MediaStore.MediaColumns.GENERATION_MODIFIED) > 0L)
    check(providerRecordingIdentityMatches(recording.fileIdentity, observed.getString("observed_identity")))
}

private fun fixtureHex(value: ByteArray): String =
    value.joinToString("") { "%02x".format(it.toInt() and 255) }

private fun fixtureEnvironment(context: Context): JSONObject {
    val preferences = JSONObject()
    val stored = getRecorderPreferences(context)
    // Repository.delete replays this journal first. Never admit unrelated pending work.
    check(stored.requireDurableStringSet(PrefKey.PENDING_RECORDING_DELETIONS).isEmpty()) {
        "Pending deletion work exists or is unknown; preserve all fixtures"
    }
    for ((key, value) in stored.all.toSortedMap()) {
        // Successful deletion may remove an explicitly empty journal key. This is the only
        // absent/empty normalization; all user settings and every other key remain exact.
        if (key == PrefKey.PENDING_RECORDING_DELETIONS.name) continue
        val typed = JSONObject().put("type", value?.javaClass?.name ?: "null")
        typed.put("value", if (value is Set<*>) JSONArray(value.map {
            check(it is String); it
        }.sorted()) else value ?: JSONObject.NULL)
        preferences.put(key, typed)
    }
    val permissions = JSONObject()
    for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
        permissions.put(permission, context.checkSelfPermission(permission))
    }
    val grants = context.contentResolver.persistedUriPermissions.map {
        "${it.uri}|${it.isReadPermission}|${it.isWritePermission}"
    }.sorted()
    return JSONObject().put("preferences", preferences).put("permissions", permissions)
        .put("pending_recording_deletions", JSONArray())
        .put("persisted_uri_grants", JSONArray(grants))
        .put("ui_mode", context.resources.configuration.uiMode)
        .put("font_scale", context.resources.configuration.fontScale.toDouble())
}

/** Prepared separately from observation; the sole operator must explicitly bind the reviewed receipt. */
internal fun cleanupNativeLibraryMigratedFixtures(
    instrumentation: Instrumentation,
    report: StringBuilder,
    nonce: String?,
    observationDirectory: String?,
    observationSha256: String?,
) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java && Build.VERSION.SDK_INT >= 30)
    check(context.applicationInfo.uid == Process.myUid() && recordingMutations.activeIds.value.isEmpty())
    check(nonce != null && nonce.matches(Regex("[a-f0-9]{32,64}")))
    check(observationDirectory != null && observationDirectory.matches(Regex("native-library-fixture-observation-[0-9]{13}")))
    check(observationSha256 != null && observationSha256.matches(Regex("[a-f0-9]{64}")))
    val acceptedFile = File(File(context.filesDir, observationDirectory), "result.json")
    val acceptedBytes = acceptedFile.readBytes()
    check(fixtureHex(MessageDigest.getInstance("SHA-256").digest(acceptedBytes)) == observationSha256)
    val accepted = JSONObject(String(acceptedBytes, Charsets.UTF_8))
    check(accepted.getBoolean("observations_verified") && !accepted.getBoolean("cleanup_authorized"))
    check(accepted.getString("session_nonce") == nonce && accepted.getString("package") == context.packageName &&
        accepted.getInt("uid") == Process.myUid())
    val environment = accepted.getJSONObject("qa_environment").toString()
    check(fixtureEnvironment(context).toString() == environment) { "QA environment changed; preserve fixtures" }
    val directory = File(context.filesDir, "native-library-fixture-cleanup-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val deleted = JSONArray()
    val attempts = JSONArray()
    val result = JSONObject().put("cleanup_successful", false).put("package", context.packageName)
        .put("uid", Process.myUid()).put("session_nonce", nonce)
        .put("accepted_observation_sha256", observationSha256).put("deleted", deleted).put("attempts", attempts)
    var failure: Throwable? = null
    try {
        publishFixtureObservation(directory, result)
        val ledger = File(context.filesDir, "native-library-parity-1792613742280/fixtures.json").readBytes()
        check(fixtureHex(MessageDigest.getInstance("SHA-256").digest(ledger)) ==
            "68ad8e3496b67419881ce4fb1eebb2f1f0dcf7e13eef50a602c2e2f64254015f")
        val expected = JSONObject(String(ledger, Charsets.UTF_8)).getJSONArray("fixtures")
        val observations = accepted.getJSONArray("observations")
        check(expected.length() == 4 && observations.length() == 4)
        val records = readExistingFixtureCatalog(context)
        check(records.size == 4 && records.map { it.displayName }.toSet() ==
            (1..4).map { "Library parity ${it}.wav" }.toSet())
        val remaining = records.toMutableList()
        fun verify(recording: RecordingEntity) {
            check(fixtureEnvironment(context).toString() == environment)
            check(readExistingFixtureCatalog(context) == remaining)
            check(recordingMutations.activeIds.value.isEmpty())
            val matches = (0 until observations.length()).map(observations::getJSONObject)
                .filter { it.getString("id") == recording.id }
            check(matches.size == 1)
            val observed = matches.single()
            check(observed.getBoolean("current_owner_identity_and_content_verified"))
            check(observed.getString("display_name") == recording.displayName &&
                observed.getString("catalog_identity") == recording.fileIdentity &&
                observed.getLong("catalog_bytes") == recording.sizeBytes)
            val original = (0 until expected.length()).map(expected::getJSONObject).single {
                File(it.getString("path")).name == recording.displayName
            }
            check(original.getBoolean("complete") && original.getLong("bytes") == recording.sizeBytes)
            val before = observeFixtureMediaRow(context, recording)
            requireFixtureMediaRow(context, recording, recording.displayName, before)
            check(before.toString() == observed.getJSONObject("provider_after").toString())
            val postcondition = recordingCatalogPostconditionObservation(context, recording)
            check(postcondition.identityMatches && postcondition.displayNameMatches)
            val digest = checkNotNull(sha256StableRecording(context, recording))
            check(digest.byteCount == original.getLong("bytes") &&
                fixtureHex(digest.sha256) == original.getString("wav_sha256"))
            val after = observeFixtureMediaRow(context, recording)
            requireFixtureMediaRow(context, recording, recording.displayName, after)
            check(before.toString() == after.toString())
        }
        records.forEach(::verify) // Validate all four before the first destructive operation.
        for (recording in records) {
            verify(recording)
            val attempt = JSONObject().put("id", recording.id).put("identity", recording.fileIdentity)
                .put("outcome", "UNKNOWN until terminal observations")
            attempts.put(attempt)
            publishFixtureObservation(directory, result) // Bind a possibly committed operation before dispatch.
            val removed = runBlocking { withTimeout(15_000L) { RecordingRepository.delete(context, recording) } }
            attempt.put("delete_returned", removed)
            check(removed)
            val state = recordingAssetState(context, recording)
            attempt.put("observed_asset_state", state.name)
            check(state == RecordingAssetState.MISSING)
            check(fixtureEnvironment(context).toString() == environment)
            remaining.remove(recording)
            check(readExistingFixtureCatalog(context) == remaining)
            attempt.put("outcome", "verified absent and catalog retired")
            deleted.put(JSONObject().put("id", recording.id).put("identity", recording.fileIdentity))
            publishFixtureObservation(directory, result)
        }
        check(fixtureEnvironment(context).toString() == environment)
        result.put("cleanup_successful", true)
    } catch (error: Throwable) {
        failure = error
        result.put("failure", error.stackTraceToString())
    } finally {
        var published = false
        try { publishFixtureObservation(directory, result); published = true } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
        report.append(if (published) "PARITY_EVIDENCE " else "PARITY_EVIDENCE_WRITE_FAILED ")
            .append(directory.name).append("/result.json\n")
    }
    failure?.let { throw it }
    report.append("PASS native_library_fixture_cleanup exact reviewed four QA outputs removed\n")
}
