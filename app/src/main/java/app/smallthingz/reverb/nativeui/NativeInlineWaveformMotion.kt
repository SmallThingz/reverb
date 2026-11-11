package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween
import smallthingz.ui.runtime.MonotonicFrameClock
import app.smallthingz.reverb.RangeWaveformPass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Released player morph and ProgressiveWaveformCanvas reveal/wobble effects. Retain this for the
 * visual row lifetime (not its changing recording revision), exactly like the three animatables.
 * Shared animation math owns no Composition. No timers poll a completed waveform.
 */
internal class NativeInlineWaveformMotion(
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val durationScale: () -> Float,
    private val publish: (ProgressiveWaveformFrame) -> Unit,
) : AutoCloseable {
    private val coarse = animation(430)
    private val detail = animation(330)
    private val morph = animation(760)
    private var loading: Boolean? = null
    private var settlingFrames = 0
    private var phase = 0.0
    private var lastPhaseFrame: Long? = null
    private var motionEnabled = true
    private var frameJob: Job? = null
    private var closed = false

    fun bind(data: ProgressiveWaveformData, loading: Boolean, morphStarted: Boolean) {
        if (closed) return
        val scale = scale()
        val coarseTarget = if (data.coarse.size == 0) 0f else
            (data.coarse.builtCount.toFloat() / data.coarse.size.toFloat()).coerceIn(0f, 1f)
        val detailTarget = if (data.pass == RangeWaveformPass.DETAIL && data.detail.size > 0)
            (data.detail.builtCount.toFloat() / data.detail.size.toFloat()).coerceIn(0f, 1f) else 0f
        coarse.target(coarseTarget, scale)
        detail.target(detailTarget, scale)
        morph.target(if (morphStarted) 1f else 0f, scale)
        if (this.loading != loading) {
            this.loading = loading
            settlingFrames = if (loading) 0 else 14
        }
        motionEnabled = scale > 0f
        if (!motionEnabled) {
            snapTargets()
            frameJob?.cancel()
            frameJob = null
        }
        publishFrame()
        requestFrames()
    }

    private fun active() = motionEnabled &&
        (loading == true || settlingFrames > 0 || coarse.running || detail.running || morph.running)
    private fun requestFrames() {
        if (frameJob?.isActive == true || !active()) return
        lastPhaseFrame = null
        frameJob = scope.launch {
            while (!closed && active()) {
                frames.withFrameNanos { time ->
                    if (!closed) {
                        val scale = scale()
                        motionEnabled = scale > 0f
                        coarse.frame(time, scale)
                        detail.frame(time, scale)
                        morph.frame(time, scale)
                        if (!motionEnabled) snapTargets()
                        else if (loading == true || settlingFrames > 0) {
                            lastPhaseFrame?.let { previous ->
                                phase += (time - previous).coerceAtLeast(0L) / (1_000_000_000.0 * scale)
                            }
                            lastPhaseFrame = maxOf(lastPhaseFrame ?: time, time)
                            if (loading != true) settlingFrames--
                        } else lastPhaseFrame = null
                        publishFrame()
                    }
                }
            }
        }
    }

    private fun scale(): Float = durationScale().let { if (it.isFinite() && it >= 0f) it else 1f }

    private fun snapTargets() {
        coarse.snap(coarse.target)
        detail.snap(detail.target)
        morph.snap(morph.target)
        settlingFrames = 0
        lastPhaseFrame = null
    }

    private fun publishFrame() = publish(ProgressiveWaveformFrame(
        morphProgress = morph.value,
        visibleCoarseFraction = coarse.value,
        visibleDetailFraction = detail.value,
        wobblePhaseSeconds = phase.toFloat(),
        // PlayerTimeline substitutes the current source-derived selection on every frame.
        startFraction = 0f, endFraction = 0f, morphFromBlob = false,
    ))

    override fun close() {
        closed = true
        frameJob?.cancel()
        frameJob = null
    }

    private fun animation(duration: Int) = LibraryTweenValue(0f, Float.VectorConverter,
        tween<Float>(duration, easing = FastOutSlowInEasing), { AnimationVector1D(0f) })
}
