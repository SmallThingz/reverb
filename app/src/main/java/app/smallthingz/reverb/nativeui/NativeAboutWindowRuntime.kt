package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * One native About window's real UI dispatcher and child jobs. No Recomposer/Composition is created.
 * The pinned dispatcher owns frame phase; stopped-window admission pauses new frame requests.
 */
internal class NativeAboutWindowRuntime : AutoCloseable {
    private val main = AndroidUiDispatcher.Main
    private val clock = PausableMonotonicFrameClock(checkNotNull(main[MonotonicFrameClock])).apply { pause() }
    private var started = false
    val frames = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            while (true) {
                // A frame already queued when ON_STOP arrives must not sample after that boundary.
                var delivered = false
                var result: Any? = null
                clock.withFrameNanos { time ->
                    if (started) { result = onFrame(time); delivered = true }
                }
                if (delivered) {
                    @Suppress("UNCHECKED_CAST")
                    return result as R
                }
            }
        }
    }
    private val duration = object : MotionDurationScale {
        override val scaleFactor get() = ValueAnimator.getDurationScale()
    }
    val scope = CoroutineScope(main + SupervisorJob() + frames + duration)

    fun setStarted(value: Boolean) {
        started = value
        if (value) clock.resume() else clock.pause()
    }

    override fun close() {
        setStarted(false)
        scope.cancel()
    }
}
