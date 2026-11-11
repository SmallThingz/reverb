package app.smallthingz.reverb

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.NativeLibraryPredictiveBack
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
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.pointer.PointerType
import kotlin.coroutines.CoroutineContext

@RunWith(Parameterized::class)
class NativeMainPanelMotionTest(private val queued: Boolean) {
    @Test fun homeRevealTakesOverOrdinaryCloseAtSlopWithPhysicalDeltaAndRelease() {
        for (settings in listOf(false, true)) Fixture(queued, settings).use { f ->
            f.openRoute(); f.at(0); f.at(220)
            f.closeRoute(); f.at(240); f.at(360)
            assertTrue(f.homeGesturesEnabled)
            assertTrue(f.panel.panelProgress > 0f && f.panel.panelProgress < .4f)
            val downPose = f.panel.panelProgress
            f.drag(0f, down = true)
            f.at(380) // Capture at recognizer acceptance, not at DOWN while the panel still moves.
            val held = f.panel.panelProgress
            assertTrue(held < downPose)
            f.drag(6f)
            assertEquals(held, f.handoffProgress, 1e-6f)
            assertEquals(held + .002f, f.dragProgress, 1e-6f) // 6px minus the existing 4px slop.
            f.at(400)
            assertEquals(held + .002f, f.panel.panelProgress, 1e-6f)
            f.drag(206f); f.at(420); f.drag(206f, up = true)
            assertTrue(f.visible)
            assertFalse(f.homeGesturesEnabled)
            f.at(440); f.at(660)
            assertEquals(1f, f.panel.panelProgress, 0f)
        }
    }

    @Test fun cancelledPartialRevealCanBeRedraggedReversedAndCancelledFromItsCurrentPose() {
        for (settings in listOf(false, true)) Fixture(queued, settings).use { f ->
            f.drag(0f, down = true); f.drag(6f); f.at(0)
            f.drag(56f); f.at(20)
            assertEquals(.052f, f.panel.panelProgress, 1e-6f)
            f.cancelDrag(); f.at(40); f.at(100)
            assertFalse(f.visible)
            assertTrue(f.homeGesturesEnabled)
            f.drag(0f, down = true); f.at(120)
            val held = f.panel.panelProgress
            assertTrue(held > 0f && held < .052f)
            f.drag(6f)
            assertEquals(held, f.handoffProgress, 1e-6f)
            f.at(140)
            assertEquals(held + .002f, f.panel.panelProgress, 1e-6f)
            f.drag(26f); f.at(160)
            assertEquals(held + .022f, f.panel.panelProgress, 1e-6f)
            f.drag(16f); f.at(180)
            assertEquals(held + .012f, f.panel.panelProgress, 1e-6f)
            f.cancelDrag(); f.at(200); f.at(420)
            assertEquals(0f, f.panel.panelProgress, 0f)
            assertFalse(f.visible)
            assertTrue(f.homeGesturesEnabled)
            assertEquals(0, f.pendingFrames)
        }
    }

    @Test fun fullyClosedRevealKeepsZeroDefaultAndClearsPriorBackCompletion() {
        for (settings in listOf(false, true)) Fixture(queued, settings).use { f ->
            f.drag(0f, down = true); f.drag(6f); f.at(0)
            assertEquals(0f, f.handoffProgress, 0f)
            assertEquals(.002f, f.panel.panelProgress, 1e-6f)
            f.drag(6f, up = true)
            assertFalse(f.visible) // Existing 12% commit threshold is unchanged.
            f.at(20); f.at(240)
            f.openRoute(); f.at(260); f.at(480)
            f.callback.handleOnBackPressed(); f.drain(); f.at(500); f.at(720)
            assertEquals(0f, f.panel.panelProgress, 0f)
            f.drag(0f, down = true); f.drag(6f); f.at(740)
            assertEquals(0f, f.handoffProgress, 0f)
            assertEquals(.002f, f.panel.panelProgress, 1e-6f)
            f.drag(206f); f.at(760); f.drag(206f, up = true)
            assertTrue(f.visible)
            f.at(780); f.at(1000)
            assertEquals(1f, f.panel.panelProgress, 0f)
        }
    }

