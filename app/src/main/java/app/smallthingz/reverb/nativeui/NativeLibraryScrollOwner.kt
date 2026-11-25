/*
 * Source-derived from foundation 1.11.2 LazyListState/Scrollable/DragGestureNode.
 * AndroidX portions copyright The Android Open Source Project, Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.reverb.ReverbLibraryLayout

import smallthingz.ui.scroll.NativeVerticalFling
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalOverscroll
import smallthingz.ui.scroll.NativeVerticalScrollSource

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import smallthingz.ui.animation.splineBasedDecay
import smallthingz.ui.foundation.MutatePriority
import smallthingz.ui.foundation.MutatorMutex
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Offset
import smallthingz.ui.unit.Velocity
import androidx.recyclerview.widget.RecyclerView
import smallthingz.ui.input.phase.NativeDragAxis
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativeDragPointerRouter
import smallthingz.ui.input.phase.NativeDragSink
import smallthingz.ui.input.phase.NativePointerCoordinates
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.nativePointerSubset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal typealias ReverbLibraryRecyclerView = smallthingz.ui.scroll.NativeSourceRecyclerView

/**
 * A finite Library adapter: RV only places/measures rows and reports actual consumed integer pixels.
 * The existing source router owns recognition; source MutatorMutex, Float residual and spline
 * sampler own scrolling. No guessed max range, RV OverScroller or second native event dispatcher.
 */
