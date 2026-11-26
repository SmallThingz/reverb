package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.ReverbService.BufferSlot.LOOPING
import app.smallthingz.reverb.ReverbService.BufferSlot.ONE_SHOT
import app.smallthingz.reverb.nativeui.NativeWindowTextAccessibilityOwner
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import smallthingz.ui.reverb.ReverbPalette
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class, shadows = [ServiceStateOsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeCaptureBufferRestorationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stoppedRecorderDestinationDoesNotReplaceBundleRestoredView() = withFixture { fixture ->
        for ((viewed, destination) in listOf(ONE_SHOT to LOOPING, LOOPING to ONE_SHOT)) {
            val saved = fixture.withMain { main, owner ->
                fixture.report(owner, listening = false, active = destination)
                assertEquals(destination, owner.state.value.selectedBuffer)
                owner.actions.selectBuffer(viewed)
                assertEquals(viewed, owner.state.value.selectedBuffer)
                main.saveState().toBundle()
            }

            fixture.withMain(NativeMainScreenSavedState.fromBundle(saved)) { main, owner ->
                assertEquals(viewed, owner.state.value.selectedBuffer)
                assertFalse(owner.state.value.body.serviceStateHydrated)
                fixture.report(owner, listening = false, active = destination)
                assertEquals(viewed, owner.state.value.selectedBuffer)
                assertEquals(viewed.storageCode.toInt(), main.saveState().selectedBufferCode)
            }
        }
    }

    @Test fun firstRunningSnapshotEstablishesBaselineAndLaterStartsAndSwitchesAreFollowed() = withFixture { fixture ->
        fixture.withMain(NativeMainScreenSavedState(selectedBufferCode = ONE_SHOT.storageCode.toInt())) { _, owner ->
            fixture.report(owner, listening = true, active = LOOPING)
            assertEquals(ONE_SHOT, owner.state.value.selectedBuffer)
            fixture.report(owner, listening = true, active = LOOPING)
            assertEquals(ONE_SHOT, owner.state.value.selectedBuffer)
            fixture.report(owner, listening = false, active = LOOPING)
            assertEquals(ONE_SHOT, owner.state.value.selectedBuffer)

            fixture.report(owner, listening = true, active = LOOPING)
            assertEquals(LOOPING, owner.state.value.selectedBuffer)
            fixture.report(owner, listening = true, active = ONE_SHOT)
            assertEquals(ONE_SHOT, owner.state.value.selectedBuffer)
        }
    }

    @Test fun invalidSavedCodeUsesColdStartupUnlessUserChoosesBeforeDelivery() = withFixture { fixture ->
        fixture.withMain(NativeMainScreenSavedState(selectedBufferCode = -1)) { main, owner ->
            assertNull(main.saveState().selectedBufferCode)
            fixture.report(owner, listening = true, active = LOOPING)
            assertEquals(LOOPING, owner.state.value.selectedBuffer)
        }

        fixture.withMain { main, owner ->
            assertNull(main.saveState().selectedBufferCode)
            fixture.request(owner, listening = true, active = LOOPING)
            assertFalse(owner.state.value.body.serviceStateHydrated)
            owner.actions.selectBuffer(ONE_SHOT) // Explicitly choose the same page as the placeholder.
            assertEquals(ONE_SHOT.storageCode.toInt(), main.saveState().selectedBufferCode)
            fixture.deliver(owner, listening = true, active = LOOPING)
            assertEquals(ONE_SHOT, owner.state.value.selectedBuffer)
        }
    }

    @Test fun periodicStateReadsAreBoundedWhileExplicitRefreshStillRuns() = withFixture { fixture ->
        fixture.withMain { _, owner ->
            repeat(24) { fixture.poll(owner) }
            assertEquals(1, fixture.audioPosts)
            assertNotNull(fixture.pendingPoll(owner))
            // An explicit action refresh must not wait behind the periodic admission gate.
            fixture.request(owner, listening = true, active = LOOPING)
            assertEquals(2, fixture.audioPosts)
            fixture.deliver(owner, listening = true, active = LOOPING)
            assertNull(fixture.pendingPoll(owner))
            fixture.poll(owner)
            assertEquals(3, fixture.audioPosts)
            fixture.deliver(owner, listening = true, active = LOOPING)
            assertNull(fixture.pendingPoll(owner))
        }
    }

    @Test fun awaitingStorageAndUnavailableStateReleasePeriodicAdmission() = withFixture { fixture ->
        fixture.withMain { _, owner ->
            fixture.storagePending(true)
            repeat(2) {
                fixture.poll(owner)
                assertNotNull(fixture.pendingPoll(owner))
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(fixture.pendingPoll(owner))
                assertFalse(owner.state.value.body.serviceStateHydrated)
            }
            fixture.storagePending(false)
            fixture.poll(owner)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(fixture.pendingPoll(owner))
            assertTrue(owner.state.value.body.serviceStateHydrated)
            assertFalse(owner.state.value.body.oneShotEnabled)
            assertFalse(owner.state.value.body.loopingEnabled)
        }
    }

    @Test fun retiredConnectionCallbackCannotReleaseNewPeriodicAdmission() = withFixture { fixture ->
        fixture.withMain { _, owner ->
            fixture.poll(owner)
            val old = checkNotNull(fixture.pendingPoll(owner))
            fixture.advanceConnection(owner)
            fixture.poll(owner)
            val current = checkNotNull(fixture.pendingPoll(owner))
            assertTrue(old !== current)
            assertEquals(2, fixture.audioPosts)
            old.awaitingStorage()
            assertSame(current, fixture.pendingPoll(owner))
            repeat(24) { fixture.poll(owner) }
            assertEquals(2, fixture.audioPosts)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(fixture.pendingPoll(owner))
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = temporary.newFolder()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root.toPath().toAbsolutePath().normalize()
        try {
            PersistentAudioChunkStore(root.resolve("one"), overwriteOldest = false, directorySync = {}).use { one ->
                PersistentAudioChunkStore(root.resolve("loop"), directorySync = {}).use { loop ->
                    one.configure(RetentionMode.SIZE, 4096L, 8_000, 1, PcmSampleFormat.PCM_16)
                    loop.configure(RetentionMode.SIZE, 4096L, 8_000, 1, PcmSampleFormat.PCM_16)
                    Fixture(one, loop).use(block)
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
        }
    }

    private class Fixture(one: PersistentAudioChunkStore, loop: PersistentAudioChunkStore) : AutoCloseable {
        // The actual Service state reader runs without Service startup or microphone work.
        private val service = Robolectric.buildService(ReverbService::class.java).get()
        private val looper = Looper.getMainLooper()
        var audioPosts = 0
            private set
        private val audioHandler = object : Handler(looper) {
            override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
                audioPosts++
                return super.sendMessageAtTime(msg, uptimeMillis)
            }
        }
        private val generation = field<AtomicLong>(service, "listeningCommandGeneration")
        private val requestState = NativeCaptureUiOwner::class.java
            .getDeclaredMethod("requestRecorderState", ReverbService::class.java).apply { isAccessible = true }
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        }
        private val board = FrameLayout(activity.get())
        private val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(board.context),
            ReverbPalette.fallback(false))
        // Only the explicitly requested Service samples run. Park UI collectors and history jobs.
        private val tasks = ArrayDeque<Runnable>()
        private val scope = CoroutineScope(Job() + object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        } + object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = awaitCancellation()
        })

        init {
            setServiceField("audioHandler", audioHandler)
            setServiceField("oneShotAudioChunkStore", one)
            setServiceField("loopingAudioChunkStore", loop)
            setServiceField("captureStoresReady", true)
            setServiceField("configuredRetentionMode", RetentionMode.SIZE)
            activity.get().setContentView(board)
        }

        fun <T> withMain(restored: NativeMainScreenSavedState? = null,
            block: (NativeMainScreenView, NativeCaptureUiOwner) -> T): T {
            val main = NativeMainScreenView.create(board.context, environment, NativeMainScreenInput(true, false),
                lifecycleOwner, OnBackPressedDispatcher(), scope, null, board,
                NativeWindowTextAccessibilityOwner(board, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }),
                restored, {}, {}, { error("Settings is not opened by this fixture") }, {})
            try {
                return main.use {
                    board.addView(main, FrameLayout.LayoutParams(-1, -1))
                    main.start()
                    block(main, field(main, "captureOwner"))
                }
            } finally {
                board.removeView(main)
            }
        }

        fun poll(owner: NativeCaptureUiOwner) {
            NativeCaptureUiOwner::class.java.getDeclaredMethod("requestRecorderState",
                ReverbService::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(owner, service, true)
        }

        fun pendingPoll(owner: NativeCaptureUiOwner): ReverbService.StateCallback? = field(owner, "pendingStatePoll")

        fun storagePending(pending: Boolean) {
            setServiceField("captureStoresReady", false)
            setServiceField("captureInitializationPending", pending)
        }

        fun advanceConnection(owner: NativeCaptureUiOwner) {
            val binding = field<CaptureServiceUiOwner>(owner, "serviceUiOwner")
            CaptureServiceUiOwner::class.java.getDeclaredField("connectionGeneration").apply {
                isAccessible = true
                setLong(binding, binding.connectionGeneration + 1)
            }
        }

        fun request(owner: NativeCaptureUiOwner, listening: Boolean, active: BufferSlot) {
            setServiceField("activeBufferSlot", active)
            setServiceField("state", if (listening) ReverbService.STATE_LISTENING else ReverbService.STATE_READY)
            setServiceField("durableListeningIntentEnabled", listening)
            generation.incrementAndGet()
            requestState.invoke(owner, service)
        }

        fun deliver(owner: NativeCaptureUiOwner, listening: Boolean, active: BufferSlot) {
            shadowOf(looper).idle()
            with(owner.state.value.body) {
                assertTrue(serviceStateHydrated)
                assertTrue(oneShotEnabled)
                assertEquals(RetentionMode.SIZE, retentionMode)
                assertEquals(active, activeBuffer)
                assertEquals(listening, this.listening)
            }
        }

        fun report(owner: NativeCaptureUiOwner, listening: Boolean, active: BufferSlot) {
            request(owner, listening, active)
            deliver(owner, listening, active)
        }

        override fun close() {
            scope.cancel()
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            lifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
            activity.pause().stop().destroy()
        }

        private fun setServiceField(name: String, value: Any) {
            ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