    @Test fun admittedBackDuringOpeningPreservesTheActualLibraryAndSettingsPose() {
        for (settings in listOf(false, true)) Fixture(queued, settings).use { f ->
            f.openPartially()
            val held = f.panel.panelProgress
            assertTrue(f.callback.isEnabled) // Actual navigation admission, before the 220ms reveal ends.
            assertTrue(held > 0f && held < 1f)
            f.callback.handleOnBackStarted(event(0f))
            f.drain()
            assertEquals(held, f.panel.panelProgress, .0001f)
            f.callback.handleOnBackProgressed(event(0f))
            f.drain()
            assertEquals(held, f.panel.panelProgress, .0001f)
            f.callback.handleOnBackProgressed(event(.5f))
            f.drain()
            assertEquals(held * .5f, f.panel.panelProgress, .0001f)
            f.callback.handleOnBackProgressed(event(.2f))
            f.drain()
            assertEquals(held * .8f, f.panel.panelProgress, .0001f)
            f.at(400) // Retired ordinary opening must not publish behind the gesture.
            assertEquals(held * .8f, f.panel.panelProgress, .0001f)
            assertEquals(0, f.commits)
        }
    }

    @Test fun ordinaryAndroidBackDuringOpeningStartsAtTheHeldPoseAndCommitsOnce() {
        for (settings in listOf(false, true)) Fixture(queued, settings).use { f ->
            f.openPartially()
            val held = f.panel.panelProgress
            f.callback.handleOnBackPressed()
            f.drain()
            assertEquals(held, f.panel.panelProgress, .0001f)
            f.at(100)
            f.at(320)
            assertEquals(0f, f.panel.panelProgress, 0f)
            assertEquals(1, f.commits)
            assertFalse(f.visible)
            f.at(600)
            assertEquals(0f, f.panel.panelProgress, 0f)
            assertEquals(0, f.pendingFrames)
        }
    }

    @Test fun repeatedOrdinaryBackRetargetsItsInFlightCommitWithoutOpeningAgain() = Fixture(queued).use { f ->
        f.openPartially()
        f.callback.handleOnBackPressed()
        f.drain()
        f.at(100)
        f.at(120)
        val held = f.panel.panelProgress
        assertTrue(held > 0f && held < 1f)
        f.callback.handleOnBackPressed()
        f.drain()
        assertEquals(held, f.panel.panelProgress, .0001f)
        f.at(140)
        f.at(360)
        assertEquals(0f, f.panel.panelProgress, 0f)
        assertEquals(1, f.commits)
        assertEquals(0, f.pendingFrames)
    }

    @Test fun fullyOpenPanelAlsoAnchorsANonzeroInitialRawEvent() = Fixture(queued).use { f ->
        f.openRoute()
        f.at(0)
        f.at(220)
        assertEquals(1f, f.panel.panelProgress, 0f)
        f.callback.handleOnBackStarted(event(.4f))
        f.callback.handleOnBackProgressed(event(.4f))
        f.drain()
        assertEquals(1f, f.panel.panelProgress, 0f)
        f.callback.handleOnBackProgressed(event(.7f))
        f.drain()
        assertEquals(.5f, f.panel.panelProgress, .0001f)
    }

    @Test fun nonzeroRawStartAnchorsTheActualOpeningPose() = Fixture(queued).use { f ->
        f.openPartially()
        val held = f.panel.panelProgress
        f.callback.handleOnBackStarted(event(.4f))
        f.drain()
        assertEquals(held, f.panel.panelProgress, .0001f)
        f.callback.handleOnBackProgressed(event(.4f))
        f.drain()
        assertEquals(held, f.panel.panelProgress, .0001f)
        f.callback.handleOnBackProgressed(event(.7f))
        f.drain()
        assertEquals(held * .5f, f.panel.panelProgress, .0001f)
        f.callback.handleOnBackProgressed(event(.2f))
        f.drain()
        assertEquals(1f - (1f - held) * .5f, f.panel.panelProgress, .0001f)
    }

    @Test fun cancellationSettlesWithoutResurrectingTheInterruptedOpening() = Fixture(queued).use { f ->
        f.openPartially()
        f.callback.handleOnBackStarted(event(0f))
        f.callback.handleOnBackProgressed(event(.5f))
        f.drain()
        val released = f.panel.panelProgress
        f.callback.handleOnBackCancelled()
        f.drain()
        assertEquals(released, f.panel.panelProgress, 0f)
        f.at(100)
        f.at(260)
        assertEquals(1f, f.panel.panelProgress, 0f)
        f.at(500)
        assertEquals(1f, f.panel.panelProgress, 0f)
        assertEquals(0, f.pendingFrames)
        assertEquals(0, f.commits)
    }

