package app.smallthingz.reverb

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.DocumentsContract
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Host boundary test; the row is supplied by a test provider, not a real MediaStore. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaStoreCatalogObservationTest {
    private val uri = Uri.parse("content://media/external/audio/media/41")
    private val columns = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING,
    )
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun registerProvider() {
        CatalogRowProvider.reset()
        val provider = CatalogRowProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @After fun clearProvider() {
        CatalogRowProvider.reset()
    }

    private fun row(name: String? = "clip.wav", size: Long? = 120L, modified: Long? = 100L,
                    generation: Long? = 9L, path: String? = "Music/Reverb/", pending: Int? = 0) =
        arrayOf<Any?>(name, size, modified, generation, path, pending)

    private fun observed() = resolveProviderCatalogObservation(app, RecordingStorageType.MEDIASTORE, uri)

    private fun selection(): RecordingEntity {
        val identity = checkNotNull(observed()).identity
        assertTrue(identity.isNotBlank())
        return RecordingEntity(uri.toString(), "clip.wav", "audio/wav", 1L, 1L, 120L, "WAV",
            RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID, identity)
    }

    private fun checkSelection(entity: RecordingEntity, identity: Boolean, name: Boolean) {
        CatalogRowProvider.calls = 0
        val result = recordingCatalogPostconditionObservation(app, entity)
        assertEquals(identity, result.identityMatches)
        assertEquals(name, result.displayNameMatches)
        assertEquals("each postcondition must come from one query", 1, CatalogRowProvider.calls)
    }

    private fun assertOwnedDatabase(db: RecordingDatabase) {
        val expected = app.getDatabasePath(checkNotNull(db.databaseName)).canonicalFile
        val appData = checkNotNull(app.filesDir.parentFile).canonicalFile
        assertTrue("database must be inside this test application's data directory",
            expected.path.startsWith(appData.path + File.separator))
        assertEquals("singleton must use this test application's database", expected,
            File(db.writableDatabase.path).canonicalFile)
    }

    private suspend fun cleanupRegistration(primary: Throwable?, steps: List<suspend () -> Unit>) {
        var failure = primary
        for (step in steps) {
            try { step() } catch (error: Throwable) {
                if (failure == null) failure = error
                else if (failure !== error) failure.addSuppressed(error)
            }
        }
        if (primary == null && failure != null) throw failure
    }

    @Test fun negativeCatalogSizeCannotMatchKnownEmptySelection() {
        CatalogRowProvider.values = row(size = 0L)
        val selected = selection().copy(sizeBytes = 0L)
        checkSelection(selected, identity = true, name = true)
        CatalogRowProvider.values = row(size = 1L)
        checkSelection(selected, identity = false, name = true)
        for (size in listOf(null, -1L, Long.MIN_VALUE)) {
            CatalogRowProvider.values = row(size = size)
            CatalogRowProvider.calls = 0
            val result = recordingCatalogPostconditionObservation(app, selected)
            assertFalse("reported size $size cannot prove the selected zero-byte revision", result.identityMatches)
            assertEquals(1, CatalogRowProvider.calls)
            assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        }
    }

    @Test fun negativeReadIdentitySizeIsUnavailableWithGenerationOrDateRevision() {
        for (generation in listOf(9L, null)) {
            CatalogRowProvider.values = row(size = 0L, generation = generation)
            val emptyIdentity = resolveProviderRecordingIdentity(app, RecordingStorageType.MEDIASTORE, uri)
            assertTrue("known empty row must retain identity", emptyIdentity.isNotBlank())
            CatalogRowProvider.values = row(size = 1L, generation = generation)
            val nonemptyIdentity = resolveProviderRecordingIdentity(app, RecordingStorageType.MEDIASTORE, uri)
            assertTrue(nonemptyIdentity.isNotBlank())
            assertNotEquals(emptyIdentity, nonemptyIdentity)
            for (size in listOf(null, -1L, Long.MIN_VALUE)) {
                CatalogRowProvider.values = row(size = size, generation = generation)
                CatalogRowProvider.calls = 0
                assertEquals("reported size $size cannot establish read authority", "",
                    resolveProviderRecordingIdentity(app, RecordingStorageType.MEDIASTORE, uri))
                assertEquals(1, CatalogRowProvider.calls)
                assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
            }
        }
    }

    @Test fun createdMediaStoreRowCannotPresentNegativeSizeAsKnownEmpty() {
        val query = Class.forName("app.smallthingz.reverb.RecordingFilesKt")
            .getDeclaredMethod("queryCreatedMediaStoreOutput", Context::class.java, Uri::class.java)
            .apply { isAccessible = true }
        for ((size, writable) in listOf(0L to true, 1L to false, null to false,
            -1L to false, Long.MIN_VALUE to false)) {
            CatalogRowProvider.values = row(size = size, pending = 1)
            CatalogRowProvider.calls = 0
            val observation = query.invoke(null, app, uri) as NewlyCreatedOutputObservation?
            assertEquals("created row size $size", writable,
                newlyCreatedOutputMayBeWritten("clip.wav", requirePending = true, observation))
            assertEquals(1, CatalogRowProvider.calls)
            assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        }
    }

    @Test fun documentListingDoesNotCertifyNegativeMetadataAsKnownSize() {
        val provider = CatalogRowProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "test.documents" })
        ShadowContentResolver.registerProviderInternal("test.documents", provider)
        val tree = Uri.parse("content://test.documents/tree/root")
        val query = Class.forName("app.smallthingz.reverb.RecordingFilesKt")
            .getDeclaredMethod("queryDocumentTreeEntries", Context::class.java, Uri::class.java)
            .apply { isAccessible = true }
        for ((size, known) in listOf(0L to true, 1L to true, null to false,
            -1L to false, Long.MIN_VALUE to false)) {
            CatalogRowProvider.values = row(size = size)
            CatalogRowProvider.calls = 0
            val entry = requireNotNull((query.invoke(null, app, tree) as List<*>).single())
            val observedKnown = entry.javaClass.getDeclaredField("sizeKnown")
                .apply { isAccessible = true }.getBoolean(entry)
            assertEquals("listed document size $size", known, observedKnown)
            assertEquals(1, CatalogRowProvider.calls)
            assertEquals(DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root"),
                CatalogRowProvider.lastUri)
            assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        }
    }

    @Test fun collectionRequiresFreshValidationWhenReportedSizeBecomesNegative() {
        val list = Class.forName("app.smallthingz.reverb.RecordingFilesKt")
            .getDeclaredMethod("listMediaStoreRecordings", Context::class.java,
                Map::class.java, Set::class.java).apply { isAccessible = true }
        CatalogRowProvider.values = row(size = 0L)
        // Synthetic cache-contract input: positive duration and known zero size.
        // This is not evidence that a zero-byte WAV contains playable audio.
        val known = selection().copy(sizeBytes = 0L)
        val cache = mapOf(known.id to known)
        CatalogRowProvider.calls = 0
        val reused = list.invoke(null, app, cache, emptySet<String>()) as List<*>
        assertEquals(1, reused.size)
        assertSame(known, reused.single())
        assertEquals(1, CatalogRowProvider.calls)
        assertEquals(0, CatalogRowProvider.openCalls)
        assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        for (size in listOf(-1L, Long.MIN_VALUE)) {
            CatalogRowProvider.values = row(size = size)
            CatalogRowProvider.calls = 0
            CatalogRowProvider.openCalls = 0
            val failure = try {
                list.invoke(null, app, cache, emptySet<String>())
                fail("negative-size collection row reused cached authority or hid read failure")
                null
            } catch (error: InvocationTargetException) { error.targetException }
            assertTrue(failure is IOException)
            assertEquals("Unable to validate discovered MediaStore recording $uri", failure!!.message)
            assertTrue(failure.cause is FileNotFoundException)
            assertEquals("injected descriptor access failure", failure.cause!!.message)
            assertEquals(1, CatalogRowProvider.calls)
            assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, CatalogRowProvider.lastUri)
            assertEquals(1, CatalogRowProvider.openCalls)
            assertEquals(uri, CatalogRowProvider.lastOpenUri)
            assertEquals("r", CatalogRowProvider.lastOpenMode)
            assertEquals(0, CatalogRowProvider.mutationCalls)
            assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
            assertSame(known, cache[known.id])
        }
    }

    @Test
    @Config(shadows = [PlaybackSessionOsShadow::class])
    fun createdDocumentWithNegativeMetadataRequiresVerifiedDescriptorSize() {
        val provider = CatalogRowProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "test.documents" })
        ShadowContentResolver.registerProviderInternal("test.documents", provider)
        val tree = Uri.parse("content://test.documents/tree/root")
        val document = Uri.parse("content://test.documents/tree/root/document/clip")
        val query = Class.forName("app.smallthingz.reverb.RecordingFilesKt")
            .getDeclaredMethod("queryCreatedDocumentOutput", Context::class.java,
                Uri::class.java, Uri::class.java).apply { isAccessible = true }
        fun created() = query.invoke(null, app, tree, document) as NewlyCreatedOutputObservation
        fun writable(observation: NewlyCreatedOutputObservation) =
            newlyCreatedOutputMayBeWritten("clip.wav", requirePending = false, observation)
        // This shared shadow can outlive another test in Robolectric's reused sandbox.
        val previousShadowPath = PlaybackSessionOsShadow.targetPath
        val source = Files.createTempFile(app.cacheDir.toPath(), "document-identity-", ".bin").toFile()
        var bodyFailure: Throwable? = null
        try {
            source.writeBytes(byteArrayOf(1, 2, 3, 4))
            PlaybackSessionOsShadow.targetPath = source.absolutePath
            CatalogRowProvider.descriptorSource = source
            CatalogRowProvider.values = row(size = 4L)
            val metadata = created()
            assertTrue(metadata.sizeKnown)
            assertEquals(4L, metadata.sizeBytes)
            assertFalse(writable(metadata))
            assertEquals(1, CatalogRowProvider.calls)
            assertEquals(0, CatalogRowProvider.openCalls)
            for (size in listOf(null, -1L, Long.MIN_VALUE)) {
                CatalogRowProvider.values = row(size = size)
                CatalogRowProvider.calls = 0
                CatalogRowProvider.openCalls = 0
                val observation = created()
                assertEquals("clip.wav", observation.displayName)
                assertTrue("descriptor provides known size for reported $size", observation.sizeKnown)
                assertEquals(4L, observation.sizeBytes)
                assertFalse("nonempty descriptor cannot be writable staging", writable(observation))
                assertEquals(3, CatalogRowProvider.calls)
                assertEquals(1, CatalogRowProvider.openCalls)
                assertEquals(document, CatalogRowProvider.lastOpenUri)
                assertEquals("r", CatalogRowProvider.lastOpenMode)
                assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
                assertFalse("actual descriptor owner must be closed",
                    checkNotNull(CatalogRowProvider.openedDescriptor).second.valid())
            }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
            // Only the test empties its own file; native zero remains legitimate authority.
            source.writeBytes(byteArrayOf())
            CatalogRowProvider.values = row(size = -1L)
            CatalogRowProvider.calls = 0
            CatalogRowProvider.openCalls = 0
            val empty = created()
            assertTrue(empty.sizeKnown)
            assertEquals(0L, empty.sizeBytes)
            assertTrue(writable(empty))
            assertEquals(3, CatalogRowProvider.calls)
            assertEquals(1, CatalogRowProvider.openCalls)
            assertFalse(checkNotNull(CatalogRowProvider.openedDescriptor).second.valid())
            CatalogRowProvider.descriptorSource = null
            CatalogRowProvider.calls = 0
            CatalogRowProvider.openCalls = 0
            val unavailable = created()
            assertFalse("failed descriptor cannot establish known empty size", unavailable.sizeKnown)
            assertFalse(writable(unavailable))
            assertEquals(2, CatalogRowProvider.calls)
            assertEquals(1, CatalogRowProvider.openCalls)
            assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
            assertEquals(0, CatalogRowProvider.mutationCalls)
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            for (cleanup in listOf<() -> Unit>(
                { CatalogRowProvider.openedDescriptor?.first?.close() },
                { Files.deleteIfExists(source.toPath()); Unit },
            )) {
                try { cleanup() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
                }
            }
            CatalogRowProvider.descriptorSource = null
            PlaybackSessionOsShadow.targetPath = previousShadowPath
            cleanupFailure?.let { if (bodyFailure == null) throw it else bodyFailure.addSuppressed(it) }
        }
    }

    @Test fun queryBindsNameAndIdentityToOnePublishedManagedRow() {
        CatalogRowProvider.values = row()
        val selected = selection()
        assertEquals(1, CatalogRowProvider.calls)
        assertArrayEquals(columns, CatalogRowProvider.lastProjection)
        assertEquals(uri, CatalogRowProvider.lastUri)
        assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        checkSelection(selected, identity = true, name = true)
        assertTrue(CatalogRowProvider.lastCursor!!.isClosed)

        // This deliberately inconsistent row isolates the two checks; it does not
        // claim a real MediaStore name update leaves generation unchanged.
        CatalogRowProvider.values = row(name = "other.wav")
        checkSelection(selected, identity = true, name = false)
        CatalogRowProvider.values = row(generation = 10L)
        checkSelection(selected, identity = false, name = true)
        CatalogRowProvider.values = row(size = 121L)
        checkSelection(selected, identity = false, name = true)
    }

    @Test fun fallbackDateChangesIdentityWhenGenerationIsUnavailable() {
        CatalogRowProvider.values = row(generation = null)
        val selected = selection()
        checkSelection(selected, identity = true, name = true)
        CatalogRowProvider.values = row(modified = 101L, generation = null)
        checkSelection(selected, identity = false, name = true)
    }

    @Test fun invalidOrUnavailableRowsNeverProduceBothProofs() {
        CatalogRowProvider.values = row()
        val selected = selection()
        for (invalid in listOf(row(path = null), row(path = "Music/Other/"),
            row(pending = null), row(pending = 1), row(name = null))) {
            CatalogRowProvider.values = invalid
            checkSelection(selected, identity = false, name = false)
            assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        }
        // Missing size or all revision evidence preserves the observed name, but
        // cannot prove identity. This distinction matters to the caller's contract.
        for (incomplete in listOf(row(size = null), row(modified = null, generation = null))) {
            CatalogRowProvider.values = incomplete
            checkSelection(selected, identity = false, name = true)
            assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        }
        CatalogRowProvider.empty = true
        checkSelection(selected, identity = false, name = false)
        assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
        CatalogRowProvider.empty = false
        CatalogRowProvider.returnNull = true
        checkSelection(selected, identity = false, name = false)
        CatalogRowProvider.returnNull = false
        CatalogRowProvider.queryFailure = true
        checkSelection(selected, identity = false, name = false)
    }

    @Test fun closeFailureMakesTheObservationUnavailable() {
        CatalogRowProvider.values = row()
        CatalogRowProvider.closeFailure = true
        assertNull(observed())
        assertEquals(1, CatalogRowProvider.calls)
        assertTrue(CatalogRowProvider.lastCursor!!.isClosed)
    }

    @Test fun registerRejectsStaleOrDeniedProviderAndPreservesCatalogRow() = runBlocking {
        CatalogRowProvider.values = row()
        val selected = selection().copy(createdAtMillis = 1L, lastSeenAtMillis = 1L)
        val db = RecordingDatabase.getInstance(app)
        assertOwnedDatabase(db)
        val dao = db.recordingDao()
        var ownsRow = false
        var bodyFailure: Throwable? = null
        try {
            assertNull("test owns a fresh catalog ID", dao.findById(selected.id))
            ownsRow = true
            val sentinel = selected.copy(codecSummary = "catalog sentinel")
            dao.upsert(sentinel)
            val before = checkNotNull(dao.findById(selected.id))
            for (case in 0..2) {
                CatalogRowProvider.values = when (case) {
                    0 -> row(name = "changed.wav")
                    1 -> row(generation = 10L)
                    else -> row()
                }
                CatalogRowProvider.queryFailure = case == 2
                CatalogRowProvider.calls = 0
                val error = try {
                    RecordingRepository.register(app, selected)
                    fail("stale or inaccessible provider was accepted")
                    null
                } catch (failure: RecordingCatalogIdentityChangedException) { failure }
                assertNotNull(error)
                assertEquals(1, CatalogRowProvider.calls)
                assertEquals(before, dao.findById(selected.id))
            }
            CatalogRowProvider.queryFailure = false
            CatalogRowProvider.values = row()
            CatalogRowProvider.calls = 0
            val registered = RecordingRepository.register(app, selected)
            assertEquals(1, CatalogRowProvider.calls)
            assertEquals("WAV", registered.codecSummary)
            assertNotEquals(before.codecSummary, registered.codecSummary)
            assertEquals(registered, dao.findById(selected.id))
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally {
            cleanupRegistration(bodyFailure, listOf(
                { if (ownsRow) dao.deleteById(selected.id) },
                { RecordingDatabase.resetAfterCorruption() },
            ))
        }
    }

    @Test fun registerRechecksProviderAfterActualCatalogWriteFailure() = runBlocking {
        CatalogRowProvider.values = row()
        val selected = selection().copy(createdAtMillis = 1L, lastSeenAtMillis = 1L)
        val db = RecordingDatabase.getInstance(app)
        assertOwnedDatabase(db)
        val dao = db.recordingDao()
        var ownsRow = false
        var ownsTrigger = false
        var bodyFailure: Throwable? = null
        try {
            assertNull("test owns a fresh catalog ID", dao.findById(selected.id))
            ownsRow = true
            dao.upsert(selected.copy(codecSummary = "catalog sentinel"))
            val before = checkNotNull(dao.findById(selected.id))
            db.writableDatabase.execSQL("CREATE TRIGGER test_reject_registration BEFORE INSERT ON recordings " +
                "BEGIN SELECT RAISE(ABORT, 'test registration failure'); END")
            ownsTrigger = true
            var originalFailure: Exception? = null
            for (case in 0..2) {
                CatalogRowProvider.values = row()
                CatalogRowProvider.secondValues = if (case == 1) row(generation = 10L) else null
                CatalogRowProvider.denySecond = case == 2
                CatalogRowProvider.calls = 0
                val failure = try {
                    RecordingRepository.register(app, selected)
                    fail("injected database write was accepted")
                    null
                } catch (error: Exception) { error }
                assertNotNull(failure)
                assertEquals(2, CatalogRowProvider.calls)
                assertEquals(before, dao.findById(selected.id))
                if (case == 0) {
                    assertFalse(failure is RecordingCatalogIdentityChangedException)
                    assertTrue("failure must originate at the injected insert",
                        (failure is SQLiteException && failure.message.orEmpty().contains("test registration failure")) ||
                            (failure is IllegalStateException &&
                                failure.message == "Unable to persist recording ${selected.id}"))
                    originalFailure = failure
                } else {
                    assertTrue(failure is RecordingCatalogIdentityChangedException)
                    val chain = mutableListOf<Throwable>()
                    val seen = IdentityHashMap<Throwable, Boolean>()
                    var current: Throwable? = failure
                    while (current != null && seen.put(current, true) == null) {
                        chain.add(current)
                        current = current.cause
                    }
                    val detail = chain.joinToString(" -> ") { "${it.javaClass.name}: ${it.message}" }
                    assertNull("cyclic cause chain: $detail", current)
                    val original = checkNotNull(originalFailure)
                    val dbIndex = chain.indexOfFirst {
                        it.javaClass == original.javaClass && it.message == original.message
                    }
                    assertTrue("original database failure absent: $detail", dbIndex > 0)
                    assertTrue("unexpected wrapper before database failure: $detail",
                        chain.take(dbIndex).all {
                            it is RecordingCatalogIdentityChangedException &&
                                it.message == "Recording changed while catalog registration failed"
                        })
                }
            }
        } catch (error: Throwable) {
            bodyFailure = error
            throw error
        } finally {
            cleanupRegistration(bodyFailure, listOf(
                { if (ownsTrigger) db.writableDatabase.execSQL("DROP TRIGGER test_reject_registration") },
                { if (ownsRow) dao.deleteById(selected.id) },
                { RecordingDatabase.resetAfterCorruption() },
            ))
        }
    }
}

