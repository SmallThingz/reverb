package app.smallthingz.reverb.nativeui

import android.view.View
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One source animation's frame job, inheriting the real host's paused clock and duration scale. */
internal class HomeFrameLoop(
    private val view: View,
    private val scope: CoroutineScope,
    private val running: () -> Boolean,
    private val sample: (Long, Float) -> Unit,
) {
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock]) {
        "Home animations require the owning window's frame clock"
    }
    private var job: Job? = null
    val durationScale get() = scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f

    fun request() {
        if (job?.isActive == true || !view.isAttachedToWindow || !running()) return
        lateinit var next: Job
        next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (view.isAttachedToWindow && running()) {
                    frames.withFrameNanos { nanos ->
                        if (view.isAttachedToWindow) sample(nanos, durationScale)
                    }
                }
            } finally {
                if (job === next) job = null
            }
        }
        job = next
        next.start()
    }

    fun cancel() {
        val previous = job
        job = null
        previous?.cancel()
    }
}