    @Test fun settingsCancellationHandsItsHeldPoseBackToOrdinaryMotion() = Fixture(queued, settings = true).use { f ->
        f.openPartially()
        f.callback.handleOnBackStarted(event(0f))
        f.callback.handleOnBackProgressed(event(.5f))
        f.drain()
        val released = f.panel.panelProgress
        f.callback.handleOnBackCancelled()
        f.drain()
        assertEquals(released, f.panel.panelProgress, 0f)
        f.at(100)
        f.at(320)
        assertEquals(1f, f.panel.panelProgress, 0f)
        assertEquals(0, f.pendingFrames)
    }

    @Test fun cancellationBeforeGestureBodyEntryReleasesTheOrdinaryPoseOwner() = Fixture(queued).use { f ->
        f.openPartially()
        val held = f.panel.panelProgress
        f.callback.handleOnBackStarted(event(0f))
        f.callback.handleOnBackCancelled()
        f.drain()
        assertEquals(held, f.panel.panelProgress, .0001f)
        f.at(100)
        f.at(320)
        assertEquals(1f, f.panel.panelProgress, 0f)
        assertEquals(0, f.pendingFrames)
        assertEquals(0, f.commits)
    }

    @Test fun routeCloseRevokesWaitingBackAndReopenRetargetsFromTheActualPose() = Fixture(queued).use { f ->
        f.openPartially()
        f.callback.handleOnBackStarted(event(0f))
        f.callback.handleOnBackProgressed(event(.3f))
        f.drain()
        val held = f.panel.panelProgress
        f.closeRoute()
        assertFalse(f.callback.isEnabled)
        assertEquals(held, f.panel.panelProgress, .0001f)
        f.callback.handleOnBackProgressed(event(.9f))
        f.drain()
        assertEquals(held, f.panel.panelProgress, .0001f)
        f.at(100)
        f.at(180)
        val closing = f.panel.panelProgress
        assertTrue(closing < held && closing > 0f)
        f.openRoute()
        assertEquals(closing, f.panel.panelProgress, 0f)
        f.at(200)
        f.at(260)
        val reopening = f.panel.panelProgress
        assertTrue(reopening > closing && reopening < 1f)
        f.callback.handleOnBackPressed()
        f.drain()
        assertEquals(reopening, f.panel.panelProgress, 1e-6f)
        f.at(280)
        f.at(500)
        assertEquals(0f, f.panel.panelProgress, 0f)
        assertEquals(1, f.commits)
        assertEquals(0, f.pendingFrames)
    }

    @Test fun zeroScaleAfterTakeoverReachesTheOwnedEndpointWithoutQueuedResurrection() = Fixture(queued).use { f ->
        f.openPartially()
        val held = f.panel.panelProgress
        f.callback.handleOnBackStarted(event(0f))
        f.drain()
        assertEquals(held, f.panel.panelProgress, .0001f)
        f.scale = 0f
        f.callback.handleOnBackPressed()
        f.drain()
        f.at(100)
        assertEquals(0f, f.panel.panelProgress, 0f)
        assertEquals(1, f.commits)
        f.at(320)
        assertEquals(0, f.pendingFrames)
        f.openRoute()
        f.at(400)
        assertEquals(1f, f.panel.panelProgress, 0f)
    }

