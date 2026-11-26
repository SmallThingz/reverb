package app.smallthingz.reverb.nativeui

import android.app.Application
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.InlineTrimResultReceiver
import app.smallthingz.reverb.NativeLibraryCallbacks
import app.smallthingz.reverb.NativeLibraryController
import app.smallthingz.reverb.NativeLibraryInput
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeLibraryPlayerLeaseTest {
    @Test fun retainedLoadingLeaseStopsFramesOnDetachAndScreenInactivity() {
        val context = RuntimeEnvironment.getApplication()
        val root = View(context)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val frames = NativeInlineWaveformMotionTest.ManualFrames()
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(dispatcher)
        val recording = RecordingEntity("/synthetic/lease.wav", "Fixture", "audio/wav", 1L, 1_000L,
            32_044L, "PCM", RecordingStorageType.FILE, "/synthetic")
        val library = NativeLibraryController(context, root, lifecycle, scope, frames, null,
            NativeLibraryInput(false, emptyList(), NativeLibraryCallbacks()), publish = {})
        val lease = NativeLibraryPlayerLease(context, recording.id, ReverbViewEnvironment(context, false),
            library, lifecycle, OnBackPressedDispatcher(), scope, frames, { 1f }, root, {})
        fun bind(active: Boolean = true) = lease.bind(recording, owner = null, expanded = true,
            screenActive = active, trimRequested = false)
        try {
            bind()
            assertNull(lease.currentPresentation)
            assertEquals(0, frames.pendingCount)

            lease.setPresentationAttached(true)
            bind()
            assertTrue(checkNotNull(lease.currentPresentation).waveformLoading)
            dispatcher.startMotion()
            assertEquals(1, frames.pendingCount)
            val controller = checkNotNull(field(lease, "controller"))
            @Suppress("UNCHECKED_CAST")
            val receiverSlot = field(controller, "activeTrimReceiver") as AtomicReference<InlineTrimResultReceiver?>
            var delivered = 0
            val accepted = InlineTrimResultReceiver(onSaved = { delivered++ }, onFailed = { throw it },
                uiVisible = true, onDetachedSuccess = { error("Reattached result must use the visible receiver") },
                onDetachedFailure = { throw it }, onTerminal = { receiverSlot.compareAndSet(it, null) })
            receiverSlot.set(accepted)

            lease.setPresentationAttached(false)
            assertEquals(0, frames.pendingCount)
            bind()
            assertFalse(lease.screenActive)
            assertSame(controller, field(lease, "controller"))
            assertSame(accepted, receiverSlot.get())

            lease.setPresentationAttached(true)
            assertEquals(0, frames.pendingCount)
            bind()
            dispatcher.startMotion()
            assertEquals(1, frames.pendingCount)
            assertSame(controller, field(lease, "controller"))
            assertSame(accepted, receiverSlot.get())
            accepted.terminal(Result.success(recording))
            assertEquals(1, delivered)
            assertNull(receiverSlot.get())

            bind(active = false)
            assertEquals(0, frames.pendingCount)
            bind()
            dispatcher.startMotion()
            assertEquals(1, frames.pendingCount)
            lease.closePresentation()
            assertEquals(0, frames.pendingCount)
        } finally {
            try { lease.closePresentation() } finally {
                try { library.close() } finally { scope.cancel() }
            }
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }

        // Bind queues the decorative job after the initial controller launches. Keep those
        // suspended so the real lease stays loading without starting a decoder or playback.
        fun startMotion() = pending.removeLast().run()
    }
}
