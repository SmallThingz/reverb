package app.smallthingz.reverb

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.DataInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
class SafRepositoryReconciliationTest {
    @Test fun legacyExportStagingStaysHiddenWithoutRenameThenPublishesExactlyOnce() = runBlocking {
        fixture(expectedRenames = 1) { f ->
            f.dao.deleteById(SAF_SELECTED)
            assertNull(f.dao.findById(SAF_SELECTED))
            val stagedName = "reverb-partial-export__old-session__token__Y2xpcC53YXY.wav"
            f.provider.selectedName = stagedName
            val inode = Files.getAttribute(f.provider.payload.toPath(), "unix:ino")
            val preferences = getRecorderPreferences(f.context)
            val keys = listOf(PrefKey.VERIFIED_EXPORT_STAGING, PrefKey.PENDING_OUTPUT_CLEANUP,
                PrefKey.PENDING_RECORDING_DELETIONS)
            val journals = keys.associateWith { preferences.getStringSet(it.name, emptySet())!!.toSet() }
            assertFalse(f.provider.renameSupported)
            assertFalse(RecordingRepository.refresh(f.context).any { it.id == SAF_SELECTED })
            assertFalse(RecordingRepository.listKnown(f.context).any { it.id == SAF_SELECTED })
            assertNull(f.dao.findById(SAF_SELECTED))
            assertEquals(stagedName, f.provider.selectedName)
            assertEquals(f.selected.fileIdentity, resolveProviderRecordingIdentity(f.context,
                RecordingStorageType.DOCUMENT, Uri.parse(SAF_SELECTED)))
            f.assertOwnedBytes()
            assertEquals(inode, Files.getAttribute(f.provider.payload.toPath(), "unix:ino"))
            assertTrue(f.provider.callMethods.isEmpty())
            assertEquals(listOf(false), f.provider.capabilityObservations)
            assertEquals(0, f.provider.mutations)
            assertTrue(f.provider.selectedOpens > 0)
            assertEquals(f.sentinel, f.dao.findById(SAF_SENTINEL))
            keys.forEach { assertEquals(journals[it], preferences.getStringSet(it.name, emptySet())) }
            val opensBeforePublish = f.provider.selectedOpens
            f.provider.renameSupported = true
            val published = RecordingRepository.refresh(f.context).single { it.id == SAF_SELECTED }
            assertEquals(listOf(Uri.parse(SAF_SELECTED) to "clip.wav"), f.provider.renameRequests)
            assertEquals(listOf(false, true), f.provider.capabilityObservations)
            assertEquals("clip.wav", f.provider.selectedName)
            assertEquals("clip.wav", published.displayName)
            assertEquals("audio/wav", published.mimeType)
            assertEquals(1644L, published.sizeBytes)
            assertEquals(100L, published.durationMillis)
            val encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(SAF_SELECTED.toByteArray(Charsets.UTF_8))
            assertEquals("provider:2:$encodedId:1644:100001", published.fileIdentity)
            assertEquals(published, f.dao.findById(SAF_SELECTED))
            assertTrue(f.provider.selectedOpens > opensBeforePublish)
            val renameIndex = f.provider.trace.indexOf("rename:selected")
            assertTrue(renameIndex >= 0)
            assertTrue(f.provider.trace.drop(renameIndex + 1).contains("query:children:complete"))
            f.assertOwnedBytes()
            assertEquals(inode, Files.getAttribute(f.provider.payload.toPath(), "unix:ino"))
            keys.forEach { assertEquals(journals[it], preferences.getStringSet(it.name, emptySet())) }
            assertEquals(published, RecordingRepository.refresh(f.context).single { it.id == SAF_SELECTED })
            assertEquals(listOf(Uri.parse(SAF_SELECTED) to "clip.wav"), f.provider.renameRequests)
            assertEquals(listOf(false, true), f.provider.capabilityObservations)
            assertEquals(inode, Files.getAttribute(f.provider.payload.toPath(), "unix:ino"))
            f.assertOwnedBytes()
            keys.forEach { assertEquals(journals[it], preferences.getStringSet(it.name, emptySet())) }
        }
    }

