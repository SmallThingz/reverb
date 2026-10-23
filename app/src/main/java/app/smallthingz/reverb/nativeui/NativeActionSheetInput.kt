/*
 * Source-derived from Material3 1.4.0 SheetDefaults/AnchoredDraggable and foundation 1.11.2.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.view.View
import android.view.ViewConfiguration
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.unit.Velocity
import app.smallthingz.reverb.NativeDragAxis
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativeDragPointerRouter
import app.smallthingz.reverb.NativeDragSink
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Actual sheet ancestor for caller-owned native verticalScroll bodies; no second scroll owner. */
internal class NativeActionSheetNestedScroll(
    private val state: NativeActionSheetState,
    private val settleToDismiss: (Float) -> Unit,
) : NativeVerticalNestedHost {
    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float =
        if (availableY < 0 && source == NativeVerticalScrollSource.USER_INPUT)
            state.dispatchRawDelta(availableY) else 0f
    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float =
        if (source == NativeVerticalScrollSource.USER_INPUT) state.dispatchRawDelta(availableY) else 0f
    override suspend fun preFling(availableY: Float): Float =
        if (availableY < 0 && state.requireOffset() > checkNotNull(state.anchors).minimum) {
            settleToDismiss(availableY); availableY
        } else 0f
    override suspend fun postFling(consumedY: Float, availableY: Float): Float {
        settleToDismiss(availableY)
        return availableY
    }
}

/** Queue ordering matches DragGestureNode: exit the UserInput mutation before onDragStopped. */
internal class NativeActionSheetDragChannel(
    private val state: NativeActionSheetState,
    scope: CoroutineScope,
    private val settleToDismiss: (Float) -> Unit,
) : NativeDragSink, AutoCloseable {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val nodeScope = CoroutineScope(scope.coroutineContext + job)
    private sealed interface Event {
        data object Start : Event
        data class Delta(val value: Float) : Event
        data class Stop(val velocity: Float) : Event
        data object Cancel : Event
    }
    private val events = Channel<Event>(Channel.UNLIMITED)
    private var listener: Job? = null
    override fun start(position: Offset) {
        if (listener == null) listener = nodeScope.launch {
            while (isActive) {
                var event = events.receive()
                if (event !is Event.Start) continue
                try {
                    state.drag {
                        while (event !is Event.Stop && event !is Event.Cancel) {
                            (event as? Event.Delta)?.let { dragTo(state.newOffsetForDelta(it.value)) }
                            event = events.receive()
                        }
                    }
                    settleToDismiss((event as? Event.Stop)?.velocity ?: 0f)
                } catch (_: CancellationException) {
                    // Source DraggableNode.onDragCancelled also calls onDragStopped(0).
                    if (isActive) settleToDismiss(0f)
                }
            }
        }
        events.trySend(Event.Start)
    }
    override fun delta(physicalDelta: Offset) { events.trySend(Event.Delta(physicalDelta.y)) }
    override fun stop(physicalVelocity: Velocity) { events.trySend(Event.Stop(physicalVelocity.y)) }
    override fun cancel() { events.trySend(Event.Cancel) }
    override fun close() { job.cancel(); listener = null; events.close() }
}

/** Source detectTapGestures(onTap) for the scrim: no ripple, keyboard click node or long press. */
internal class NativeSheetScrimTapNode(
    override val view: View,
    private val density: () -> Float,
    private val action: () -> Unit,
) : NativeViewPhasePointerNode {
    private var waiting = false
    private var ignoreFinalDown = false
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass == NativePointerPass.INITIAL || event.changes.isEmpty()) return
        if (pass == NativePointerPass.MAIN) {
            ignoreFinalDown = false
            if (!waiting) {
                if (event.changes.all { it.changedToDown && !it.consumed }) {
                    event.changes.forEach { it.consume() }
                    waiting = true; ignoreFinalDown = true
                }
                return
            }
            // waitForUpOrCancellation checks all-up before consumed/out-of-bounds.
            if (event.changes.all { it.changedToUp && !it.consumed }) {
                event.changes.forEach { it.consume() }
                waiting = false
                action()
            } else if (event.changes.any { it.consumed || outside(it) }) waiting = false
        } else if (waiting && !ignoreFinalDown && event.changes.any { it.consumed }) waiting = false
    }
    private fun outside(change: NativeDragPointerChange): Boolean {
        val min = 48f * density()
        val x = if (change.type == PointerType.Touch) max(0f, min-view.width)*.5f else 0f
        val y = if (change.type == PointerType.Touch) max(0f, min-view.height)*.5f else 0f
        return change.position.x < -x || change.position.x > view.width+x ||
            change.position.y < -y || change.position.y > view.height+y
    }
    override fun cancel() { waiting = false; ignoreFinalDown = false }
}

/** Explicit real-View hit node; caller supplies released clip and modifier order, never guessed. */
internal class NativeActionSheetHitLayout(
    private val view: View,
    private val geometry: NativeWindowPointerCoordinates,
    private val minimum: () -> Size,
    override val nodes: List<NativeViewPhasePointerNode>,
    private val children: () -> List<NativeViewPointerHitLayout> = { emptyList() },
    private val clip: (Offset) -> Boolean = { true },
) : NativeViewPointerHitLayout {
    override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE
    override val size get() = Size(view.width.toFloat(), view.height.toFloat())
    override val minimumTouchTarget get() = minimum()
    override val childrenInHitOrder get() = children()
    override fun screenToLocal(screen: Offset) = geometry.coordinates(view).local(screen)
    override fun withinLayerBounds(local: Offset) = clip(local)
}

internal class NativeActionSheetDragNode(
    override val view: View,
    private val state: NativeActionSheetState,
    channel: NativeActionSheetDragChannel,
) : NativeViewPhasePointerNode {
    private val configuration = ViewConfiguration.get(view.context)
    private val router = NativeDragPointerRouter(
        NativeDragAxis.VERTICAL,
        { configuration.scaledTouchSlop.toFloat() },
        { configuration.scaledMaximumFlingVelocity.toFloat() },
        { state.animationRunning },
        channel,
    )
    fun interested(change: NativeDragPointerChange) = state.visible && router.interested(change)
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        router.enabled = state.visible
        router.dispatch(event, pass)
    }
    override fun cancel() = router.reset()
}
