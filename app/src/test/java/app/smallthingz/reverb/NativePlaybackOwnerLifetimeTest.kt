package app.smallthingz.reverb

import android.app.Application
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import smallthingz.ui.runtime.MonotonicFrameClock

/** Actual orchestration owners; cached source/no snapshot keeps storage and native audio out of this lane. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativePlaybackOwnerLifetimeTest {
    @Test fun rangeCloseRetiresPendingFramesCollectorsAndLifecycleObserver() {
        val lifecycle = TestLifecycle()
        val dispatcher = OwnerDispatcher()
        val parent = SupervisorJob()
        val frames = HeldFrames()
        val scope = CoroutineScope(parent + dispatcher + frames)
        var renders = 0
        val owner = NativeRangeExportOwner(RuntimeEnvironment.getApplication(), lifecycle,
            ReverbService.BufferSlot.ONE_SHOT, 2f, scope, true, { _, _ -> renders++ })
        val preview = field(owner.state, "previewController") as TimelineAudioPreviewController
        val observerCount = lifecycle.lifecycle.observerCount
        try {
            assertTrue(observerCount > 0)
            owner.bind(null, null, 60.0, 0f, true) { _, _ -> renders++ }
            dispatcher.drain()
            assertTrue("Actual range motion/invalidation must be awaiting its owner frame", frames.pending.get() > 0)
            assertTrue(parent.children.any())
            val beforeClose = renders
            owner.close()
            owner.close()
            dispatcher.drain()
            lifecycle.lifecycle.currentState = Lifecycle.State.CREATED
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
            dispatcher.drain()
            assertEquals(beforeClose, renders)
            assertEquals(0, frames.pending.get())
            assertEquals(0, lifecycle.lifecycle.observerCount)
            assertFalse(parent.children.any())
            assertTrue(parent.isActive)
            awaitPreviewShutdown(preview)
        } finally {
            owner.close()
            scope.cancel()
            dispatcher.drain()
            awaitPreviewShutdown(preview)
        }
    }

    @Test fun inlineSameRevisionReusesWaveformAndReplacementCloseRetiresActualOwners() {
        val app = RuntimeEnvironment.getApplication()
        val lifecycle = TestLifecycle()
        val dispatcher = OwnerDispatcher()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + dispatcher)
        val presentations = mutableListOf<NativeInlinePlayerPresentation>()
        var cachedCallbacks = 0
        var busyCallbacks = 0
        var failClose = false
        val failure = IllegalStateException("fixture busy cleanup failure")
        val callbacks = NativeInlinePlayerCallbacks({}, {}, {}, { cachedCallbacks++ }, {},
            { error("Cached inactive fixture must not open playback") },
            { busyCallbacks++; if (failClose) throw failure })
        val first = cachedRecording("first")
        val input = NativeInlinePlayerInput(first, false, false, null, { false }, callbacks)
        val owner = NativeRecordingInlinePlayerController(app, View(app), lifecycle, scope, input, presentations::add)
        val firstSession = field(owner, "session")
        val firstJob = job(firstSession, "revisionScope")
        val firstPreview = field(firstSession, "fineSeekPreviewController") as TimelineAudioPreviewController
        var replacementPreview: TimelineAudioPreviewController? = null
        val ordinaryState = RecordingPlayback.state.value
        try {
            settle(dispatcher)
            assertTrue(presentations.isNotEmpty())
            assertFalse(presentations.last().waveformLoading)
            val baselineObservers = lifecycle.lifecycle.observerCount
            assertTrue(baselineObservers > 0)
            val firstWaveform = presentations.last().waveform
            repeat(3) { owner.bind(input); settle(dispatcher) }
            assertSame(firstSession, field(owner, "session"))
            assertSame(firstWaveform, presentations.last().waveform)
            assertEquals(0, cachedCallbacks)

            owner.bind(input.copy(recording = cachedRecording("replacement")))
            val replacement = field(owner, "session")
            replacementPreview = field(replacement, "fineSeekPreviewController") as TimelineAudioPreviewController
            assertNotSame(firstSession, replacement)
            settle(dispatcher)
            assertTrue(firstJob.isCompleted && firstJob.isCancelled)
            assertNotSame(firstWaveform, presentations.last().waveform)
            assertEquals("Revision replacement must not accumulate lifecycle observers",
                baselineObservers, lifecycle.lifecycle.observerCount)
            awaitPreviewShutdown(firstPreview)

            val replacementJob = job(replacement, "revisionScope")
            val rowJob = job(owner, "rowScope")
            owner.bind(input.copy(recording = cachedRecording("replacement"))) // Queue a render before disposal.
            val renderedBeforeClose = presentations.size
            val busyBeforeClose = busyCallbacks
            failClose = true
            assertSame(failure, assertThrows(IllegalStateException::class.java) { owner.close() })
            owner.close() // Idempotent even when the first callback threw.
            settle(dispatcher)
            lifecycle.lifecycle.currentState = Lifecycle.State.CREATED
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
            settle(dispatcher)
            assertEquals(renderedBeforeClose, presentations.size)
            assertEquals(busyBeforeClose + 1, busyCallbacks)
            assertTrue(rowJob.isCompleted && replacementJob.isCompleted)
            assertEquals(0, lifecycle.lifecycle.observerCount)
            assertFalse(parent.children.any())
            assertTrue(parent.isActive)
            assertEquals(ordinaryState, RecordingPlayback.state.value)
            awaitPreviewShutdown(checkNotNull(replacementPreview))
        } finally {
            failClose = false
            owner.close()
            scope.cancel()
            settle(dispatcher)
            awaitPreviewShutdown(firstPreview)
            replacementPreview?.let(::awaitPreviewShutdown)
        }
    }

    private fun cachedRecording(identity: String): RecordingEntity {
        val recording = RecordingEntity("/fixture/cached.wav", "cached.wav", "audio/wav", 1L, 2_000L,
            192_044L, "PCM", RecordingStorageType.FILE, "/fixture", identity,
            createdAtMillis = 1L, lastSeenAtMillis = 1L)
        return recording.copy(waveformRevision = recordingWaveformRevision(recording),
            waveformData = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { .4f }))
    }

    private fun field(owner: Any, name: String): Any =
        checkNotNull(owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner))

    private fun job(owner: Any, name: String): Job =
        checkNotNull((field(owner, name) as CoroutineScope).coroutineContext[Job])

    private fun awaitPreviewShutdown(preview: TimelineAudioPreviewController) {
        for (name in listOf("executor", "controlExecutor", "releaseExecutor")) {
            assertTrue("Actual preview $name must terminate after owner close",
                (field(preview, name) as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun settle(dispatcher: OwnerDispatcher) {
        // Only currently due work; never fast-forward MediaPlayer or idle-retirement timers.
        repeat(20) {
            dispatcher.drain()
            ShadowLooper.idleMainLooper()
            if (dispatcher.empty) return
        }
        error("Owner did not settle within twenty dispatch turns")
    }

    private class TestLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private class OwnerDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        val empty: Boolean get() = tasks.isEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    private class HeldFrames : MonotonicFrameClock {
        val pending = AtomicInteger()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
            pending.incrementAndGet()
            continuation.invokeOnCancellation { pending.decrementAndGet() }
        }
    }
}