    @Test fun renameUsesOneStrictNameIdentityObservationAndKeepsContentAndCatalogBookends() = runBlocking {
        fixture(expectedRenames = 1) { f ->
            f.provider.renameSupported = true
            val renamed = checkNotNull(RecordingRepository.rename(f.context, f.selected, "renamed"))
            assertEquals(listOf(Uri.parse(SAF_SELECTED) to "renamed.wav"), f.provider.renameRequests)
            assertEquals(SAF_SELECTED, renamed.id)
            assertEquals("renamed.wav", renamed.displayName)
            val encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(SAF_SELECTED.toByteArray(Charsets.UTF_8))
            assertEquals("provider:2:$encodedId:1644:100001", renamed.fileIdentity)
            assertEquals(renamed, f.dao.findById(SAF_SELECTED))
            val strictColumns = listOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            assertEquals(strictColumns, f.provider.postRenameProjections.first())
            assertFalse("post-rename name must come from the identity observation, not a second IPC",
                f.provider.postRenameProjections.contains(listOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
            val renameIndex = f.provider.trace.indexOf("rename:selected")
            assertTrue(renameIndex >= 0)
            assertTrue("post-rename payload verification remains", f.provider.trace.drop(renameIndex + 1).contains("open:selected"))
            f.assertOwnedBytes()
        }
    }

    @Test fun renameWithWrongBlankOrUnavailableObservedNameStaysUncertainAndPreservesCatalog() = runBlocking {
        for (name in listOf("other.wav", "", null)) fixture(expectedRenames = 1) { f ->
            f.provider.renameSupported = true
            f.provider.nameAfterRename = name
            f.provider.itemAfterRename = if (name == null) "missing" else "present"
            val failure = try {
                RecordingRepository.rename(f.context, f.selected, "renamed")
                fail("unproven requested name was accepted")
                null
            } catch (error: RecordingRenameStateUncertainException) { error }
            assertNotNull(failure)
            assertEquals(f.selected, f.dao.findById(SAF_SELECTED))
            assertFalse(f.provider.postRenameProjections.contains(listOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
            f.assertOwnedBytes()
        }
    }

    @Test fun renamePreservesAnExternalNameChangedAfterPostRenameObservation() = runBlocking {
        fixture(expectedRenames = 1) { f ->
            f.provider.renameSupported = true
            f.provider.onPostRenameOpen = {
                assertEquals("renamed.wav", f.provider.selectedName)
                f.provider.selectedName = "external.wav"
            }

            val failure = try {
                RecordingRepository.rename(f.context, f.selected, "renamed")
                fail("rename returned success with a stale name")
                null
            } catch (error: IOException) { error }
            assertEquals("Recording changed before rename catalog commit", failure?.message)
            assertEquals(listOf(Uri.parse(SAF_SELECTED) to "renamed.wav"), f.provider.renameRequests)
            assertEquals("external.wav", f.provider.selectedName)
            assertNull(f.dao.findById(SAF_SELECTED))
            val refreshed = RecordingRepository.refresh(f.context).single { it.id == SAF_SELECTED }
            assertEquals("external.wav", refreshed.displayName)
            assertEquals(refreshed, f.dao.findById(SAF_SELECTED))
            f.assertOwnedBytes()
        }
    }

    @Test fun renameDoesNotReturnStaleCatalogWhenProviderAlreadyHasRequestedName() = runBlocking {
        fixture(expectRenameIncident = true) { f ->
            f.provider.renameSupported = true
            val opensBefore = f.provider.selectedOpens
            assertEquals(f.selected, RecordingRepository.rename(f.context, f.selected, "clip"))
            assertEquals("An exact no-op needs no payload descriptor", opensBefore, f.provider.selectedOpens)
            f.provider.selectedName = "renamed.wav"
            val failure = try {
                RecordingRepository.rename(f.context, f.selected, "renamed")
                fail("rename reported success with the old catalog row")
                null
            } catch (error: RecordingRenameStateUncertainException) { error }
            assertNotNull(failure)
            assertEquals("The rejected rename verified the selected bytes", opensBefore + 1, f.provider.selectedOpens)
            f.provider.assertClosed()
            assertEquals(f.selected, f.dao.findById(SAF_SELECTED))
            assertEquals("renamed.wav", f.provider.selectedName)
            f.assertOwnedBytes()
        }
    }

    @Test fun incompleteConfiguredListingPreservesKnownCatalogAndStableRetryRecovers() = runBlocking {
        for (fault in listOf("null", "denied", "loading", "error", "duplicate")) fixture { f ->
            f.provider.listing = fault
            unavailableRefresh(f.context)
            assertEquals(f.selected, f.dao.findById(SAF_SELECTED))
            assertTrue(RecordingRepository.listKnown(f.context).any { it.id == SAF_SELECTED })
            f.assertOwnedBytes()
            assertEquals(0, f.provider.selectedOpens)
            f.provider.listing = "complete"
            val recovered = RecordingRepository.refresh(f.context).single { it.id == SAF_SELECTED }
            assertEquals(f.selected.fileIdentity, recovered.fileIdentity)
            assertEquals("clip.wav", recovered.displayName)
            assertEquals(f.original.size.toLong(), recovered.sizeBytes)
            assertNull(recovered.missingSinceMillis)
            assertEquals(0, f.provider.selectedOpens)
        }
    }

    @Test fun failedListingAndEmptyItemKeepKnownRecordingVisibleUntilRetry() = runBlocking {
        fixture { f ->
            f.provider.listing = "null"
            f.provider.item = "missing"
            unavailableRefresh(f.context)
            assertEquals(f.selected, f.dao.findById(SAF_SELECTED))
            assertTrue(RecordingRepository.listKnown(f.context).any { it.id == SAF_SELECTED })
            f.assertOwnedBytes()
            f.provider.listing = "complete"
            f.provider.item = "present"
            val recovered = RecordingRepository.refresh(f.context).single { it.id == SAF_SELECTED }
            assertEquals(f.selected.fileIdentity, recovered.fileIdentity)
            assertEquals(f.selected.createdAtMillis, recovered.createdAtMillis)
            assertNull(recovered.missingSinceMillis)
            f.assertOwnedBytes()
        }
    }

    @Test fun failedSecondaryDirectoryHidesWaveformUntilTheSameRecordingIsObservedAgain() = runBlocking {
        fixture { f ->
            val legacyDirectory = getSavedRecordingsDirectory(f.context)
            check(legacyDirectory.mkdir())
            val legacyFile = File(legacyDirectory, "legacy.wav").apply { writeBytes(wav(456)) }
            val displacedDirectory = File(legacyDirectory.parentFile, "displaced-recordings")
            val legacyId = legacyFile.absolutePath
            val waveform = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.5f })
            try {
                val initialRows = RecordingRepository.refresh(f.context).associateBy { it.id }
                val selected = checkNotNull(initialRows[SAF_SELECTED])
                val legacy = checkNotNull(initialRows[legacyId])
                val selectedRevision = recordingWaveformRevision(selected)
                val legacyRevision = recordingWaveformRevision(legacy)
                assertTrue(RecordingRepository.cacheWaveform(f.context, selected, waveform, selectedRevision))
                assertTrue(RecordingRepository.cacheWaveform(f.context, legacy, waveform, legacyRevision))
                val selectedCached = checkNotNull(f.dao.findById(SAF_SELECTED))
                val legacyCached = checkNotNull(f.dao.findById(legacyId))
                assertEquals(waveform, legacyCached.waveformData)

                check(legacyDirectory.renameTo(displacedDirectory))
                legacyDirectory.writeText("temporary non-directory entry")
                assertEquals(StoragePathState.UNAVAILABLE, storageDirectoryState(legacyDirectory))
                val partialRows = RecordingRepository.refresh(f.context).associateBy { it.id }
                assertEquals(selectedCached, partialRows[SAF_SELECTED])
                assertEquals(legacyCached.copy(waveformData = "", waveformRevision = ""), partialRows[legacyId])
                assertEquals(legacyCached, f.dao.findById(legacyId))

                check(legacyDirectory.delete())
                check(displacedDirectory.renameTo(legacyDirectory))
                val recoveredRows = RecordingRepository.refresh(f.context).associateBy { it.id }
                assertEquals(selectedCached, recoveredRows[SAF_SELECTED])
                assertEquals(legacyCached, recoveredRows[legacyId])
                assertEquals(legacyCached, f.dao.findById(legacyId))
            } finally {
                f.dao.deleteById(legacyId)
            }
        }
    }

    @Test fun completeEmptyListingDistinguishesUnavailableFromMissingAndRestoresSameRow() = runBlocking {
        fixture { f ->
            f.provider.listing = "empty"
            f.provider.item = "unavailable"
            assertTrue(RecordingRepository.refresh(f.context).any { it.id == SAF_SELECTED })
            assertEquals(f.selected, f.dao.findById(SAF_SELECTED))
            f.assertOwnedBytes()
            f.provider.item = "missing"
            val before = System.currentTimeMillis()
            assertFalse(RecordingRepository.refresh(f.context).any { it.id == SAF_SELECTED })
            val after = System.currentTimeMillis()
            val hidden = checkNotNull(f.dao.findById(SAF_SELECTED))
            assertNotNull(hidden.missingSinceMillis)
            assertTrue(checkNotNull(hidden.missingSinceMillis) in before..after)
            assertEquals(f.selected, hidden.copy(missingSinceMillis = null))
            assertFalse(RecordingRepository.listKnown(f.context).any { it.id == SAF_SELECTED })
            f.assertOwnedBytes()
            f.provider.item = "present"
            f.provider.listing = "complete"
            val recovered = RecordingRepository.refresh(f.context).single { it.id == SAF_SELECTED }
            assertEquals(f.selected.id, recovered.id)
            assertEquals(f.selected.fileIdentity, recovered.fileIdentity)
            assertEquals(f.selected.createdAtMillis, recovered.createdAtMillis)
            assertNull(recovered.missingSinceMillis)
            assertTrue(RecordingRepository.listKnown(f.context).any { it.id == SAF_SELECTED })
            assertEquals(0, f.provider.selectedOpens)
        }
    }

    @Test fun acquisitionReplacementAbortsReconciliationAndPreservesOriginalCatalogAndBothWavs() = runBlocking {
        fixture { f ->
            f.provider.revision = 101_000L
            val replacement = f.original.clone().also { it[44] = 42; it[45] = 43 }
            f.provider.replaceOnOpen = replacement
            unavailableRefresh(f.context)
            assertEquals(1, f.provider.selectedOpens)
            assertEquals(f.selected, f.dao.findById(SAF_SELECTED))
            assertTrue(RecordingRepository.listKnown(f.context).any { it.id == SAF_SELECTED })
            assertArrayEquals(f.original, checkNotNull(f.provider.retainedOriginal).readBytes())
            assertArrayEquals(replacement, f.provider.payload.readBytes())
            val openIndex = f.provider.trace.indexOf("open:selected")
            assertTrue(openIndex >= 0)
            assertTrue(f.provider.trace.drop(openIndex + 1).contains("query:selected:102000"))
            assertTrue(getRecorderPreferences(f.context).getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.isEmpty())
            assertTrue(getRecorderPreferences(f.context).getStringSet(PrefKey.PENDING_RECORDING_DELETIONS.name, emptySet())!!.isEmpty())
        }
    }

    @Test fun acceptedSaveRejectsNameChangedWhileOpeningCatalog() = runBlocking {
        fixture { f ->
            val incoming = f.selected.copy(codecSummary = "new save metadata")
            RecordingDatabase.getInstance(f.context).close()
            val traceStart = f.provider.trace.size
            f.context.beforeCatalogOpen = {
                f.provider.trace += "catalog:open"
                f.provider.selectedName = "external.wav"
            }
            val failure = try {
                RecordingRepository.registerFromAcceptedOperation(f.context, incoming)
                fail("registration accepted a stale provider name")
                null
            } catch (error: RecordingCatalogIdentityChangedException) {
                error
            } finally {
                f.context.beforeCatalogOpen = null
            }
            assertEquals("Recording changed before catalog registration", failure?.message)
            assertEquals(listOf("catalog:open", "query:selected:100000"), f.provider.trace.drop(traceStart))
            assertEquals(f.selected, f.dao.findById(SAF_SELECTED))
            f.assertOwnedBytes()

            val retryTraceStart = f.provider.trace.size
            val registered = RecordingRepository.registerFromAcceptedOperation(f.context,
                incoming.copy(displayName = "external.wav"))
            assertEquals(listOf("query:selected:100000"), f.provider.trace.drop(retryTraceStart))
            assertEquals("external.wav", registered.displayName)
            assertEquals(incoming.fileIdentity, registered.fileIdentity)
            assertEquals(incoming.codecSummary, registered.codecSummary)
            assertEquals(registered, f.dao.findById(SAF_SELECTED))
            f.assertOwnedBytes()
        }
    }

    private suspend fun unavailableRefresh(context: Context) {
        val error = try {
            RecordingRepository.refresh(context)
            fail("uncertain configured directory became an authoritative refresh")
            null
        } catch (failure: ConfiguredRecordingDirectoryUnavailableException) { failure }
        assertNotNull(error)
    }

    private class Fixture(val context: FixtureContext, val provider: SafRefreshProvider, val dao: RecordingDao,
        val selected: RecordingEntity, val sentinel: RecordingEntity, val original: ByteArray, val sentinelBytes: ByteArray) {
        fun assertOwnedBytes() {
            assertArrayEquals(original, provider.payload.readBytes())
            assertArrayEquals(sentinelBytes, provider.sentinel.readBytes())
        }
    }

    /** Remap only the app-owned environment; all catalog and scanner work stays production code. */
    private class FixtureContext(base: Context, private val root: File, private val preferences: SharedPreferences) : ContextWrapper(base) {
        var beforeCatalogOpen: (() -> Unit)? = null

        override fun getDatabasePath(name: String): File {
            if (name == DATABASE_FILE_NAME) {
                val onOpen = beforeCatalogOpen
                beforeCatalogOpen = null
                onOpen?.invoke()
            }
            return super.getDatabasePath(name)
        }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdirs()) }
        override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdirs()) }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { check(isDirectory || mkdirs()) }
        override fun getExternalFilesDir(type: String?): File = File(root, "legacy").apply { check(isDirectory || mkdirs()) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    private suspend fun fixture(
        expectedRenames: Int = 0,
        expectRenameIncident: Boolean = false,
        body: suspend (Fixture) -> Unit,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "saf-refresh-${UUID.randomUUID()}").apply { check(mkdir()) }
        val preferenceName = "saf-refresh-${root.name}"
        val preferences = app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val context = FixtureContext(app, root, preferences)
        val original = wav(123)
        val sentinelBytes = wav(-123)
        val payload = File(root, "clip.wav").apply { writeBytes(original) }
        val sentinelFile = File(root, "sentinel.wav").apply { writeBytes(sentinelBytes) }
        val provider = SafRefreshProvider(payload, sentinelFile)
        val media = EmptyRefreshMediaProvider()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        val incidentScope = RecordingIncidentStore::class.java.getDeclaredField("historyMutationScope")
            .apply { isAccessible = true }.get(RecordingIncidentStore) as CoroutineScope
        val incidentOwner = checkNotNull(incidentScope.coroutineContext[Job])
        var ownsShadow = false
        var incidentWritersDrained = false
        var ownsSelected = false
        var ownsSentinel = false
        var db: RecordingDatabase? = null
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { record(error) } }
        try {
            check(previousRoot == null)
            check(incidentOwner.children.none()) { "Incident writers already owned before SAF fixture" }
            JournalHostIoShadow.reset()
            ownsShadow = true
            // SQLite remains at its real Robolectric app path; no host metadata outside this app is admitted.
            ServiceStateOsShadow.fixtureRoot = checkNotNull(app.filesDir.parentFile).toPath()
            provider.attachInfo(context, ProviderInfo().apply { authority = SAF_AUTHORITY })
            media.attachInfo(context, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal(SAF_AUTHORITY, provider)
            ShadowContentResolver.registerProviderInternal("media", media)
            assertTrue(setConfiguredExportTreeUri(context, Uri.parse(SAF_TREE)))
            db = RecordingDatabase.getInstance(context)
            val activeDb = checkNotNull(db)
            assertEquals(app.getDatabasePath(checkNotNull(activeDb.databaseName)).canonicalFile,
                File(activeDb.writableDatabase.path).canonicalFile)
            assertTrue(File(activeDb.writableDatabase.path).canonicalPath.startsWith(
                checkNotNull(app.filesDir.parentFile).canonicalPath + File.separator))
            val dao = activeDb.recordingDao()
            assertNull(dao.findById(SAF_SELECTED))
            assertNull(dao.findById(SAF_SENTINEL))
            val selected = row(SAF_SELECTED, "clip.wav", original.size, 100_000L).copy(
                waveformData = "original-waveform-sentinel", waveformRevision = "original-waveform-revision")
            val sentinel = row(SAF_SENTINEL, "sentinel.wav", sentinelBytes.size, 90_000L)
            ownsSelected = true
            dao.upsert(selected)
            ownsSentinel = true
            dao.upsert(sentinel)
            assertTrue(suppressProviderOutputWithoutDeletion(context, RecordingStorageType.DOCUMENT, SAF_SENTINEL,
                CopyDigest(sentinelBytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(sentinelBytes))))
            val suppression = preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            body(Fixture(context, provider, dao, selected, sentinel, original, sentinelBytes))
            assertEquals(sentinel, dao.findById(SAF_SENTINEL))
            assertArrayEquals(sentinelBytes, sentinelFile.readBytes())
            assertEquals(suppression, preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
            assertEquals(0, provider.mutations)
            assertEquals(List(expectedRenames) { "android:renameDocument" }, provider.callMethods)
            assertEquals(expectedRenames, provider.renameRequests.size)
            assertEquals(0, media.mutations)
            provider.assertClosed()
            media.assertClosed()
        } catch (error: Throwable) { record(error) }
        finally {
            cleanup { provider.assertClosed() }
            cleanup { provider.drain() }
            cleanup { media.assertClosed() }
            cleanup { media.drain() }
            cleanup { runBlocking { if (ownsSelected) checkNotNull(db).recordingDao().deleteById(SAF_SELECTED) } }
            cleanup { runBlocking { if (ownsSentinel) checkNotNull(db).recordingDao().deleteById(SAF_SENTINEL) } }
            cleanup { if (db != null) RecordingDatabase.resetAfterCorruption() }
            if (ownsShadow) {
                cleanup {
                    // Rename failures can queue an incident write before returning. That writer
                    // owns the directory descriptor until its publication barrier completes.
                    runBlocking { withTimeout(8_000) { incidentOwner.children.toList().joinAll() } }
                    incidentWritersDrained = true
                    if (expectRenameIncident) {
                        val history = File(context.noBackupFilesDir, "recording-incidents.bin")
                        val incident = DataInputStream(history.inputStream().buffered()).use { input ->
                            readIncidentPayloadExact(input, "SAF rename incident",
                                RecordingIncidentStore::readHistoryPayload)
                        }.single()
                        assertEquals(RecordingIncidentKind.UNEXPECTED_ERROR, incident.kind)
                        val description = incident.description.orEmpty()
                        assertTrue(description.contains("Rename recording"))
                        assertTrue(description.contains("Document recording changed before rename"))
                        assertTrue("Incident history crossed the directory barrier",
                            JournalHostIoShadow.directorySyncAttempts > 0)
                    }
                }
                // A timed-out owner may still be using the fixture. Leave its evidence intact.
                if (incidentWritersDrained) {
                    cleanup { JournalHostIoShadow.finishFixture()?.let(::record) }
                    cleanup { ServiceStateOsShadow.fixtureRoot = previousRoot }
                }
            }
            if (failure == null) cleanup { check(app.deleteSharedPreferences(preferenceName)) }
            if (failure == null) cleanup { check(root.deleteRecursively()) }
        }
        failure?.let { throw it }
    }

    private fun row(id: String, name: String, size: Int, revision: Long): RecordingEntity {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
        return RecordingEntity(id, name, "audio/wav", 1L, 100L, size.toLong(), "WAV", RecordingStorageType.DOCUMENT,
            SAF_TREE, "provider:2:$encoded:$size:$revision", createdAtMillis = 1L, lastSeenAtMillis = 1L)
    }

    private fun wav(sample: Short): ByteArray = ByteBuffer.allocate(1644).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(1636)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(1600)
        repeat(800) { putShort(sample) }
    }.array()
}