internal class NativeLibraryScrollOwner(
    private val view: ReverbLibraryRecyclerView,
    private val environment: ReverbViewEnvironment,
    private val frames: MonotonicFrameClock,
    private val parentScope: CoroutineScope,
    private val nested: NativeVerticalNestedHost,
    private val parentInterested: (NativeDragPointerChange) -> Boolean,
) : View.OnAttachStateChangeListener, AutoCloseable {
    private val mutex = MutatorMutex()
    private var fraction = NativeLibraryScrollAccumulator()
    private val decay = splineBasedDecay(environment.textDensity)
    private val overscroll = NativeVerticalOverscroll(view.context, environment.textDensity,
        ReverbLibraryLayout.overscrollGlowColor(environment.palette), view::invalidate)
    private var scope: CoroutineScope? = null
    private var channel: Channel<Event>? = null
    private var router: NativeDragPointerRouter? = null
    private var listener: Job? = null
    private var mutating = false
    private var countingConsumption = false
    private var consumedY = 0
    private val consumeIntegerPixels: (Int) -> Int = ::consumeInteger
    private val userInputScroll: (Float) -> Float = { dispatch(it, NativeVerticalScrollSource.USER_INPUT) }
    private val sideEffectScroll: (Float) -> Float = { dispatch(it, NativeVerticalScrollSource.SIDE_EFFECT) }
    private var closed = false
    private val stopScroll: () -> Unit = {
        // A position reset retires the same custom jobs as detachment, even on a retained View.
        onViewDetachedFromWindow(view)
        if (!closed && view.isAttachedToWindow) onViewAttachedToWindow(view)
    }
    private val semanticsScroll: (Float) -> Boolean = { amount ->
        val current=scope
        if(current==null || closed)false else {
            view.sourceOnUserScroll?.invoke()
            current.launch { semanticsScrollBy(amount) }
            true
        }
    }
    private sealed interface Event {
        data object Start : Event
        data class Delta(val value: Offset) : Event
        data class Stop(val velocity: Velocity) : Event
        data object Cancel : Event
    }
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (countingConsumption) consumedY += dy
        }
    }
    private val sink = object : NativeDragSink {
        override fun start(position: Offset) {
            view.sourceOnUserScroll?.invoke()
            ensureListener()
            channel?.trySend(Event.Start)
        }
        override fun delta(physicalDelta: Offset) { channel?.trySend(Event.Delta(physicalDelta)) }
        override fun stop(physicalVelocity: Velocity) { channel?.trySend(Event.Stop(physicalVelocity)) }
        override fun cancel() { channel?.trySend(Event.Cancel) }
    }

    val dragNode: NativeViewPhasePointerNode = object : NativeViewPhasePointerNode {
        override val view: View get() = this@NativeLibraryScrollOwner.view
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) { router?.dispatch(event, pass) }
        override fun cancel() { router?.reset() }
    }
    val observerNode: NativeViewPhasePointerNode = object : NativeViewPhasePointerNode {
        override val view: View get() = this@NativeLibraryScrollOwner.view
        private var event: MotionEvent? = null
        private var coordinates: NativePointerCoordinates? = null
        private var mask = 0
        override fun bindMotion(event: MotionEvent?, coordinates: NativePointerCoordinates?, androidPointerMask: Int) {
            this.event = event; this.coordinates = coordinates; mask = androidPointerMask
        }
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            if (pass != NativePointerPass.INITIAL) return
            // A pending reveal must not move a row beneath an already pressed pointer.
            if (view.isEnabled && event.changes.any { it.pressed && !it.previouslyPressed }) {
                this@NativeLibraryScrollOwner.view.sourceOnUserScroll?.invoke()
            }
            val local = requireNotNull(this.event).nativePointerSubset(mask)
            try {
                local.transform(requireNotNull(coordinates).rootToLocal)
                overscroll.pointer(local)
            } finally { local.recycle() }
        }
        override fun cancel() = Unit
    }
    fun interested(change: NativeDragPointerChange) = router?.interested(change) == true

    init {
        view.sourceOverscroll = overscroll
        view.sourceSemanticsScrollBy = semanticsScroll
        view.sourceStopScroll = stopScroll
        overscroll.measured(view.width, view.height)
        view.addOnScrollListener(scrollListener)
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (closed || scope != null) return
        scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        channel = Channel(Channel.UNLIMITED)
        val configuration = ViewConfiguration.get(view.context)
        router = NativeDragPointerRouter(NativeDragAxis.VERTICAL,
            { configuration.scaledTouchSlop.toFloat() }, { configuration.scaledMaximumFlingVelocity.toFloat() },
            { mutating || overscroll.isInProgress }, sink, parentInterested)
    }
    override fun onViewDetachedFromWindow(view: View) {
        scope?.cancel()
        scope = null
        fraction = NativeLibraryScrollAccumulator()
        channel?.close()
        channel = null
        listener = null
        router?.reset()
        router = null
        overscroll.dispose()
    }
    private fun ensureListener() {
        if (listener != null) return
        val scope = scope ?: return
        val events = channel ?: return
        listener = scope.launch {
            while (isActive) {
                var event = events.receive()
                if (event !is Event.Start) continue
                try {
                    mutate(MutatePriority.UserInput) {
                        while (event !is Event.Stop && event !is Event.Cancel) {
                            (event as? Event.Delta)?.let { scroll(it.value.y, NativeVerticalScrollSource.USER_INPUT) }
                            event = events.receive()
                        }
                    }
                    val velocity = (event as? Event.Stop)?.velocity?.y ?: 0f
                    scope.launch { stopped(velocity) }
                } catch (_: CancellationException) {
                    scope.launch { stopped(0f) }
                }
            }
        }
    }

    private suspend fun <T> mutate(priority: MutatePriority, block: suspend () -> T): T = coroutineScope {
        mutex.mutate(priority) {
            mutating = true
            try { block() } finally { mutating = false }
        }
    }
    private fun consumeInteger(physical: Int): Int {
        check(!countingConsumption)
        consumedY = 0
        countingConsumption = true
        try { view.scrollBy(0, -physical) }
        finally { countingConsumption = false }
        return -consumedY
    }
    private fun dispatch(physical: Float, source: NativeVerticalScrollSource): Float {
        val before = nested.preScroll(physical, source)
        val available = physical - before
        val self = fraction.dispatch(available, view.canScrollVertically(1),
            view.canScrollVertically(-1), consumeIntegerPixels)
        val after = nested.postScroll(self, available - self, source)
        return before + self + after
    }
    private fun scroll(physical: Float, source: NativeVerticalScrollSource): Float =
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)) {
            val performScroll = when (source) {
                NativeVerticalScrollSource.USER_INPUT -> userInputScroll
                NativeVerticalScrollSource.SIDE_EFFECT -> sideEffectScroll
            }
            overscroll.scroll(physical, source, performScroll)
        } else dispatch(physical, source)

    private suspend fun stopped(physicalVelocity: Float) {
        val owner = scope ?: return
        val edge = if (view.canScrollVertically(1) || view.canScrollVertically(-1)) overscroll.beginFling(physicalVelocity) else null
        val initial = edge?.remainingVelocity ?: physicalVelocity
        val before = nested.preFling(initial)
        if (scope !== owner) throw CancellationException("scroll owner retired")
        val available = initial - before
        var logicalLeftover = -available
        mutate(MutatePriority.Default) {
            val fling = NativeVerticalFling(decay,
                consume = { logical ->
                    if (scope !== owner || logical != 0f && !view.isAttachedToWindow) throw CancellationException("scroll node detached")
                    -scroll(-logical, NativeVerticalScrollSource.SIDE_EFFECT)
                }, finished = { logicalLeftover = it })
            fling.start(-available)
            try {
                while (fling.isRunning) frames.withFrameNanos { fling.frame(it) }
            } catch (_: CancellationException) {
                logicalLeftover = fling.remainingVelocity
            } finally { fling.cancel() }
        }
        val leftover = -logicalLeftover
        val after = nested.postFling(available - leftover, leftover)
        if (edge != null) overscroll.finishFling(edge, leftover - after)
    }

    /** Same source semantics path as NativeVerticalScrollOwner, using real RV consumption. */
    private suspend fun semanticsScrollBy(logicalDelta:Float) {
        val owner = scope ?: return
        var consumed=0f
        mutate(MutatePriority.Default) {
            val animation=TargetBasedAnimation(spring<Float>(),Float.VectorConverter,0f,logicalDelta)
            var start:Long?=null
            var finished=false
            while(!finished) {
                val scale=coroutineContext[MotionDurationScale]?.scaleFactor?:1f
                check(scale>=0f)
                frames.withFrameNanos { time ->
                    if (scope !== owner) throw CancellationException("scroll owner retired")
                    val first=start?:time.also{start=it}
                    val play=if(scale==0f)animation.durationNanos else ((time-first)/scale).toLong()
                    consumed+=-dispatch(-(animation.getValueFromNanos(play)-consumed),NativeVerticalScrollSource.USER_INPUT)
                    finished=animation.isFinishedFromNanos(play)
                }
            }
        }
    }

    /** Source PageUp/PageDown changes one captured viewport under UserInput, without edge pull. */
    fun pageKey(pageDown: Boolean, keyDown: Boolean, ctrl: Boolean): Boolean {
        val current = scope ?: return false
        if (!keyDown || ctrl) return false
        view.sourceOnUserScroll?.invoke()
        val amount = if (pageDown) -view.height.toFloat() else view.height.toFloat()
        current.launch { mutate(MutatePriority.UserInput) { dispatch(amount, NativeVerticalScrollSource.USER_INPUT) } }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        view.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(view)
        view.removeOnScrollListener(scrollListener)
        view.sourceOverscroll = null
        if(view.sourceSemanticsScrollBy===semanticsScroll)view.sourceSemanticsScrollBy=null
        if(view.sourceStopScroll===stopScroll)view.sourceStopScroll=null
    }
}
