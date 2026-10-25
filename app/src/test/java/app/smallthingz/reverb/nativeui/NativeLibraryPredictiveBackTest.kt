package app.smallthingz.reverb.nativeui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlin.coroutines.CoroutineContext

@RunWith(Parameterized::class)
class NativeLibraryPredictiveBackTest(private val queued: Boolean) {
    @Test fun cancelledGestureClearsActiveWithoutRequestingRollbackFrames() = scenario { f ->
        f.start(.6f, BackEventCompat.EDGE_RIGHT)
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        assertEquals(.6f, f.last.progress, 0f)
        assertEquals(1f, f.last.direction, 0f)
        assertEquals(0, f.pendingFrames)
        assertEquals(0, f.frameRequests)
        assertFalse(f.back.commitCompleted)
        val publications = f.states.size
        f.at(0)
        f.at(80)
        f.at(160)
        assertEquals(publications, f.states.size)
        assertEquals(0, f.commits)
    }

    @Test fun duplicateCancelAndLateProgressDoNotRepublishOrRequestRollback() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackCancelled()
        f.drain()
        val cancelled = f.last
        val publications = f.states.size
        f.callback.handleOnBackCancelled()
        f.callback.handleOnBackProgressed(event(.9f))
        f.drain()
        assertEquals(cancelled, f.last)
        f.at(160)
        assertFalse(f.last.active)
        assertEquals(.6f, f.last.progress, 0f)
        assertEquals(publications, f.states.size)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun replacementGestureOwnsStateAfterPriorCancellation() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        f.start(.3f, BackEventCompat.EDGE_RIGHT)
        assertTrue(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        assertEquals(-1f, f.last.direction, 0f)
        f.at(160)
        assertTrue(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun ordinaryBackAfterCancellationCommitsOnce() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        f.callback.handleOnBackPressed()
        f.drain()
        f.at(100)
        f.at(320)
        assertEquals(1, f.commits)
        assertTrue(f.back.commitCompleted)
        assertFalse(f.last.active)
        f.at(500)
        assertEquals(1, f.commits)
    }

    @Test fun disableAfterCancellationResetsWithoutCommitting() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackCancelled()
        f.drain()
        f.back.bind(false) { f.commits++ }
        f.drain()
        assertFalse(f.last.active)
        assertEquals(0f, f.last.progress, 0f)
        assertEquals(0, f.frameRequests)
        f.at(160)
        assertFalse(f.last.active)
        assertEquals(0, f.commits)
    }

    @Test fun disposalAfterCancellationPublishesNoLaterFramesOrCommit() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackCancelled()
        f.drain()
        f.back.close()
        val publications = f.states.size
        f.drain()
        f.at(160)
        assertEquals(publications, f.states.size)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun cancellationAtEitherEndpointNeverCommits() {
        for (progress in listOf(0f, 1f)) scenario { f ->
            f.start(progress)
            f.callback.handleOnBackCancelled()
            f.drain()
            assertFalse(f.last.active)
            assertEquals(progress, f.last.progress, 0f)
            assertEquals(0, f.frameRequests)
            f.at(0)
            f.at(160)
            assertEquals(0, f.commits)
            assertFalse(f.back.commitCompleted)
            assertFalse(f.last.active)
            assertEquals(progress, f.last.progress, 0f)
        }
    }

