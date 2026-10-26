package app.smallthingz.reverb

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val PCM_KERNEL_LOBES = 32.0
internal const val PCM_MAX_FILTER_RADIUS = 4096

internal fun checkPcmConversionNotInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PCM conversion cancelled")
}

/** Validate in Double before converting or allocating from a supplied sample-rate ratio. */
internal fun pcmResamplingRadius(sourceStep: Double): Int {
    if (!sourceStep.isFinite() || sourceStep <= 0.0) throw IOException("Invalid PCM conversion ratio")
    val support = if (sourceStep > 1.0) ceil(PCM_KERNEL_LOBES * sourceStep / 0.9) else 0.0
    // Covers every app rate pair and 655350 Hz FLAC into the lowest 7350 Hz app rate.
    if (!support.isFinite() || support > PCM_MAX_FILTER_RADIUS) throw IOException("PCM conversion ratio is too large")
    return support.toInt()
}

/** A primitive SAM avoids boxing a frame index and sample value for every filter tap. */
internal fun interface PcmFrameSamples {
    fun sample(frame: Int, channel: Int): Float
}

/**
 * Bounded-support conversion over an already verified PCM window. Downsampling uses
 * a Blackman-windowed sinc at 90% of target Nyquist, with a transition band before
 * frequencies can alias. Constant endpoint extension preserves DC even for one frame.
 * Upsampling retains the existing linear interpolation.
 */
internal class PcmRateConverter(
    private val sourceFrames: Int,
    sourceStep: Double,
    private val readSample: PcmFrameSamples,
) {
    internal val radius = pcmResamplingRadius(sourceStep)
    private val cutoff = if (radius > 0) 0.9 / sourceStep else 1.0
    private var observations = 0

    init {
        require(sourceFrames > 0)
        checkPcmConversionNotInterrupted()
    }

    fun sample(position: Double, channel: Int): Float {
        if ((observations++ and 255) == 0) checkPcmConversionNotInterrupted()
        require(position.isFinite())
        val base = floor(position).toInt().coerceIn(0, sourceFrames - 1)
        val fraction = (position - base).coerceIn(0.0, 1.0)
        if (radius == 0) {
            val first = readSample.sample(base, channel)
            val second = readSample.sample(minOf(base + 1, sourceFrames - 1), channel)
            return first + (second - first) * fraction.toFloat()
        }
        // Worker-local scratch avoids per-grain coefficient arrays. Reentrant sample
        // accessors get their own scratch rather than overwriting the outer weights.
        val shared = workspace.get()
        val current = if (shared.inUse) KernelWorkspace() else shared
        current.prepare(radius, cutoff, fraction)
        current.inUse = true
        return try {
            var sum = 0.0
            for (tap in 0 until current.count) {
                val frame = (base.toLong() + tap - radius).coerceIn(0L, sourceFrames - 1L).toInt()
                sum += readSample.sample(frame, channel) * current.weights[tap]
            }
            (sum / current.weightSum).toFloat()
        } finally { current.inUse = false }
    }

    private class KernelWorkspace {
        var weights = DoubleArray(0)
        var count = 0
        var weightSum = 1.0
        var inUse = false
        private var previousCutoff = Double.NaN
        private var previousFraction = Double.NaN

        fun prepare(radius: Int, cutoff: Double, fraction: Double) {
            val needed = radius * 2 + 1
            if (count == needed && cutoff == previousCutoff && fraction == previousFraction) return
            if (weights.size < needed) weights = DoubleArray(needed)
            count = needed
            weightSum = 0.0
            for (tap in 0 until needed) {
                val value = kernel((tap - radius - fraction) * cutoff)
                weights[tap] = value
                weightSum += value
            }
            previousCutoff = cutoff
            previousFraction = fraction
        }
    }

    companion object {
        private const val TABLE_STEPS_PER_LOBE = 1024
        private val workspace = ThreadLocal.withInitial { KernelWorkspace() }
        // One process-wide 128 KiB table; no trigonometry or allocation in the tap loop.
        private val table: FloatArray by lazy {
            FloatArray((PCM_KERNEL_LOBES * TABLE_STEPS_PER_LOBE).toInt() + 1) { index ->
                val x = index.toDouble() / TABLE_STEPS_PER_LOBE
                if (index == 0) 1f else {
                    val window = 0.42 + 0.5 * cos(PI * x / PCM_KERNEL_LOBES) +
                        0.08 * cos(2 * PI * x / PCM_KERNEL_LOBES)
                    (sin(PI * x) / (PI * x) * window).toFloat()
                }
            }
        }

        private fun kernel(distance: Double): Double {
            val position = abs(distance) * TABLE_STEPS_PER_LOBE
            if (position >= PCM_KERNEL_LOBES * TABLE_STEPS_PER_LOBE) return 0.0
            val first = position.toInt()
            val fraction = position - first
            return table[first] + (table[first + 1] - table[first]) * fraction
        }
    }
}
