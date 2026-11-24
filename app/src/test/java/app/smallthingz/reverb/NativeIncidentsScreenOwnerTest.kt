package app.smallthingz.reverb

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.runtime.MonotonicFrameClock

/** Exercises the shipping owner's private motion through renderer/navigation/back seams. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeIncidentsScreenOwnerTest {
    @Test fun closeDuringEntryAndReopenDuringExitPreserveTheDeliveredPose() = Fixture().use { f ->
        assertEquals(Pose(false, 0f), f.renderer.poses.last())
        f.navigate(true)
        f.clock.frames(6)
        val opening = f.renderer.poses.last().progress
        assertTrue(opening > 0f && opening < 1f)

        f.navigate(false)
        assertEquals(Pose(true, opening), f.renderer.poses.last())
        f.clock.frames(4)
        val closing = f.renderer.poses.last().progress
        assertTrue(closing > 0f && closing < opening)
        f.navigate(true)
        assertEquals(Pose(true, closing), f.renderer.poses.last())
        f.clock.frames(40)
        assertEquals(Pose(true, 1f), f.renderer.poses.last())
        f.assertSettled()

        f.navigate(false)
        f.clock.frames(40)
        assertEquals(Pose(false, 0f), f.renderer.poses.last())
        f.assertSettled()
        // Hidden overlay still has exactly one main-screen history observer.
        assertEquals(1, f.access.revisions.subscriptionCount.value)
        f.navigate(true)
        f.clock.frames(40)
        assertEquals(Pose(true, 1f), f.renderer.poses.last())
        f.assertSettled()
    }

    @Test fun cancelledBackDuringEntryAndInterruptedReturnUseTheActualDeliveredPose() = Fixture().use { f ->
        f.navigate(true)
        f.clock.frames(6)
        val opening = f.renderer.poses.last().progress
        f.startBack()
        assertEquals(opening, f.renderer.poses.last().progress, 0f)
        f.progressBack(0.6f)
        val dragged = f.renderer.poses.last().progress
        assertTrue(dragged > 0f && dragged < opening)
        f.clock.frames(8)
        assertEquals("Entry must no longer advance behind Back", dragged, f.renderer.poses.last().progress, 0f)

        f.dispatcher.dispatchOnBackCancelled()
        assertEquals(dragged, f.renderer.poses.last().progress, 0f)
        f.clock.frames(4)
        val returning = f.renderer.poses.last().progress
        assertTrue(returning > dragged && returning < opening)
        f.startBack()
        assertEquals("New Back starts at interrupted return", returning, f.renderer.poses.last().progress, 0f)
        f.progressBack(0.5f)
        val interrupted = f.renderer.poses.last().progress
        f.clock.frames(12)
        assertEquals(interrupted, f.renderer.poses.last().progress, 0f)
        f.dispatcher.dispatchOnBackCancelled()
        f.clock.frames(60)
        assertEquals(Pose(true, 1f), f.renderer.poses.last())
        assertEquals(0, f.backRequests)
        f.assertSettled()
    }

    @Test fun committedBackDeliversOneTerminalNavigationAndAllowsReopening() = Fixture(true).use { f ->
        f.startBack()
        f.progressBack(0.4f)
        f.dispatcher.onBackPressed()
        assertEquals("Navigation waits for visual completion", 0, f.backRequests)
        f.clock.frames(40)
        assertEquals(1, f.backRequests)
        assertEquals(Pose(false, 0f), f.renderer.poses.last())
        assertFalse(f.dispatcher.hasEnabledCallbacks())
        f.assertSettled()
        f.navigate(true)
        f.clock.frames(40)
        assertEquals(Pose(true, 1f), f.renderer.poses.last())
        assertTrue(f.dispatcher.hasEnabledCallbacks())
        assertEquals(1, f.backRequests)
        f.assertSettled()
    }

    @Test fun closeCancelsReadReturnFramesAndBackRegistrationWithoutLateDelivery() = Fixture(true).use { f ->
        val pendingRead = CompletableDeferred<IncidentHistoryPresentation>()
        f.access.pending = pendingRead
        f.access.revisions.value++
        assertEquals(1, f.access.activeReads)
        f.startBack()
        f.progressBack(0.7f)
        f.dispatcher.dispatchOnBackCancelled()
        f.clock.frames(3)
        assertTrue(f.clock.waiting > 0)
        val poses = f.renderer.poses.toList()
        val histories = f.renderer.histories.toList()
        val alerts = f.alerts.toList()
        val reads = f.access.reads

        f.owner.close()
        f.owner.close()
        assertEquals(0, f.clock.waiting)
        assertEquals(0, f.access.activeReads)
        assertEquals(0, f.access.revisions.subscriptionCount.value)
        assertFalse(f.dispatcher.hasEnabledCallbacks())
        assertFalse("Owner's child job must retire", f.parent.children.any())
        assertTrue("Closing the UI must not cancel its parent", f.parent.isActive)

        pendingRead.complete(prepareIncidentHistory(listOf(RecordingIncident(1_000L))))
        f.access.revisions.value++
        f.clock.frames(60)
        f.owner.bindNavigation(true, true)
        f.owner.requestBack()
        f.dispatcher.onBackPressed()
        assertEquals(poses, f.renderer.poses)
        assertEquals(histories, f.renderer.histories)
        assertEquals(alerts, f.alerts)
        assertEquals(reads, f.access.reads)
        assertEquals(0, f.backRequests)
        assertEquals(1, f.fallbackRequests)
    }

    @Test fun retiredRendererLeaseCannotDisconnectReplacementAndClosedControllerCanBeReowned() = Fixture().use { f ->
        val retired = f.renderer
        val retiredHistory = retired.histories.toList()
        val retiredPoses = retired.poses.toList()
        f.connection.close()
        val replacement = Renderer()
        f.owner.connect(replacement).use {
            f.connection.close() // Stale lease must not detach the replacement renderer.
            val replacementHistories = replacement.histories.size
            f.access.revisions.value++
            assertTrue(replacement.histories.size > replacementHistories)
            f.navigate(true)
            f.clock.frames(40)
            assertEquals(Pose(true, 1f), replacement.poses.last())
            assertEquals(retiredHistory, retired.histories)
            assertEquals(retiredPoses, retired.poses)
            assertEquals(1, f.access.revisions.subscriptionCount.value)
        }
        val replacementCount = replacement.poses.size
        f.navigate(false)
        f.clock.frames(40)
        assertEquals(replacementCount, replacement.poses.size)
        f.owner.close()
        assertEquals(0, f.access.revisions.subscriptionCount.value)

        // Reuse the real controller: close must release its single-observer admission too.
        val next = NativeIncidentsScreenOwner(RuntimeEnvironment.getApplication(), f.scope,
            false, {}, {}, f.owner.history)
        try {
            assertEquals(1, f.access.revisions.subscriptionCount.value)
            assertEquals(IncidentHistoryLoadStatus.READY, next.state.status)
        } finally { next.close() }
        assertEquals(0, f.access.revisions.subscriptionCount.value)
    }

    private data class Pose(val visible: Boolean, val progress: Float)

    private class Renderer : NativeIncidentsRenderer {
        val poses = mutableListOf<Pose>()
        val histories = mutableListOf<IncidentHistoryUiState>()
        override fun historyChanged(state: IncidentHistoryUiState) { histories += state }
        override fun panelChanged(visible: Boolean, openProgress: Float) {
            assertTrue(openProgress.isFinite() && openProgress in 0f..1f)
            poses += Pose(visible, openProgress)
        }
    }

    private class Access : IncidentHistoryAccess {
        override val revisions = MutableStateFlow(0L)
        var pending: CompletableDeferred<IncidentHistoryPresentation>? = null
        var reads = 0
        var activeReads = 0
        override suspend fun readHistory(): IncidentHistoryPresentation {
            reads++
            activeReads++
            try { return pending?.await() ?: prepareIncidentHistory(emptyList()) }
            finally { activeReads-- }
        }
        override fun toggleAcknowledgedInBackground(incident: RecordingIncident) = error("Unexpected mutation")
        override fun deleteInBackground(incidents: List<RecordingIncident>) = error("Unexpected mutation")
    }

    private class Fixture(initiallyOpen: Boolean = false) : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val parent = SupervisorJob()
        val clock = FrameClock()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined + clock)
        val access = Access()
        val alerts = mutableListOf<Boolean>()
        var backRequests = 0
        var fallbackRequests = 0
        val dispatcher = OnBackPressedDispatcher { fallbackRequests++ }
        val owner = NativeIncidentsScreenOwner(RuntimeEnvironment.getApplication(), scope,
            initiallyOpen, ::backRequested, { alerts += it }, IncidentHistoryController(access))
        val renderer = Renderer()
        val connection = owner.connect(renderer)
        private val registration = owner.registerBack(dispatcher, this)
        private fun backRequested() { backRequests++; navigate(false) }
        fun navigate(open: Boolean) = owner.bindNavigation(open, open)
        fun startBack() = dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        fun progressBack(progress: Float) = dispatcher.dispatchOnBackProgressed(
            BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT))
        fun assertSettled() {
            assertEquals("No animation frame waiter after terminal", 0, clock.waiting)
            val count = renderer.poses.size
            clock.frames(20)
            assertEquals("No redundant terminal publications", count, renderer.poses.size)
        }
        override fun close() {
            try { owner.close(); registration.close(); connection.close() }
            finally { scope.cancel(); lifecycle.currentState = Lifecycle.State.DESTROYED }
        }
    }

    /** Broadcast only to current waiters, with cancellation removing the actual queued lease. */
    private class FrameClock : MonotonicFrameClock {
        private val callbacks = linkedSetOf<(Long) -> Unit>()
        private var time = 0L
        val waiting get() = callbacks.size
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
            val callback: (Long) -> Unit = { timestamp ->
                if (continuation.isActive) continuation.resumeWith(runCatching { onFrame(timestamp) })
            }
            callbacks += callback
            continuation.invokeOnCancellation { callbacks -= callback }
        }
        fun frames(count: Int) = repeat(count) {
            time += 16_000_000L
            val current = callbacks.toList()
            callbacks.clear()
            current.forEach { it(time) }
        }
    }
}
