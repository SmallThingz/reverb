package app.smallthingz.reverb

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PlaybackSessionOsShadow::class])
class ProviderPublicationBoundaryTest {
    @Test fun deleteFalseOrThrowAfterPositiveAbsenceRetiresOnlySelectedCatalogAndJournal() = runBlocking {
        for (throwAfterDelete in listOf(false, true)) fixture(expectedDeletes = 1) { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            provider.removeOnDelete = true
            provider.deleteResult = 0
            provider.throwAfterDelete = throwAfterDelete
            assertTrue(RecordingRepository.delete(app, selected))
            assertEquals(1, provider.deletes)
            assertEquals(throwAfterDelete, provider.didThrowDelete)
            assertFalse(provider.payload.exists())
            assertNull(dao.findById(selected.id))
            assertTrue(getRecorderPreferences(app).getStringSet(PrefKey.PENDING_RECORDING_DELETIONS.name, emptySet())!!.isEmpty())
            assertTrue(provider.trace.drop(provider.trace.indexOf("delete") + 1).contains("query:missing"))
        }
    }

    @Test fun deleteSuccessWithoutPositiveAbsenceRetainsSelectedBytesCatalogAndIntent() = runBlocking {
        for (unknown in listOf(false, true)) fixture(expectedDeletes = 1) { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            provider.deleteResult = 1
            provider.unavailableAfterDelete = unknown
            assertFalse(RecordingRepository.delete(app, selected))
            assertEquals(1, provider.deletes)
            assertArrayEquals(bytes, provider.payload.readBytes())
            assertEquals(selected, dao.findById(selected.id))
            val entries = getRecorderPreferences(app).getStringSet(PrefKey.PENDING_RECORDING_DELETIONS.name, emptySet())!!
            val encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(selected.id.toByteArray(Charsets.UTF_8))
            val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(setOf("v1|$encodedId|48|$hex|0"), entries)
            assertTrue(provider.trace.drop(provider.trace.indexOf("delete") + 1)
                .contains(if (unknown) "query:unavailable" else "query"))
        }
    }

    @Test fun renameCommittedThenThrowRebindsSameBytesInRealCatalog() = runBlocking {
        fixture { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            provider.expectedNameQuery = "renamed.wav"
            provider.throwAfterCommit = true
            val renamed = checkNotNull(RecordingRepository.rename(app, selected, "renamed"))
            assertTrue(provider.didThrow)
            assertEquals(1, provider.updates.size)
            provider.assertRenameRequest()
            assertEquals("renamed.wav", provider.name)
            assertEquals("renamed.wav", renamed.displayName)
            assertEquals(selected.id, renamed.id)
            assertEquals(selected.fileIdentity, renamed.fileIdentity)
            assertEquals(selected.codecSummary, renamed.codecSummary)
            assertEquals(renamed, dao.findById(selected.id))
            assertArrayEquals(bytes, provider.payload.readBytes())
        }
    }

    @Test fun renameZeroStatusWithUnchangedProviderIsDefiniteFailureAndKeepsCatalog() = runBlocking {
        fixture { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            provider.expectedNameQuery = "renamed.wav"
            provider.applyUpdate = false
            provider.updateResult = 0
            val failure = try {
                RecordingRepository.rename(app, selected, "renamed")
                fail("unchanged provider was accepted as renamed")
                null
            } catch (error: RecordingRenameDefiniteFailure) { error }
            assertNotNull(failure)
            assertEquals(1, provider.updates.size)
            provider.assertRenameRequest()
            assertEquals("clip.wav", provider.name)
            assertEquals(selected, dao.findById(selected.id))
            assertArrayEquals(bytes, provider.payload.readBytes())
        }
    }

    @Test fun renameReplacementAfterAcceptedMutationStaysUncertainAndPreservesBothObjects() = runBlocking {
        fixture { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            provider.expectedNameQuery = "renamed.wav"
            val replacement = bytes.clone().also { it[44] = 42; it[45] = 43 }
            provider.replacementOnUpdate = replacement
            val failure = try {
                RecordingRepository.rename(app, selected, "renamed")
                fail("replacement content was accepted as the selected recording")
                null
            } catch (error: RecordingRenameStateUncertainException) { error }
            assertNotNull(failure)
            assertEquals(1, provider.updates.size)
            provider.assertRenameRequest()
            assertEquals("renamed.wav", provider.name)
            assertEquals(selected, dao.findById(selected.id))
            assertArrayEquals(bytes, checkNotNull(provider.retainedOriginal).readBytes())
            assertArrayEquals(replacement, provider.payload.readBytes())
        }
    }

