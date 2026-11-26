package app.smallthingz.reverb

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
class NativeTransactionFixtureIsolationTest {
    private lateinit var context: Context
    private lateinit var evidence: File
    private lateinit var provider: CoordinatorMediaProvider
    private val catalog get() = context.getDatabasePath(DATABASE_FILE_NAME)
    private val preferences get() = getRecorderPreferences(context)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun getPackageName(): String = "app.smallthingz.reverb.uitheme.reliability"
            override fun getExternalFilesDir(type: String?): File? = null
        }
        ServiceStateOsShadow.fixtureRoot = checkNotNull(app.filesDir.parentFile).toPath().toAbsolutePath().normalize()
        evidence = context.filesDir.resolve("fixture-evidence")
        check(evidence.mkdir())
        provider = CoordinatorMediaProvider(context.filesDir.resolve("prior-output.wav"))
        provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @After fun tearDown() {
        try {
            if (::provider.isInitialized) provider.closeLeaked()
            RecordingDatabase.resetAfterCorruption()
        } finally {
            ServiceStateOsShadow.fixtureRoot = null
        }
    }

    @Test fun priorPendingOutputRefusesBeforeRecoveryOrAdmissionCanTouchItsPayload() {
        val output = createOutputTarget(context, "prior output.wav", "audio/wav", 1L)
        val bytes = wav()
        val file = provider.output(checkNotNull(output.uri))
        file.writeBytes(bytes)
        val fingerprint = checkNotNull(readStableOutputFingerprint(context, output.storageType, output.id))
        val cleanup = PendingOutputCleanupRecord(output.storageType, output.id, bytes.size.toLong(),
            sha256(bytes), fileKey = null, providerIdentity = checkNotNull(fingerprint.providerIdentity))
        val raw = encodePendingOutputCleanupRecord(cleanup)
        assertEquals(cleanup, decodePendingOutputCleanupRecord(raw))
        assertTrue(pendingOutputCleanupRecordMatchesFingerprint(cleanup, output.storageType, fingerprint))
        assertEquals(0L, rawCatalogCount())
        val key = PrefKey.PENDING_OUTPUT_CLEANUP
        assertTrue(preferences.edit().putStringSet(key, setOf(raw)).commit())
        val opens = provider.openedModes.toList()

        assertRefusesBeforeRecovery(key)
        assertThrows(IllegalStateException::class.java) { fixtures().admit() }

        assertFalse(evidence.resolve("native-fixtures.json").exists())
        assertEquals(setOf(raw), preferences.requireDurableStringSet(key))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(0L, rawCatalogCount())
        assertEquals(opens, provider.openedModes)
        assertTrue(provider.attemptedDeleteUris.isEmpty())
        provider.assertClosed()
    }

    @Test fun refusesEveryJournalAndPreservesMalformedRawPreference() {
        for (key in listOf(PrefKey.PENDING_RECORDING_DELETIONS, PrefKey.PENDING_OUTPUT_CLEANUP,
            PrefKey.VERIFIED_EXPORT_STAGING, PrefKey.FILE_COPY_PUBLICATIONS)) {
            assertTrue(preferences.edit().putStringSet(key, setOf("unparseable prior record")).commit())
            assertRefusesBeforeRecovery(key)
            assertThrows(IllegalStateException::class.java) { fixtures().admit() }
            assertEquals(setOf("unparseable prior record"), preferences.requireDurableStringSet(key))
            assertTrue(preferences.edit().putInt(key.name, 7).commit())
            assertRefusesBeforeRecovery(key)
            assertThrows(IllegalStateException::class.java) { fixtures().admit() }
            assertEquals(7, preferences.getInt(key.name, -1))
            assertTrue(preferences.edit().remove(key.name).commit())
        }
        assertFalse(catalog.exists())
        provider.assertClosed()
    }

    @Test fun missingCatalogRowIsCountedDespiteAnEmptyVisibleLibrary() = runBlocking {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        dao.upsert(providerRow(provider.uri, 44L).copy(missingSinceMillis = 5L))
        val before = dao.listAll()
        assertEquals(1, before.size)
        assertTrue(RecordingRepository.listKnown(context).isEmpty())

        assertThrows(IllegalStateException::class.java) { fixtures().admit() }
        assertThrows(IllegalStateException::class.java) {
            recoverEmptyLibraryFixtureJournal(context, JSONObject()) { throw AssertionError("Recovery started") }
        }

        assertEquals(1L, rawCatalogCount())
        assertEquals(before, dao.listAll())
        assertFalse(evidence.resolve("native-fixtures.json").exists())
        provider.assertClosed()
    }

    @Test fun corruptCatalogAndOrphanWalArePreservedOnRefusal() {
        val bytes = "unavailable catalog bytes".toByteArray()
        check(catalog.parentFile?.isDirectory == true || catalog.parentFile?.mkdirs() == true)
        catalog.writeBytes(bytes)
        assertThrows(RuntimeException::class.java) { fixtures().admit() }
        assertArrayEquals(bytes, catalog.readBytes())
        check(catalog.delete())

        val orphan = File(catalog.path + "-wal").apply { writeBytes(bytes) }
        assertThrows(IllegalStateException::class.java) { fixtures().admit() }
        assertFalse(catalog.exists())
        assertArrayEquals(bytes, orphan.readBytes())
        assertFalse(evidence.resolve("native-fixtures.json").exists())
        provider.assertClosed()
    }

    @Test fun unjournaledPendingMediaStoreRowRefusesAdmission() = assertProviderOnlyOutputRefused(pending = true)

    @Test fun unjournaledPublishedMediaStoreRowRefusesAdmission() = assertProviderOnlyOutputRefused(pending = false)

    @Test fun unavailableOrIncompleteMediaStoreListingRefusesAdmission() {
        val columns = arrayOf(MediaStore.MediaColumns._ID)
        val loading = MatrixCursor(columns).apply {
            extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
        }
        val error = MatrixCursor(columns).apply {
            extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "incomplete fixture listing") }
        }
        for (response in listOf(null, loading, error)) {
            installAdmissionListing { response }
            assertThrows(IllegalStateException::class.java) { fixtures().admit() }
            if (response != null) assertTrue(response.isClosed)
        }
        assertFalse(catalog.exists())
        assertFalse(evidence.resolve("native-fixtures.json").exists())
    }

    @Test fun cleanFixtureRunDeletesOnlyItsOwnedOutputs() {
        provider.allowRetry = true
        val ledger = fixtures()
        ledger.admit()
        val owned = ledger.create()
        ledger.close()

        assertEquals(owned.map { Uri.parse(it.id) }.toSet(), provider.attemptedDeleteUris.toSet())
        assertEquals(0L, rawCatalogCount())
        val receipt = JSONObject(evidence.resolve("native-fixtures.json").readText())
        assertTrue(receipt.getBoolean("cleanup_verified_empty"))
        provider.assertClosed()
    }

    @Test fun unrelatedPendingDeletionAfterCreationPreservesEveryFixtureAndTheJournal() = runBlocking {
        provider.allowRetry = true
        val ledger = fixtures()
        ledger.admit()
        val owned = ledger.create()
        val bytes = wav()
        provider.addSentinel(bytes)
        val unrelated = providerRow(provider.sentinelUri, bytes.size.toLong())
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        dao.upsert(unrelated)
        val before = dao.listAll()
        assertEquals(owned.size + 1, before.size)
        val raw = encodePendingDeletionIntent(PendingDeletionIntent(unrelated.id, bytes.size.toLong(),
            sha256(bytes), assetDeleted = false))
        val key = PrefKey.PENDING_RECORDING_DELETIONS
        assertTrue(preferences.edit().putStringSet(key, setOf(raw)).commit())
        val payloads = (owned + unrelated).associate { recording ->
            val file = provider.output(Uri.parse(recording.id))
            file to file.readBytes()
        }

        assertFalse(deleteOwnedTransactionFixture(context, owned.first()))
        assertThrows(IllegalStateException::class.java) { ledger.close() }

        assertEquals(setOf(raw), preferences.requireDurableStringSet(key))
        assertEquals(before, dao.listAll())
        for ((file, expected) in payloads) assertArrayEquals(expected, file.readBytes())
        assertTrue(provider.attemptedDeleteUris.isEmpty())
        val receipt = JSONObject(evidence.resolve("native-fixtures.json").readText())
        assertTrue(receipt.has("cleanup_error"))
        assertFalse(receipt.optBoolean("cleanup_verified_empty"))
        val entries = receipt.getJSONArray("fixtures")
        for (index in 0 until entries.length()) assertFalse(entries.getJSONObject(index).optBoolean("deleted_by_cleanup"))
        provider.assertClosed()
    }

    private fun assertProviderOnlyOutputRefused(pending: Boolean) {
        val output = createOutputTarget(context, "prior output.wav", "audio/wav", 1L)
        val uri = checkNotNull(output.uri)
        val file = provider.output(uri)
        val bytes = wav()
        file.writeBytes(bytes)
        if (!pending) provider.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "prior output.wav")
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        val originalName = provider.nameOf(uri)
        val originalPending = provider.pendingOf(uri)
        val originalUpdates = provider.updates
        val originalPreferences = preferences.all
        val row = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID)).apply {
            addRow(arrayOf(checkNotNull(uri.lastPathSegment).toLong()))
        }
        installAdmissionListing { row }

        val refusal = assertThrows(IllegalStateException::class.java) { fixtures().admit() }

        assertTrue(refusal.message.orEmpty().contains("MediaStore directory is not empty"))
        assertTrue(row.isClosed)
        assertEquals(1, provider.inserts)
        assertEquals(originalUpdates, provider.updates)
        assertEquals(originalName, provider.nameOf(uri))
        assertEquals(originalPending, provider.pendingOf(uri))
        assertTrue(provider.openedModes.isEmpty())
        assertTrue(provider.attemptedDeleteUris.isEmpty())
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(originalPreferences, preferences.all)
        assertFalse(catalog.exists())
        assertFalse(evidence.resolve("native-fixtures.json").exists())
        provider.assertClosed()
    }

    private fun installAdmissionListing(listing: () -> Cursor?) {
        val stub = object : ContentProvider() {
            override fun onCreate() = true

            override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?,
                cancellationSignal: CancellationSignal?): Cursor? {
                assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, uri)
                assertEquals(listOf(MediaStore.MediaColumns._ID), projection?.toList())
                val args = checkNotNull(queryArgs)
                assertEquals("${MediaStore.MediaColumns.RELATIVE_PATH}=?", args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION))
                assertEquals(listOf("Music/Reverb/"), args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.toList())
                assertEquals(MediaStore.MATCH_INCLUDE, args.getInt(MediaStore.QUERY_ARG_MATCH_PENDING))
                return listing()
            }

            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = unexpectedProviderAccess()
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = unexpectedProviderAccess()
            override fun getType(uri: Uri): String = unexpectedProviderAccess()
            override fun insert(uri: Uri, values: ContentValues?): Uri = unexpectedProviderAccess()
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
                unexpectedProviderAccess()
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unexpectedProviderAccess()
        }
        stub.attachInfo(context.applicationContext, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", stub)
    }

    private fun unexpectedProviderAccess(): Nothing = throw AssertionError("Admission must only list the QA MediaStore directory")

    private fun assertRefusesBeforeRecovery(key: PrefKey) {
        val result = JSONObject()
        var published = false
        val error = assertThrows(IllegalStateException::class.java) {
            recoverEmptyLibraryFixtureJournal(context, result) { published = true }
        }
        assertTrue(error.message.orEmpty().contains(key.name))
        assertFalse(published)
        assertFalse(result.has("save_recovery"))
    }

    private fun fixtures() = TransactionFixtures(context, evidence, "native", currentUi = true)

    private fun rawCatalogCount() = DatabaseUtils.queryNumEntries(
        RecordingDatabase.getInstance(context).readableDatabase, RecordingDatabase.TABLE_RECORDINGS,
    )

    private fun providerRow(uri: Uri, sizeBytes: Long) = RecordingEntity(
        uri.toString(), "prior record.wav", "audio/wav", 1L, 100L, sizeBytes, "PCM",
        RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID,
        resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, uri),
    )

    private fun wav(): ByteArray {
        val pcm = ByteArray(1_600) { (it * 17).toByte() }
        return buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
