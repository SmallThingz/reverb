/*
 * Adapted from AndroidX foundation 1.11.2 DragGestureNode event ownership and ScrollableNode.
 * Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import app.smallthingz.nativeui.foundation.gestures.ScrollScope
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.unit.Velocity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Source-shaped drag event channel: pointer dispatch stays synchronous while one coroutine owns
 * the UserInput mutation. The normal and cancellation terminals both release drag before settling.
 * Required delivery/settling callbacks keep nested-scroll + overscroll in their existing owners.
 */
internal class NativePagerDragChannel(
    private val pager: NativeOnboardingPager,
    scope: CoroutineScope,
    private val deliverDelta: suspend ScrollScope.(Offset) -> Unit,
    private val settle: suspend (Velocity) -> Unit,
    private val interactionStarted: (Offset) -> Unit = {},
    private val interactionStopped: (cancelled: Boolean) -> Unit = {},
) : NativeDragSink, AutoCloseable {
    private val nodeJob = SupervisorJob(scope.coroutineContext[Job])
    private val nodeScope = CoroutineScope(scope.coroutineContext + nodeJob)

    private sealed interface Event {
        data class Start(val position: Offset) : Event
        data class Delta(val value: Offset) : Event
        data class Stop(val velocity: Velocity) : Event
        data object Cancel : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private var listener: Job? = null

    override fun start(position: Offset) {
        if (listener == null) listener = nodeScope.launch {
            while (isActive) {
                var event = events.receive()
                if (event !is Event.Start) continue
                interactionStarted(event.position)
                try {
                    pager.drag {
                        while (event !is Event.Stop && event !is Event.Cancel) {
                            (event as? Event.Delta)?.let { deliverDelta(it.value) }
                            event = events.receive()
                        }
                    }
                    val cancelled = event is Event.Cancel
                    interactionStopped(cancelled)
                    val velocity = (event as? Event.Stop)?.velocity ?: Velocity.Zero
                    // Source ScrollableNode starts settling in its node scope, outside drag.
                    nodeScope.launch { settle(velocity) }
                } catch (_: CancellationException) {
                    interactionStopped(true)
                    nodeScope.launch { settle(Velocity.Zero) }
                }
            }
        }
        events.trySend(Event.Start(position))
    }

    override fun delta(physicalDelta: Offset) { events.trySend(Event.Delta(physicalDelta)) }
    override fun stop(physicalVelocity: Velocity) { events.trySend(Event.Stop(physicalVelocity)) }
    override fun cancel() { events.trySend(Event.Cancel) }

    /** Node disposal cancels drag AND settling before any cleanup can start a new fling. */
    override fun close() {
        nodeJob.cancel()
        listener = null
        events.close()
    }
}

/**
 * Separate Initial-pass observer from LazyLayoutPager.dragDirectionDetector. Recognition/consumption
 * never replaces this full down->all-up distance with the post-slop deltas delivered to scrolling.
 */
internal class NativePagerDirectionObserver(private val pager: NativeOnboardingPager) {
    private var downPosition: Offset? = null

    fun initial(event: NativeDragPointerEvent) {
        if (downPosition == null) {
            if (!event.isDown()) return
            downPosition = event.changes.first().position
            pager.beginPointerGesture()
        } else if (event.changes.isNotEmpty() && event.changes.all { it.changedToUp && !it.consumed }) {
            val displacement = event.changes.first().position - requireNotNull(downPosition)
            pager.endPointerGesture(displacement.x, displacement.y)
            downPosition = null
        }
    }

    fun cancel() { downPosition = null }
}