    private suspend fun registerPublishedSelection(app: Application, provider: PublicationMediaProvider,
        target: RecordingOutputTarget, fingerprint: StableOutputFingerprint, bytes: ByteArray): RecordingEntity {
        assertTrue(getRecorderPreferences(app).getStringSet(PrefKey.PENDING_RECORDING_DELETIONS.name, emptySet())!!.isEmpty())
        provider.pending = 0
        provider.pendingAfterUpdate = 0
        provider.name = "clip.wav"
        val selected = RecordingEntity(target.id, "clip.wav", "audio/wav", 1L, 1L, bytes.size.toLong(), "WAV",
            RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID, checkNotNull(fingerprint.providerIdentity))
        return RecordingRepository.register(app, selected)
    }

    @Test fun positiveUpdateWithoutKnownPublishedStatePreservesVerifiedStaging() = runBlocking {
        for (pending in listOf<Int?>(1, null)) fixture { app, provider, target, fingerprint, bytes, dao ->
            provider.pendingAfterUpdate = pending
            val markers = getRecorderPreferences(app).getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.toSet()
            val suppression = getRecorderPreferences(app).getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            val error = try {
                finalizeOutputTarget(app, target, fingerprint)
                fail("positive update without known nonpending postcondition was accepted")
                null
            } catch (failure: IOException) { failure }
            assertNotNull(error)
            assertTrue(provider.updated)
            assertEquals(1, provider.updates.size)
            assertEquals("clip.wav", provider.updates.single().getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            assertEquals(0, provider.updates.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertEquals(pending, provider.pending)
            assertArrayEquals(bytes, provider.payload.readBytes())
            assertNull(dao.findById(target.id))
            assertEquals(markers, getRecorderPreferences(app).getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
            assertEquals(suppression, getRecorderPreferences(app).getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
            assertNotNull(verifiedExportStagingRecovery(app, target.storageType, target.id))
            assertEquals(0, provider.deletes)
            assertTrue(provider.trace.indexOf("update") > provider.trace.indexOf("open:r"))
            assertTrue(provider.trace.drop(provider.trace.indexOf("update") + 1).contains("open:r"))
            provider.assertClosed()
        }
    }

    @Test fun transportFailureAfterCommitRecoversVerifiedPublicationAndRealCatalog() = runBlocking {
        fixture { app, provider, target, fingerprint, bytes, dao ->
            provider.pendingAfterUpdate = 0
            provider.throwAfterCommit = true
            val published = finalizeOutputTarget(app, target, fingerprint)
            assertEquals(1, provider.updates.size)
            assertTrue(provider.didThrow)
            assertFalse(published.staging)
            assertEquals("clip.wav", published.displayName)
            assertEquals(target.id, published.id)
            assertEquals(fingerprint.providerIdentity, published.publishedIdentity)
            assertArrayEquals(bytes, provider.payload.readBytes())
            val entity = RecordingEntity(
                published.id, published.displayName, "audio/wav", 1L, 1L, bytes.size.toLong(), "WAV",
                RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID, published.publishedIdentity,
            )
            val registered = RecordingRepository.register(app, entity)
            assertEquals(registered, dao.findById(target.id))
            assertEquals("clip.wav", registered.displayName)
            assertEquals(bytes.size.toLong(), registered.sizeBytes)
            assertEquals(fingerprint.providerIdentity, registered.fileIdentity)
            assertEquals(0, provider.deletes)
            assertTrue(provider.trace.drop(provider.trace.indexOf("update") + 1).contains("open:r"))
            provider.assertClosed()
        }
    }

    private suspend fun fixture(expectedDeletes: Int = 0, body: suspend (Application, PublicationMediaProvider, RecordingOutputTarget,
        StableOutputFingerprint, ByteArray, RecordingDao) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "publication-boundary-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val bytes = wav()
        val payload = File(directory, "owned.wav").apply { writeBytes(bytes) }
        val sentinel = File(directory, "unrelated.bin").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
        val sentinelBytes = sentinel.readBytes()
        val provider = PublicationMediaProvider(payload)
        val previousShadowPath = PlaybackSessionOsShadow.targetPath
        val uri = Uri.parse("content://media/external/audio/media/730031")
        val preferences = getRecorderPreferences(app)
        val keys = listOf(PrefKey.VERIFIED_EXPORT_STAGING.name, PrefKey.PENDING_OUTPUT_CLEANUP.name,
            PrefKey.PENDING_RECORDING_DELETIONS.name)
        val previous = keys.associateWith { key ->
            if (preferences.contains(key)) preferences.getStringSet(key, emptySet())!!.toSet() else null
        }
        val db = RecordingDatabase.getInstance(app)
        val dao = db.recordingDao()
        val sentinelId = "content://media/external/audio/media/730032"
        var ownsRow = false
        var ownsSentinel = false
        var primary: Throwable? = null
        try {
            val databasePath = File(db.writableDatabase.path).canonicalFile
            assertEquals(app.getDatabasePath(checkNotNull(db.databaseName)).canonicalFile, databasePath)
            assertTrue(databasePath.path.startsWith(checkNotNull(app.filesDir.parentFile).canonicalPath + File.separator))
            assertNull(dao.findById(uri.toString()))
            assertNull(dao.findById(sentinelId))
            ownsRow = true
            ownsSentinel = true
            val sentinelRow = RecordingEntity(sentinelId, "sentinel.wav", "audio/wav", 1L, 1L, 48L, "sentinel",
                RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID, "sentinel-identity")
            dao.upsert(sentinelRow)
            provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            PlaybackSessionOsShadow.targetPath = payload.absolutePath
            val identity = "provider:3:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(uri.toString().toByteArray(Charsets.UTF_8)) + ":${bytes.size}:9"
            val target = RecordingOutputTarget(uri.toString(), "clip.wav", "audio/wav",
                RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID, 1L, uri = uri, staging = true,
                stagingDisplayName = provider.name, stagingIdentity = identity)
            // Actual complete WAV verification supplies the publication authority; the digest oracle is independent.
            val fingerprint = verifyWavOutputTargetAndDigest(app, target, bytes.size.toLong(),
                bytes.copyOfRange(0, 44), 44L, 4L, MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(44, 48)))
            assertEquals(bytes.size.toLong(), fingerprint.digest.byteCount)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), fingerprint.digest.sha256)
            assertEquals(identity, fingerprint.providerIdentity)
            assertTrue(putVerifiedExportStaging(app, target, fingerprint))
            assertTrue(suppressProviderOutputWithoutDeletion(app, RecordingStorageType.MEDIASTORE,
                sentinelId, CopyDigest(sentinelBytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(sentinelBytes))))
            val suppressionBefore = preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            body(app, provider, target, fingerprint, bytes, dao)
            assertEquals(suppressionBefore, preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
            assertArrayEquals(sentinelBytes, sentinel.readBytes())
            assertEquals(sentinelRow, dao.findById(sentinelId))
            assertEquals(0, provider.inserts)
            assertEquals(expectedDeletes, provider.deletes)
            provider.assertClosed()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            val cleanup = listOf<() -> Unit>(
                { provider.assertClosed() },
                { provider.closeLeakedDescriptors() },
                { provider.closeLeakedCursors() },
                { runBlocking { if (ownsRow) dao.deleteById(uri.toString()) } },
                { runBlocking { if (ownsSentinel) dao.deleteById(sentinelId) } },
                { RecordingDatabase.resetAfterCorruption() },
                { check(preferences.edit().also { editor -> previous.forEach { (key, value) ->
                    if (value == null) editor.remove(key) else editor.putStringSet(key, value)
                } }.commit()) },
                { PlaybackSessionOsShadow.targetPath = previousShadowPath },
            )
            var cleanupFailure: Throwable? = null
            for (step in cleanup) try { step() } catch (failure: Throwable) {
                val owner = primary ?: cleanupFailure
                if (owner != null) owner.addSuppressed(failure) else cleanupFailure = failure
            }
            if (primary == null && cleanupFailure == null) check(directory.deleteRecursively())
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }

    private fun wav(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(40)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(4); putShort(123); putShort(-321)
    }.array()
}

/** Script only the external provider; payload verification, journals and SQLite stay real.
 * Robolectric resets its provider registry between test methods; the pending-state loop replaces
 * this provider before each fixture uses it. The retired provider is never accessed after cleanup.
 */
private class PublicationMediaProvider(val payload: File) : ContentProvider() {
    var name = ".reverb-export-publication.wav"
    var pending: Int? = 1
    var pendingAfterUpdate: Int? = 1
    var throwAfterCommit = false
    var didThrow = false
    var updated = false
    var applyUpdate = true
    var updateResult = 1
    var expectedNameQuery = "clip.wav"
    var generation = 9L
    var replacementOnUpdate: ByteArray? = null
    var retainedOriginal: File? = null
    var removeOnDelete = false
    var throwAfterDelete = false
    var didThrowDelete = false
    var unavailableAfterDelete = false
    var queryUnavailable = false
    var missing = false
    var deleteResult = 0
    var deletes = 0
    var inserts = 0
    val updates = mutableListOf<ContentValues>()
    val trace = mutableListOf<String>()
    private val descriptors = mutableListOf<Pair<ParcelFileDescriptor, FileDescriptor>>()
    private val cursors = mutableListOf<Cursor>()
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (queryUnavailable) { trace += "query:unavailable"; return null }
        trace += "query"
        val columns = requireNotNull(projection)
        val cursor = MatrixCursor(columns)
        if (uri.lastPathSegment == "730031") {
            if (missing) { trace += "query:missing"; cursors += cursor; return cursor }
            val values: Map<String, Any?> = mapOf(
                MediaStore.MediaColumns._ID to 730031L,
                MediaStore.MediaColumns.DISPLAY_NAME to name,
                MediaStore.MediaColumns.SIZE to payload.length(),
                MediaStore.MediaColumns.DATE_MODIFIED to 100L,
                MediaStore.MediaColumns.GENERATION_MODIFIED to generation,
                MediaStore.MediaColumns.RELATIVE_PATH to "Music/Reverb/",
                MediaStore.MediaColumns.IS_PENDING to pending,
            )
            cursor.addRow(columns.map { column -> check(values.containsKey(column)); values[column] })
        } else {
            check(uri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            assertArrayEquals(arrayOf("Music/Reverb/", expectedNameQuery), selectionArgs)
        }
        cursors += cursor
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(uri.lastPathSegment == "730031" && mode == "r")
        trace += "open:$mode"
        return ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY).also {
            descriptors += it to it.fileDescriptor
        }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        check(uri.lastPathSegment == "730031")
        trace += "update"
        updates += ContentValues(requireNotNull(values))
        if (applyUpdate) {
            name = checkNotNull(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            pending = pendingAfterUpdate
            replacementOnUpdate?.let { replacement ->
                val retained = File(payload.parentFile, "selected-original.wav")
                check(payload.renameTo(retained))
                retainedOriginal = retained
                payload.writeBytes(replacement)
                generation = 10L
            }
        }
        updated = true
        if (throwAfterCommit) { didThrow = true; throw IllegalStateException("committed then transport failed") }
        return updateResult
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { inserts++; error("unexpected insert") }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        check(uri.lastPathSegment == "730031")
        deletes++
        trace += "delete"
        if (removeOnDelete) { check(payload.delete()); missing = true }
        queryUnavailable = unavailableAfterDelete
        if (throwAfterDelete) { didThrowDelete = true; throw IllegalStateException("deleted then transport failed") }
        return deleteResult
    }
    override fun getType(uri: Uri) = "audio/wav"
    fun assertRenameRequest() {
        assertEquals(setOf(MediaStore.MediaColumns.DISPLAY_NAME), updates.single().keySet())
        assertEquals("renamed.wav", updates.single().getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
    }
    fun assertClosed() {
        assertTrue("all acquired descriptors must close", descriptors.all { !it.second.valid() })
        assertTrue("all acquired cursors must close", cursors.all { it.isClosed })
    }
    fun closeLeakedDescriptors() {
        var failure: Throwable? = null
        for ((owner, descriptor) in descriptors) if (descriptor.valid()) try { owner.close() }
        catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
    fun closeLeakedCursors() {
        var failure: Throwable? = null
        for (cursor in cursors) if (!cursor.isClosed) try { cursor.close() }
        catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
}
