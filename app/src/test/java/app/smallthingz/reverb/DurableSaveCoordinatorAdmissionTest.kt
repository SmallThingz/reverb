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
import kotlinx.coroutines.ensureActive
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

    @Test fun delayedRecoverySkipsDeliveredSaveAndDeliversFirstPublishedRecovery() = withFixture { context, root, store, _ ->
        withOwnedMedia(context, root) { provider ->
            provider.allowRetry = true
            val coordinator = coordinator(context)
            fun admit(name: String): String = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                val handle = coordinator.admit(it, managedWavRequest().copy(name = name))
                provider.ownedJobIds += handle.id
                handle.id
            }

            val retryId = admit("retry")
            val interruption = java.io.IOException("interrupted after publication")
            DurableSaveFaultHooks.onBoundary = { id, stage ->
                if (id == retryId && stage == "PUBLISHED_DURABLE") throw interruption
            }
            try {
                coordinator.materialize(retryId) {}
                fail("expected interruption after publication")
            } catch (error: java.io.IOException) {
                assertSame(interruption, error)
            } finally {
                DurableSaveFaultHooks.onBoundary = null
            }
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            assertEquals(DurableSaveStage.PUBLISHED, journal.read(retryId).stage)

            val activeId = admit("active")
            val scanned = coordinator.recover()
            assertEquals(setOf(retryId, activeId), scanned.map { it.id }.toSet())
            // Main has not consumed the scan. The original worker finishes and posts success first.
            val originalPosts = mutableListOf<String>()
            coordinator.materialize(activeId, onReady = { originalPosts += it.id }) {}
            assertFalse(journal.directory(activeId).exists())
            val writesBeforeRecovery = provider.updates
            val recoveryPosts = mutableListOf<String>()
            for (handle in scanned) {
                coordinator.materialize(handle.id, onReady = { recoveryPosts += it.id }) {}
            }

            assertEquals(listOf(provider.retryUri.toString()), originalPosts)
            assertEquals(listOf(provider.uri.toString()), recoveryPosts)
            assertEquals(2, provider.inserts)
            assertEquals(writesBeforeRecovery, provider.updates)
            assertFalse(journal.directory(retryId).exists())
            assertEquals(0, coordinator.pendingCount())
        }
    }

    @Test fun recoveryFinishesFirstAndRetiredSaveGetsCachedResultWithoutReplayingSuccess() =
        withFixture { context, root, store, pcm ->
            withOwnedMedia(context, root) { provider ->
                val coordinator = coordinator(context)
                val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                    coordinator.admit(it, managedWavRequest())
                }
                provider.ownedJobIds += handle.id
                val journal = DurableSaveJournal(File(root, "durable-saves"))
                val queued = journal.read(handle.id)
                assertEquals(DurableSaveStage.QUEUED, queued.stage)
                val source = queued.source.objects
                assertTrue(source.all { DurablePcmPins.isProtected(File(it.sourcePath)) })

                val originalPosts = mutableListOf<RecordingEntity>()
                // Retain the original invocation until recovery has completed on the same coordinator.
                val drainRetiredOriginal = {
                    coordinator.materialize(handle.id, onReady = { originalPosts += it }) {
                        error("The retired generation cannot start materialization")
                    }
                }
                val recoveryPosts = mutableListOf<RecordingEntity>()
                val recovered = coordinator.recover().single()
                assertEquals(handle.id, recovered.id)
                val recording = coordinator.materialize(recovered.id, onReady = { recoveryPosts += it }) {}
                assertEquals(listOf(recording), recoveryPosts)
                assertEquals(provider.uri.toString(), recording.id)

                val writes = provider.updates
                assertSame(recording, drainRetiredOriginal())
                assertTrue("The retired original must not replay success", originalPosts.isEmpty())
                assertEquals(listOf(recording), recoveryPosts)
                assertEquals(1, provider.inserts)
                assertEquals(writes, provider.updates)
                assertEquals(0, provider.pending)
                assertArrayEquals(expectedWav(pcm), File(root, "provider-output.wav").readBytes())
                assertFalse(journal.directory(handle.id).exists())
                assertTrue(source.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
                assertEquals(0, coordinator.pendingCount())
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

    @Test fun cancelledWritingDeletesOnlyPartialThenRetriesExactPcm() = withFixture { context, root, store, initial ->
        withOwnedMedia(context, root) { provider ->
            provider.allowRetry = true
            val sentinel = byteArrayOf(19, 7, 31, 11)
            provider.addSentinel(sentinel)
            val sentinelIdentity = resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, provider.sentinelUri)
            assertEquals(expectedProviderIdentity(provider.sentinelUri, sentinel.size), sentinelIdentity)
            val stagingBefore = getRecorderPreferences(context)
                .getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.toSet()
            val cleanupBefore = getRecorderPreferences(context)
                .getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            store.configure(RetentionMode.SIZE, 262_144, 8_000, 1, PcmSampleFormat.PCM_16)
            val added = ByteArray(131_072) { (it * 17 + 23).toByte() }
            assertEquals(added.size, store.append(added, 0, added.size))
            val pcm = initial + added
            val coordinator = coordinator(context)
            val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                coordinator.admit(it, managedWavRequest())
            }
            provider.ownedJobIds += handle.id
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            val job = Job()
            val cancellation = java.util.concurrent.CancellationException("cancel accepted materialization")
            var batches = 0
            var failureCallbacks = 0
            DurableSaveFaultHooks.onBoundary = { id, boundary ->
                if (id == handle.id && boundary == "PCM_BATCH") {
                    batches++
                    if (batches == 1) job.cancel(cancellation)
                }
            }
            try {
                val caught = try {
                    coordinator.materialize(handle.id, onFailurePublished = { failureCallbacks++ },
                        checkActive = { job.ensureActive() })
                    fail("expected cancellation")
                    null
                } catch (error: java.util.concurrent.CancellationException) { error }
                assertSame(cancellation, caught)
            } finally { DurableSaveFaultHooks.onBoundary = null }
            assertEquals(1, batches)
            assertEquals(1, failureCallbacks)
            assertEquals(1, provider.pendingOf(provider.uri))
            assertNull(runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(provider.uri.toString()) })
            assertEquals(stagingBefore, getRecorderPreferences(context)
                .getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
            val partial = provider.output(provider.uri).readBytes()
            val written = partial.size - 44
            assertTrue(written > 0 && written < pcm.size && written % 2 == 0)
            assertArrayEquals(expectedWav(pcm.copyOfRange(0, written)), partial)
            val retained = journal.read(handle.id)
            assertEquals(DurableSaveStage.WRITING, retained.stage)
            assertEquals(provider.uri.toString(), requireNotNull(retained.output).id)
            assertNull(retained.fingerprint)
            assertTrue(retained.error.isNotBlank())
            assertArrayEquals(pcm, readPcm(retained))
            assertTrue(retained.source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
            assertArrayEquals(sentinel, provider.output(provider.sentinelUri).readBytes())
            store.clear(); store.close()
            reopenStore(root).use { assertEquals(0.0, it.durationSeconds(), 0.0) }
            val fresh = coordinator(context)
            assertEquals(listOf(handle.id), fresh.recover().map { it.id })
            val recording = fresh.materialize(handle.id, checkActive = {})
            assertEquals(provider.retryUri.toString(), recording.id)
            assertEquals(2, provider.inserts)
            assertEquals(0, provider.pendingOf(provider.retryUri))
            assertEquals(listOf(provider.uri), provider.deletedUris)
            assertEquals(listOf(provider.uri), provider.attemptedDeleteUris)
            assertArrayEquals(partial, provider.deletedPayloads[provider.uri])
            assertTrue(provider.isDeleted(provider.uri))
            assertArrayEquals(sentinel, provider.output(provider.sentinelUri).readBytes())
            assertEquals("sentinel.wav", provider.nameOf(provider.sentinelUri))
            assertEquals(0, provider.pendingOf(provider.sentinelUri))
            assertEquals(sentinelIdentity, resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, provider.sentinelUri))
            assertArrayEquals(expectedWav(pcm), provider.output(provider.retryUri).readBytes())
            assertEquals(expectedProviderIdentity(provider.retryUri, pcm.size + 44), recording.fileIdentity)
            assertEquals(recording, runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(recording.id) })
            assertNull(runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(provider.uri.toString()) })
            assertEquals(stagingBefore, getRecorderPreferences(context)
                .getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
            assertEquals(cleanupBefore, getRecorderPreferences(context)
                .getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
            assertFalse(journal.directory(handle.id).exists())
            assertTrue(retained.source.objects.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
            provider.assertClosed()
        }
    }

    @Test fun writingRecoveryRejectsReplacementDuringProviderReadAcquisition() = withFixture { context, root, store, pcm ->
        withOwnedMedia(context, root) { provider ->
            val coordinator = coordinator(context)
            val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                coordinator.admit(it, managedWavRequest())
            }
            provider.ownedJobIds += handle.id
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            val beforeStaging = getRecorderPreferences(context)
                .getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.toSet()
            val beforeCleanup = getRecorderPreferences(context)
                .getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            DurableSaveFaultHooks.onBoundary = { id, boundary ->
                if (id == handle.id && boundary == "BEFORE_WRITER") throw SimulatedProcessDeath()
            }
            try {
                try { coordinator.materialize(handle.id, checkActive = {}); fail("expected interruption") }
                catch (_: SimulatedProcessDeath) { }
            } finally { DurableSaveFaultHooks.onBoundary = null }
            val original = journal.read(handle.id)
            assertEquals(DurableSaveStage.WRITING, original.stage)
            assertEquals(provider.uri.toString(), requireNotNull(original.output).id)
            val emptyIdentity = expectedProviderIdentity(provider.uri, 0)
            assertEquals(emptyIdentity, original.output!!.stagingIdentity)
            assertEquals(emptyIdentity, resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, provider.uri))
            val originalFile = provider.output(provider.uri)
            assertArrayEquals(byteArrayOf(), originalFile.readBytes())
            val inode = Files.getAttribute(originalFile.toPath(), "unix:ino")
            val replacement = expectedWav(byteArrayOf(71, 19, 82, 24, 91, 33))
            val writableOpensBefore = provider.openedModes.count { it == "rw" }
            provider.replaceOnNextRead = replacement
            val fresh = coordinator(context)
            assertEquals(listOf(handle.id), fresh.recover().map { it.id })
            var failureCallbacks = 0
            val failure = try {
                fresh.materialize(handle.id, onFailurePublished = { failureCallbacks++ }, checkActive = {})
                fail("replacement was accepted")
                null
            } catch (error: java.io.IOException) { error }
            assertNotNull(failure)
            assertEquals("Output staging changed before write: ${provider.uri}", requireNotNull(failure).message)
            assertEquals(1, failureCallbacks)
            assertEquals(1, provider.readReplacementCount)
            assertEquals(writableOpensBefore, provider.openedModes.count { it == "rw" })
            val retainedOriginal = requireNotNull(provider.retainedBeforeReplacement)
            assertEquals(inode, Files.getAttribute(retainedOriginal.toPath(), "unix:ino"))
            assertArrayEquals(byteArrayOf(), retainedOriginal.readBytes())
            assertNotEquals(inode, Files.getAttribute(originalFile.toPath(), "unix:ino"))
            assertArrayEquals(replacement, originalFile.readBytes())
            assertEquals(expectedProviderIdentity(provider.uri, replacement.size, 10),
                resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, provider.uri))
            assertEquals(1, provider.inserts)
            assertEquals(0, provider.updates)
            assertTrue(provider.attemptedDeleteUris.isEmpty())
            assertNull(runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(provider.uri.toString()) })
            assertEquals(beforeStaging, getRecorderPreferences(context)
                .getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
            assertEquals(beforeCleanup, getRecorderPreferences(context)
                .getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
            val retained = journal.read(handle.id)
            assertEquals(DurableSaveStage.WRITING, retained.stage)
            assertEquals(provider.uri.toString(), requireNotNull(retained.output).id)
            assertEquals(emptyIdentity, requireNotNull(retained.output).stagingIdentity)
            assertEquals(original.output!!.stagingDisplayName, provider.nameOf(provider.uri))
            assertEquals(1, provider.pendingOf(provider.uri))
            assertNull(retained.fingerprint)
            assertTrue(retained.error.isNotBlank())
            assertArrayEquals(pcm, readPcm(retained))
            assertTrue(retained.source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
            // The isolated fixture retains both provider objects; only retire its source ownership.
            val cancelled = retained.copy(stage = DurableSaveStage.CANCELLED, output = null,
                fingerprint = null, creationName = "")
            journal.write(cancelled)
            journal.retire(cancelled)
            assertFalse(journal.directory(handle.id).exists())
            assertTrue(retained.source.objects.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
            assertArrayEquals(byteArrayOf(), retainedOriginal.readBytes())
            assertArrayEquals(replacement, originalFile.readBytes())
            assertTrue(provider.attemptedDeleteUris.isEmpty())
            provider.assertClosed()
        }
    }

    @Test fun sourceChangedAfterWritingCannotPublishVerifiedOutput() = withFixture { context, root, store, pcm ->
        withOwnedMedia(context, root) { provider ->
            val coordinator = coordinator(context)
            val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                coordinator.admit(it, managedWavRequest())
            }
            provider.ownedJobIds += handle.id
            val journal = DurableSaveJournal(File(root, "durable-saves"))
            val source = journal.read(handle.id).source
            assertArrayEquals(pcm, readPcm(journal.read(handle.id)))
            val segment = source.range.segments.first { it.frameCount > 0 }
            val proof = source.objects.single { it.sourcePath == segment.record.file.absolutePath }
            val mutationOffset = Math.addExact(proof.payloadOffset,
                Math.multiplyExact(segment.startFrame, segment.record.frameBytes.toLong()))
            assertTrue(mutationOffset >= proof.payloadOffset && mutationOffset < proof.payloadOffset + proof.payloadBytes)
            val originals = source.objects.associate { it.sourcePath to File(it.sourcePath).readBytes() }
            val inodes = source.objects.associate { it.sourcePath to Files.getAttribute(File(it.sourcePath).toPath(), "unix:ino") }
            val sourceFile = File(proof.sourcePath)
            val original = requireNotNull(originals[proof.sourcePath])
            source.objects.filter { it.sourcePath != proof.sourcePath }.forEach { other ->
                check(!Files.isSameFile(sourceFile.toPath(), File(other.sourcePath).toPath())) {
                    "Fixture proof objects unexpectedly share the mutated inode"
                }
            }
            assertEquals(8_000, segment.record.sampleRate)
            assertEquals(2, segment.record.frameBytes)
            val selectedBytes = Math.toIntExact(Math.multiplyExact(segment.frameCount, segment.record.frameBytes.toLong()))
            assertArrayEquals(pcm.copyOfRange(0, selectedBytes),
                original.copyOfRange(Math.toIntExact(mutationOffset), Math.toIntExact(mutationOffset) + selectedBytes))
            val mutated = original.clone().also { bytes ->
                val index = Math.toIntExact(mutationOffset)
                bytes[index] = (bytes[index].toInt() xor 1).toByte()
            }
            val stagingBefore = getRecorderPreferences(context)
                .getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.toSet()
            val cleanupBefore = getRecorderPreferences(context)
                .getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            var batches = 0
            var mutatedAfterCompleteOutput = false
            var failureCallbacks = 0
            DurableSaveFaultHooks.onBoundary = { id, boundary ->
                if (id == handle.id && boundary == "PCM_BATCH") {
                    batches++
                    val staged = provider.output(provider.uri).readBytes()
                    if (!mutatedAfterCompleteOutput && staged.size == 44 + pcm.size) {
                        check(staged.copyOfRange(44, staged.size).contentEquals(pcm))
                        java.io.RandomAccessFile(sourceFile, "rw").use { file ->
                            file.seek(mutationOffset)
                            file.write(mutated[Math.toIntExact(mutationOffset)].toInt())
                            file.fd.sync()
                        }
                        mutatedAfterCompleteOutput = true
                    }
                }
            }
            val failure = try {
                try {
                    coordinator.materialize(handle.id, onFailurePublished = { failureCallbacks++ }, checkActive = {})
                    fail("changed source was published")
                    null
                } catch (error: java.io.IOException) { error }
            } finally { DurableSaveFaultHooks.onBoundary = null }
            assertEquals("Pinned PCM changed during verification", requireNotNull(failure).message)
            assertTrue(batches > 0)
            assertTrue(mutatedAfterCompleteOutput)
            assertEquals(1, failureCallbacks)
            assertTrue(provider.openedModes.count { it == "r" } >= 1)
            source.objects.forEach { objectProof ->
                val file = File(objectProof.sourcePath)
                assertEquals(inodes[objectProof.sourcePath], Files.getAttribute(file.toPath(), "unix:ino"))
                assertArrayEquals(if (objectProof.sourcePath == proof.sourcePath) mutated else originals[objectProof.sourcePath],
                    file.readBytes())
            }
            assertArrayEquals(expectedWav(pcm), provider.output(provider.uri).readBytes())
            assertEquals(1, provider.pendingOf(provider.uri))
            assertEquals(1, provider.inserts)
            assertEquals(0, provider.updates)
            assertTrue(provider.attemptedDeleteUris.isEmpty())
            assertNull(runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(provider.uri.toString()) })
            assertEquals(stagingBefore, getRecorderPreferences(context)
                .getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
            assertEquals(cleanupBefore, getRecorderPreferences(context)
                .getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
            val retained = journal.read(handle.id)
            assertEquals(DurableSaveStage.WRITING, retained.stage)
            assertEquals(provider.uri.toString(), requireNotNull(retained.output).id)
            assertNull(retained.fingerprint)
            assertTrue(retained.error.isNotBlank())
            assertTrue(retained.source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
            // Fixture-only retirement after preserving the failed source and output observations.
            val cancelled = retained.copy(stage = DurableSaveStage.CANCELLED, output = null,
                fingerprint = null, creationName = "")
            journal.write(cancelled)
            journal.retire(cancelled)
            assertFalse(journal.directory(handle.id).exists())
            assertTrue(retained.source.objects.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
            assertArrayEquals(mutated, sourceFile.readBytes())
            source.objects.filter { it.sourcePath != proof.sourcePath }.forEach { objectProof ->
                assertArrayEquals(originals[objectProof.sourcePath], File(objectProof.sourcePath).readBytes())
            }
            assertArrayEquals(expectedWav(pcm), provider.output(provider.uri).readBytes())
            assertTrue(provider.attemptedDeleteUris.isEmpty())
            provider.assertClosed()
        }
    }

    @Test fun publishedCatalogWriteFailurePreservesVerifiedSuccessAndRepairsWithoutAudioMutation() = withFixture { context, root, store, pcm ->
        withOwnedMedia(context, root) { provider ->
            val db = RecordingDatabase.getInstance(context)
            val dao = db.recordingDao()
            val sentinelBytes = expectedWav(byteArrayOf(9, 8, 7, 6))
            provider.addSentinel(sentinelBytes)
            val sentinel = RecordingEntity(provider.sentinelUri.toString(), "sentinel.wav", "audio/wav", 1L, 1L,
                sentinelBytes.size.toLong(), "sentinel", RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID,
                expectedProviderIdentity(provider.sentinelUri, sentinelBytes.size), createdAtMillis = 1L, lastSeenAtMillis = 1L)
            val trigger = "test_coordinator_catalog_" + java.util.UUID.randomUUID().toString().replace("-", "")
            val audit = trigger + "_audit"
            var ownsTrigger = false
            var ownsAudit = false
            var ownsSentinel = false
            var primary: Throwable? = null
            fun assertAudit() {
                db.readableDatabase.rawQuery("SELECT targetId FROM $audit", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(provider.uri.toString(), cursor.getString(0))
                    assertFalse(cursor.moveToNext())
                }
            }
            try {
                assertNull(runBlocking { dao.findById(sentinel.id) })
                ownsSentinel = true
                runBlocking { dao.upsert(sentinel) }
                val coordinator = coordinator(context)
                val handle = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                    coordinator.admit(it, managedWavRequest())
                }
                provider.ownedJobIds += handle.id
                val journal = DurableSaveJournal(File(root, "durable-saves"))
                val accepted = journal.read(handle.id)
                assertArrayEquals(pcm, readPcm(accepted))
                assertTrue(accepted.source.objects.all { DurablePcmPins.isProtected(File(it.sourcePath)) })
                val preferences = getRecorderPreferences(context)
                val staging = preferences.getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.toSet()
                val cleanup = preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
                var publicationBoundaries = 0
                var catalogTraceStart = -1
                var publishedInode: Any? = null
                DurableSaveFaultHooks.onBoundary = { id, boundary ->
                    if (id == handle.id && boundary == "PUBLISHED_DURABLE") {
                        publicationBoundaries++
                        assertEquals(1, publicationBoundaries)
                        assertEquals(0, provider.pendingOf(provider.uri))
                        assertArrayEquals(expectedWav(pcm), provider.output(provider.uri).readBytes())
                        publishedInode = Files.getAttribute(provider.output(provider.uri).toPath(), "unix:ino")
                        assertNull(runBlocking { dao.findById(provider.uri.toString()) })
                        assertFalse(db.writableDatabase.inTransaction())
                        db.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE name IN (?,?)",
                            arrayOf(trigger, audit)).use { assertFalse(it.moveToFirst()) }
                        ownsAudit = true
                        db.writableDatabase.execSQL("CREATE TABLE $audit (targetId TEXT NOT NULL)")
                        db.readableDatabase.rawQuery("PRAGMA table_info($audit)", null).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("cid")))
                            assertEquals("targetId", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                            assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value")))
                            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("pk")))
                            assertFalse(cursor.moveToNext())
                        }
                        ownsTrigger = true
                        db.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON recordings " +
                            "WHEN NEW.id='${provider.uri}' BEGIN INSERT INTO $audit VALUES(NEW.id); " +
                            "SELECT RAISE(FAIL, 'owned catalog rejection'); END")
                        catalogTraceStart = provider.catalogObservationUris.size
                    }
                }
                var failureCallbacks = 0
                val recording = try {
                    coordinator.materialize(handle.id, onFailurePublished = { failureCallbacks++ }, checkActive = {})
                } finally { DurableSaveFaultHooks.onBoundary = null }
                assertEquals(1, publicationBoundaries)
                assertEquals(0, failureCallbacks)
                // FAIL retains this trigger's audit write while rejecting the recording insert.
                assertAudit()
                // The second strict provider observation is the actual registration failure recheck.
                // Skipping registration, or skipping its SQLite upsert, cannot satisfy this trace.
                assertEquals(listOf(provider.uri, provider.uri), provider.catalogObservationUris.drop(catalogTraceStart))
                assertNull(runBlocking { dao.findById(provider.uri.toString()) })
                assertEquals(provider.uri.toString(), recording.id)
                assertEquals("test.wav", recording.displayName)
                assertEquals(expectedProviderIdentity(provider), recording.fileIdentity)
                assertEquals(556L, recording.sizeBytes)
                assertEquals(1, provider.inserts)
                assertEquals(1, provider.updates)
                assertTrue(provider.attemptedDeleteUris.isEmpty())
                assertArrayEquals(expectedWav(pcm), provider.output(provider.uri).readBytes())
                assertEquals(publishedInode, Files.getAttribute(provider.output(provider.uri).toPath(), "unix:ino"))
                assertFalse(journal.directory(handle.id).exists())
                assertTrue(accepted.source.objects.none { DurablePcmPins.isProtected(File(it.sourcePath)) })
                assertEquals(staging, preferences.getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
                assertEquals(cleanup, preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
                db.writableDatabase.execSQL("DROP TRIGGER $trigger")
                ownsTrigger = false
                val repaired = runBlocking { RecordingRepository.register(context, recording) }
                assertEquals(repaired, runBlocking { dao.findById(provider.uri.toString()) })
                assertEquals(recording.fileIdentity, repaired.fileIdentity)
                assertEquals(recording.displayName, repaired.displayName)
                assertAudit()
                assertEquals(listOf(provider.uri, provider.uri, provider.uri), provider.catalogObservationUris.drop(catalogTraceStart))
                assertEquals(1, provider.inserts)
                assertEquals(1, provider.updates)
                assertTrue(provider.attemptedDeleteUris.isEmpty())
                assertArrayEquals(expectedWav(pcm), provider.output(provider.uri).readBytes())
                assertEquals(publishedInode, Files.getAttribute(provider.output(provider.uri).toPath(), "unix:ino"))
                assertArrayEquals(sentinelBytes, provider.output(provider.sentinelUri).readBytes())
                assertEquals("sentinel.wav", provider.nameOf(provider.sentinelUri))
                assertEquals(0, provider.pendingOf(provider.sentinelUri))
                assertEquals(sentinel.fileIdentity, resolveProviderRecordingIdentity(context,
                    RecordingStorageType.MEDIASTORE, provider.sentinelUri))
                assertEquals(sentinel, runBlocking { dao.findById(sentinel.id) })
                store.clear(); store.close()
                reopenStore(root).use { assertEquals(0.0, it.durationSeconds(), 0.0) }
                assertArrayEquals(expectedWav(pcm), provider.output(provider.uri).readBytes())
                provider.assertClosed()
            } catch (error: Throwable) {
                primary = error
                throw error
            } finally {
                DurableSaveFaultHooks.onBoundary = null
                val steps = listOf<() -> Unit>(
                    { if (ownsTrigger) db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger") },
                    { if (ownsAudit) db.writableDatabase.execSQL("DROP TABLE IF EXISTS $audit") },
                    { if (ownsSentinel) runBlocking { dao.deleteById(sentinel.id) } },
                )
                var cleanupFailure: Throwable? = null
                for (step in steps) try { step() } catch (error: Throwable) {
                    val first = primary ?: cleanupFailure
                    if (first == null) cleanupFailure = error else if (first !== error) first.addSuppressed(error)
                }
                if (primary == null) cleanupFailure?.let { throw it }
            }
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
        expectedProviderIdentity(provider.uri, 556)

    private fun expectedProviderIdentity(uri: android.net.Uri, size: Int, generation: Int = 9): String =
        "provider:3:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(uri.toString().toByteArray(Charsets.UTF_8)) + ":$size:$generation"

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
            check(runBlocking { activeDao.findById(provider.retryUri.toString()) } == null)
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
                        val acknowledged = terminal.associate { job ->
                            val target = provider.ownedOutputUris.single { it.toString() == job.publishedRecordingId }
                            val identity = expectedProviderIdentity(target, provider.output(target).length().toInt())
                            check(job.publishedRecordingIdentity == identity)
                            val row = runBlocking { requireNotNull(dao).findById(target.toString()) }
                            check(row != null && row.fileIdentity == identity)
                            check(DurableSaveJobs.state.value.none { it.id !in provider.ownedJobIds &&
                                it.publishedRecordingId == target.toString() })
                            target.toString() to identity
                        }
                        DurableSaveJobs.acknowledgePublished(acknowledged)
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
                { if (ownsCatalogId && handoffProven) runBlocking {
                    provider.ownedOutputUris.forEach { requireNotNull(dao).deleteById(it.toString()) }
                } },
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
