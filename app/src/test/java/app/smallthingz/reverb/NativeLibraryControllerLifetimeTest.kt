package app.smallthingz.reverb

import android.app.Application
import android.content.Context
import android.os.Handler
import android.view.Choreographer
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.platform.AndroidUiFrameClock
import java.io.DataInputStream
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeLibraryControllerLifetimeTest {
    private val foreign = RecordingEntity("content://media/external/audio/media/1", "one.wav", "audio/wav",
        1L, 1000L, 32044L, "PCM", RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID, "one")
    private val writable = foreign.copy(id = "content://media/external/audio/media/2", fileIdentity = "two")
    private val file = foreign.copy(id = "/synthetic/local.wav", storageType = RecordingStorageType.FILE)

    private fun partition(batch: List<RecordingEntity>): RecordingDeletionPlan {
        val (prompt, undo) = batch.partition { it == foreign }
        return RecordingDeletionPlan(undo, prompt.map {
            RecordingDeletionConsentTarget(it, PendingDeletionIntent(it.id, it.sizeBytes, "01".repeat(32), false))
        })
    }

    @Test fun consentOnlySelectionSkipsUndoAndWritableMediaOffersUndo() {
        for (selected in listOf(foreign, writable)) {
            val prompted = mutableListOf<RecordingEntity>()
            Fixture(true, { _, batch -> partition(batch) }, { targets ->
                prompted += targets.map { it.recording }; CompletableDeferred<Unit>()
            }).use { f ->
                f.owner.toggleSelection(selected)
                f.owner.deleteSelected()
                f.owner.publishInitialPresentation()
                val undo = f.latest?.undo
                if (selected == foreign) {
                    assertEquals(listOf(foreign), prompted)
                    assertNull(undo)
                    assertNull((f.field("deletionJob") as Array<*>)[0])
                } else {
                    assertTrue(prompted.isEmpty())
                    f.owner.undoDelete(checkNotNull(undo).window)
                }
                f.lifecycle.currentState = Lifecycle.State.CREATED
                assertTrue(f.handoffs.isEmpty())
            }
        }
    }

    @Test fun immediatePromptPreservesExistingUndoAndOnlyAddsNewDirectSiblings() {
        for (siblings in listOf(emptyList(), listOf(writable, file.copy(id = "/synthetic/other.wav")))) {
            val gate = CompletableDeferred<Unit>()
            val prompted = mutableListOf<RecordingEntity>()
            Fixture(true, { _, batch -> partition(batch) }, { targets ->
                prompted += targets.map { it.recording }; gate
            }).use { f ->
                f.owner.deleteRecordings(listOf(file))
                f.owner.publishInitialPresentation()
                val earlier = checkNotNull(f.latest?.undo).window
                (siblings + foreign).forEach(f.owner::toggleSelection)
                f.owner.deleteSelected()
                f.owner.deleteRecordings(listOf(foreign))
                (f.field("delivery") as Runnable).run()
                val current = checkNotNull(f.latest?.undo).window
                assertEquals(siblings.isEmpty(), earlier == current)
                assertEquals(listOf(foreign), prompted)
                assertEquals(listOf(file) + siblings, (f.field("pendingDeletions") as Map<*, *>).values.toList())
                assertNull((f.field("deletionJob") as Array<*>)[0])
                f.lifecycle.currentState = Lifecycle.State.CREATED
                f.onDispatch = { it.run() }
                assertTrue(gate.complete(Unit))
                assertTrue(f.handoffs.isEmpty())
                assertNull((f.field("deletionJob") as Array<*>)[0])
                f.lifecycle.currentState = Lifecycle.State.RESUMED
                assertNotNull((f.field("deletionJob") as Array<*>)[0])
                f.owner.undoDelete(current)
                f.owner.close()
                assertTrue(f.handoffs.isEmpty())
            }
        }
    }

    @Test fun stoppedOrDisposedConsentCheckKeepsExactlyOneOwnerForEachAcceptedTarget() {
        for (dispose in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val checked = mutableListOf<List<RecordingEntity>>()
            val prompted = mutableListOf<RecordingEntity>()
            Fixture(true, { _, batch ->
                checked.add(batch)
                release.await()
                partition(batch)
            }, { targets -> prompted += targets.map { it.recording }; CompletableDeferred<Unit>() }).use { f ->
                val selection = mutableListOf(foreign, writable)
                f.owner.deleteRecordings(selection)
                selection[0] = foreign.copy(fileIdentity = "replacement")
                f.owner.deleteRecordings(selection)
                assertEquals(listOf(listOf(foreign, writable)), checked)
                f.lifecycle.currentState = Lifecycle.State.CREATED
                if (dispose) f.owner.close() else f.lifecycle.currentState = Lifecycle.State.STARTED
                assertTrue(f.handoffs.isEmpty())
                assertTrue(prompted.isEmpty())
                f.onDispatch = { it.run() }
                assertTrue(release.complete(Unit))
                f.owner.close()
                assertEquals(listOf(foreign), prompted)
                assertEquals(listOf(listOf(writable)), f.handoffs)
            }
        }
    }

    @Test fun reopenedDirectTargetsStayPendingUntilTheirBackgroundReceiptSettles() {
        val media = writable.copy(fileIdentity = providerRecordingIdentity(
            writable.storageType, writable.id, writable.sizeBytes, revisionToken = 2L))
        assertTrue(sameRecordingActionTarget(media, media))
        val release = CompletableDeferred<Unit>()
        val planned = mutableListOf<List<RecordingEntity>>()
        Fixture(true, { _, batch ->
            planned.add(batch)
            release.await()
            RecordingDeletionPlan(batch, emptyList())
        }).use { f ->
            f.owner.deleteRecordings(listOf(file))
            f.owner.deleteRecordings(listOf(media))
            f.lifecycle.currentState = Lifecycle.State.CREATED
            f.lifecycle.currentState = Lifecycle.State.RESUMED
            f.onDispatch = { it.run() }
            assertTrue(release.complete(Unit))
            val accepted = listOf(file, media)
            assertEquals(listOf(accepted), f.handoffs)
            assertSame(f.receipt, (f.field("backgroundDeletionReceipt") as Array<*>)[0])
            assertEquals(accepted, (f.field("pendingDeletions") as Map<*, *>).values.toList())
            f.owner.deleteRecordings(listOf(media))
            assertEquals(listOf(listOf(media)), planned)
            assertEquals(listOf(accepted), f.handoffs)
            // Both physical failures must release the exact targets for a deliberate retry.
            assertTrue(f.receipt.complete(RecordingDeletionBatchResult(listOf(false, false), false)))
            assertTrue((f.field("pendingDeletions") as Map<*, *>).isEmpty())
            assertTrue((f.field("deletionState") as LibraryDeletionState).acceptsDeletions)
            f.owner.close()
            assertEquals(listOf(accepted), f.handoffs)
        }
    }

    @Test
    @Config(shadows = [JournalHostIoShadow::class])
    fun mixedUndoPublicationCloseOrFailureRetainsExactlyOneDirectOwner() {
        val context: Application = RuntimeEnvironment.getApplication()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = checkNotNull(context.filesDir.parentFile).canonicalFile.toPath()
        try {
            exerciseMixedUndoPublicationFailures(context)
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
        }
    }

    private fun exerciseMixedUndoPublicationFailures(context: Application) {
        val history = File(context.noBackupFilesDir, "recording-incidents.bin")
        fun persistedIncidents() = DataInputStream(history.inputStream().buffered()).use { input ->
            readIncidentPayloadExact(input, "Library Undo incident history", RecordingIncidentStore::readHistoryPayload)
        }.filterNot { it.deleted }
        for (callback in listOf("visible", "undo")) {
            for (closeInCallback in listOf(false, true)) {
                val failure = java.io.IOException("mixed Undo publication $callback close=$closeInCallback")
                val description = unexpectedErrorIncidentDescription("Library Undo publication", failure)
                val revisionBefore = RecordingIncidentStore.historyRevision.value
                val syncsBefore = JournalHostIoShadow.directorySyncAttempts
                val gate = CompletableDeferred<Unit>()
                val prompted = mutableListOf<RecordingEntity>()
                val priorFeedback = AppFeedbackCenter.events.value.map { it.id }.toSet()
                Fixture(true, { _, batch -> partition(batch) }, { targets ->
                    prompted += targets.map { it.recording }
                    gate
                }).use { f ->
                    try {
                        f.lifecycle.currentState = Lifecycle.State.RESUMED
                        f.onUi = { event ->
                            if (event == callback) {
                                f.onUi = {}
                                assertEquals(listOf(writable),
                                    (f.field("pendingDeletions") as Map<*, *>).values.toList())
                                if (closeInCallback) f.owner.close()
                                throw failure
                            }
                        }
                        f.owner.deleteRecordings(listOf(foreign, writable))
                        assertEquals(listOf(foreign), prompted)
                        if (closeInCallback) {
                            assertEquals(listOf(listOf(writable)), f.handoffs)
                        } else {
                            assertTrue(f.handoffs.isEmpty())
                            f.owner.publishInitialPresentation()
                            assertFalse(checkNotNull(f.latest).acceptsDeletions)
                        }
                        f.onDispatch = { it.run() }
                        assertTrue(gate.complete(Unit))
                        if (!closeInCallback) {
                            (f.field("delivery") as Runnable).run()
                            assertTrue(checkNotNull(f.latest).acceptsDeletions)
                            assertTrue(((f.field("deletionJob") as Array<*>)[0] as Job).isActive)
                            f.owner.close()
                        }
                        assertEquals(listOf(listOf(writable)), f.handoffs)
                        assertFalse(gate.isCancelled)
                        runBlocking {
                            withTimeout(5_000L) {
                                RecordingIncidentStore.historyRevision.first {
                                    it > revisionBefore && persistedIncidents().any { incident -> incident.description == description }
                                }
                            }
                        }
                        val matches = persistedIncidents().filter { it.description == description }
                        assertEquals(1, matches.size)
                        assertEquals(RecordingIncidentKind.UNEXPECTED_ERROR, matches.single().kind)
                        assertTrue(JournalHostIoShadow.directorySyncAttempts > syncsBefore)
                    } finally {
                        AppFeedbackCenter.events.value.filterNot { it.id in priorFeedback }
                            .forEach { AppFeedbackCenter.acknowledge(it.id) }
                    }
                }
            }
        }
    }

    @Test fun failedPermissionPlanningRestoresEarlierUndoOrHandsItOffOnceAfterClose() {
        for (dispose in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            Fixture(true, { _, _ ->
                release.await()
                throw java.io.IOException("permission check unavailable")
            }, { error("Failed planning cannot request platform consent") }).use { f ->
                f.owner.deleteRecordings(listOf(file))
                f.owner.publishInitialPresentation()
                val window = checkNotNull(f.latest?.undo).window
                val originalJob = (f.field("deletionJob") as Array<*>)[0] as Job
                f.owner.deleteRecordings(listOf(writable))
                assertTrue(originalJob.isCancelled)
                if (dispose) f.owner.close()
                val priorFeedback = AppFeedbackCenter.events.value.map { it.id }.toSet()
                try {
                    f.onDispatch = { it.run() }
                    assertTrue(release.complete(Unit))
                    if (dispose) {
                        assertEquals(listOf(listOf(file)), f.handoffs)
                    } else {
                        val deadline = (f.field("deletionJob") as Array<*>)[0] as Job
                        assertTrue(deadline.isActive)
                        assertNotSame(originalJob, deadline)
                        f.owner.undoDelete(window)
                        assertTrue(deadline.isCancelled)
                        assertTrue((f.field("pendingDeletions") as Map<*, *>).isEmpty())
                        f.owner.close()
                        assertTrue(f.handoffs.isEmpty())
                    }
                } finally {
                    AppFeedbackCenter.events.value.filterNot { it.id in priorFeedback }
                        .forEach { AppFeedbackCenter.acknowledge(it.id) }
                }
            }
        }
    }

    @Test fun stopHandsOffAcceptedTargetsDespiteUndoRendererFailureAndReceiptSurvivesClose() = Fixture(true).use { f ->
        val target = RecordingEntity("/synthetic/accepted.wav", "Accepted", "audio/wav", 1L,
            1000L, 32044L, "PCM", RecordingStorageType.FILE, "/synthetic")
        f.owner.deleteRecordings(listOf(target)) // Actual Undo-window acceptance, no physical IO.
        val undoJob = (f.field("deletionJob") as Array<*>)[0] as Job
        val failure = IllegalStateException("Undo renderer on stop")
        f.onUi = { if (it == "undo") throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            f.lifecycle.currentState = Lifecycle.State.CREATED
        })
        assertEquals(listOf(listOf(target)), f.handoffs)
        assertTrue(undoJob.isCancelled)
        assertTrue((f.field("deletionState") as LibraryDeletionState).committedInBackground)
        assertSame(f.receipt, (f.field("backgroundDeletionReceipt") as Array<*>)[0])
        assertFalse(f.receipt.isCancelled)
        f.onUi = {}
        f.owner.close()
        assertFalse(f.receipt.isCancelled)
        assertEquals(1, f.handoffs.size)
        assertTrue(f.receipt.complete(RecordingDeletionBatchResult(listOf(true), false)))
        assertTrue(f.receipt.isCompleted)
        assertEquals(0, f.lifecycle.observerCount)
    }

    @Test fun bindCannotInstallSuccessorsAfterCleanupCallbacksReentrantlyCloseOwner() {
        for (stage in listOf("undo", "refresh", "new-undo", "playback-finished")) {
            Fixture(stage != "new-undo").use { f ->
                val initialEffect = f.field("initialEffect")
                val activeEffect = f.field("activeEffect")
                var scheduledAtClose = -1
                val close = {
                    f.onUi = {}
                    f.owner.close()
                    scheduledAtClose = f.scheduled
                }
                if (stage == "playback-finished") {
                    NativeLibraryController::class.java.getDeclaredField("playbackRequestFinished")
                        .apply { isAccessible = true }.set(f.owner, { _: RecordingEntity? -> close() })
                } else f.onUi = { name -> if (name == if (stage == "new-undo") "undo" else stage) close() }
                f.owner.bind(NativeLibraryInput(stage == "new-undo", emptyList(), f.callbacks))
                assertTrue("Callback must actually close: $stage", scheduledAtClose >= 0)
                assertEquals(scheduledAtClose, f.scheduled)
                assertSame(initialEffect, f.field("initialEffect"))
                assertSame(activeEffect, f.field("activeEffect"))
                assertNull(f.field("installedLifecycle"))
                assertEquals(0, f.lifecycle.observerCount)
                assertTrue(checkNotNull((f.field("scope") as CoroutineScope).coroutineContext[Job]).isCancelled)
                assertFalse((f.field("main") as Handler).hasCallbacks(f.field("delivery") as Runnable))
                (f.field("delivery") as Runnable).run()
                assertEquals(0, f.publications)
            }
        }
    }

    @Test fun throwingUiCleanupStillRetiresActualControllerScopeCallbacksAndObservers() {
        for (active in listOf(false, true)) {
            val lifecycleOwner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            val parent = CoroutineScope(object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
            }) // No repository work; no pending physical mutations in this lifetime test.
            val primary = IllegalStateException("Undo renderer")
            val refreshFailure = IllegalStateException("Refresh renderer")
            val selectionFailure = IllegalStateException("Selection renderer")
            var closing = false
            val calls = mutableListOf<String>()
            val context = RuntimeEnvironment.getApplication()
            val owner = NativeLibraryController(context, View(context), lifecycleOwner, parent,
                AndroidUiFrameClock(Choreographer.getInstance()), null,
                NativeLibraryInput(active, emptyList(), NativeLibraryCallbacks(
                    onUndoVisibleChange = { if (closing) { calls.add("undo"); throw primary } },
                    onParentRefreshRequested = { calls.add("refresh"); throw refreshFailure },
                    onSelectionActiveChange = { calls.add("selection"); throw selectionFailure },
                    onExpandedRecordingActiveChange = { calls.add("expanded") },
                )), publish = { error("Closed owner must not publish") })
            fun field(name: String) = NativeLibraryController::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(owner)
            val scope = field("scope") as CoroutineScope
            val main = field("main") as Handler
            val delivery = field("delivery") as Runnable
            try {
                assertTrue(checkNotNull(scope.coroutineContext[Job]).isActive)
                assertTrue(main.hasCallbacks(delivery))
                closing = true
                assertSame(primary, assertThrows(IllegalStateException::class.java) { owner.close() })
                assertEquals(if (active) listOf(refreshFailure, selectionFailure) else listOf(selectionFailure),
                    primary.suppressed.toList())
                assertEquals(if (active) listOf("undo", "refresh", "undo", "selection", "expanded")
                    else listOf("undo", "selection", "expanded"), calls)
                assertTrue(checkNotNull(scope.coroutineContext[Job]).isCancelled)
                assertEquals(0, lifecycleOwner.lifecycle.observerCount)
                assertFalse(main.hasCallbacks(delivery))
                assertEquals(false, field("deliveryPosted"))
                val completed = calls.toList()
                owner.close()
                delivery.run() // A previously captured queue item cannot publish after disposal.
                assertEquals(completed, calls)
            } finally { parent.cancel() }
        }
    }

    private class Fixture(active: Boolean,
        plan: suspend (Context, List<RecordingEntity>) -> RecordingDeletionPlan = RecordingDeletionConsent::planDeletion,
        prompt: (List<RecordingDeletionConsentTarget>) -> kotlinx.coroutines.Deferred<Unit> = RecordingDeletionConsent::enqueueImmediate,
    ) : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        var scheduled = 0
        var onDispatch: (Runnable) -> Unit = {}
        private val parent = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { scheduled++; onDispatch(block) }
        })
        var onUi: (String) -> Unit = {}
        var publications = 0
        var latest: NativeLibraryPresentation? = null
        val receipt = CompletableDeferred<RecordingDeletionBatchResult>() // Independent process lifetime.
        val handoffs = mutableListOf<List<RecordingEntity>>()
        val callbacks = NativeLibraryCallbacks(
            onVisibleRecordingsChanged = { onUi("visible") },
            onUndoVisibleChange = { onUi("undo") },
            onParentRefreshRequested = { onUi("refresh") },
        )
        private val context = RuntimeEnvironment.getApplication()
        val owner = NativeLibraryController(context, View(context), this, parent,
            AndroidUiFrameClock(Choreographer.getInstance()), null,
            NativeLibraryInput(active, emptyList(), callbacks),
            submitBackgroundDeletion = { _, targets -> handoffs.add(targets.toList()); receipt },
            planDeletion = plan, enqueueDeletionPrompt = prompt, publish = { latest = it; publications++ })
        fun field(name: String): Any? = NativeLibraryController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
        override fun close() {
            onUi = {}
            try { owner.close() } finally { parent.cancel(); receipt.cancel() }
        }
    }
}