    private class Fixture(queued: Boolean, private val settings: Boolean = false) : AutoCloseable {
        private val dispatcher = TestDispatcher(queued)
        private val clock = TestFrameClock()
        var scale = 1f
        private val scope = CoroutineScope(dispatcher + Job() + clock + object : MotionDurationScale {
            override val scaleFactor get() = scale
        })
        private val navigation = NativeMainNavigationState()
        var commits = 0
        val panel = NativeMainPanelMotion(false, scope, OnBackPressedDispatcher(), InactiveOwner, {},
            animateBackCancellation = !settings)
        private val back = NativeMainPanelMotion::class.java.getDeclaredField("back").run {
            isAccessible = true; get(panel) as NativeLibraryPredictiveBack
        }
        val callback = NativeLibraryPredictiveBack::class.java.getDeclaredField("callback").run {
            isAccessible = true; get(back) as OnBackPressedCallback
        }
        val visible get() = if (settings) navigation.showSettings else navigation.showLibrary
        val pendingFrames get() = clock.pendingCount
        val homeGesturesEnabled get() = navigation.homeGesturesEnabled
        val dragProgress get() = if (settings) navigation.settingsDragProgress else navigation.libraryDragProgress
        var handoffProgress = Float.NaN
            private set
        private val vertical = NativeHomeVerticalDrag({ 4f }, { point ->
            // Same production call shape: v8 takes2args, successor samples both presented poses at onStart.
            val method = NativeMainNavigationState::class.java.declaredMethods.single { it.name == "beginVerticalReveal" }
            val target = if (method.parameterCount == 2) method.invoke(navigation, point.y, 1000f)
                else method.invoke(navigation, point.y, 1000f,
                    if (settings) panel.panelProgress else 0f, if (settings) 0f else panel.panelProgress)
            assertEquals(if (settings) NativeMainPanel.SETTINGS else NativeMainPanel.LIBRARY, target)
            panel.beginForwardMotion()
            bind()
            handoffProgress = dragProgress
        }, { delta -> navigation.verticalRevealDelta(delta, 1000f); bind() },
            { navigation.endVerticalReveal(); bind() }, { navigation.cancelVerticalReveal(); bind() })
        private var previousScreenY = 0f
        private var pointerTime = 0L
        init { bind(); drain() }
        private fun bind() = panel.bind(visible,
            navigation.dragTarget == if (settings) NativeMainPanel.SETTINGS else NativeMainPanel.LIBRARY, dragProgress,
            if (settings) navigation.settingsBackEnabled else navigation.libraryBackEnabled) {
            commits++
            closeRoute()
        }
        fun openRoute() { if (settings) navigation.openSettings() else navigation.openLibrary(); bind(); drain() }
        fun closeRoute() { if (settings) navigation.closeSettings() else navigation.closeLibrary(); bind(); drain() }
        fun openPartially() { openRoute(); at(0); at(80) }
        fun drag(distance: Float, down: Boolean = false, up: Boolean = false) {
            assertTrue(homeGesturesEnabled)
            val screenY = if (settings) 400f + distance else 600f - distance
            val homeTranslation = if (settings) panel.panelProgress * 1000f else 0f
            val position = Offset(200f, screenY - homeTranslation)
            val previous = Offset(200f, previousScreenY - homeTranslation)
            val event = NativeDragPointerEvent(listOf(NativeDragPointerChange(1L, pointerTime, position,
                previous, !up, !down, PointerType.Touch, Offset(200f, screenY))), Offset.Zero)
            for (pass in NativePointerPass.entries) vertical.dispatch(event, pass)
            previousScreenY = screenY
            pointerTime += 16
            drain()
        }
        fun cancelDrag() { vertical.cancel(); drain() }
        fun drain() = dispatcher.drain()
        fun at(milliseconds: Long) { clock.frame(1_000_000_000L + milliseconds * 1_000_000L); drain() }
        override fun close() { panel.close(); scope.cancel(); drain() }
    }

    private class TestDispatcher(private val queued: Boolean) : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext) = queued
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class TestFrameClock : MonotonicFrameClock {
        private val pending = mutableListOf<(Long) -> Unit>()
        val pendingCount get() = pending.size
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
            val request: (Long) -> Unit = { time ->
                if (continuation.isActive) continuation.resumeWith(runCatching { onFrame(time) })
            }
            pending.add(request)
            continuation.invokeOnCancellation { pending.remove(request) }
        }
        fun frame(time: Long) { val batch = pending.toList(); pending.clear(); batch.forEach { it(time) } }
    }
    private object InactiveOwner : LifecycleOwner {
        override val lifecycle: Lifecycle = object : Lifecycle() {
            override val currentState get() = State.DESTROYED
            override fun addObserver(observer: LifecycleObserver) = Unit
            override fun removeObserver(observer: LifecycleObserver) = Unit
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "queued={0}")
        fun dispatchers(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
        private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)
    }
}