    @Test fun rapidReplacementBeforeCancellationCleanupKeepsItsOwnerAndChannel() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackCancelled()
        f.callback.handleOnBackStarted(event(0f, BackEventCompat.EDGE_RIGHT))
        f.callback.handleOnBackProgressed(event(.3f, BackEventCompat.EDGE_RIGHT))
        f.drain()
        assertTrue(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        assertEquals(-1f, f.last.direction, 0f)
        f.callback.handleOnBackProgressed(event(.4f, BackEventCompat.EDGE_RIGHT))
        f.drain()
        assertEquals(.4f, f.last.progress, 0f)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun cancellationBeforeFirstBodyEntryStillPublishesTerminalCleanup() = scenario { f ->
        f.callback.handleOnBackStarted(event(0f, BackEventCompat.EDGE_RIGHT))
        f.callback.handleOnBackProgressed(event(.8f, BackEventCompat.EDGE_RIGHT))
        val sampled = f.last.progress
        val publications = f.states.size
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        assertEquals(sampled, f.last.progress, 0f)
        assertEquals(1f, f.last.direction, 0f)
        assertEquals(publications + 1, f.states.size)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun replacementCancelledBeforeBodyEntryCannotLeaveTheOldPoseActive() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackStarted(event(0f, BackEventCompat.EDGE_RIGHT))
        f.callback.handleOnBackProgressed(event(.3f, BackEventCompat.EDGE_RIGHT))
        val sampled = f.last.progress
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        assertEquals(sampled, f.last.progress, 0f)
        assertEquals(1f, f.last.direction, 0f)
        assertFalse(f.back.commitCompleted)
        val publications = f.states.size
        f.at(0)
        f.at(160)
        assertEquals(publications, f.states.size)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun cancellationBeforeBodyEntryCanPublishAReentrantReplacement() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackStarted(event(0f))
        f.onFrame = { state ->
            if (!state.active) {
                f.onFrame = null
                f.start(.4f, BackEventCompat.EDGE_RIGHT)
            }
        }
        f.callback.handleOnBackCancelled()
        f.drain()
        assertTrue(f.last.active)
        assertEquals(.4f, f.last.progress, 0f)
        assertEquals(-1f, f.last.direction, 0f)
        f.callback.handleOnBackProgressed(event(.5f, BackEventCompat.EDGE_RIGHT))
        f.drain()
        assertEquals(.5f, f.last.progress, 0f)
        assertEquals(0, f.pendingFrames)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun cancellationCleanupCanSynchronouslyStartAReplacement() = scenario { f ->
        f.start(.6f)
        f.onFrame = { state ->
            if (!state.active) {
                f.onFrame = null
                f.start(.3f, BackEventCompat.EDGE_RIGHT)
            }
        }
        f.callback.handleOnBackCancelled()
        f.drain()
        assertTrue(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        assertEquals(-1f, f.last.direction, 0f)
        f.callback.handleOnBackProgressed(event(.4f, BackEventCompat.EDGE_RIGHT))
        f.drain()
        assertEquals(.4f, f.last.progress, 0f)
        assertEquals(0, f.pendingFrames)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun cancellationCleanupCanSynchronouslyDisableItsCallback() = scenario { f ->
        f.start(.6f)
        f.onFrame = { state ->
            if (!state.active) {
                f.onFrame = null
                f.back.bind(false) { error("Disabled callback must not commit") }
            }
        }
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.callback.isEnabled)
        assertFalse(f.last.active)
        assertEquals(0f, f.last.progress, 0f)
        val publications = f.states.size
        f.callback.handleOnBackStarted(event(0f))
        f.callback.handleOnBackProgressed(event(.8f))
        f.drain()
        f.at(160)
        assertEquals(publications, f.states.size)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun cancellationCleanupCanSynchronouslyCloseItsOwner() = scenario { f ->
        f.start(.6f)
        f.onFrame = { state ->
            if (!state.active) {
                f.onFrame = null
                f.back.close()
            }
        }
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        val publications = f.states.size
        f.callback.handleOnBackProgressed(event(.8f))
        f.at(160)
        assertEquals(publications, f.states.size)
        assertEquals(0, f.pendingFrames)
        assertEquals(0, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun cancellationWhileCommitWaitsForAFrameCannotPublishTerminalOrBack() = scenario { f ->
        f.start(.6f)
        f.callback.handleOnBackPressed()
        f.drain()
        assertEquals(1, f.pendingFrames)
        val requests = f.frameRequests
        f.callback.handleOnBackCancelled()
        f.drain()
        assertFalse(f.last.active)
        assertEquals(.6f, f.last.progress, 0f)
        assertFalse(f.back.commitCompleted)
        assertEquals(0, f.pendingFrames)
        val publications = f.states.size
        f.at(0)
        f.at(220)
        assertEquals(publications, f.states.size)
        assertEquals(requests, f.frameRequests)
        assertEquals(0, f.commits)
    }

    @Test fun initialFrameCanSynchronouslyReplaceItsGesture() = scenario { f ->
        f.onFrame = { state ->
            if (state.active) {
                f.onFrame = null
                f.start(.3f, BackEventCompat.EDGE_RIGHT)
            }
        }
        f.callback.handleOnBackStarted(event(0f))
        f.drain()
        assertTrue(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        assertEquals(-1f, f.last.direction, 0f)
        f.callback.handleOnBackCancelled()
        f.drain()
        f.at(0)
        f.at(160)
        assertFalse(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        assertEquals(0, f.commits)
    }

    @Test fun freshOrdinaryBackDoesNotCloseOrDemoteItsReentrantReplacement() = scenario { f ->
        f.onFrame = { state ->
            if (state.active) {
                f.onFrame = null
                f.start(.3f, BackEventCompat.EDGE_RIGHT)
            }
        }
        f.callback.handleOnBackPressed()
        f.drain()
        f.at(0)
        f.at(220)
        assertEquals("The original Back must not commit its replacement", 0, f.commits)
        assertTrue(f.last.active)
        assertEquals(.3f, f.last.progress, 0f)
        assertEquals(-1f, f.last.direction, 0f)
        // Its own release must retain predictive status and continue from .3, taking 154ms.
        f.callback.handleOnBackPressed()
        f.drain()
        f.at(240)
        f.at(393)
        assertEquals(0, f.commits)
        f.at(394)
        assertEquals(1, f.commits)
        assertTrue(f.back.commitCompleted)
        assertFalse(f.last.active)
    }

    @Test fun initialFrameCanSynchronouslyCloseItsOwner() = scenario { f ->
        f.onFrame = { state -> if (state.active) f.back.close() }
        f.callback.handleOnBackStarted(event(0f))
        f.drain()
        val publications = f.states.size
        f.at(0)
        f.at(220)
        assertEquals(publications, f.states.size)
        assertEquals(0, f.commits)
    }

    @Test fun commitCanSynchronouslyStartAReplacementWithoutOldFinallyClearingIt() = scenario { f ->
        f.back.bind(true) {
            f.commits++
            f.start(.25f, BackEventCompat.EDGE_RIGHT)
        }
        f.start(.7f)
        f.callback.handleOnBackPressed()
        f.drain()
        f.at(0)
        f.at(66)
        assertEquals(1, f.commits)
        assertTrue(f.last.active)
        assertEquals(.25f, f.last.progress, 0f)
        assertEquals(-1f, f.last.direction, 0f)
        assertFalse(f.back.commitCompleted)
        f.callback.handleOnBackCancelled()
        f.drain()
        f.at(100)
        f.at(260)
        assertFalse(f.last.active)
        assertEquals(1, f.commits)
    }

    @Test fun commitCanSynchronouslyDisableItsCallbackAndRetainCompletion() = scenario { f ->
        f.back.bind(true) {
            f.commits++
            f.back.bind(false) { error("Disabled callback must not commit again") }
        }
        f.start(.7f)
        f.callback.handleOnBackPressed()
        f.drain()
        f.at(0)
        f.at(66)
        assertEquals(1, f.commits)
        assertTrue(f.back.commitCompleted)
        assertFalse(f.last.active)
        assertEquals(1f, f.last.progress, 0f)
    }

    private fun scenario(block: (Fixture) -> Unit) {
        Fixture(queued).use(block)
    }

    private data class Frame(val active: Boolean, val progress: Float, val direction: Float)

    private class Fixture(queued: Boolean) : AutoCloseable {
        val dispatcher = TestDispatcher(queued)
        private val scope = CoroutineScope(dispatcher + Job())
        private val clock = TestFrameClock()
        val states = mutableListOf<Frame>()
        var commits = 0
        var onFrame: ((Frame) -> Unit)? = null
        val back = NativeLibraryPredictiveBack(OnBackPressedDispatcher(), InactiveOwner, scope, clock) {
                active, progress, direction ->
            val state = Frame(active, progress, direction)
            states.add(state)
            onFrame?.invoke(state)
        }
        // Exercise the actual production callback/controller without an Android lifecycle or Looper.
        // Dispatcher registration and physical gesture routing require separate device qualification.
        val callback = NativeLibraryPredictiveBack::class.java.getDeclaredField("callback").run {
            isAccessible = true
            get(back) as OnBackPressedCallback
        }
        val last: Frame get() = states.last()
        val pendingFrames: Int get() = clock.pendingCount
        val frameRequests: Int get() = clock.requestCount
        init { back.bind(true) { commits++ }; drain() }
        fun start(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) {
            callback.handleOnBackStarted(event(0f, edge))
            callback.handleOnBackProgressed(event(progress, edge))
            drain()
        }
        fun drain() = dispatcher.drain()
        fun at(milliseconds: Long) { clock.frame(1_000_000_000L + milliseconds * 1_000_000L); drain() }
        override fun close() { back.close(); scope.cancel(); drain() }
    }

    private class TestDispatcher(private val queued: Boolean) : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = queued
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class TestFrameClock : MonotonicFrameClock {
        private val pending = mutableListOf<(Long) -> Unit>()
        val pendingCount: Int get() = pending.size
        var requestCount = 0
            private set
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            suspendCancellableCoroutine { continuation ->
                requestCount++
                val request: (Long) -> Unit = { time ->
                    if (continuation.isActive) continuation.resumeWith(runCatching { onFrame(time) })
                }
                pending.add(request)
                continuation.invokeOnCancellation { pending.remove(request) }
            }
        fun frame(time: Long) {
            val batch = pending.toList()
            pending.clear()
            batch.forEach { it(time) }
        }
    }

    private object InactiveOwner : LifecycleOwner {
        override val lifecycle: Lifecycle = object : Lifecycle() {
            override val currentState: State get() = State.DESTROYED
            override fun addObserver(observer: LifecycleObserver) = Unit
            override fun removeObserver(observer: LifecycleObserver) = Unit
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "queued={0}")
        fun dispatchers(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

        private fun event(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) =
            BackEventCompat(0f, 0f, progress, edge)
    }
}
