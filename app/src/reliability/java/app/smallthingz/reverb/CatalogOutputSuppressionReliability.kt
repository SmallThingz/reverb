package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Actual repository/SQLite/provider calls; dedicated synthetic package and explicit selector only. */
internal fun verifyCatalogOutputSuppression(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
): Unit = runBlocking {
    check(context.packageName.endsWith(".reliability"))
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    check(recordingMutations.activeIds.value.isEmpty())
    val preferences = getRecorderPreferences(context)
    val dao = RecordingDatabase.getInstance(context).recordingDao()
    // Full refresh can replay/migrate old state. Refuse existing mutation-bearing fixtures.
    check(dao.listAll().isEmpty()) { "Use a QA catalog with no existing rows" }
    check(provider.retainedBytes().isEmpty()) { "Prior provider evidence must be preserved" }
    val providerDirectory = File(context.filesDir, "reliability-documents")
    check(requireNotNull(providerDirectory.listFiles()).isEmpty()) { "Unindexed provider evidence must be preserved" }
    check(context.contentResolver.persistedUriPermissions.all { it.uri == tree })
    for (key in listOf(PrefKey.PENDING_RECORDING_DELETIONS, PrefKey.PENDING_OUTPUT_CLEANUP,
        PrefKey.VERIFIED_EXPORT_STAGING)) {
        check(preferences.requireDurableStringSet(key).isEmpty()) { "Existing journal must be preserved: $key" }
    }
    val cleanupBefore = preferences.snapshotDurablePreferenceValue(PrefKey.PENDING_OUTPUT_CLEANUP)
    val directoryBefore = preferences.snapshotDurablePreferenceValue(PrefKey.EXPORT_DIRECTORY_URI)
    check(durablePreferenceSnapshotIsExactlyRestorable(cleanupBefore))
    check(durablePreferenceSnapshotIsExactlyRestorable(directoryBefore))
    val legacyDirectory = getSavedRecordingsDirectory(context)
    if (legacyDirectory.exists()) {
        check(requireNotNull(legacyDirectory.listFiles()).isEmpty()) { "Existing legacy files must be preserved" }
    }
    // Published shared rows may be read into this QA catalog, but no pre-existing pending
    // MediaStore row may enter recovery during the real default-directory refresh below.
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}!=0",
        arrayOf("${Environment.DIRECTORY_MUSIC}/${APP_STORAGE_FOLDER_NAME}/"), null,
    ).let { cursor ->
        requireNotNull(cursor).use { check(!it.moveToFirst()) { "Existing pending MediaStore output must be preserved" } }
    }

    val token = UUID.randomUUID().toString()
    val file = File(legacyDirectory, "catalog-suppression-$token.wav")
    val pcm = ByteArray(3_200) { (it * 17 + 11).toByte() }
    val bytes = buildWavHeaderBytes(16_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
    var failure: Throwable? = null
    var ownedFileIdentity: String? = null
    try {
        provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
        ensureRecordingDirectoryDurable(legacyDirectory)
        check(file.createNewFile())
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
        forceRecordingDirectoryDurable(legacyDirectory)
        fun document(name: String): Uri {
            val id = provider.createDocument(ReliabilityDocumentsProvider.ROOT_ID, "audio/wav", name)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "w"))
            val stream = try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            } catch (error: Throwable) {
                throw requireNotNull(closePreservingPrimaryFailure(error) { descriptor.close() })
            }
            stream.use { output -> output.write(bytes); output.flush(); output.fd.sync() }
            return uri
        }
        val suppressedDocument = document("catalog-suppression-$token.wav")
        val controlDocument = document("catalog-control-$token.wav")
        val fileRow = listLegacyAppStorageRecordings(context).single { it.id == file.absolutePath }
        val documentRows = listOutputDirectoryRecordings(context, tree)
        val documentRow = documentRows.single { it.id == suppressedDocument.toString() }
        val controlRow = documentRows.single { it.id == controlDocument.toString() }
        check(fileRow.fileIdentity.isNotBlank() && documentRow.fileIdentity.isNotBlank())
        ownedFileIdentity = fileRow.fileIdentity
        dao.applyChanges(listOf(fileRow, documentRow, controlRow), emptyList())
        val fileFingerprint = requireNotNull(readStableOutputFingerprint(context, fileRow.storageType, fileRow.id))
        val documentFingerprint = requireNotNull(readStableOutputFingerprint(context, documentRow.storageType, documentRow.id))
        check(suppressFileOutputWithoutDeletion(context, fileRow.id, fileFingerprint.digest))
        check(suppressProviderOutputWithoutDeletion(context, documentRow.storageType, documentRow.id, documentFingerprint.digest))
        val suppressedIds = setOf(fileRow.id, documentRow.id)
        val suppressionEntries = preferences.requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP)
        check(suppressionEntries.size == 2)
        check(suppressionEntries.all { raw ->
            val record = requireNotNull(decodePendingOutputCleanupRecord(raw))
            record.fileKey == null && record.providerIdentity == null
        })
        check(preferences.edit().putString(PrefKey.EXPORT_DIRECTORY_URI, tree.toString()).commit())

        fun assertBytesRetained() {
            check(file.readBytes().contentEquals(bytes))
            check(fileIdentityMatches(fileRow.fileIdentity, resolveFileIdentity(file)))
            check(requireNotNull(providerDirectory.listFiles()).map { it.name }.toSet() ==
                setOf("catalog-suppression-$token.wav", "catalog-control-$token.wav"))
            check(provider.retainedBytes().size == 2)
            check(provider.retainedBytes().all { it.contentEquals(bytes) })
            check(provider.deleteCalls == 0)
        }
        suspend fun assertCatalogRetained() {
            for (expected in listOf(fileRow, documentRow)) {
                val stored = requireNotNull(dao.findById(expected.id))
                check(stored.fileIdentity == expected.fileIdentity && stored.missingSinceMillis == null)
            }
            check(pendingOutputCleanupIds(context) == suppressedIds)
            assertBytesRetained()
        }
        fun assertVisible(rows: List<RecordingEntity>) {
            check(rows.none { it.id in suppressedIds }) { "Suppressed catalog row became visible" }
            check(rows.any { it.id == controlRow.id }) { "Unrelated recording was hidden" }
        }
        assertVisible(RecordingRepository.listKnown(context))
        check(!RecordingRepository.hasMovableKnownRecordings(context, tree.toString()))
        val moved = RecordingRepository.moveAllToDirectory(context, tree)
        check(moved == RecordingRepository.MoveResult(skipped = 3)) { "Suppressed rows entered Move: $moved" }
        assertCatalogRetained()
        assertVisible(RecordingRepository.refresh(context))
        assertCatalogRetained()

        // Durable-read uncertainty must escape every public entry point; it must never
        // become an empty suppression set or grant movement of these exact existing rows.
        check(preferences.edit().putInt(PrefKey.PENDING_OUTPUT_CLEANUP, 7).commit())
        suspend fun requireFailure(label: String, action: suspend () -> Unit) {
            var rejected = false
            try { action() } catch (_: Exception) { rejected = true }
            check(rejected) { "$label accepted an unreadable suppression journal" }
        }
        requireFailure("listKnown") { RecordingRepository.listKnown(context) }
        requireFailure("hasMovableKnownRecordings") { RecordingRepository.hasMovableKnownRecordings(context, tree.toString()) }
        requireFailure("moveAllToDirectory") { RecordingRepository.moveAllToDirectory(context, tree) }
        requireFailure("refresh") { RecordingRepository.refresh(context) }
        assertBytesRetained()
        check(preferences.edit().putStringSet(PrefKey.PENDING_OUTPUT_CLEANUP, suppressionEntries).commit())

        // Exercise actual automatic legacy migration, not just its eligibility predicate.
        // Only our suppressed FILE existed in the legacy directory at admission.
        check(preferences.edit().remove(PrefKey.EXPORT_DIRECTORY_URI).commit())
        val defaultRows = RecordingRepository.refresh(context)
        check(defaultRows.none { it.id in suppressedIds })
        check(defaultRows.none { it.displayName == fileRow.displayName }) { "Suppressed legacy output migrated" }
        assertCatalogRetained()
    } catch (error: Throwable) {
        failure = error
    } finally {
        // Failure evidence owns the synthetic bytes, rows and journals. Do not reset them,
        // and do not continue cleanup after its first failure. Only successful QA is restored.
        provider.afterNextListing = null
        if (failure == null) {
            failure = captureNativeParityCleanup(null) {
                check(preferences.edit()
                    .restoreDurablePreferenceValue(PrefKey.PENDING_OUTPUT_CLEANUP, cleanupBefore)
                    .restoreDurablePreferenceValue(PrefKey.EXPORT_DIRECTORY_URI, directoryBefore)
                    .commit())
            }
            if (failure == null) {
                try {
                    // The initial QA catalog was empty; these are only rows read/seeded above.
                    dao.applyChanges(emptyList(), dao.listAll().map { it.id })
                } catch (error: Throwable) {
                    failure = error
                }
            }
            if (failure == null) failure = captureNativeParityCleanup(null) {
                check(file.readBytes().contentEquals(bytes))
                check(fileIdentityMatches(requireNotNull(ownedFileIdentity), resolveFileIdentity(file)))
                check(file.delete())
                forceRecordingDirectoryDurable(legacyDirectory)
            }
            if (failure == null) failure = captureNativeParityCleanup(null) {
                provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
            }
        }
    }
    failure?.let { throw it }
}
