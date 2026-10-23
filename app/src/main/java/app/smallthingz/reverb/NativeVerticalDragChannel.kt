/*
 * Source-shaped DragGestureNode queue for the existing vertical mutation owner.
 * Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0.
 */
package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.unit.Velocity
import app.smallthingz.reverb.nativeui.NativeVerticalScrollOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One attached vertical input node. No detector, View scrolling or alternate mutation state. */
internal class NativeVerticalDragChannel(
    private val owner: NativeVerticalScrollOwner,
    scope: CoroutineScope,
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
                try {
                    owner.drag {
                        while (event !is Event.Stop && event !is Event.Cancel) {
                            (event as? Event.Delta)?.let { scrollBy(it.value.y) }
                            event = events.receive()
                        }
                    }
                    val velocity = (event as? Event.Stop)?.velocity?.y ?: 0f
                    // Released ScrollableNode exits UserInput drag before launching its fling.
                    nodeScope.launch { owner.onScrollStopped(velocity) }
                } catch (_: CancellationException) {
                    nodeScope.launch { owner.onScrollStopped(0f) }
                }
            }
        }
        events.trySend(Event.Start(position))
    }
    override fun delta(physicalDelta: Offset) { events.trySend(Event.Delta(physicalDelta)) }
    override fun stop(physicalVelocity: Velocity) { events.trySend(Event.Stop(physicalVelocity)) }
    override fun cancel() { events.trySend(Event.Cancel) }
    override fun close() {
        nodeJob.cancel()
        listener = null
        events.close()
    }
}
