package app.smallthingz.reverb

import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One retained range owner's pending render. This does not observe global snapshots or run a
 * Composition. It waits on the caller's actual (possibly paused) frame clock. Writes are already
 * complete before request(); only View binding is coalesced. Close retires this consumer only.
 */
internal class NativeRangeUiInvalidation(
    scope: CoroutineScope,
    private val render: () -> Unit,
) : AutoCloseable {
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock]) {
        "Range UI invalidation requires its owning window's frame clock"
    }
    private val closed = AtomicBoolean(false)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val job: Job = scope.launch {
        for (request in requests) {
            frames.withFrameNanos { }
            // Every write received before this bind is represented by the current model.
            // A write triggered by render itself remains pending for the next inherited frame.
            while (requests.tryReceive().isSuccess) { }
            if (!closed.get()) render()
        }
    }

    fun request() {
        if (!closed.get()) requests.trySend(Unit)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requests.close()
        job.cancel()
    }
}
