package app.smallthingz.nativeui.runtime

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** A visibility latch; frame time and callback dispatch remain owned by the wrapped clock. */
class PausableMonotonicFrameClock(private val clock: MonotonicFrameClock) : MonotonicFrameClock {
    private val lock = Any()
    private var paused = false
    private var waiting = ArrayList<CancellableContinuation<Unit>>()
    val isPaused get() = synchronized(lock) { paused }
    fun pause() { synchronized(lock) { paused = true } }
    fun resume() {
        val ready = synchronized(lock) {
            if (!paused) return
            paused = false
            waiting.also { waiting = ArrayList() }
        }
        for (continuation in ready) continuation.resume(Unit)
    }
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        // The normal visible path must not allocate an additional continuation per frame.
        if (!isPaused) return clock.withFrameNanos(onFrame)
        suspendCancellableCoroutine<Unit> { continuation ->
            val immediate = synchronized(lock) {
                if (paused) { waiting.add(continuation); false } else true
            }
            if (immediate) continuation.resume(Unit)
            else continuation.invokeOnCancellation { synchronized(lock) { waiting.remove(continuation) } }
        }
        return clock.withFrameNanos(onFrame)
    }
}
