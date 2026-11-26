package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.provider.Settings
import android.system.ErrnoException
import android.system.OsConstants
import app.smallthingz.reverb.incidentcontextisolated.IncidentContextSandboxMarker
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowProcess

/** Actual persistent recovery with Android exit evidence controlled only at ActivityManager. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class],
    // The process marker is cached for the app lifetime, while Robolectric resets shadow PID.
    // Give this fixture's borrowed PID its own sandbox instead of resetting production state.
    instrumentedPackages = ["app.smallthingz.reverb.incidentcontextisolated"])
class IncidentContextRecoveryV2Test {
    @Before fun verifySandboxOwnership() {
        val loader = IncidentContextSandboxMarker::class.java.classLoader
        assertSame(loader, javaClass.classLoader)
        assertSame(loader, ReverbService::class.java.classLoader)
        assertSame(loader, RecordingIncidentStore::class.java.classLoader)
    }

    @Test fun bufferedPreparationFailureHasOnlyItsTerminalIncident() = fixture { context, root ->
        withReportingService(context) { service ->
            val failure = IllegalStateException("Save preparation failed")
            val terminals = mutableListOf<Throwable?>()
            val receiver = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) { fail("preparation failure succeeded") }
                override fun fileFailed(message: String, error: Throwable?) { terminals += error }
            }
            val ownerType = ReverbService::class.java.declaredClasses.single { it.simpleName == "DurableSaveOwner" }
            val owner = ownerType.declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
                .newInstance(1L, receiver, 0L, null, null)
            val ownersField = ReverbService::class.java.getDeclaredField("durableSaveOwners").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val owners = ownersField.get(service) as MutableMap<Long, Any>
            owners[1L] = owner
            val prepare = ReverbService::class.java.getDeclaredMethod("prepareBufferedSave",
                String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            val finish = ReverbService::class.java.getDeclaredMethod("finishDurableSaveFailure", ownerType,
                String::class.java, Exception::class.java).apply { isAccessible = true }
            val acquire: () -> Nothing = { throw failure }
            val work = createBufferedExportWork<java.io.Closeable>(
                started = java.util.concurrent.atomic.AtomicBoolean(), beforeAcquire = {},
                acquire = {
                    try { prepare.invoke(service, "prepare export", acquire); null }
                    catch (wrapped: java.lang.reflect.InvocationTargetException) { throw wrapped.targetException }
                },
                releaseUnclaimed = { fail("no range was acquired") },
                onReleaseFailure = { throw AssertionError(it) }, onEmpty = { fail("failure became empty") },
                onFailure = { error ->
                    assertSame(failure, error)
                    // Retire without foreground work; preparation above ran on a live, stopped Service.
                    ReverbService::class.java.getDeclaredField("serviceDestroying").apply { isAccessible = true }
                        .setBoolean(service, true)
                    repeat(2) { finish.invoke(service, owner, "Save failed", error) }
                }, export = { _, _ -> fail("failure exported") },
            )
            try {
                work.queuePreparation { it.run(); true }
                work.task.run()
                work.task.get()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                awaitHistoryWriters()
                assertEquals(listOf(failure), terminals)
                assertTrue(owners.isEmpty())
                val incident = RecordingIncidentStore.readIncidents(context).single()
                assertEquals(RecordingIncidentKind.UNEXPECTED_ERROR, incident.kind)
                assertTrue(incident.description.orEmpty().contains(failure.message!!))
                assertFalse(File(root, "recording-session.bin").exists())
            } finally { owners.clear() }
        }
    }

    @Test fun saveCleanupFailuresReportOnceWithoutChangingTheOperationResult() {
        for (path in listOf("buffered", "unclaimed", "parent", "child")) {
            for (failure in listOf(IOException("cleanup IO"), IllegalStateException("cleanup invariant"),
                    java.util.concurrent.CancellationException("cleanup cancelled"))) fixture { context, root ->
                withReportingService(context) { service ->
                    val reportMethod = ReverbService::class.java.getDeclaredMethod("reportSaveCleanupFailure",
                        String::class.java, Throwable::class.java).apply { isAccessible = true }
                    val reported = java.util.concurrent.CountDownLatch(1)
                    val report: (Exception) -> Unit = {
                        assertSame(failure, it)
                        try { reportMethod.invoke(service, path, it) } finally { reported.countDown() }
                    }
                    var closes = 0
                    var successes = 0
                    val failing = java.io.Closeable { closes++; throw failure }
                    val started = java.util.concurrent.atomic.AtomicBoolean()
                    if (path == "buffered" || path == "unclaimed") {
                        val work = createBufferedExportWork(started, {}, { failing },
                            releaseUnclaimed = { releaseTimelineSnapshotOffMain(it, report) },
                            onReleaseFailure = report, onEmpty = { fail("lost range") },
                            onFailure = { throw AssertionError("cleanup changed terminal", it) },
                            export = { _, close -> close(); close(); successes++ })
                        work.queuePreparation { it.run(); true }
                        if (path == "unclaimed") assertTrue(work.task.cancel(false))
                        else { work.task.run(); work.task.get() }
                    } else {
                        val quiet = java.io.Closeable {}
                        val task = createTimelineSnapshotExportTask(
                            snapshot = if (path == "parent") failing else quiet, started = started,
                            releaseUnstarted = { it.close() }, onParentReleaseFailure = report,
                            onChildReleaseFailure = report, beforeAcquire = {},
                            acquire = { if (path == "child") failing else quiet },
                            onEmpty = { fail("lost range") },
                            onFailure = { throw AssertionError("cleanup changed terminal", it) },
                            export = { _, close -> close(); close(); successes++ })
                        task.run(); task.get()
                    }
                    assertTrue(reported.await(5, TimeUnit.SECONDS))
                    awaitHistoryWriters()
                    assertEquals(1, closes)
                    assertEquals(if (path == "unclaimed") 0 else 1, successes)
                    val incidents = RecordingIncidentStore.readIncidents(context)
                    assertEquals(if (failure is java.util.concurrent.CancellationException) 0 else 1, incidents.size)
                    assertTrue(incidents.all { it.kind == RecordingIncidentKind.UNEXPECTED_ERROR })
                    assertFalse(File(root, "recording-session.bin").exists())
                }
            }
        }
    }

    @Test fun committedBookkeepingFailureReportsOnceAndKeepsSaveSuccess() {
        for (failure in listOf(IOException("range memory IO"), IllegalStateException("range memory invariant"),
                java.util.concurrent.CancellationException("range memory cancelled"))) fixture { context, root ->
            var saved = 0
            var terminals = 0
            var status: CaptureSaveStatus? = null
            val receiver = SaveResultReceiver(context, setSaving = {}, onStatus = { status = it },
                onError = { fail("verified save failed") }, onSaved = { saved++ },
                onCommitted = { throw failure }, onTerminal = { terminals++ })
            val recording = RecordingEntity("/saved.wav", "saved.wav", "audio/wav", 1L, 100L, 100L,
                "WAV", RecordingStorageType.FILE, "/", "identity")
            repeat(2) { receiver.fileReady(recording) }
            receiver.fileCancelled()
            awaitHistoryWriters()
            assertEquals(CaptureSaveStatus.Saved(recording), status)
            assertEquals(1, saved)
            assertEquals(1, terminals)
            val incidents = RecordingIncidentStore.readIncidents(context)
            assertEquals(if (failure is java.util.concurrent.CancellationException) 0 else 1, incidents.size)
            assertTrue(incidents.all { it.kind == RecordingIncidentKind.UNEXPECTED_ERROR })
            assertFalse(File(root, "recording-session.bin").exists())
        }
    }

    private fun withReportingService(context: Context, test: (ReverbService) -> Unit) {
        val service = org.robolectric.Robolectric.buildService(ReverbService::class.java).get()
        val base = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        val oldBase = base.get(service)
        base.set(service, context)
        try { test(service) } finally {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            awaitHistoryWriters()
            base.set(service, oldBase)
        }
    }

    @Test fun busyTrimReportsOnceWithoutReleasingTheExistingOwner() = fixture { context, _ ->
        val selected = RecordingEntity("/isolated/busy.wav", "busy.wav", "audio/wav",
            1L, 1000L, 100L, "WAV", RecordingStorageType.FILE, "/isolated", "selected")
        val original = checkNotNull(recordingMutations.tryBegin(selected.id))
        try {
            val terminals = mutableListOf<Result<RecordingEntity>>()
            runBlocking { runCommittedInlineTrim(
                save = { saveTrimmedRecordingCopy(context, selected, 0, 500) },
                onTerminal = { terminals += it },
            ) }
            awaitHistoryWriters()
            assertTrue(terminals.single().exceptionOrNull() is IOException)
            assertEquals("Trim is already in progress for this recording", terminals.single().exceptionOrNull()?.message)
            assertTrue(recordingMutations.isActive(selected.id))
            assertNull(recordingMutations.tryBegin(selected.id))
            assertEquals(1, RecordingIncidentStore.readIncidents(context).size)
        } finally { recordingMutations.finish(original) }
    }

    @Test fun copiedRenameCleanupFailureHasOneIncidentAndRetainsBothFiles() = fileCopyFixture { context, source, target, record, bytes ->
        context.rejectDeletionCommit = true
        val failure = runBlocking { runCatching { RecordingRepository.rename(context, source, "renamed") }.exceptionOrNull() }
        assertTrue(failure is RecordingRenameStateUncertainException)
        assertEquals("Renamed copy saved; original cleanup remains pending", failure?.message)
        awaitHistoryWriters()
        assertEquals(1, RecordingIncidentStore.readIncidents(context).size)
        assertFalse(recordingMutations.isActive(source.id))
        assertArrayEquals(bytes, File(source.id).readBytes())
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(FileCopyPhase.CATALOGED, FileCopyPublications.snapshot(context).records.single { it.token == record.token }.phase)
    }

    @Test fun copiedRenameRecoveryExceptionKeepsTokenReportingAcrossRepeatedOperations() = fileCopyFixture { context, source, target, record, bytes ->
        // This earlier transaction owns another name, so actual rename must preserve it and fail before mutation.
        repeat(2) {
            val failure = runBlocking { runCatching { RecordingRepository.rename(context, source, "different") }.exceptionOrNull() }
            assertTrue(failure is FileCopyRecoveryRequired)
            assertEquals("An earlier FILE publication needs recovery", failure?.message)
        }
        awaitHistoryWriters()
        assertEquals(1, RecordingIncidentStore.readIncidents(context).size)
        assertFalse(recordingMutations.isActive(source.id))
        assertEquals(record, FileCopyPublications.snapshot(context).records.single())
        assertArrayEquals(bytes, File(source.id).readBytes())
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun standaloneFileCopyRecoveryRetainsItsSingleTokenIncident() = fileCopyFixture { context, source, target, record, bytes ->
        val incomplete = record.copy(phase = FileCopyPhase.CREATED, targetIdentity = "")
        FileCopyPublications.replace(context, record, incomplete)
        // Remove only this fixture's synthetic target, modeling a prior partial creation loss.
        assertTrue(target.delete())
        repeat(2) { recoverPendingFileCopyPublications(context) }
        awaitHistoryWriters()
        assertEquals(1, RecordingIncidentStore.readIncidents(context).size)
        assertEquals(incomplete, FileCopyPublications.snapshot(context).records.single())
        assertArrayEquals(bytes, File(source.id).readBytes())
        assertFalse(target.exists())
    }

    @Test fun reservedPartialPreservationReportsOnceThroughActualRename() = partialCopyPreservation(FileCopyPhase.RESERVED)
    @Test fun createdPartialPreservationReportsOnceThroughActualRename() = partialCopyPreservation(FileCopyPhase.CREATED)

    private fun partialCopyPreservation(phase: FileCopyPhase) = fileCopyFixture { context, source, target, record, bytes ->
        val partial = bytes.copyOf(16)
        target.writeBytes(partial)
        val incomplete = record.copy(phase = phase, targetIdentity = "",
            createdIdentity = if (phase == FileCopyPhase.RESERVED) "" else record.createdIdentity)
        FileCopyPublications.replace(context, record, incomplete)
        val failure = runBlocking { runCatching { RecordingRepository.rename(context, source, "renamed") }.exceptionOrNull() }
        assertTrue(failure is FileCopyRecoveryRequired)
        awaitHistoryWriters()
        assertEquals(1, RecordingIncidentStore.readIncidents(context).size)
        assertFalse(recordingMutations.isActive(source.id))
        assertTrue(FileCopyPublications.snapshot(context).records.isEmpty())
        val suppression = getRecorderPreferences(context).getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!
            .mapNotNull(::decodePendingOutputCleanupRecord).single { it.id == target.path }
        assertEquals(partial.size.toLong(), suppression.byteCount)
        assertNull(suppression.fileKey)
        assertNull(suppression.providerIdentity)
        assertArrayEquals(bytes, File(source.id).readBytes())
        assertArrayEquals(partial, target.readBytes())
    }

    private class CopyContext(base: Context, private val root: File) : ContextWrapper(base) {
        var rejectDeletionCommit = false
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
            val prefs = super.getSharedPreferences(name + "-" + root.name, mode)
            return object : android.content.SharedPreferences by prefs {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val editor = prefs.edit()
                    var deletion = false
                    return object : android.content.SharedPreferences.Editor by editor {
                        override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor {
                            if (key == PrefKey.PENDING_RECORDING_DELETIONS.name && !values.isNullOrEmpty()) deletion = true
                            editor.putStringSet(key, values)
                            return this
                        }
                        override fun commit(): Boolean = if (rejectDeletionCommit && deletion) false else editor.commit()
                    }
                }
            }
        }
    }

    private fun fileCopyFixture(test: (CopyContext, RecordingEntity, File, FileCopyPublication, ByteArray) -> Unit) = fixture { base, root ->
        val context = CopyContext(base, root)
        val directory = getSavedRecordingsDirectory(context).apply { check(mkdirs()) }
        val bytes = ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(4); putShort(123); putShort(-321)
        }.array()
        val sourceFile = File(directory, "source.wav").apply { writeBytes(bytes) }
        val target = File(directory, "renamed.wav").apply { writeBytes(bytes) }
        val sourceProof = checkNotNull(readStableFileOutputFingerprint(sourceFile))
        val targetProof = checkNotNull(readStableFileOutputFingerprint(target))
        val selected = RecordingEntity(sourceFile.path, sourceFile.name, "audio/wav", 1L, 1L, 48L, "WAV",
            RecordingStorageType.FILE, directory.path, checkNotNull(sourceProof.fileKey))
        val record = FileCopyPublication(java.util.UUID.randomUUID().toString(), FileCopyPurpose.RENAME,
            sourceFile.path, target.name, target.path, selected.fileIdentity, 48L,
            sourceProof.digest.sha256.joinToString("") { "%02x".format(it.toInt() and 255) },
            FileCopyPhase.VERIFIED, checkNotNull(targetProof.fileKey), targetProof.fileKey)
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        try {
            runBlocking { RecordingRepository.register(context, selected) }
            FileCopyPublications.replace(context, null, record)
            test(context, selected, target, record, bytes)
        } finally {
            context.rejectDeletionCommit = false
            awaitHistoryWriters()
            runBlocking { dao.deleteById(selected.id); dao.deleteById(target.path) }
            check(getRecorderPreferences(context).edit().clear().commit())
            RecordingDatabase.resetAfterCorruption()
        }
    }

    @Test fun stoppedSaveSealFailureHasOneIncidentAndOneTerminalDelivery() = fixture { context, root ->
        val service = org.robolectric.Robolectric.buildService(ReverbService::class.java).get()
        val base = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        val oldBase = base.get(service)
        base.set(service, context)
        val destroying = ReverbService::class.java.getDeclaredField("serviceDestroying").apply { isAccessible = true }
        destroying.setBoolean(service, true) // Retired service must not promote foreground during terminal cleanup.
        val failures = mutableListOf<Throwable?>()
        val receiver = object : ReverbService.AudioFileReceiver {
            override fun fileReady(recording: RecordingEntity) { fail("failed save succeeded") }
            override fun fileFailed(message: String, error: Throwable?) { failures += error }
        }
        val ownerType = ReverbService::class.java.declaredClasses.single { it.simpleName == "DurableSaveOwner" }
        val constructor = ownerType.declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
        val owner = constructor.newInstance(1L, receiver, 0L, null, null)
        val ownersField = ReverbService::class.java.getDeclaredField("durableSaveOwners").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val owners = ownersField.get(service) as MutableMap<Long, Any>
        owners[1L] = owner
        val finish = ReverbService::class.java.getDeclaredMethod("finishDurableSaveFailure", ownerType,
            String::class.java, Exception::class.java).apply { isAccessible = true }
        val store = PersistentAudioChunkStore(File(root, "unused-source"))
        val error = PersistentAudioChunkStore.SaveSealFailure(store, false, IOException("source close failure"))
        try {
            repeat(2) { finish.invoke(service, owner, "Save failed", error) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            awaitHistoryWriters()
            assertEquals(listOf(error), failures)
            assertTrue(owners.isEmpty())
            val incident = RecordingIncidentStore.readIncidents(context).single()
            assertEquals(RecordingIncidentKind.UNEXPECTED_ERROR, incident.kind)
            assertTrue(incident.description.orEmpty().contains("source close failure"))
            assertFalse(File(root, "recording-session.bin").exists())
        } finally {
            owners.clear()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            base.set(service, oldBase)
            store.close()
        }
    }

    @Test fun stoppedTrimExceptionReportsOnceDeliversTerminalAndReleasesReservation() = fixture { context, root ->
        val expected = IOException("Trim filename settings unavailable")
        val failingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = throw expected
        }
        val selected = RecordingEntity("/isolated/trim-source.wav", "source.wav", "audio/wav",
            1L, 1000L, 100L, "WAV", RecordingStorageType.FILE, "/isolated", "selected")
        val terminals = mutableListOf<Result<RecordingEntity>>()
        runBlocking {
            runCommittedInlineTrim(
                save = { saveTrimmedRecordingCopy(failingContext, selected, 0, 500) },
                onTerminal = { terminals += it },
            )
        }
        awaitHistoryWriters()
        val delivered = checkNotNull(terminals.single().exceptionOrNull())
        assertEquals(IOException::class.java, delivered.javaClass)
        assertEquals(expected.message, delivered.message)
        // Coroutine stack recovery may clone IOException, retaining the original as its cause.
        assertTrue(generateSequence(delivered) { it.cause }.take(8).any { it === expected })
        assertFalse(recordingMutations.isActive(selected.id))
        val incident = RecordingIncidentStore.readIncidents(context).single()
        assertEquals(RecordingIncidentKind.UNEXPECTED_ERROR, incident.kind)
        assertTrue(incident.description.orEmpty().contains("Trim filename settings unavailable"))
        assertFalse(File(root, "recording-session.bin").exists())
    }

    @Test fun caughtSaveAndFilenameErrorsPersistWithoutACaptureSession() = fixture { context, root ->
        assertFalse(File(root, "recording-session.bin").exists())
        reportUnexpectedRecordingOperationFailure(context, "Save recording", IllegalStateException("save invariant"))
        reportUnexpectedRecordingOperationFailure(context, "Rename recording",
            IOException("Rename uncertain", NullPointerException("filename")))
        reportUnexpectedRecordingOperationFailure(context, "Save recording", IOException("Storage unavailable"))
        reportUnexpectedRecordingOperationFailure(context, "Rename recording", SecurityException("Permission denied"))
        reportUnexpectedRecordingOperationFailure(context, "Save recording", java.util.concurrent.CancellationException())
        awaitHistoryWriters()

        val incidents = RecordingIncidentStore.readIncidents(context)
        assertEquals(4, incidents.size)
        assertTrue(incidents.all { it.kind == RecordingIncidentKind.UNEXPECTED_ERROR })
        assertTrue(incidents.any { it.description.orEmpty().contains("save invariant") })
        assertTrue(incidents.any { it.description.orEmpty().contains("filename") })
        assertFalse(File(root, "recording-session.bin").exists())
    }

    // Independently assembled published big-endian wire fixtures (Python struct).
    private val priorSession = hex("524953310301000000290000000000000000000000000000006400000000000000000000000000000007ffffffff")
    private val sentinel = hex("02000000000000025800000000000002bc0000000000000320000000030000000400000063000000640000000000000065000000000000006600000000000001900000000000000000000873656e74696e656c0000000000000000")
    private val deleted = hex("0100000000000000960000000000000000800000000000000000000000800000000000002980000000ffffffffffffffffffffffffffffffff00000000000000640000000000000064000764656c657465640000000000000000")
    private val expectedEnrichedHistory = hex("5249483104000201000000000000009600000000000000000000000000000000000000040000001100000029000000640000000000000065000000000000006600000000000000640000000000000064000a6c6174652d6372617368000000000000000002000000000000025800000000000002bc0000000000000320000000030000000400000063000000640000000000000065000000000000006600000000000001900000000000000000000873656e74696e656c0000000000000000")

    @Test fun observationEndAfterVisibleBarrierFailureRetainsAcknowledgementAndSignalsDurableRevision() = fixture { context, root ->
        val duration = CaptureObservationDuration(1_000L)
        try {
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_000L, CaptureInputObservationReason.POLICY_SILENCED, duration))
            val opened = awaitOpenObservation(context, duration)
            RecordingIncidentStore.toggleIncidentAcknowledged(context, opened)
            val revision = RecordingIncidentStore.historyRevision.value
            val barriers = JournalHostIoShadow.directorySyncAttempts
            JournalHostIoShadow.failDirectorySyncs = 1
            duration.finish(3_250L)
            awaitHistoryWriters()

            val raw = wireRows(File(root, "recording-incidents.bin").readBytes()).single()
            assertEquals(12_250L, ByteBuffer.wrap(raw).getLong(9))
            assertEquals(opened.observationId, ByteBuffer.wrap(raw).getLong(raw.size - 8))
            assertTrue(ByteBuffer.wrap(raw).getLong(17) > 0)
            assertTrue(JournalHostIoShadow.directorySyncAttempts >= barriers + 2)
            assertTrue(RecordingIncidentStore.historyRevision.value >= revision + 2)
            val closed = RecordingIncidentStore.readIncidents(context).single()
            assertNull(closed.ongoingPolicyDuration)
            assertEquals(2_250L, recordingIncidentDowntimeMillis(closed))
        } finally {
            duration.finish(-1L)
            awaitHistoryWriters()
        }
    }

    @Test fun exactObservationEndCannotReinsertDeletedOrEvictedHistory() = fixture { context, root ->
        val deletedDuration = CaptureObservationDuration(1_000L)
        val evictedDuration = CaptureObservationDuration(2_000L)
        val file = File(root, "recording-incidents.bin")
        try {
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_000L, CaptureInputObservationReason.POLICY_SILENCED, deletedDuration))
            val deleted = awaitOpenObservation(context, deletedDuration)
            RecordingIncidentStore.deleteIncidentInBackground(context, deleted)
            awaitCondition { RecordingIncidentStore.readIncidents(context).none { it.observationId == deleted.observationId } }
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_001L, CaptureInputObservationReason.POLICY_SILENCED, evictedDuration))
            val evicted = awaitOpenObservation(context, evictedDuration)
            // Reproduce the history remaining after this exact row fell out of the bounded list.
            val remaining = java.io.DataInputStream(file.inputStream()).use(RecordingIncidentStore::readHistoryPayload)
                .filterNot { it.observationId == evicted.observationId }
            java.io.DataOutputStream(file.outputStream()).use { RecordingIncidentStore.writeHistoryPayload(it, remaining) }
            val expectedBytes = file.readBytes()

            deletedDuration.finish(4_000L)
            evictedDuration.finish(4_001L)
            awaitHistoryWriters()
            assertTrue(RecordingIncidentStore.readIncidents(context).isEmpty())
            assertArrayEquals(expectedBytes, file.readBytes())
        } finally {
            deletedDuration.finish(-1L)
            evictedDuration.finish(-1L)
            awaitHistoryWriters()
        }
    }

    @Test fun alreadyEndedObservationSurvivesUncertainFirstPublicationAsOneRow() = fixture { context, root ->
        assertTrue(RecordingIncidentStore.readIncidents(context).isEmpty())
        val duration = CaptureObservationDuration(2_000L)
        synchronized(RecordingIncidentStore) {
            JournalHostIoShadow.failDirectorySyncs = 1
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_000L, CaptureInputObservationReason.POLICY_SILENCED, duration))
            duration.finish(3_000L)
        }
        awaitHistoryWriters()

        val closed = RecordingIncidentStore.readIncidents(context).single()
        assertNull(closed.ongoingPolicyDuration)
        assertEquals(1_000L, recordingIncidentDowntimeMillis(closed))
        val raw = wireRows(File(root, "recording-incidents.bin").readBytes()).single()
        assertEquals(11_000L, ByteBuffer.wrap(raw).getLong(9))
        assertEquals(closed.observationId, ByteBuffer.wrap(raw).getLong(raw.size - 8))
    }

    @Test fun orphanedV4OngoingRecordShowsUnknownWithoutRewritingHistory() = fixture { context, root ->
        val file = File(root, "recording-incidents.bin")
        val orphan = RecordingIncident(10_000L, resumedAtMillis = 0L,
            kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION, pid = 41,
            processStartedAtMillis = 7L, observationId = 1L,
            description = CaptureInputObservationReason.POLICY_SILENCED.description)
        java.io.DataOutputStream(file.outputStream()).use { RecordingIncidentStore.writeHistoryPayload(it, listOf(orphan)) }
        val original = file.readBytes()
        val loaded = RecordingIncidentStore.readIncidents(context).single()
        assertEquals(-1L, loaded.resumedAtMillis)
        assertNull(loaded.ongoingPolicyDuration)
        assertEquals(recordingIncidentPresentationKey(orphan), recordingIncidentPresentationKey(loaded))
        assertTrue(formatIncidentSummary(loaded).endsWith("duration unknown"))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun priorArmedProcessPublishesPendingAndProvisionalHistoryBeforeRetiringMarker() = fixture { context, root ->
        seed(root)
        val before = System.currentTimeMillis()
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val after = System.currentTimeMillis()
        assertFalse(File(root, "recording-session.bin").exists())
        val pending = File(root, "recording-incident-pending.bin").readBytes()
        assertArrayEquals(hex("52495031030001"), pending.copyOfRange(0, 7))
        assertEquals(64, pending.size)
        assertArrayEquals(priorSession.copyOfRange(5, 46), pending.copyOfRange(7, 48))
        assertEquals(0L, ByteBuffer.wrap(pending).getLong(48))
        assertTrue(ByteBuffer.wrap(pending).getLong(56) > 150L)
        val history = File(root, "recording-incidents.bin").readBytes()
        val rows = wireRows(history)
        assertEquals(2, rows.size)
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val provisional = rows.single { wirePid(it) == 41 }
        assertEquals(1, provisional[0].toInt())
        assertTrue(ByteBuffer.wrap(provisional).getLong(1) in before..after)
        assertEquals(0L, ByteBuffer.wrap(provisional).getLong(9))
        assertEquals(0, ByteBuffer.wrap(provisional).getInt(25))
        assertEquals(100L, ByteBuffer.wrap(provisional).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(provisional).getLong(65))
        assertEquals("Recording process ended while capture was running; exit evidence pending", wireDescription(provisional))
        repeat(2) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(pending, File(root, "recording-incident-pending.bin").readBytes())
        assertArrayEquals(history, File(root, "recording-incidents.bin").readBytes())
        assertEquals(setOf("recording-incidents.bin", "recording-incident-pending.bin"), root.list()!!.toSet())
    }

    @Test fun settledCaptureLaneDoesNotRepeatAbsenceBarrierDuringStoppedOnlyRetries() = fixture { context, root ->
        val before = JournalHostIoShadow.directorySyncAttempts
        var result = runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertFalse(result.getOrThrow())
        assertEquals(before + 1, JournalHostIoShadow.directorySyncAttempts)
        // Exercise all six possible passes while the independent stopped-app lane keeps retrying.
        repeat(6) {
            result = RecordingIncidentStore.retryStartupCaptureRecovery(context, result)
            assertFalse(result.getOrThrow())
            assertEquals(before + 1, JournalHostIoShadow.directorySyncAttempts)
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun failedInitialCaptureProofRetriesMarkerBeforeSettlingItsQueue() = fixture { context, root ->
        seed(root)
        val barriers = JournalHostIoShadow.directorySyncAttempts
        JournalHostIoShadow.failDirectorySyncs = 1
        var result = runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        val failure = result.exceptionOrNull()
        // Os.fsync propagates its exact ErrnoException; the directory barrier does not wrap it.
        assertTrue("Expected injected fsync EIO, got $failure", failure is ErrnoException)
        val errnoFailure = failure as ErrnoException
        assertTrue(errnoFailure.message.orEmpty().startsWith("fsync failed:"))
        assertEquals(OsConstants.EIO, errnoFailure.errno)
        assertEquals(0, JournalHostIoShadow.failDirectorySyncs)
        assertEquals(barriers + 1, JournalHostIoShadow.directorySyncAttempts)
        assertTrue(File(root, "recording-session.bin").exists())
        assertArrayEquals(priorSession, File(root, "recording-session.bin").readBytes())
        result = RecordingIncidentStore.retryStartupCaptureRecovery(context, result)
        assertTrue(result.getOrThrow())
        assertFalse(File(root, "recording-session.bin").exists())
        assertTrue(File(root, "recording-incident-pending.bin").exists())
        assertEquals(1, wireRows(File(root, "recording-incidents.bin").readBytes()).count { wirePid(it) == 41 })
    }

    @Test fun startupRetryEnrichesLateUserRequestedExitWhileHistoryObserverStaysAlive() = fixture { context, root ->
        seed(root)
        var captureRecovery = runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertTrue(captureRecovery.getOrThrow())
        val pendingFile = File(root, "recording-incident-pending.bin")
        val pendingBefore = pendingFile.readBytes()
        runBlocking {
            withTimeout(5_000L) {
                // This is Home's retained presentation owner; never reopen or restart its collector.
                val controller = IncidentHistoryController(context)
                val states = kotlinx.coroutines.channels.Channel<IncidentHistoryUiState>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED)
                val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
                suspend fun readyCapture(reason: Int): RecordingIncident {
                    while (true) {
                        val next = states.receive()
                        if (next.status != IncidentHistoryLoadStatus.READY) continue
                        val captures = next.history.rows.map { it.incident }.filter {
                            it.pid == 41 && it.captureArmedAtMillis == 100L
                        }
                        if (captures.size == 1 && captures.single().exitReason == reason) return captures.single()
                    }
                }
                try {
                    val provisional = readyCapture(ApplicationExitInfo.REASON_UNKNOWN)
                    val identity = recordingIncidentPresentationKey(provisional)
                    val revision = RecordingIncidentStore.historyRevision.value
                    // Missing ExitInfo keeps only the capture lane pending, without revision churn.
                    captureRecovery = RecordingIncidentStore.retryStartupCaptureRecovery(context, captureRecovery)
                    assertTrue(captureRecovery.getOrThrow())
                    assertFalse(RecordingIncidentStore.recoverUnexpectedProcessExitAndMarkCurrent(context))
                    assertEquals(revision, RecordingIncidentStore.historyRevision.value)
                    assertArrayEquals(pendingBefore, pendingFile.readBytes())

                    val manager = context.getSystemService(ActivityManager::class.java)
                    Shadow.extract<ShadowActivityManager>(manager).addApplicationExitInfo(
                        ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                            .setPid(41).setProcessName(context.packageName)
                            .setReason(ApplicationExitInfo.REASON_USER_REQUESTED).setTimestamp(150L)
                            .setStatus(0).setImportance(100).setPss(101L).setRss(102L)
                            .setDescription("late-force-stop").build())
                    // The exact entry called by the existing startup retry loop publishes the revision.
                    // No capture start, acknowledgement, deletion, panel reopen or manual history read.
                    captureRecovery = RecordingIncidentStore.retryStartupCaptureRecovery(context, captureRecovery)
                    assertFalse(captureRecovery.getOrThrow())
                    assertFalse(RecordingIncidentStore.recoverUnexpectedProcessExitAndMarkCurrent(context))
                    val enriched = readyCapture(ApplicationExitInfo.REASON_USER_REQUESTED)
                    assertTrue(observer.isActive)
                    assertTrue(RecordingIncidentStore.historyRevision.value > revision)
                    assertEquals(identity, recordingIncidentPresentationKey(enriched))
                    assertEquals("late-force-stop", enriched.description)
                    assertEquals(0L, enriched.resumedAtMillis)
                    assertEquals(1, controller.state.history.rows.count { it.incident.pid == 41 })
                    assertFalse(pendingFile.exists())
                    assertFalse(File(root, "recording-session.bin").exists())
                    val settledRevision = RecordingIncidentStore.historyRevision.value
                    val settledHistory = File(root, "recording-incidents.bin").readBytes()
                    captureRecovery = RecordingIncidentStore.retryStartupCaptureRecovery(context, captureRecovery)
                    assertFalse(captureRecovery.getOrThrow())
                    assertEquals(settledRevision, RecordingIncidentStore.historyRevision.value)
                    assertArrayEquals(settledHistory, File(root, "recording-incidents.bin").readBytes())
                    assertArrayEquals(sentinel, wireRows(settledHistory).single { wirePid(it) == 99 })
                } finally {
                    observer.cancelAndJoin()
                    states.close()
                }
            }
        }
    }

    @Test fun delayedExitEnrichesOneExactSessionAndRetiresPendingOnlyAfterHistoryPublication() = fixture { context, root ->
        seed(root)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        addLateCrash(context)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertArrayEquals(expectedEnrichedHistory, File(root, "recording-incidents.bin").readBytes())
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        assertFalse(File(root, "recording-session.bin").exists())
        val visible = RecordingIncidentStore.readIncidents(context)
        assertEquals(2, visible.size)
        val selected = visible.single { it.pid == 41 }
        assertEquals(100L, selected.captureArmedAtMillis)
        assertEquals(100L, selected.processStartedAtMillis)
        assertEquals(ApplicationExitInfo.REASON_CRASH, selected.exitReason)
        assertEquals("late-crash", selected.description)
        repeat(2) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(expectedEnrichedHistory, File(root, "recording-incidents.bin").readBytes())
    }

    @Test fun durableDeletedSessionStaysHiddenWhenRealPendingRecoveryFindsLateExitEvidence() = fixture { context, root ->
        File(root, "recording-session.bin").writeBytes(priorSession)
        File(root, "recording-incidents.bin").writeBytes(history(deleted, sentinel))
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val provisional = wireRows(File(root, "recording-incidents.bin").readBytes()).single { wirePid(it) == 41 }
        assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(provisional).getLong(17))
        addLateCrash(context)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val rows = wireRows(File(root, "recording-incidents.bin").readBytes())
        assertEquals(2, rows.size)
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val tombstone = rows.single { wirePid(it) == 41 }
        assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(tombstone).getLong(17))
        assertEquals(100L, ByteBuffer.wrap(tombstone).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(tombstone).getLong(65))
        assertEquals(ApplicationExitInfo.REASON_CRASH, ByteBuffer.wrap(tombstone).getInt(25))
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        assertEquals(listOf(99), RecordingIncidentStore.readIncidents(context).map { it.pid })
    }

    @Test fun actualFullPendingQueueRejectsRecoveryWithoutDisarmingOrDroppingAnyExistingOwner() = fixture { context, root ->
        seed(root)
        val pending = fullPendingQueue()
        File(root, "recording-incident-pending.bin").writeBytes(pending)
        val originalHistory = File(root, "recording-incidents.bin").readBytes()
        assertThrows(IOException::class.java) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(priorSession, File(root, "recording-session.bin").readBytes())
        assertArrayEquals(pending, File(root, "recording-incident-pending.bin").readBytes())
        assertArrayEquals(originalHistory, File(root, "recording-incidents.bin").readBytes())
        assertEquals(setOf("recording-session.bin", "recording-incidents.bin", "recording-incident-pending.bin"), root.list()!!.toSet())
    }

    @Test fun unavailableHistoryAfterPendingPublicationKeepsArmedEvidenceUntilActualRetrySucceeds() = fixture { context, root ->
        seed(root)
        val historyFile = File(root, "recording-incidents.bin")
        val originalHistory = historyFile.readBytes()
        val blocker = File(historyFile.path + ".new").apply { check(mkdir()) }
        assertThrows(IOException::class.java) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(priorSession, File(root, "recording-session.bin").readBytes())
        assertArrayEquals(originalHistory, historyFile.readBytes())
        assertTrue(blocker.isDirectory)
        val pendingFile = File(root, "recording-incident-pending.bin")
        val pending = pendingFile.readBytes()
        assertArrayEquals(hex("52495031030001"), pending.copyOfRange(0, 7))
        assertEquals(64, pending.size)
        assertArrayEquals(priorSession.copyOfRange(5, 46), pending.copyOfRange(7, 48))
        check(blocker.delete())
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(File(root, "recording-session.bin").exists())
        assertArrayEquals(pending, pendingFile.readBytes())
        val rows = wireRows(historyFile.readBytes())
        assertEquals(2, rows.size)
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        assertEquals(1, rows.count { wirePid(it) == 41 })
    }

    @Test @Config(sdk = [29]) fun preAndroidElevenRecoveryPublishesGenericIncidentWithoutInventingPendingEvidence() = fixture { context, root ->
        seed(root)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(File(root, "recording-session.bin").exists())
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        val rows = wireRows(File(root, "recording-incidents.bin").readBytes())
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val generic = rows.single { wirePid(it) == 41 }
        assertEquals(100L, ByteBuffer.wrap(generic).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(generic).getLong(65))
        assertEquals(0, ByteBuffer.wrap(generic).getInt(25))
        assertEquals("Recording process ended while capture was running", wireDescription(generic))
        val completed = File(root, "recording-incidents.bin").readBytes()
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertArrayEquals(completed, File(root, "recording-incidents.bin").readBytes())
    }

    @Test fun observedBootBoundaryPublishesOneSyntheticIncidentAndRetiresPriorArmedOwner() = fixture { context, root ->
        seed(root)
        val priorBoot = priorSession.copyOf().also { ByteBuffer.wrap(it).putInt(42, 9) }
        File(root, "recording-session.bin").writeBytes(priorBoot)
        assertTrue(Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 10))
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(File(root, "recording-session.bin").exists())
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        val rows = wireRows(File(root, "recording-incidents.bin").readBytes())
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val reboot = rows.single { wirePid(it) == 41 }
        assertEquals(100L, ByteBuffer.wrap(reboot).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(reboot).getLong(65))
        assertEquals("Device restarted while capture was running", wireDescription(reboot))
        val completed = File(root, "recording-incidents.bin").readBytes()
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertArrayEquals(completed, File(root, "recording-incidents.bin").readBytes())
    }

    @Test fun failedArmKeepsLateCrashOpenUntilRetryIsAdmitted() = fixture { context, root ->
        seed(root)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val pending = File(root, "recording-incident-pending.bin")
        val history = File(root, "recording-incidents.bin")
        val openPending = pending.readBytes()
        val openHistory = history.readBytes()
        val blocker = File(root, "recording-session.bin.new")
        val failingArm = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): android.content.pm.PackageManager {
                // Marker construction samples the package after the prior-session recovery barrier.
                check(blocker.mkdir())
                return super.getPackageManager()
            }
        }
        assertThrows(IOException::class.java) { RecordingIncidentStore.recordCaptureStarted(failingArm) }
        assertTrue(blocker.delete())
        assertFalse(File(root, "recording-session.bin").exists())
        assertArrayEquals(openPending, pending.readBytes())
        assertArrayEquals(openHistory, history.readBytes())

        addLateCrash(context)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(pending.exists())
        assertArrayEquals(expectedEnrichedHistory, history.readBytes())
        val token = RecordingIncidentStore.recordCaptureStarted(context)
        assertArrayEquals(expectedEnrichedHistory, history.readBytes())
        val admittedAt = laterCaptureTime(checkNotNull(token.marker).armedAtMillis)
        RecordingIncidentStore.recordCaptureAdmitted(context, token, admittedAt)
        awaitHistoryWriters()
        val crash = RecordingIncidentStore.readIncidents(context).single { it.pid == 41 }
        assertEquals(ApplicationExitInfo.REASON_CRASH, crash.exitReason)
        assertEquals(150L, crash.occurredAtMillis)
        assertEquals(admittedAt, crash.resumedAtMillis)
    }

    @Test fun lateCrashPreservesAdmissionTimeRecordedWhileEvidenceWasPending() = fixture { context, root ->
        seed(root)
        val token = RecordingIncidentStore.recordCaptureStarted(context)
        val pending = File(root, "recording-incident-pending.bin")
        assertEquals(0L, ByteBuffer.wrap(pending.readBytes()).getLong(48))
        val provisional = RecordingIncidentStore.readIncidents(context).single { it.pid == 41 }
        assertEquals(0L, provisional.resumedAtMillis)

        val admittedAt = laterCaptureTime(checkNotNull(token.marker).armedAtMillis)
        val history = File(root, "recording-incidents.bin")
        fun historyResumeTime(): Long {
            val row = wireRows(history.readBytes()).single { wirePid(it) == 41 }
            return ByteBuffer.wrap(row).getLong(9)
        }
        val admission = java.util.concurrent.FutureTask<Unit> {
            RecordingIncidentStore.recordCaptureAdmitted(context, token, admittedAt)
        }
        val caller = Thread(admission, "capture-admission-test").apply { isDaemon = true }
        try {
            synchronized(RecordingIncidentStore) {
                caller.start()
                admission.get(5, TimeUnit.SECONDS)
                assertEquals(0L, ByteBuffer.wrap(pending.readBytes()).getLong(48))
                assertEquals(0L, historyResumeTime())
            }
        } finally {
            // A caller blocked on the store can finish once the monitor is released, even on timeout.
            caller.join(5_000)
            assertFalse("Admission caller did not finish", caller.isAlive)
            awaitHistoryWriters()
        }
        assertEquals(admittedAt, ByteBuffer.wrap(pending.readBytes()).getLong(48))
        assertEquals(admittedAt, historyResumeTime())
        addLateCrash(context)
        val crash = RecordingIncidentStore.readIncidents(context).single { it.pid == 41 }
        assertFalse(pending.exists())
        assertEquals(provisional.captureArmedAtMillis, crash.captureArmedAtMillis)
        assertEquals(ApplicationExitInfo.REASON_CRASH, crash.exitReason)
        assertEquals(admittedAt, crash.resumedAtMillis)
    }

    @Test fun historyReadRetriesFailedAdmissionWriteWithOriginalTimestamp() = fixture { context, root ->
        seed(root)
        val token = RecordingIncidentStore.recordCaptureStarted(context)
        val admittedAt = laterCaptureTime(checkNotNull(token.marker).armedAtMillis)
        val writeAt = laterCaptureTime(admittedAt)
        JournalHostIoShadow.failDirectorySyncs = 1
        RecordingIncidentStore.recordCaptureAdmitted(context, token, admittedAt)
        awaitHistoryWriters()
        assertEquals(0, JournalHostIoShadow.failDirectorySyncs)

        laterCaptureTime(writeAt)
        val retried = RecordingIncidentStore.readIncidents(context).single { it.pid == 41 }
        assertEquals(admittedAt, retried.resumedAtMillis)
        val pending = File(root, "recording-incident-pending.bin")
        assertEquals(admittedAt, ByteBuffer.wrap(pending.readBytes()).getLong(48))
        val rows = wireRows(File(root, "recording-incidents.bin").readBytes())
        assertEquals(2, rows.size)
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        assertEquals(admittedAt, ByteBuffer.wrap(rows.single { wirePid(it) == 41 }).getLong(9))
    }

    @Test fun visibleHistoryResumeRetriesFailedReplayBarrierWithOriginalTimestamp() = fixture { context, root ->
        seed(root)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val pending = File(root, "recording-incident-pending.bin")
        assertTrue(pending.isFile)
        addLateCrash(context)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(pending.exists())

        val token = RecordingIncidentStore.recordCaptureStarted(context)
        val history = File(root, "recording-incidents.bin")
        assertArrayEquals(expectedEnrichedHistory, history.readBytes())
        val admittedAt = laterCaptureTime(checkNotNull(token.marker).armedAtMillis)
        JournalHostIoShadow.failDirectorySyncs = 1
        RecordingIncidentStore.recordCaptureAdmitted(context, token, admittedAt)
        awaitHistoryWriters()
        assertEquals(0, JournalHostIoShadow.failDirectorySyncs)
        val visible = history.readBytes()
        val rows = wireRows(visible)
        assertEquals(2, rows.size)
        assertEquals(admittedAt, ByteBuffer.wrap(rows.single { wirePid(it) == 41 }).getLong(9))
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })

        laterCaptureTime(admittedAt)
        JournalHostIoShadow.failDirectorySyncs = 1
        val replayFailure = assertThrows(IOException::class.java) { RecordingIncidentStore.readIncidents(context) }
        // Other recovery paths also sync this directory. This failure must belong to history publication.
        assertTrue(replayFailure.message.orEmpty().contains(history.path))
        assertEquals(0, JournalHostIoShadow.failDirectorySyncs)
        assertArrayEquals(visible, history.readBytes())

        val crash = RecordingIncidentStore.readIncidents(context).single { it.pid == 41 }
        assertEquals(ApplicationExitInfo.REASON_CRASH, crash.exitReason)
        assertEquals(admittedAt, crash.resumedAtMillis)
        assertArrayEquals(visible, history.readBytes())
        assertFalse(pending.exists())
    }

    @Test fun supersededArmCannotResumePendingOutageForDetachedInput() = fixture { context, root ->
        seed(root)
        val detached = RecordingIncidentStore.recordCaptureStarted(context)
        val current = RecordingIncidentStore.recordCaptureStarted(context)
        val pending = File(root, "recording-incident-pending.bin")
        val history = File(root, "recording-incidents.bin")
        val openPending = pending.readBytes()
        val openHistory = history.readBytes()
        val staleAt = laterCaptureTime(checkNotNull(current.marker).armedAtMillis)

        RecordingIncidentStore.recordCaptureAdmitted(context, detached, staleAt)
        awaitHistoryWriters()
        assertArrayEquals(openPending, pending.readBytes())
        assertArrayEquals(openHistory, history.readBytes())
        val stillOpen = RecordingIncidentStore.readIncidents(context).single { it.pid == 41 }
        assertEquals(0L, stillOpen.resumedAtMillis)

        val admittedAt = laterCaptureTime(staleAt)
        RecordingIncidentStore.recordCaptureAdmitted(context, current, admittedAt)
        awaitHistoryWriters()
        assertEquals(admittedAt, ByteBuffer.wrap(pending.readBytes()).getLong(48))
        val resumed = wireRows(history.readBytes()).single { wirePid(it) == 41 }
        assertEquals(admittedAt, ByteBuffer.wrap(resumed).getLong(9))
    }

    private fun laterCaptureTime(previous: Long): Long {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
        awaitCondition { System.currentTimeMillis() > previous }
        return System.currentTimeMillis()
    }

    private fun seed(root: File) {
        File(root, "recording-session.bin").writeBytes(priorSession)
        File(root, "recording-incidents.bin").writeBytes(history(sentinel))
    }

    private fun awaitOpenObservation(context: Context, duration: CaptureObservationDuration): RecordingIncident {
        var seen: RecordingIncident? = null
        awaitCondition {
            seen = RecordingIncidentStore.readIncidents(context).firstOrNull { it.ongoingPolicyDuration === duration }
            seen != null
        }
        return checkNotNull(seen)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Observation did not settle in isolated history" }
            Thread.sleep(2)
        }
    }

    private fun awaitHistoryWriters() {
        val field = RecordingIncidentStore::class.java.getDeclaredField("historyMutationScope").apply { isAccessible = true }
        val scope = field.get(RecordingIncidentStore) as CoroutineScope
        runBlocking { withTimeout(8_000) {
            checkNotNull(scope.coroutineContext[Job]).children.toList().forEach { it.join() }
        } }
    }

    private fun addLateCrash(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val shadow = Shadow.extract<ShadowActivityManager>(manager)
        val exit = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setPid(41).setProcessName(context.packageName).setReason(ApplicationExitInfo.REASON_CRASH)
            .setTimestamp(150L).setStatus(17).setImportance(100).setPss(101L).setRss(102L)
            .setDescription("late-crash").build()
        shadow.addApplicationExitInfo(exit)
    }

    private fun fullPendingQueue(): ByteArray {
        val owner = hex("01000000290000000000000000000000000000006400000000000000000000000000000007ffffffff000000000000000000000000000003e8")
        return ByteArray(7 + 128 * owner.size).also { bytes ->
            hex("52495031030080").copyInto(bytes)
            repeat(128) { index ->
                val offset = 7 + index * owner.size
                owner.copyInto(bytes, offset)
                ByteBuffer.wrap(bytes).putInt(offset + 1, 10000 + index)
            }
        }
    }

    private fun history(vararg records: ByteArray): ByteArray =
        hex("5249483104") + byteArrayOf(0, records.size.toByte()) + records.fold(byteArrayOf()) { bytes, record -> bytes + record }

    // Fixed published v4 field positions, with assertions; no production decoder is used.
    private fun wireRows(bytes: ByteArray): List<ByteArray> {
        assertArrayEquals(hex("5249483104"), bytes.copyOfRange(0, 5))
        val count = ByteBuffer.wrap(bytes).getShort(5).toInt() and 0xffff
        val rows = mutableListOf<ByteArray>()
        var offset = 7
        repeat(count) {
            val descriptionBytes = ByteBuffer.wrap(bytes).getShort(offset + 73).toInt() and 0xffff
            val end = offset + 83 + descriptionBytes
            rows += bytes.copyOfRange(offset, end)
            offset = end
        }
        assertEquals(bytes.size, offset)
        return rows
    }
    private fun wirePid(record: ByteArray): Int = ByteBuffer.wrap(record).getInt(33)
    private fun wireDescription(record: ByteArray): String =
        record.copyOfRange(75, record.size - 8).toString(Charsets.US_ASCII)
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
    }

    private fun fixture(test: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("incident-recovery-v2-").toFile()
        val previousPid = Process.myPid()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        var ownsFixture = false
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { record(error) } }
        try {
            check(previousRoot == null)
            JournalHostIoShadow.reset()
            ownsFixture = true
            val app = RuntimeEnvironment.getApplication()
            ShadowProcess.setPid(4242)
            // Initialize the Android runtime before aligning its synthetic process start with
            // the reset Robolectric elapsed clock. Incident identity is captured lazily once.
            val started = Process.getStartElapsedRealtime()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                (started - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            check(Process.myPid() > 0 && started >= 0L &&
                android.os.SystemClock.elapsedRealtime() >= started && System.currentTimeMillis() > 0L)
            ServiceStateOsShadow.fixtureRoot = root.toPath()
            test(IsolatedContext(app, root), root)
        } catch (error: Throwable) { failure = error }
        finally {
            if (ownsFixture) cleanup { JournalHostIoShadow.finishFixture()?.let(::record) }
            cleanup { ServiceStateOsShadow.fixtureRoot = previousRoot }
            cleanup { ShadowProcess.setPid(previousPid) }
            if (failure == null) cleanup { check(root.deleteRecursively()) }
        }
        failure?.let { throw it }
    }
}