private const val SAF_AUTHORITY = "reverb.saf.fixture"
private const val SAF_TREE = "content://reverb.saf.fixture/tree/root"
private const val SAF_SELECTED = "content://reverb.saf.fixture/tree/root/document/root%2Fclip.wav"
private const val SAF_SENTINEL = "content://reverb.saf.fixture/tree/root/document/root%2Fsentinel.wav"

/** Only external DocumentsContract query/open behavior is scripted. Registry resets per Robolectric method. */
private class SafRefreshProvider(val payload: File, val sentinel: File) : ContentProvider() {
    var listing = "complete"
    var item = "present"
    var revision = 100_000L
    var selectedName = "clip.wav"
    var renameSupported = false
    var nameAfterRename: String? = null
    var onPostRenameOpen: (() -> Unit)? = null
    var itemAfterRename = "present"
    val postRenameProjections = mutableListOf<List<String>>()
    val capabilityObservations = mutableListOf<Boolean>()
    val callMethods = mutableListOf<String>()
    val renameRequests = mutableListOf<Pair<Uri, String>>()
    var replaceOnOpen: ByteArray? = null
    var retainedOriginal: File? = null
    var selectedOpens = 0
    var mutations = 0
    val trace = mutableListOf<String>()
    private val cursors = mutableListOf<Cursor>()
    private val descriptors = mutableListOf<Pair<ParcelFileDescriptor, FileDescriptor>>()
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        check(selection == null && selectionArgs == null)
        val children = uri.lastPathSegment == "children"
        val selected = uri.toString() == SAF_SELECTED
        check(children || selected || uri.toString() == SAF_SENTINEL)
        trace += if (children) "query:children:$listing" else if (selected) "query:selected:$revision" else "query:sentinel"
        if ((children && listing == "null") || (selected && item == "unavailable")) return null
        if (children && listing == "denied") throw SecurityException("SAF tree temporarily denied")
        val extras = Bundle().apply {
            if (children && listing == "loading") putBoolean(DocumentsContract.EXTRA_LOADING, true)
            if (children && listing == "error") putString(DocumentsContract.EXTRA_ERROR, "injected listing error")
        }
        val columns = checkNotNull(projection)
        if (selected && renameRequests.isNotEmpty()) postRenameProjections += columns.toList()
        if (selected && columns.contentEquals(arrayOf(DocumentsContract.Document.COLUMN_FLAGS))) {
            capabilityObservations += renameSupported
        }
        val cursor = object : MatrixCursor(columns) { override fun getExtras(): Bundle = extras }
        cursors += cursor
        if (children && listing == "empty" || selected && item == "missing") return cursor
        val isSelected = children || selected
        val file = if (isSelected) payload else sentinel
        val values: Map<String, Any?> = mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to if (isSelected) "root/clip.wav" else "root/sentinel.wav",
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (isSelected) selectedName else "sentinel.wav",
            DocumentsContract.Document.COLUMN_MIME_TYPE to "audio/wav",
            DocumentsContract.Document.COLUMN_SIZE to file.length(),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to if (isSelected) revision else 90_000L,
            DocumentsContract.Document.COLUMN_FLAGS to if (isSelected && renameSupported)
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME else 0,
        )
        val row = columns.map { check(values.containsKey(it)); values[it] }
        cursor.addRow(row)
        if (children && listing == "duplicate") cursor.addRow(row)
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "r")
        val selected = uri.toString() == SAF_SELECTED
        check(selected || uri.toString() == SAF_SENTINEL)
        trace += if (selected) "open:selected" else "open:sentinel"
        if (selected) {
            selectedOpens++
            if (renameRequests.isNotEmpty()) {
                val onOpen = onPostRenameOpen
                onPostRenameOpen = null
                onOpen?.invoke()
            }
            replaceOnOpen?.let { replacement ->
                val retained = File(payload.parentFile, "original-retained.wav")
                check(payload.renameTo(retained))
                retainedOriginal = retained
                payload.writeBytes(replacement)
                revision = 102_000L
                replaceOnOpen = null
            }
        }
        return ParcelFileDescriptor.open(if (selected) payload else sentinel, ParcelFileDescriptor.MODE_READ_ONLY).also {
            descriptors += it to it.fileDescriptor
        }
    }
    override fun getType(uri: Uri) = "audio/wav"
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        callMethods += method
        check(method == "android:renameDocument" && arg == null && renameSupported)
        val request = checkNotNull(extras)
        val source = checkNotNull(request.getParcelable("uri", Uri::class.java))
        check(source.toString() == SAF_SELECTED)
        val requestedName = checkNotNull(request.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        renameRequests += source to requestedName
        selectedName = nameAfterRename ?: requestedName
        item = itemAfterRename
        revision = 100_001L
        trace += "rename:selected"
        return Bundle().apply { putParcelable("uri", source) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; error("refresh cannot insert provider audio") }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        mutations++; error("refresh cannot update provider audio")
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        mutations++; error("refresh cannot delete provider audio")
    }
    fun assertClosed() {
        assertTrue("every acquired cursor closed", cursors.all { it.isClosed })
        assertTrue("every acquired descriptor closed", descriptors.all { !it.second.valid() })
    }
    fun drain() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        } }
        for (cursor in cursors) attempt { if (!cursor.isClosed) cursor.close() }
        for ((owner, descriptor) in descriptors) attempt { if (descriptor.valid()) owner.close() }
        failure?.let { throw it }
    }
}

private class EmptyRefreshMediaProvider : ContentProvider() {
    var mutations = 0
    private val cursors = mutableListOf<Cursor>()
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
        MatrixCursor(checkNotNull(projection)).also { cursors += it }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; error("unexpected MediaStore insert") }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        mutations++; error("unexpected MediaStore update")
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        mutations++; error("unexpected MediaStore delete")
    }
    fun assertClosed() = assertTrue("every MediaStore cursor closed", cursors.all { it.isClosed })
    fun drain() {
        var failure: Throwable? = null
        for (cursor in cursors) try { if (!cursor.isClosed) cursor.close() } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
