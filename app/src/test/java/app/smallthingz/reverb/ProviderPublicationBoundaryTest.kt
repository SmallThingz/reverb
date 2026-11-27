package app.smallthingz.reverb

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.smallthingz.reverb.incidentcontextisolated.IncidentContextSandboxMarker
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowProcess
import org.robolectric.shadows.ShadowSystemClock

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

    @Test
    @Config(shadows = [JournalHostIoShadow::class],
        instrumentedPackages = ["app.smallthingz.reverb.incidentcontextisolated"])
    fun deniedRenameConsentReachesRepositoryWithoutAnUnexpectedIncident() =
        renameConsentIncidentBoundary(RenameConsentTerminal.DENIED)

    @Test
    @Config(shadows = [JournalHostIoShadow::class],
        instrumentedPackages = ["app.smallthingz.reverb.incidentcontextisolated"])
    fun retiredRenameHostReachesRepositoryWithoutAnUnexpectedIncident() =
        renameConsentIncidentBoundary(RenameConsentTerminal.HOST_RETIRED)

    @Test
    @Config(shadows = [JournalHostIoShadow::class],
        instrumentedPackages = ["app.smallthingz.reverb.incidentcontextisolated"])
    fun failedRenameConsentLaunchReachesDurableUnexpectedIncidentHistory() =
        renameConsentIncidentBoundary(RenameConsentTerminal.LAUNCH_FAILED)

    @Test
    @Config(shadows = [JournalHostIoShadow::class],
        instrumentedPackages = ["app.smallthingz.reverb.incidentcontextisolated"])
    fun acceptedRenameLaunchFailureSurvivesLastHostRetirement() =
        renameConsentIncidentBoundary(RenameConsentTerminal.LAUNCH_FAILED_THEN_HOST_RETIRED)

    private enum class RenameConsentTerminal(val launchFailed: Boolean = false) {
        DENIED, HOST_RETIRED, LAUNCH_FAILED(true), LAUNCH_FAILED_THEN_HOST_RETIRED(true),
    }

    private fun renameConsentIncidentBoundary(terminal: RenameConsentTerminal) = runBlocking {
        val loader = IncidentContextSandboxMarker::class.java.classLoader
        assertSame(loader, javaClass.classLoader)
        assertSame(loader, RecordingDeletionConsent::class.java.classLoader)
        assertSame(loader, RecordingIncidentStore::class.java.classLoader)
        val app = RuntimeEnvironment.getApplication()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        val previousPid = Process.myPid()
        check(previousRoot == null)
        JournalHostIoShadow.reset()
        var primary: Throwable? = null
        try {
            // Use the existing incident sandbox's borrowed PID and clock setup. Keep it
            // separate from the ordinary provider/classifier lane; do not reset production statics.
            ShadowProcess.setPid(4242)
            val started = Process.getStartElapsedRealtime()
            ShadowSystemClock.advanceBy(
                (started - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            check(Process.myPid() > 0 && started >= 0L &&
                android.os.SystemClock.elapsedRealtime() >= started && System.currentTimeMillis() > 0L)
            ServiceStateOsShadow.fixtureRoot = checkNotNull(app.filesDir.parentFile).canonicalFile.toPath()
            fixture { _, provider, target, fingerprint, bytes, dao ->
                val historyRoot = File(provider.payload.parentFile, "incidents").apply { check(mkdir()) }
                val context = object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this
                    override fun getNoBackupFilesDir(): File = historyRoot
                }
                val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
                provider.expectedNameQuery = "renamed.wav"
                // Only the external provider refuses mutation. Real stable-byte/postcondition
                // verification must produce the consent proof; no synthetic proof or reporter call.
                provider.failureBeforeUpdateCommit = SecurityException("provider requires rename consent")
                assertTrue(RecordingIncidentStore.readIncidents(context).isEmpty())
                assertTrue("real incident process publication must cross a journal barrier",
                    JournalHostIoShadow.directorySyncAttempts > 0)
                val hosts = ViewModelStore()
                ViewModelProvider(hosts, ViewModelProvider.NewInstanceFactory())[
                    RecordingRenameConsentHostViewModel::class.java]
                val lifetime = RecordingDeletionConsent.renameLifetime()
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { RecordingRepository.rename(context, selected, "renamed", lifetime) }
                }
                var launch: RecordingDeletionConsentLaunch? = null
                var bodyFailure: Throwable? = null
                try {
                    val prompt = withTimeout(5_000L) {
                        RecordingDeletionConsent.changes.mapNotNull { RecordingDeletionConsent.claim(context) }.first()
                    }
                    launch = prompt
                    assertTrue(RecordingDeletionConsent.owns(prompt.token))
                    assertTrue(recordingMutations.isActive(selected.id))
                    assertFalse(result.isCompleted)
                    assertEquals(listOf(selected.id), provider.writeRequestUris)
                    assertTrue(provider.didThrow)
                    provider.assertRenameRequest()
                    val launchFailure = SecurityException("fixture genuine activity launch failure")
                    when (terminal) {
                        RenameConsentTerminal.DENIED ->
                            RecordingDeletionConsent.finish(context, prompt.token, granted = false)
                        RenameConsentTerminal.HOST_RETIRED -> hosts.clear()
                        RenameConsentTerminal.LAUNCH_FAILED,
                        RenameConsentTerminal.LAUNCH_FAILED_THEN_HOST_RETIRED -> {
                            val error = runCatching {
                                prompt.launchAfterStoppingPlayback { throw launchFailure }
                            }.exceptionOrNull()
                            assertSame(launchFailure, error)
                            val finish = {
                                RecordingDeletionConsent.finish(context, prompt.token, granted = false,
                                    launchFailed = true, launchFailure = error)
                            }
                            if (terminal == RenameConsentTerminal.LAUNCH_FAILED_THEN_HOST_RETIRED) {
                                finishRenameLaunchThenRetireHost(hosts, finish)
                            } else finish()
                        }
                    }
                    val failure = withTimeout(5_000L) { result.await() }.exceptionOrNull()
                    assertTrue(failure is RecordingRenameDefiniteFailure)
                    failure as RecordingRenameDefiniteFailure
                    assertEquals(when (terminal) {
                        RenameConsentTerminal.DENIED -> RecordingRenameConsentRejection.DENIED
                        RenameConsentTerminal.HOST_RETIRED -> RecordingRenameConsentRejection.HOST_RETIRED
                        RenameConsentTerminal.LAUNCH_FAILED,
                        RenameConsentTerminal.LAUNCH_FAILED_THEN_HOST_RETIRED -> null
                    }, failure.expectedConsentRejection)
                    if (terminal.launchFailed) assertSame(launchFailure, failure.cause)
                    awaitRenameBoundaryWork()
                    assertFalse(RecordingDeletionConsent.owns(prompt.token))
                    assertFalse(recordingMutations.isActive(selected.id))
                    assertEquals(selected, dao.findById(selected.id))
                    assertEquals("clip.wav", provider.name)
                    assertArrayEquals(bytes, provider.payload.readBytes())
                    assertEquals(1, provider.updates.size)
                    val incidents = RecordingIncidentStore.readIncidents(context)
                    if (terminal.launchFailed) {
                        val incident = incidents.single()
                        assertEquals(RecordingIncidentKind.UNEXPECTED_ERROR, incident.kind)
                        assertTrue(checkNotNull(incident.description).contains("Rename recording"))
                        assertTrue(checkNotNull(incident.description).contains(launchFailure.message!!))
                        assertEquals(Process.myPid(), incident.pid)
                        assertTrue(JournalHostIoShadow.directorySyncAttempts > 0)
                    } else assertTrue(incidents.isEmpty())
                    // A late duplicate result must neither retry the rename nor append an incident.
                    RecordingDeletionConsent.finish(context, prompt.token, granted = false,
                        launchFailed = true, launchFailure = launchFailure)
                    awaitRenameBoundaryWork()
                    assertEquals(incidents, RecordingIncidentStore.readIncidents(context))
                    assertEquals(1, provider.updates.size)
                } catch (error: Throwable) { bodyFailure = error; throw error }
                finally {
                    // Finish the real coordinator before the existing fixture retires its files/DB.
                    withContext(NonCancellable) {
                        val cleanup = listOf<suspend () -> Unit>(
                            { hosts.clear() },
                            { withTimeout(5_000L) { result.join() } },
                            { awaitRenameBoundaryWork() },
                            { launch?.let { assertFalse(RecordingDeletionConsent.owns(it.token)) } },
                        )
                        var cleanupFailure: Throwable? = null
                        for (step in cleanup) try { step() } catch (error: Throwable) {
                            val owner = bodyFailure ?: cleanupFailure
                            if (owner != null) owner.addSuppressed(error) else cleanupFailure = error
                        }
                        if (bodyFailure == null) cleanupFailure?.let { throw it }
                    }
                }
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val cleanup = listOf<() -> Unit>(
                { JournalHostIoShadow.finishFixture()?.let { throw it } },
                { ServiceStateOsShadow.fixtureRoot = previousRoot },
                { ShadowProcess.setPid(previousPid) },
            )
            var cleanupFailure: Throwable? = null
            for (step in cleanup) try { step() } catch (error: Throwable) {
                val owner = primary ?: cleanupFailure
                if (owner != null) owner.addSuppressed(error) else cleanupFailure = error
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }

    private fun finishRenameLaunchThenRetireHost(hosts: ViewModelStore, finish: () -> Unit) {
        val coordinator = RecordingDeletionConsent
        val work = synchronized(coordinator) {
            val field = coordinator.javaClass.getDeclaredField("renames").apply { isAccessible = true }
            (field.get(coordinator) as Map<*, *>).values.single() as RecordingRenameConsentWork
        }
        val finishThread = Thread.currentThread()
        var completedOnFinishThread = false
        // Observe only completion on the caller thread. An IO worker winning the race
        // cannot satisfy the check, and a completion during clear() is already too late.
        val observation = work.result.invokeOnCompletion {
            if (Thread.currentThread() === finishThread) completedOnFinishThread = true
        }
        try {
            finish()
            val publishedBeforeRetirement = completedOnFinishThread
            hosts.clear()
            assertTrue("accepted failure must settle before last-host retirement", publishedBeforeRetirement)
        } finally {
            observation.dispose()
        }
    }

    private suspend fun awaitRenameBoundaryWork() {
        // Extend IncidentContextRecoveryV2Test's read-only scope fence to the coordinator:
        // owns(token) stops at result acceptance, before completeRename's finally retirement.
        // These scopes belong to this isolated sandbox; no production state is replaced/reset.
        for ((owner, name) in listOf(RecordingDeletionConsent to "scope",
            RecordingIncidentStore to "historyMutationScope")) {
            val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
            val scope = field.get(owner) as CoroutineScope
            withTimeout(8_000L) { checkNotNull(scope.coroutineContext[Job]).children.toList().forEach { it.join() } }
        }
    }

    @Test fun deniedImmediateWriteRequestPreservesPayloadCatalogAndJournalAfterGrantChanges() = runBlocking {
        fixture { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            val siblingId = "content://media/external/audio/media/730033"
            var writable = true
            val context = object : ContextWrapper(app) {
                override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) = PackageManager.PERMISSION_DENIED
                override fun checkContentUriPermissionFull(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int {
                    assertTrue(uri.toString() == selected.id || uri.toString() == siblingId)
                    assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, modeFlags)
                    return if (writable || uri.toString() == siblingId) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                }
            }
            // Robolectric defaults unconfigured AppOps to allowed, which bypasses the per-row checks.
            val appOps = shadowOf(checkNotNull(app.getSystemService(AppOpsManager::class.java)))
            val uid = app.applicationInfo.uid
            val storageOp = checkNotNull(AppOpsManager.permissionToOp(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
            val mediaOp = checkNotNull(AppOpsManager.permissionToOp(Manifest.permission.MANAGE_MEDIA))
            appOps.setMode(storageOp, uid, app.packageName, AppOpsManager.MODE_IGNORED)
            appOps.setMode(mediaOp, uid, context.opPackageName, AppOpsManager.MODE_IGNORED)
            // SDK 35 resolves the first external directory before checking the all-files AppOp.
            ShadowEnvironment.addExternalDir("provider-publication-boundary")
            assertFalse(hasAllFilesAccess())
            assertFalse(MediaStore.canManageMedia(context))
            val batch = listOf(selected)
            assertEquals(batch, RecordingDeletionConsent.planDeletion(context, batch).undo)
            writable = false
            provider.ownerPackage = app.packageName
            assertEquals(batch, RecordingDeletionConsent.planDeletion(context, batch).undo)
            provider.ownerPackage = "fixture.foreign"
            // A real second row and descriptor ensure proof failure cannot masquerade as writable access.
            assertNull(dao.findById(siblingId))
            provider.alternateRowAvailable = true
            val sibling = selected.copy(id = siblingId,
                fileIdentity = providerIdentity(Uri.parse(siblingId), bytes.size.toLong(), provider.generation))
            try {
                dao.upsert(sibling)
                assertEquals(sibling, RecordingRepository.snapshotDeletionConsentTarget(context, sibling)?.recording)
                assertTrue(requiresMediaWriteConsent(context, selected))
                assertFalse(requiresMediaWriteConsent(context, sibling))
                val mixed = RecordingDeletionConsent.planDeletion(context, listOf(selected, sibling))
                assertEquals(listOf(sibling), mixed.undo)
                assertEquals(batch, mixed.prompt.map { it.recording })
                val completion = RecordingDeletionConsent.enqueueImmediate(mixed.prompt)
                // If preparation called the ordinary delete attempt now, this provider would remove the bytes.
                writable = true
                provider.removeOnDelete = true
                val launch = withTimeout(5_000L) {
                    RecordingDeletionConsent.changes.mapNotNull { RecordingDeletionConsent.claim(context) }.first()
                }
                val revision = RecordingDeletionConsent.completions.value
                try {
                    assertEquals(batch, launch.targets.map { it.recording })
                    assertEquals(listOf(selected.id), provider.writeRequestUris)
                    assertEquals(0, provider.deletes)
                    assertEquals(selected, dao.findById(selected.id))
                } finally {
                    RecordingDeletionConsent.finish(context, launch.token, granted = false)
                    withTimeout(5_000L) { RecordingDeletionConsent.completions.first { it > revision } }
                }
                withTimeout(5_000L) { completion.await() }
                assertFalse(RecordingDeletionConsent.owns(launch.token))
                assertEquals(selected, dao.findById(selected.id))
                assertArrayEquals(bytes, provider.payload.readBytes())
                assertTrue(getRecorderPreferences(app).getStringSet(PrefKey.PENDING_RECORDING_DELETIONS.name, emptySet())!!.isEmpty())
                assertEquals(sibling, dao.findById(siblingId))
            } finally {
                dao.deleteById(siblingId)
            }
        }
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun restoredNotificationWaitsForProcessPromptAndPostSettlementCatalog() = runBlocking {
        for (granted in listOf(false, true)) fixture(expectedDeletes = if (granted) 1 else 0) {
                app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            val proof = checkNotNull(RecordingRepository.snapshotDeletionConsentTarget(app, selected))
            val rows = dao.listAll()
            val other = rows.single { it.id.endsWith("/730032") }
            val request = RecordingPlaybackRequest("saved-request", selected.id, selected.storageType, selected.fileIdentity)
            provider.removeOnDelete = true
            lateinit var receipt: kotlinx.coroutines.Deferred<Unit>
            NativeLibraryPlaybackStateTest.NotificationFixture(rows, selected,
                plan = { _, _ -> RecordingDeletionPlan(emptyList(), listOf(proof)) },
                prompt = { RecordingDeletionConsent.enqueueImmediate(it).also { result -> receipt = result } },
                ownsCatalog = false).use { first ->
                first.library.controller.deleteRecordings(listOf(selected))
                first.request(request)
                first.completeRefresh(rows)
                first.assertNoStart()
            }
            val launch = withTimeout(5_000L) {
                RecordingDeletionConsent.changes.mapNotNull { RecordingDeletionConsent.claim(app) }.first()
            }
            try {
                NativeLibraryPlaybackStateTest.NotificationFixture(rows, selected, ownsCatalog = false).use { next ->
                    next.request("other", other)
                    next.completeRefresh(rows)
                    assertEquals(other, next.takeStart().recording)
                    RecordingPlayback.stop(other)
                    next.library.controller.info(other)
                    next.request(checkNotNull(RecordingPlaybackRequest.fromBundle(request.toBundle())))
                    next.completeRefresh(rows)
                    assertEquals(listOf(other), next.completed)
                    assertEquals(other, next.library.latest.infoRecording)
                    next.assertNoStart()

                    next.holdRefresh()
                    RecordingDeletionConsent.finish(app, launch.token, granted)
                    withTimeout(5_000L) { receipt.await() }
                    next.library.controller.dateFormattingChanged()
                    next.library.settle()
                    assertEquals(listOf(other), next.completed)
                    next.assertNoStart()
                    next.completeRefresh(dao.listAll())
                    if (!granted) assertEquals(selected, next.takeStart().recording)
                    assertEquals(listOf(other, selected.takeUnless { granted }), next.completed)
                    next.assertNoStart()
                }
            } finally {
                RecordingDeletionConsent.finish(app, launch.token, false)
                withTimeout(5_000L) { receipt.await() }
            }
        }
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun matchingProcessConsentQueuedDuringNotificationScanRevokesThatProof() = runBlocking {
        fixture(expectedDeletes = 1) { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            val proof = checkNotNull(RecordingRepository.snapshotDeletionConsentTarget(app, selected))
            val rows = dao.listAll()
            provider.removeOnDelete = true
            NativeLibraryPlaybackStateTest.NotificationFixture(rows, selected, ownsCatalog = false).use { next ->
                next.request("scan-before-enqueue")
                val receipt = RecordingDeletionConsent.enqueueImmediate(listOf(proof))
                val launch = withTimeout(5_000L) {
                    RecordingDeletionConsent.changes.mapNotNull { RecordingDeletionConsent.claim(app) }.first()
                }
                try {
                    next.completeRefresh(rows)
                    next.assertNoStart()
                    next.holdRefresh()
                    RecordingDeletionConsent.finish(app, launch.token, true)
                    withTimeout(5_000L) { receipt.await() }
                    assertNull(dao.findById(selected.id))
                    next.library.controller.dateFormattingChanged()
                    next.library.settle()
                    assertTrue(next.completed.isEmpty())
                    next.assertNoStart()
                    next.completeRefresh(dao.listAll())
                    assertEquals(listOf<RecordingEntity?>(null), next.completed)
                    next.assertNoStart()
                } finally {
                    RecordingDeletionConsent.finish(app, launch.token, false)
                    withTimeout(5_000L) { receipt.await() }
                }
            }
        }
    }

    @Test fun matchingEnqueueAndVerifiedPlaybackHaveOneAdmissionOrder() = runBlocking {
        fixture { app, provider, target, fingerprint, bytes, _ ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            val proof = checkNotNull(RecordingRepository.snapshotDeletionConsentTarget(app, selected))
            val request = RecordingPlaybackRequest("verified-before-IO", selected.id, selected.storageType, selected.fileIdentity)
            val earlierWatch = RecordingPlaybackRoute()
            val delivery = RecordingPlaybackRoute()
            delivery.submit(request)
            delivery.verified(request.token, delivery.proofGeneration)
            RecordingDeletionConsent.watchPlayback(earlierWatch)
            RecordingDeletionConsent.watchPlayback(delivery)
            val enqueue = FutureTask { RecordingDeletionConsent.enqueueImmediate(listOf(proof)) }
            val consume = FutureTask { RecordingDeletionConsent.takePlaybackIfReady(delivery, true) }
            val writer = Thread(enqueue, "test-consent-enqueue")
            val reader = Thread(consume, "test-consent-delivery")
            fun awaitMonitor(thread: Thread) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (thread.isAlive && thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertEquals("Expected monitor contention from ${thread.name}", Thread.State.BLOCKED, thread.state)
            }
            try {
                assertFalse(RecordingDeletionConsent.holdsPlayback(request))
                // Stop enqueue after insertion, before it can invalidate delivery's still-ready proof.
                synchronized(earlierWatch) {
                    writer.start()
                    awaitMonitor(writer)
                    reader.start()
                    awaitMonitor(reader)
                }
                assertNull(consume.get(5, TimeUnit.SECONDS))
            } finally {
                RecordingDeletionConsent.retirePlayback(earlierWatch)
                RecordingDeletionConsent.retirePlayback(delivery)
                if (writer.state != Thread.State.NEW) {
                    val receipt = enqueue.get(5, TimeUnit.SECONDS)
                    val launch = withTimeout(5_000L) {
                        RecordingDeletionConsent.changes.mapNotNull { RecordingDeletionConsent.claim(app) }.first()
                    }
                    RecordingDeletionConsent.finish(app, launch.token, false)
                    withTimeout(5_000L) { receipt.await() }
                }
            }
        }
    }

    @Test fun consentProofReadAllowsCatalogWorkAndRejectsAReplacementRow() = runBlocking {
        for (queued in listOf(false, true)) fixture { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            val original = if (queued) checkNotNull(RecordingRepository.snapshotDeletionConsentTarget(app, selected))
            else null
            val opened = CountDownLatch(1)
            val allowOpen = CountDownLatch(1)
            provider.beforeReadOpen = {
                opened.countDown()
                assertTrue("Provider open was not released", allowOpen.await(15L, TimeUnit.SECONDS))
            }
            val proof = async(start = CoroutineStart.UNDISPATCHED) {
                RecordingRepository.snapshotDeletionConsentTarget(app, selected, original)
            }
            val replacement = selected.copy(
                fileIdentity = providerIdentity(Uri.parse(selected.id), selected.sizeBytes, provider.generation + 1))
            try {
                assertTrue("Consent proof never reached the provider", opened.await(5L, TimeUnit.SECONDS))
                val known = withTimeout(5_000L) { RecordingRepository.listKnown(app) }
                assertTrue(known.any { sameRecordingActionTarget(selected, it) })
                assertFalse(proof.isCompleted)
                // Change only the catalog. The provider can still prove the originally selected
                // bytes and identity, so only the final catalog recheck can reject this result.
                dao.upsert(replacement)
            } finally {
                allowOpen.countDown()
            }
            assertNull(withTimeout(5_000L) { proof.await() })
            assertEquals(replacement, dao.findById(selected.id))
            assertArrayEquals(bytes, provider.payload.readBytes())
            assertTrue(getRecorderPreferences(app).requireDurableStringSet(PrefKey.PENDING_RECORDING_DELETIONS).isEmpty())
            assertTrue(provider.writeRequestUris.isEmpty())
        }
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun ordinaryPlaybackRejectsHeldApprovedTargetUntilConsentSettles() = runBlocking {
        fixture(expectedDeletes = 1) { app, provider, target, fingerprint, bytes, dao ->
            val selected = registerPublishedSelection(app, provider, target, fingerprint, bytes)
            val earlierUri = Uri.parse("content://media/external/audio/media/730033")
            val earlier = selected.copy(id = earlierUri.toString(),
                fileIdentity = providerIdentity(earlierUri, selected.sizeBytes, provider.generation))
            provider.alternateRowAvailable = true
            dao.upsert(earlier)
            val priorFeedback = AppFeedbackCenter.events.value.map { it.id }.toSet()
            try {
                val proofs = listOf(earlier, selected).map {
                    checkNotNull(RecordingRepository.snapshotDeletionConsentTarget(app, it))
                }
                val receipt = RecordingDeletionConsent.enqueueImmediate(proofs)
                val launch = withTimeout(5_000L) {
                    RecordingDeletionConsent.changes.mapNotNull { RecordingDeletionConsent.claim(app) }.first()
                }
                val opened = CountDownLatch(1)
                val allowOpen = CountDownLatch(1)
                val appShadow = shadowOf(app)
                val replacement = selected.copy(
                    fileIdentity = providerIdentity(Uri.parse(selected.id), selected.sizeBytes, provider.generation + 1))
                fun assertAdmitted(recording: RecordingEntity) {
                    assertTrue(RecordingPlayback.play(app, recording))
                    val intent = checkNotNull(appShadow.nextStartedService)
                    val token = intent.getLongExtra(RecordingPlaybackService.EXTRA_REQUEST, -1L)
                    assertEquals(recording, checkNotNull(RecordingPlayback.takeStart(token)).recording)
                }
                try {
                    assertEquals(listOf(earlier, selected), launch.targets.map { it.recording })
                    provider.beforeReadOpen = { uri ->
                        if (uri == earlierUri) {
                            opened.countDown()
                            assertTrue("Earlier approved read was not released", allowOpen.await(15L, TimeUnit.SECONDS))
                            throw IOException("Earlier approved source temporarily unavailable")
                        }
                    }
                    RecordingDeletionConsent.finish(app, launch.token, granted = true)
                    assertTrue("Earlier approved retry never reached its provider", opened.await(5L, TimeUnit.SECONDS))
                    assertFalse(receipt.isCompleted)
                    assertFalse(recordingMutations.isActive(selected.id))
                    assertFalse(RecordingPlayback.play(app, selected))
                    assertFalse(RecordingPlayback.hasPendingStart())
                    assertNull(appShadow.nextStartedService)
                    assertAdmitted(replacement)
                } finally {
                    allowOpen.countDown()
                    try {
                        RecordingDeletionConsent.finish(app, launch.token, granted = false)
                        withTimeout(5_000L) { receipt.await() }
                    } finally {
                        RecordingPlayback.stop(selected)
                        RecordingPlayback.stop(replacement)
                    }
                }
                // The test provider rejects deletion without removing this recording.
                assertEquals(selected, dao.findById(selected.id))
                assertArrayEquals(bytes, provider.payload.readBytes())
                assertAdmitted(selected)
            } finally {
                AppFeedbackCenter.events.value.filterNot { it.id in priorFeedback }
                    .forEach { AppFeedbackCenter.acknowledge(it.id) }
                dao.deleteById(earlier.id)
            }
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

    @Test fun updateFailureRetainsCauseAndPendingPublicationDiagnostic() =
        rejectedUpdateRetainsDiagnostic(observationUnavailable = false)

    @Test fun updateFailureRetainsCauseAndUnavailablePublicationDiagnostic() =
        rejectedUpdateRetainsDiagnostic(observationUnavailable = true)

    private fun rejectedUpdateRetainsDiagnostic(observationUnavailable: Boolean) = runBlocking {
        fixture { app, provider, target, fingerprint, bytes, dao ->
            val preferences = getRecorderPreferences(app)
            val markers = preferences.getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.toSet()
            val suppressed = preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            val original = IllegalStateException("provider update failed before commit")
            val originalName = provider.name
            provider.failureBeforeUpdateCommit = original
            provider.unavailableAfterUpdateFailure = observationUnavailable
            var publicationAttempts = 0
            val failure = publicationFailure {
                finalizeOutputTarget(app, target, fingerprint, beforePublish = { publicationAttempts++ })
            }
            assertEquals(IOException::class.java, failure.javaClass)
            assertEquals("MediaStore publication result is uncertain: ${target.uri}", failure.message)
            assertSame("retain the actual provider exception as the cause", original, failure.cause)
            assertEquals(1, failure.suppressed.size)
            val diagnostic = failure.suppressed.single()
            assertEquals(IOException::class.java, diagnostic.javaClass)
            val predicate = if (observationUnavailable) "metadata before payload unavailable" else "row still pending"
            assertEquals("Unable to verify MediaStore publication postcondition ($predicate): ${target.uri}", diagnostic.message)
            assertEquals(1, publicationAttempts)
            assertEquals(1, provider.updates.size)
            assertTrue(provider.didThrow)
            assertFalse(provider.updated)
            assertEquals(originalName, provider.name)
            assertEquals(1, provider.pending)
            assertEquals(0, provider.inserts)
            assertEquals(0, provider.deletes)
            assertArrayEquals(bytes, provider.payload.readBytes())
            assertNull(dao.findById(target.id))
            assertEquals(markers, preferences.getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
            assertEquals(suppressed, preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
        }
    }

    @Test fun pendingZeroSizeCanRefreshSizeAndGenerationAtPublication() = runBlocking {
        fixture(initialReportedSize = 0L) { app, provider, target, fingerprint, bytes, _ ->
            assertEquals(providerIdentity(target.uri!!, 0L, 9L), fingerprint.providerIdentity)
            assertEquals(bytes.size.toLong(), fingerprint.digest.byteCount)
            provider.pendingAfterUpdate = 0
            provider.refreshMetadataOnUpdate = true
            val published = finalizeOutputTarget(app, target, fingerprint)
            assertEquals(target.id, published.id)
            assertFalse(published.staging)
            assertEquals("clip.wav", published.displayName)
            assertEquals(providerIdentity(target.uri!!, bytes.size.toLong(), 10L), published.publishedIdentity)
            assertEquals(0, provider.pending)
            assertEquals(1, provider.updates.size)
            assertArrayEquals(bytes, provider.payload.readBytes())
        }
    }

    // Regression candidate: the original single observation returns null when openFile changes
    // the revision, then throws. Retrying a complete observation must accept only the stable read.
    @Test fun firstUnstablePublicationObservationThenStableReadUsesOneUpdate() = runBlocking {
        fixture(initialReportedSize = 0L) { app, provider, target, fingerprint, bytes, _ ->
            provider.pendingAfterUpdate = 0
            provider.unstablePublishedOpensRemaining = 1
            val published = finalizeOutputTarget(app, target, fingerprint)
            assertEquals(0, provider.unstablePublishedOpensRemaining)
            assertEquals("one rejected read and one stable read", 2, provider.publishedOpens)
            assertEquals(providerIdentity(target.uri!!, bytes.size.toLong(), 10L), published.publishedIdentity)
            assertEquals(target.id, published.id)
            assertFalse(published.staging)
            assertEquals(1, provider.updates.size)
            assertArrayEquals(bytes, provider.payload.readBytes())
        }
    }

    @Test(timeout = 15_000) fun persistentlyUnstablePublicationThenStableRecoveryKeepsSameUriWithoutInsert() = runBlocking {
        fixture(initialReportedSize = 0L) { app, provider, target, fingerprint, bytes, dao ->
            val markers = getRecorderPreferences(app).getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!.toSet()
            provider.pendingAfterUpdate = 0
            provider.churnPublishedRevision = true
            var publishing: RecordingOutputTarget? = null
            publicationFailure {
                finalizeOutputTarget(app, target, fingerprint, beforePublish = { publishing = it })
            }
            assertEquals("unstable publication has a bounded read budget", 3, provider.publishedOpens)
            assertEquals(1, provider.updates.size)
            assertEquals(markers, getRecorderPreferences(app).getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet()))
            assertNull(dao.findById(target.id))
            assertArrayEquals(bytes, provider.payload.readBytes())

            provider.churnPublishedRevision = false
            provider.unstablePublishedOpensRemaining = 1
            val opensBeforeRecovery = provider.publishedOpens
            val expectedIdentity = providerIdentity(target.uri!!, bytes.size.toLong(), provider.generation + 1L)
            // Exercise the real PUBLISHING-stage recovery entry point twice, not a fresh save.
            // This covers target re-observation, not coordinator journal reload or Service delivery.
            repeat(2) {
                val recovered = checkNotNull(recoverDurableSavePublication(app, checkNotNull(publishing), fingerprint))
                assertEquals(target.id, recovered.id)
                assertEquals(target.uri, recovered.uri)
                assertEquals("clip.wav", recovered.displayName)
                assertFalse(recovered.staging)
                assertEquals(expectedIdentity, recovered.publishedIdentity)
            }
            assertEquals(0, provider.unstablePublishedOpensRemaining)
            assertEquals(opensBeforeRecovery + 3, provider.publishedOpens)
            assertEquals(1, provider.updates.size)
            assertEquals(0, provider.inserts)
            assertEquals(0, provider.deletes)
            assertArrayEquals(bytes, provider.payload.readBytes())
        }
    }

    @Test fun recoveredPublicationRemovesOnlyItsMatchingSuppressionWithoutNewOutput() = runBlocking {
        fixture(initialReportedSize = 0L) { app, provider, target, fingerprint, bytes, dao ->
            val preferences = getRecorderPreferences(app)
            val suppressionBefore = preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            provider.pendingAfterUpdate = 0
            provider.refreshMetadataOnUpdate = true
            provider.nameAfterUpdate = "provider-temporary-name.wav"
            var publishing: RecordingOutputTarget? = null
            publicationFailure {
                finalizeOutputTarget(app, target, fingerprint, beforePublish = { publishing = it })
            }
            val suppressed = preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet())!!.toSet()
            val added = suppressed - suppressionBefore
            assertEquals(1, added.size)
            val record = checkNotNull(decodePendingOutputCleanupRecord(added.single()))
            assertEquals(target.id, record.id)
            assertEquals(RecordingStorageType.MEDIASTORE, record.storageType)
            assertEquals(bytes.size.toLong(), record.byteCount)
            assertNull("uncertain publication grants no delete identity", record.providerIdentity)
            assertTrue(pendingOutputCleanupIds(app).contains(target.id))
            assertTrue(preferences.getStringSet(PrefKey.VERIFIED_EXPORT_STAGING.name, emptySet())!!
                .none { decodeVerifiedExportStagingRecord(it)?.id == target.id })
            assertNull(verifiedExportStagingRecovery(app, target.storageType, target.id))
            assertNull(dao.findById(target.id))

            // Simulate the provider subsequently exposing the requested name. The original
            // accepted job retains its fingerprint and exact URI even after marker revocation.
            provider.name = "clip.wav"
            provider.generation++
            repeat(2) { attempt ->
                val recovered = checkNotNull(recoverDurableSavePublication(app, checkNotNull(publishing), fingerprint))
                assertEquals(target.id, recovered.id)
                assertEquals(target.uri, recovered.uri)
                assertFalse(recovered.staging)
                assertEquals(providerIdentity(target.uri!!, bytes.size.toLong(), provider.generation), recovered.publishedIdentity)
                if (attempt == 0) assertEquals(suppressed,
                    preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
                // Helper-level integration only: recovery observes publication; suppression is
                // retired later under mutation ownership, after cataloging and a fresh full read.
                // This does not run DurableSaveCoordinator.materialize or its journal/pin retirement.
                val mutation = checkNotNull(recordingMutations.tryBegin(recovered.id))
                try {
                    val entity = RecordingEntity(recovered.id, recovered.displayName, "audio/wav",
                        1L, 1L, bytes.size.toLong(), "WAV", RecordingStorageType.MEDIASTORE,
                        MEDIA_STORE_DIRECTORY_ID, recovered.publishedIdentity)
                    val registered = RecordingRepository.registerFromAcceptedOperation(app, entity)
                    assertEquals(registered, dao.findById(recovered.id))
                    val current = checkNotNull(readStableOutputFingerprint(app, recovered.storageType,
                        recovered.id, recovered.publishedIdentity, reuseVerifiedProviderRevision = false))
                    assertEquals(recovered.publishedIdentity, current.providerIdentity)
                    assertEquals(bytes.size.toLong(), current.digest.byteCount)
                    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), current.digest.sha256)
                    assertTrue(stableOutputFingerprintMatches(recovered.storageType,
                        outputCleanupFingerprintForTarget(recovered, fingerprint), current))
                    assertTrue(removePublishedMediaStoreSaveSuppression(app, recovered, current))
                } finally {
                    recordingMutations.finish(mutation)
                }
                assertEquals(suppressionBefore, preferences.getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP.name, emptySet()))
                assertFalse(pendingOutputCleanupIds(app).contains(target.id))
            }
            assertEquals(1, provider.updates.size)
            assertEquals(0, provider.inserts)
            assertEquals(0, provider.deletes)
            assertArrayEquals(bytes, provider.payload.readBytes())
        }
    }

    @Test fun publicationRecoveryRejectsChangedBytes() = rejectedPublicationRecovery("bytes")
    @Test fun publicationRecoveryRejectsChangedName() = rejectedPublicationRecovery("name")
    @Test fun publicationRecoveryRejectsDifferentRowWithSameBytes() = rejectedPublicationRecovery("row")

    private fun rejectedPublicationRecovery(change: String) = runBlocking {
        fixture(initialReportedSize = 0L) { app, provider, target, fingerprint, bytes, dao ->
            provider.pendingAfterUpdate = 0
            provider.refreshMetadataOnUpdate = true
            finalizeOutputTarget(app, target, fingerprint)
            var recoveryTarget = target
            when (change) {
                "bytes" -> provider.payload.writeBytes(bytes.clone().also { it[44] = 42 })
                "name" -> provider.name = "other.wav"
                "row" -> {
                    provider.alternateRowAvailable = true
                    val other = Uri.parse("content://media/external/audio/media/730033")
                    recoveryTarget = target.copy(id = other.toString(), uri = other)
                }
                else -> error("Unknown fixture change")
            }
            val retainedBytes = provider.payload.readBytes()
            val retainedName = provider.name
            repeat(2) { publicationFailure { recoverDurableSavePublication(app, recoveryTarget, fingerprint) } }
            assertEquals(retainedName, provider.name)
            assertArrayEquals(retainedBytes, provider.payload.readBytes())
            assertNull(dao.findById(target.id))
            assertNull(dao.findById(recoveryTarget.id))
            assertEquals(1, provider.updates.size)
            assertEquals(0, provider.inserts)
            assertEquals(0, provider.deletes)
        }
    }

    private fun publicationFailure(action: () -> Any?): IOException {
        try { action() } catch (failure: IOException) { return failure }
        throw AssertionError("Unproven publication was accepted")
    }

    private fun providerIdentity(uri: Uri, size: Long, generation: Long): String =
        "provider:3:" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(uri.toString().toByteArray(Charsets.UTF_8)) + ":$size:$generation"

    private suspend fun fixture(expectedDeletes: Int = 0, initialReportedSize: Long? = null,
        body: suspend (Application, PublicationMediaProvider, RecordingOutputTarget,
        StableOutputFingerprint, ByteArray, RecordingDao) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "publication-boundary-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val bytes = wav()
        val payload = File(directory, "owned.wav").apply { writeBytes(bytes) }
        val sentinel = File(directory, "unrelated.bin").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
        val sentinelBytes = sentinel.readBytes()
        val provider = PublicationMediaProvider(payload).apply { reportedSize = initialReportedSize }
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
            val sentinelSize = 48L
            val sentinelRow = RecordingEntity(sentinelId, "sentinel.wav", "audio/wav", 1L, 1L, sentinelSize, "sentinel",
                RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID,
                providerIdentity(Uri.parse(sentinelId), sentinelSize, provider.generation))
            dao.upsert(sentinelRow)
            provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            PlaybackSessionOsShadow.targetPath = payload.absolutePath
            val identity = providerIdentity(uri, initialReportedSize ?: bytes.size.toLong(), 9L)
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
    var ownerPackage: String? = null
    var name = ".reverb-export-publication.wav"
    var pending: Int? = 1
    var pendingAfterUpdate: Int? = 1
    var throwAfterCommit = false
    var failureBeforeUpdateCommit: RuntimeException? = null
    var unavailableAfterUpdateFailure = false
    var didThrow = false
    var updated = false
    var applyUpdate = true
    var updateResult = 1
    var expectedNameQuery = "clip.wav"
    var generation = 9L
    var reportedSize: Long? = null
    var refreshMetadataOnUpdate = false
    var nameAfterUpdate: String? = null
    var unstablePublishedOpensRemaining = 0
    var churnPublishedRevision = false
    var publishedOpens = 0
    var alternateRowAvailable = false
    @Volatile var beforeReadOpen: ((Uri) -> Unit)? = null
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
        if (uri.lastPathSegment == "730031" || (alternateRowAvailable && uri.lastPathSegment == "730033")) {
            if (missing) { trace += "query:missing"; cursors += cursor; return cursor }
            val values: Map<String, Any?> = mapOf(
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME to ownerPackage,
                MediaStore.MediaColumns._ID to uri.lastPathSegment!!.toLong(),
                MediaStore.MediaColumns.DISPLAY_NAME to name,
                MediaStore.MediaColumns.SIZE to (reportedSize ?: payload.length()),
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
        check((uri.lastPathSegment == "730031" || (alternateRowAvailable && uri.lastPathSegment == "730033")) && mode == "r")
        beforeReadOpen?.invoke(uri)
        trace += "open:$mode"
        if (updated && pending == 0) {
            publishedOpens++
            if (churnPublishedRevision || unstablePublishedOpensRemaining > 0) {
                if (unstablePublishedOpensRemaining > 0) unstablePublishedOpensRemaining--
                reportedSize = payload.length()
                generation++
                trace += "refresh:$reportedSize:$generation"
            }
        }
        return ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY).also {
            descriptors += it to it.fileDescriptor
        }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        check(uri.lastPathSegment == "730031")
        trace += "update"
        updates += ContentValues(requireNotNull(values))
        failureBeforeUpdateCommit?.let { failure ->
            didThrow = true
            queryUnavailable = unavailableAfterUpdateFailure
            throw failure
        }
        if (applyUpdate) {
            name = nameAfterUpdate ?: checkNotNull(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            pending = pendingAfterUpdate
            if (refreshMetadataOnUpdate) { reportedSize = payload.length(); generation++ }
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
    var writeRequestUris = emptyList<String>()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "create_write_request") return super.call(method, arg, extras)
        val clip = checkNotNull(extras?.getParcelable("clip_data", ClipData::class.java))
        writeRequestUris = (0 until clip.itemCount).map { clip.getItemAt(it).uri.toString() }
        val sender = PendingIntent.getActivity(checkNotNull(context), 41, Intent("reverb.test.WRITE_REQUEST"),
            PendingIntent.FLAG_IMMUTABLE)
        return Bundle().apply { putParcelable("result", sender) }
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
