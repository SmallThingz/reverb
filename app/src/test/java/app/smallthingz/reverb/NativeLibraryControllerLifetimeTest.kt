package app.smallthingz.reverb

import android.app.Application
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.platform.AndroidUiFrameClock
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeLibraryControllerLifetimeTest {
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

    private class Fixture(active: Boolean) : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        var scheduled = 0
        private val parent = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { scheduled++ }
        })
        var onUi: (String) -> Unit = {}
        var publications = 0
        val receipt = CompletableDeferred<RecordingDeletionBatchResult>() // Independent process lifetime.
        val handoffs = mutableListOf<List<RecordingEntity>>()
        val callbacks = NativeLibraryCallbacks(
            onUndoVisibleChange = { onUi("undo") },
            onParentRefreshRequested = { onUi("refresh") },
        )
        private val context = RuntimeEnvironment.getApplication()
        val owner = NativeLibraryController(context, View(context), this, parent,
            AndroidUiFrameClock(Choreographer.getInstance()), null,
            NativeLibraryInput(active, emptyList(), callbacks),
            submitBackgroundDeletion = { _, targets -> handoffs.add(targets.toList()); receipt },
            publish = { publications++ })
        fun field(name: String): Any? = NativeLibraryController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
        override fun close() {
            onUi = {}
            try { owner.close() } finally { parent.cancel(); receipt.cancel() }
        }
    }
}
