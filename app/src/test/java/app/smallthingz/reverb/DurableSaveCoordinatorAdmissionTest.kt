package app.smallthingz.reverb

import android.content.Context
import android.content.pm.ProviderInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.robolectric.shadows.ShadowContentResolver
import android.content.ContextWrapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import android.app.NotificationManager
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
class DurableSaveCoordinatorAdmissionTest {
    private class SimulatedProcessDeath : Error()

    private fun coordinator(context: Context): DurableSaveCoordinator =
        DurableSaveCoordinator::class.java.getDeclaredConstructor(Context::class.java).apply {
            isAccessible = true
        }.newInstance(context)

    private fun readPcm(record: DurableSaveRecord): ByteArray = ByteArrayOutputStream().also { output ->
        record.source.range.readNormalized(8_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, size ->
            output.write(bytes, offset, size)
            size
        }
    }.toByteArray()

    @Test fun admissionCrashBoundariesRecoverOnlyDurablyQueuedWork() {
        for (boundary in listOf("PIN_PLAN_DURABLE", "PINS_DURABLE", "QUEUED_DURABLE")) {
            withFixture { context, root, store, pcm ->
                val first = coordinator(context)
                var observedId: String? = null
                DurableSaveFaultHooks.onBoundary = { id, reached ->
                    if (reached == boundary) { observedId = id; throw SimulatedProcessDeath() }
                }
                try {
                    requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                        try { first.admit(lease, request()); fail("expected process death at $boundary") }
                        catch (_: SimulatedProcessDeath) { }
                    }
                } finally { DurableSaveFaultHooks.onBoundary = null }
                val id = requireNotNull(observedId)
                val journal = DurableSaveJournal(File(root, "durable-saves"))
                val before = journal.read(id)
                assertEquals(if (boundary == "QUEUED_DURABLE") DurableSaveStage.QUEUED else DurableSaveStage.PINNING,
                    before.stage)
                if (boundary != "PIN_PLAN_DURABLE") assertArrayEquals(pcm, readPcm(before))
                val recovered = coordinator(context).recover()
                if (boundary == "QUEUED_DURABLE") {
                    assertEquals(listOf(id), recovered.map { it.id })
                    assertArrayEquals(pcm, readPcm(journal.read(id)))
                } else {
                    assertTrue(recovered.isEmpty())
                    assertFalse(journal.directory(id).exists())
                }
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    val bytes = ByteArrayOutputStream()
                    lease.withSealedRange { range ->
                        range.readNormalized(8_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { b, o, n ->
                            bytes.write(b, o, n); n
                        }
                    }
                    assertArrayEquals(pcm, bytes.toByteArray())
                }
                if (boundary == "QUEUED_DURABLE") retireQueued(journal, id)
                DurableSaveJobs.remove(id)
            }
        }
    }

    @Test fun returnedAdmissionSurvivesRecoveryAndClearWithExactPcm() = withFixture { context, root, store, pcm ->
        val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
            coordinator(context).admit(it, request())
        }
        val journal = DurableSaveJournal(File(root, "durable-saves"))
        assertEquals(DurableSaveStage.QUEUED, journal.read(handle.id).stage)
        store.clear()
        store.close()
        reopenStore(root).use { assertEquals(0.0, it.durationSeconds(), 0.0) }
        val fresh = coordinator(context)
        assertEquals(listOf(handle.id), fresh.recover().map { it.id })
        assertEquals(1, fresh.pendingCount())
        assertArrayEquals(pcm, readPcm(journal.read(handle.id)))
        retireQueued(journal, handle.id)
        DurableSaveJobs.remove(handle.id)
    }

    @Test fun overlappingAcceptedJobsRetireIndependently() = withFixture { context, root, store, pcm ->
        val admission = coordinator(context)
        fun admit(startFrame: Int, endFrame: Int): DurableSaveHandle =
            requireNotNull(store.acquireRange(startFrame / 8_000.0, endFrame / 8_000.0)).use {
                admission.admit(it, request())
            }
        val first = admit(0, 192)
        val second = admit(64, 256)
        assertNotEquals(first.id, second.id)
        val journal = DurableSaveJournal(File(root, "durable-saves"))
        store.clear()
        store.close()
        reopenStore(root).use { assertEquals(0.0, it.durationSeconds(), 0.0) }
        val fresh = coordinator(context)
        assertEquals(setOf(first.id, second.id), fresh.recover().map { it.id }.toSet())
        assertArrayEquals(pcm.copyOfRange(0, 384), readPcm(journal.read(first.id)))
        assertArrayEquals(pcm.copyOfRange(128, 512), readPcm(journal.read(second.id)))
        assertTrue(journal.read(second.id).source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
        retireQueued(journal, first.id)
        assertFalse(journal.directory(first.id).exists())
        assertArrayEquals(pcm.copyOfRange(128, 512), readPcm(journal.read(second.id)))
        assertTrue(journal.read(second.id).source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
        assertEquals(listOf(second.id), coordinator(context).recover().map { it.id })
        retireQueued(journal, second.id)
        DurableSaveJobs.remove(first.id)
        DurableSaveJobs.remove(second.id)
    }

    private fun request() = DurableSaveRequest("test", "fixture-destination", ExportFormat.WAV,
        ExportCodec.PCM_16, 8_000, 1, PcmSampleFormat.PCM_16, 5)

    private fun retireQueued(journal: DurableSaveJournal, id: String) {
        val cancelled = journal.read(id).copy(stage = DurableSaveStage.CANCELLED)
        journal.write(cancelled)
        journal.retire(cancelled)
    }

    private fun reopenStore(root: File) = PersistentAudioChunkStore(File(root, "store")).also {
        it.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16, deferRetentionCleanup = true)
    }

    @Test fun materializedWavPublishesExactPcmAndRetiresPins() = withFixture { context, root, store, pcm ->
        withOwnedMedia(context, root) { provider ->
            val first = coordinator(context)
            val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                first.admit(it, managedWavRequest())
            }
            provider.ownedJobIds += handle.id
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            val source = journal.read(handle.id).source.objects
            assertTrue(source.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
            val recording = first.materialize(handle.id, checkActive = {})
            assertEquals(provider.uri.toString(), recording.id)
            assertEquals("test.wav", recording.displayName)
            assertEquals(0, provider.pending)
            assertEquals(1, provider.inserts)
            assertArrayEquals(expectedWav(pcm), File(root, "provider-output.wav").readBytes())
            val catalog = runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(recording.id) }
            assertEquals(recording, catalog)
            assertEquals(expectedProviderIdentity(provider), recording.fileIdentity)
            assertFalse(provider.deleted)
            assertFalse(journal.directory(handle.id).exists())
            assertTrue(source.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
            provider.assertClosed()
        }
    }

    @Test fun verifiedBoundaryInterruptionRecoversSameWavAndRetiresPins() = withFixture { context, root, store, pcm ->
        withOwnedMedia(context, root) { provider ->
            val first = coordinator(context)
            val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                first.admit(it, managedWavRequest())
            }
            provider.ownedJobIds += handle.id
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            DurableSaveFaultHooks.onBoundary = { id, stage ->
                if (id == handle.id && stage == "VERIFIED_DURABLE") throw SimulatedProcessDeath()
            }
            try {
                try { first.materialize(handle.id, checkActive = {}); fail("expected interruption") }
                catch (_: SimulatedProcessDeath) { }
            } finally { DurableSaveFaultHooks.onBoundary = null }
            val verified = journal.read(handle.id)
            assertEquals(DurableSaveStage.VERIFIED, verified.stage)
            assertEquals(provider.uri.toString(), requireNotNull(verified.output).id)
            val payload = File(root, "provider-output.wav")
            assertArrayEquals(expectedWav(pcm), payload.readBytes())
            val before = java.nio.file.Files.getAttribute(payload.toPath(), "unix:ino")
            assertTrue(verified.source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
            store.clear(); store.close()
            reopenStore(root).use { assertEquals(0.0, it.durationSeconds(), 0.0) }
            val fresh = coordinator(context)
            assertEquals(listOf(handle.id), fresh.recover().map { it.id })
            val recording = fresh.materialize(handle.id, checkActive = {})
            assertEquals(provider.uri.toString(), recording.id)
            assertEquals(before, java.nio.file.Files.getAttribute(payload.toPath(), "unix:ino"))
            assertEquals(1, provider.inserts)
            assertEquals(0, provider.pending)
            assertFalse(provider.deleted)
            assertEquals(expectedProviderIdentity(provider), recording.fileIdentity)
            assertArrayEquals(expectedWav(pcm), payload.readBytes())
            assertEquals(recording, runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(recording.id) })
            assertFalse(journal.directory(handle.id).exists())
            assertTrue(verified.source.objects.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
            provider.assertClosed()
        }
    }

    @Test fun publishedBoundaryInterruptionRecoversWithoutSecondProviderUpdate() = withFixture { context, root, store, pcm ->
        withOwnedMedia(context, root) { provider ->
            val first = coordinator(context)
            val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                first.admit(it, managedWavRequest())
            }
            provider.ownedJobIds += handle.id
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            DurableSaveFaultHooks.onBoundary = { id, stage ->
                if (id == handle.id && stage == "AFTER_RENAME_BEFORE_MANIFEST") throw SimulatedProcessDeath()
            }
            try {
                try { first.materialize(handle.id, checkActive = {}); fail("expected interruption") }
                catch (_: SimulatedProcessDeath) { }
            } finally { DurableSaveFaultHooks.onBoundary = null }
            val publishing = journal.read(handle.id)
            assertEquals(DurableSaveStage.PUBLISHING, publishing.stage)
            assertEquals(provider.uri.toString(), requireNotNull(publishing.output).id)
            assertEquals("test.wav", provider.name)
            assertEquals(0, provider.pending)
            assertEquals(1, provider.inserts)
            assertEquals(1, provider.updates)
            assertFalse(provider.deleted)
            val updatesBeforeRetry = provider.updates
            val payload = File(root, "provider-output.wav")
            assertArrayEquals(expectedWav(pcm), payload.readBytes())
            val inode = Files.getAttribute(payload.toPath(), "unix:ino")
            assertTrue(publishing.source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
            assertNull(runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(provider.uri.toString()) })
            store.clear(); store.close()
            reopenStore(root).use { assertEquals(0.0, it.durationSeconds(), 0.0) }
            val fresh = coordinator(context)
            assertEquals(listOf(handle.id), fresh.recover().map { it.id })
            val recording = fresh.materialize(handle.id, checkActive = {})
            assertEquals(provider.uri.toString(), recording.id)
            assertEquals("test.wav", recording.displayName)
            assertEquals(expectedProviderIdentity(provider), recording.fileIdentity)
            assertEquals(inode, Files.getAttribute(payload.toPath(), "unix:ino"))
            assertEquals(1, provider.inserts)
            assertEquals(updatesBeforeRetry, provider.updates)
            assertEquals(0, provider.pending)
            assertFalse(provider.deleted)
            assertArrayEquals(expectedWav(pcm), payload.readBytes())
            assertEquals(recording, runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(recording.id) })
            assertFalse(journal.directory(handle.id).exists())
            assertTrue(publishing.source.objects.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
            provider.assertClosed()
        }
    }

    @Test fun failureAfterPinRetirementDoesNotResurrectCompletedSave() = withFixture { context, root, store, pcm ->
        withOwnedMedia(context, root) { provider ->
            val coordinator = coordinator(context)
            val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                coordinator.admit(it, managedWavRequest())
            }
            provider.ownedJobIds += handle.id
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            val source = journal.read(handle.id).source.objects
            val injected = java.io.IOException("after durable retirement")
            var failureCallbacks = 0
            DurableSaveFaultHooks.onBoundary = { id, boundary ->
                if (id == handle.id && boundary == "AFTER_PIN_RELEASE") throw injected
            }
            try {
                val caught = try {
                    coordinator.materialize(handle.id, onFailurePublished = { failureCallbacks++ }, checkActive = {})
                    fail("expected post-retirement failure")
                    null
                } catch (error: java.io.IOException) { error }
                assertSame(injected, caught)
            } finally { DurableSaveFaultHooks.onBoundary = null }
            assertEquals(0, failureCallbacks)
            assertFalse(journal.directory(handle.id).exists())
            assertTrue(source.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
            assertEquals(0, coordinator.pendingCount())
            val beforeRecovery = DurableSaveJobs.state.value.filter { it.id == handle.id }
            assertTrue(beforeRecovery.none { it.phase == SavePhase.FAILED })
            assertEquals(1, beforeRecovery.size)
            assertTrue(beforeRecovery.single().terminallyRetired)
            assertEquals(provider.uri.toString(), beforeRecovery.single().publishedRecordingId)
            assertEquals(expectedProviderIdentity(provider), beforeRecovery.single().publishedRecordingIdentity)
            assertTrue(coordinator(context).recover().isEmpty())
            val jobs = DurableSaveJobs.state.value.filter { it.id == handle.id }
            assertTrue(jobs.none { it.phase == SavePhase.FAILED })
            val row = requireNotNull(runBlocking {
                RecordingDatabase.getInstance(context).recordingDao().findById(provider.uri.toString())
            })
            assertEquals(expectedProviderIdentity(provider), row.fileIdentity)
            assertEquals("test.wav", row.displayName)
            assertEquals(1, provider.inserts)
            assertEquals(0, provider.pending)
            assertFalse(provider.deleted)
            assertArrayEquals(expectedWav(pcm), File(root, "provider-output.wav").readBytes())
            provider.assertClosed()
        }
    }

    private fun managedWavRequest() = DurableSaveRequest("test", MEDIA_STORE_DIRECTORY_ID, ExportFormat.WAV,
        ExportCodec.PCM_16, 8_000, 1, PcmSampleFormat.PCM_16, 5)

    // Independent RIFF oracle, exact full file including EOF; no production WAV helpers.
    private fun expectedWav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
        }.array()

    private fun expectedProviderIdentity(provider: CoordinatorMediaProvider): String =
        "provider:3:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(provider.uri.toString().toByteArray(Charsets.UTF_8)) + ":556:9"

    private fun withOwnedMedia(context: Context, root: File, body: (CoordinatorMediaProvider) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val preferences = getRecorderPreferences(context)
        val keys = listOf(PrefKey.VERIFIED_EXPORT_STAGING.name, PrefKey.PENDING_OUTPUT_CLEANUP.name,
            PrefKey.PENDING_RECORDING_DELETIONS.name)
        val previous = keys.associateWith { key ->
            if (preferences.contains(key)) preferences.getStringSet(key, emptySet())!!.toSet() else null
        }
        val provider = CoordinatorMediaProvider(File(root, "provider-output.wav"))
        var dao: RecordingDao? = null
        var databaseAcquired = false
        @Suppress("UNCHECKED_CAST")
        val registry = ShadowContentResolver::class.java.getDeclaredField("providers").apply {
            isAccessible = true
        }.get(null) as MutableMap<String, android.content.ContentProvider>
        val previousProvider = registry["media"]
        var ownsProvider = false
        var ownsCatalogId = false
        var handoffProven = false
        var primary: Throwable? = null
        try {
            check(getConfiguredOutputDirectoryId(context) == MEDIA_STORE_DIRECTORY_ID)
            val db = RecordingDatabase.getInstance(context)
            databaseAcquired = true
            val path = File(db.writableDatabase.path).canonicalFile
            check(path == app.getDatabasePath(checkNotNull(db.databaseName)).canonicalFile)
            check(path.path.startsWith(checkNotNull(app.filesDir.parentFile).canonicalPath + File.separator))
            val activeDao = db.recordingDao()
            dao = activeDao
            check(runBlocking { activeDao.findById(provider.uri.toString()) } == null)
            ownsCatalogId = true
            provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            ownsProvider = true
            body(provider)
            provider.assertClosed()
        } catch (error: Throwable) { primary = error }
        finally {
            val cleanup = listOf<() -> Unit>(
                {
                    val jobs = DurableSaveJobs.state.value.filter { it.id in provider.ownedJobIds }
                    val terminal = jobs.filter { it.terminallyRetired }
                    if (terminal.isNotEmpty()) {
                        check(terminal.all { it.publishedRecordingId == provider.uri.toString() &&
                            it.publishedRecordingIdentity == expectedProviderIdentity(provider) })
                        val row = runBlocking { requireNotNull(dao).findById(provider.uri.toString()) }
                        check(row != null && row.fileIdentity == expectedProviderIdentity(provider))
                        check(DurableSaveJobs.state.value.none { it.id !in provider.ownedJobIds &&
                            it.publishedRecordingId == provider.uri.toString() })
                        DurableSaveJobs.acknowledgePublished(mapOf(provider.uri.toString() to row.fileIdentity))
                        check(DurableSaveJobs.state.value.none { it.id in terminal.map { job -> job.id } })
                    }
                    handoffProven = true
                },
                { if (ownsProvider) {
                    check(registry["media"] === provider) { "Media provider owner changed" }
                    if (previousProvider == null) registry.remove("media") else registry["media"] = previousProvider
                } },
                { provider.assertClosed() },
                { provider.closeLeaked() },
                { if (ownsCatalogId && handoffProven) runBlocking { requireNotNull(dao).deleteById(provider.uri.toString()) } },
                { if (databaseAcquired) RecordingDatabase.resetAfterCorruption() },
                { check(preferences.edit().also { editor -> previous.forEach { (key, value) ->
                    if (value == null) editor.remove(key) else editor.putStringSet(key, value)
                } }.commit()) },
            )
            cleanup.forEach { action -> try { action() } catch (error: Throwable) {
                if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
            } }
        }
        primary?.let { throw it }
    }

    private fun withFixture(body: (Context, File, PersistentAudioChunkStore, ByteArray) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        check(ServiceStateOsShadow.fixtureRoot == null)
        check(DurableSaveFaultHooks.onBoundary == null)
        JournalHostIoShadow.reset()
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "coordinator-admission-").toFile()
        var failure: Throwable? = null
        var store: PersistentAudioChunkStore? = null
        var parent: Job? = null
        var priorChildren: Set<Job> = emptySet()
        var startedField: java.lang.reflect.Field? = null
        var readiness: MutableStateFlow<DurableSaveJobs.Readiness>? = null
        var priorReadiness: DurableSaveJobs.Readiness? = null
        var manager: NotificationManager? = null
        var priorTags: Set<Pair<String?, Int>> = emptySet()
        var ownsCollector = false
        var ownsJobs = false
        fun record(error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        try {
            ServiceStateOsShadow.fixtureRoot = root.toPath().toAbsolutePath().normalize()
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = root
            }
            check(DurableSaveJobs.state.value.isEmpty()) { "Other save jobs own process state" }
            ownsJobs = true
            manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
            priorTags = manager!!.activeNotifications.map { it.tag to it.id }.toSet()
            check(priorTags.none { it.first?.startsWith("reverb-save-job:") == true })
            @Suppress("UNCHECKED_CAST")
            val readinessState = DurableSaveJobs::class.java.getDeclaredField("mutableReadiness").apply {
                isAccessible = true
            }.get(DurableSaveJobs) as MutableStateFlow<DurableSaveJobs.Readiness>
            readiness = readinessState
            priorReadiness = readinessState.value
            val started = DurableSaveNotifications::class.java.getDeclaredField("started").apply { isAccessible = true }
            val scopeField = DurableSaveNotifications::class.java.getDeclaredField("scope").apply { isAccessible = true }
            check(!started.getBoolean(DurableSaveNotifications)) { "Notification collector already owned" }
            startedField = started
            val scope = scopeField.get(DurableSaveNotifications) as CoroutineScope
            parent = requireNotNull(scope.coroutineContext[Job])
            priorChildren = parent!!.children.toSet()
            ownsCollector = true
            val activeStore = PersistentAudioChunkStore(File(root, "store"))
            store = activeStore
            activeStore.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
            val pcm = ByteArray(512) { (it * 29 + 7).toByte() }
            assertEquals(pcm.size, activeStore.append(pcm, 0, pcm.size))
            body(context, root, activeStore, pcm)
        } catch (error: Throwable) { record(error) }
        finally {
            DurableSaveFaultHooks.onBoundary = null
            if (ownsJobs) try {
                val ids = File(root, "durable-saves").listFiles().orEmpty().map { it.name }.toSet() +
                    DurableSaveJobs.state.value.map { it.id }
                ids.forEach { DurableSaveJobs.remove(it) }
            }
            catch (error: Throwable) { record(error) }
            if (ownsCollector) {
                var joined = true
                try {
                    val owned = parent!!.children.toSet() - priorChildren
                    if (owned.size > 1) record(AssertionError("Unexpected notification collector ownership: $owned"))
                    owned.forEach { child ->
                        try { runBlocking { withTimeout(8_000) { child.cancelAndJoin() } } }
                        catch (error: Throwable) { joined = false; record(error) }
                    }
                } catch (error: Throwable) { joined = false; record(error) }
                if (joined) try { startedField!!.setBoolean(DurableSaveNotifications, false) }
                catch (error: Throwable) { record(error) }
                if (joined) try {
                    manager!!.activeNotifications.filter {
                        it.tag?.startsWith("reverb-save-job:") == true && (it.tag to it.id) !in priorTags
                    }.forEach { manager!!.cancel(it.tag, it.id) }
                } catch (error: Throwable) { record(error) }
                if (joined) try { readiness!!.value = priorReadiness!! } catch (error: Throwable) { record(error) }
            }
            try { store?.close() } catch (error: Throwable) { record(error) }
            try { JournalHostIoShadow.finishFixture()?.let(::record) } catch (error: Throwable) { record(error) }
            if (failure == null) {
                try { check(root.deleteRecursively()) } catch (error: Throwable) { record(error) }
            }
            ServiceStateOsShadow.fixtureRoot = null
        }
        failure?.let { throw it }
    }
}
