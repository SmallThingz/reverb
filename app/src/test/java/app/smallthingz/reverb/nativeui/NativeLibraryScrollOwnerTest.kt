package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.splineBasedDecay
import smallthingz.ui.animation.core.spring
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeLibraryScrollOwnerTest {
    @Test fun semanticsPrewarmsScaleAndReadsItAgainOnTheFirstFrame() = withOwner { fixture ->
        assertTrue(fixture.view.sourceSemanticsScrollBy!!.invoke(80f))

        fixture.frames.deliver(1_000_000_000L) {
            assertEquals(1, fixture.scale.reads)
            assertTrue(fixture.userDeltas.isEmpty())
            fixture.scale.scaleFactor = 0f
        }

        assertEquals(2, fixture.scale.reads)
        assertEquals(80f, -fixture.userDeltas.sum(), .001f)
        assertFalse(fixture.frames.hasPendingFrame())
    }

    @Test fun semanticsAccumulatesScaledIntervalsAndFinishesWhenScaleBecomesZero() = withOwner { fixture ->
        val spring = TargetBasedAnimation(spring<Float>(), Float.VectorConverter, 0f, 80f)
        assertTrue(fixture.view.sourceSemanticsScrollBy!!.invoke(80f))

        fixture.frames.deliver(1_000_000_000L)
        fixture.frames.deliver(1_016_000_000L)
        assertEquals(spring.getValueFromNanos(8_000_000L), -fixture.userDeltas.sum(), .001f)

        fixture.frames.deliver(1_032_000_000L) { fixture.scale.scaleFactor = .5f }
        assertEquals(spring.getValueFromNanos(40_000_000L), -fixture.userDeltas.sum(), .001f)

        fixture.frames.deliver(1_048_000_000L) { fixture.scale.scaleFactor = 1f }
        assertEquals(spring.getValueFromNanos(56_000_000L), -fixture.userDeltas.sum(), .001f)

        fixture.frames.deliver(1_064_000_000L) { fixture.scale.scaleFactor = 0f }
        assertEquals(spring.getValueFromNanos(Long.MAX_VALUE), -fixture.userDeltas.sum(), .001f)
        assertFalse(fixture.frames.hasPendingFrame())
    }

    @Test fun dragMovesImmediatelyAndFlingUsesScaleAtFrameDelivery() = withOwner { fixture ->
        fixture.scale.scaleFactor = 0f
        fixture.pointer(0L, 240f, 240f, pressed = true, previouslyPressed = false)
        fixture.pointer(10L, 200f, 240f)
        fixture.pointer(20L, 160f, 200f)
        yield()
        assertTrue(fixture.userDeltas.isNotEmpty())
        val deltasAfterSlop = fixture.userDeltas.size

        fixture.pointer(30L, 120f, 160f)
        yield()
        assertEquals(listOf(-40f), fixture.userDeltas.drop(deltasAfterSlop))
        assertFalse(fixture.preFling.isCompleted)

        fixture.scale.scaleFactor = 2f
        fixture.pointer(40L, 120f, 120f, pressed = false)

        val initialVelocity = fixture.preFling.await()
        assertTrue(initialVelocity < -1f)
        assertFalse(fixture.postFling.isCompleted)
        fixture.frames.deliver(1_000_000_000L) {
            assertEquals(1, fixture.scale.reads)
        }
        assertEquals(2, fixture.scale.reads)
        assertFalse(fixture.postFling.isCompleted)
        // The source spline's first table entry is a numerical approximation at time zero.
        val decay = splineBasedDecay(fixture.density)
        val logicalVelocity = -initialVelocity
        val first = decay.getValueFromNanos(0L, 0f, logicalVelocity)
        assertEquals(listOf(-first), fixture.flingDeltas)

        fixture.frames.deliver(1_016_000_000L) { fixture.scale.scaleFactor = 0f }
        val (consumed, remaining) = fixture.postFling.await()
        val target = decay.getTargetValue(0f, logicalVelocity)
        assertEquals(listOf(-first, -(target - first)), fixture.flingDeltas)
        assertEquals(initialVelocity, consumed + remaining, .001f)
        assertFalse(fixture.frames.hasPendingFrame())
    }

    private fun withOwner(block: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(5_000L) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
            try {
                val fixture = Fixture(activity.get(), this)
                try {
                    block(fixture)
                } finally {
                    fixture.owner.close()
                }
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    private class Fixture(activity: Activity, parent: CoroutineScope) {
        val view = ReverbLibraryRecyclerView(activity)
        val density = Density(1f)
        val scale = TestScale(2f)
        val frames = ControlledFrames()
        val userDeltas = mutableListOf<Float>()
        val flingDeltas = mutableListOf<Float>()
        val preFling = CompletableDeferred<Float>()
        val postFling = CompletableDeferred<Pair<Float, Float>>()
        val owner: NativeLibraryScrollOwner

        init {
            activity.setContentView(view)
            assertTrue(view.isAttachedToWindow)
            val environment = ReverbViewEnvironment(
                density, createFontFamilyResolver(activity), ReverbPalette.fallback(false),
            )
            owner = NativeLibraryScrollOwner(
                view, environment, frames, CoroutineScope(parent.coroutineContext + scale),
                object : NativeVerticalNestedHost {
                    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float {
                        when (source) {
                            NativeVerticalScrollSource.USER_INPUT -> userDeltas += availableY
                            NativeVerticalScrollSource.SIDE_EFFECT -> flingDeltas += availableY
                        }
                        return availableY
                    }

                    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f

                    override suspend fun preFling(availableY: Float): Float {
                        assertSame(scale, currentCoroutineContext()[MotionDurationScale])
                        preFling.complete(availableY)
                        return 0f
                    }

                    override suspend fun postFling(consumedY: Float, availableY: Float): Float {
                        assertSame(scale, currentCoroutineContext()[MotionDurationScale])
                        postFling.complete(consumedY to availableY)
                        return 0f
                    }
                },
                parentInterested = { false },
            )
        }

        fun pointer(time: Long, y: Float, previousY: Float, pressed: Boolean = true, previouslyPressed: Boolean = true) {
            val position = Offset(100f, y)
            val change = NativeDragPointerChange(
                1L, time, position, Offset(100f, previousY), pressed, previouslyPressed,
                PointerType.Touch, position,
            )
            val event = NativeDragPointerEvent(listOf(change), Offset.Zero)
            NativePointerPass.entries.forEach { owner.dragNode.dispatch(event, it) }
        }
    }

    private class TestScale(initialScale: Float) : MotionDurationScale {
        var reads = 0
            private set
        override var scaleFactor = initialScale
            get() {
                reads++
                return field
            }
    }

    private class ControlledFrames : MonotonicFrameClock {
        private val requests = Channel<CompletableDeferred<Long>>(Channel.UNLIMITED)

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val time = CompletableDeferred<Long>()
            requests.send(time)
            return onFrame(time.await())
        }

        suspend fun deliver(time: Long, beforeDelivery: () -> Unit = {}) {
            val frame = requests.receive()
            beforeDelivery()
            frame.complete(time)
            yield()
        }

        fun hasPendingFrame() = requests.tryReceive().isSuccess
    }
}