/** Only the external provider boundary is substituted. */
private class CatalogRowProvider : ContentProvider() {
    companion object {
        var values: Array<Any?>? = null
        var secondValues: Array<Any?>? = null
        var denySecond = false
        var empty = false
        var returnNull = false
        var queryFailure = false
        var closeFailure = false
        var calls = 0
        var openCalls = 0
        var mutationCalls = 0
        var lastOpenUri: Uri? = null
        var lastOpenMode: String? = null
        var descriptorSource: File? = null
        var openedDescriptor: Pair<ParcelFileDescriptor, FileDescriptor>? = null
        var lastProjection: Array<out String>? = null
        var lastUri: Uri? = null
        var lastCursor: MatrixCursor? = null
        fun reset() {
            values = null; secondValues = null; denySecond = false
            empty = false; returnNull = false; queryFailure = false; closeFailure = false
            calls = 0; lastProjection = null; lastUri = null; lastCursor = null
            openCalls = 0; mutationCalls = 0; lastOpenUri = null; lastOpenMode = null
            descriptorSource = null; openedDescriptor = null
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        calls++
        lastProjection = projection
        lastUri = uri
        if (queryFailure || (denySecond && calls == 2)) throw SecurityException("provider access denied")
        if (returnNull) return null
        val cursor = object : MatrixCursor(requireNotNull(projection)) {
            override fun close() {
                super.close()
                if (closeFailure) throw IllegalStateException("close failed")
            }
        }
        if (!empty) {
            val names = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING,
            )
            val selectedValues = if (calls == 2) secondValues ?: values else values
            val namedValues = names.zip(requireNotNull(selectedValues)).toMap() + mapOf(
                MediaStore.MediaColumns._ID to 41L,
                MediaStore.MediaColumns.MIME_TYPE to "audio/wav",
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to "clip",
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to 100_000L,
            )
            cursor.addRow(requireNotNull(projection).map { namedValues[it] })
        }
        lastCursor = cursor
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        openCalls++
        lastOpenUri = uri
        lastOpenMode = mode
        val source = descriptorSource ?: throw FileNotFoundException("injected descriptor access failure")
        check(mode == "r")
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also {
            openedDescriptor = it to it.fileDescriptor
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? { mutationCalls++; return null }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        mutationCalls++; return 0
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        mutationCalls++; return 0
    }
}
