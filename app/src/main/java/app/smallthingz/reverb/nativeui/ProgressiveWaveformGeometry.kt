package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.RangeWaveformPass
import app.smallthingz.reverb.rangeWaveformRenderPointCount

/**
 * One published waveform snapshot. Samples are copied once on publication, never on a frame update.
 * Read access cannot expose the backing arrays to a producer that might publish another snapshot.
 */
internal class ProgressiveWaveformData(
    coarseSamples: FloatArray,
    coarseBuiltCount: Int,
    detailSamples: FloatArray,
    detailBuiltCount: Int,
    val pass: RangeWaveformPass,
) {
    val coarse = ProgressiveWaveformSamples(coarseSamples, coarseBuiltCount)
    val detail = ProgressiveWaveformSamples(detailSamples, detailBuiltCount)
}

internal class ProgressiveWaveformSamples(samples: FloatArray, val builtCount: Int) {
    private val values = samples.copyOf()
    val size: Int get() = values.size
    val lastIndex: Int get() = values.lastIndex

    operator fun get(index: Int): Float = values[index]
}

/**
 * A complete frame supplied by the owner, not an animation clock owned by the drawing View.
 *
 * visibleCoarseFraction and visibleDetailFraction are the already-animated availability fronts
 * (the released renderer uses 430 ms and 330 ms FastOutSlowIn tweens respectively).
 * wobblePhaseSeconds is the same Float seconds value derived from the owner's frame clock.
 * morphFromBlob corresponds to the released renderer's non-null morphStartColor; that color's
 * presence selects the material path, while the actual material color is always palette.primary.
 */
internal data class ProgressiveWaveformFrame(
    val morphProgress: Float,
    val visibleCoarseFraction: Float,
    val visibleDetailFraction: Float,
    val wobblePhaseSeconds: Float,
    val startFraction: Float,
    val endFraction: Float,
    val morphFromBlob: Boolean,
)

/** Cached source-derived contour math; contains no Android, Compose state, scheduler or audio. */
internal class ProgressiveWaveformGeometry {
    private var basis = WaveformMorphBasis(0)
    private var amplitudes = FloatArray(0)

    fun prepare(detailSampleCount: Int) {
        if (amplitudes.size == detailSampleCount) return
        basis = WaveformMorphBasis(detailSampleCount)
        amplitudes = FloatArray(detailSampleCount)
    }

    fun amplitudeAt(point: Int): Float = amplitudes[point]

    /**
     * Returns the upper contour's point count; the lower contour mirrors these same cached values.
     * A zero return means the released renderer would not draw a path.
     */
    fun update(
        data: ProgressiveWaveformData,
        visibleCoarseFraction: Float,
        visibleDetailFraction: Float,
        morphProgress: Float,
        phase: Float,
        width: Float,
        height: Float,
    ): Int {
        prepare(data.detail.size)
        if (
            data.detail.size < 2 ||
            data.coarse.size < 2 ||
            amplitudes.size < basis.pointCount ||
            width <= 0f ||
            height <= 0f
        ) return 0

        val visibleCoarse = visibleCoarseFraction.coerceIn(0f, 1f)
        val visibleDetail = visibleDetailFraction.coerceIn(0f, visibleCoarse)
        val coarseAvailable = (data.coarse.builtCount.toFloat() / data.coarse.size.toFloat()).coerceIn(0f, 1f)
        val detailAvailable = (data.detail.builtCount.toFloat() / data.detail.size.toFloat()).coerceIn(0f, 1f)
        val ribbonProgress = ((morphProgress - 0.08f) / 0.92f).coerceIn(0f, 1f)
        val morph = waveformSmoothStep(ribbonProgress)
        val minimumAmplitude = height * 0.035f * morph
        val maxAmplitude = height * (0.50f - 0.06f * morph)
        val phase19 = phase * 0.70f
        val phase31 = phase * 2.25f
        val phase67 = -phase * 1.62f
        val phase113 = phase * 1.08f
        val phase19Sin = kotlin.math.sin(phase19)
        val phase19Cos = kotlin.math.cos(phase19)
        val phase31Sin = kotlin.math.sin(phase31)
        val phase31Cos = kotlin.math.cos(phase31)
        val phase67Sin = kotlin.math.sin(phase67)
        val phase67Cos = kotlin.math.cos(phase67)
        val phase113Sin = kotlin.math.sin(phase113)
        val phase113Cos = kotlin.math.cos(phase113)

        fun revealWeight(front: Float, u: Float, feather: Float): Float {
            if (front >= 0.999f) return 1f
            val distance = front - u
            if (distance >= 0f) return 1f
            if (distance <= -feather) return 0f
            return waveformSmoothStep(((distance + feather) / feather).coerceIn(0f, 1f))
        }

        fun blobEnvelopeAt(index: Int): Float {
            val organic = 1f + 0.018f * morph * shiftedSin(
                basis.sin19[index],
                basis.cos19[index],
                phase19Sin,
                phase19Cos,
            )
            return (basis.circle[index] * organic).coerceIn(0f, 1f)
        }

        fun amplitudeAtBasis(index: Int): Float {
            val u = basis.u[index]
            val blobEnvelope = blobEnvelopeAt(index)
            val ribbonWobble = (
                0.27f +
                    0.070f * shiftedSin(
                        basis.sin31[index], basis.cos31[index], phase31Sin, phase31Cos,
                    ) +
                    0.040f * shiftedSin(
                        basis.sin67[index], basis.cos67[index], phase67Sin, phase67Cos,
                    ) +
                    0.022f * shiftedSin(
                        basis.sin113[index], basis.cos113[index], phase113Sin, phase113Cos,
                    )
                ).coerceIn(0.08f, 0.68f)
            var sample = blobEnvelope + (ribbonWobble - blobEnvelope) * morph

            if (u <= coarseAvailable + 0.04f) {
                withWaveformSample(data.coarse, u) { coarse ->
                    val fixedShape = blobEnvelope + (coarse.coerceIn(0f, 1f) - blobEnvelope) * morph
                    sample += (fixedShape - sample) * revealWeight(visibleCoarse, u, 0.036f)
                }
            }
            if (u <= detailAvailable + 0.03f) {
                withWaveformSample(data.detail, u) { detail ->
                    val fixedShape = blobEnvelope + (detail.coerceIn(0f, 1f) - blobEnvelope) * morph
                    sample += (fixedShape - sample) * revealWeight(visibleDetail, u, 0.024f)
                }
            }
            return minimumAmplitude + maxAmplitude * sample.coerceIn(0f, 1f)
        }

        val renderPointCount = rangeWaveformRenderPointCount(basis.pointCount, morphProgress)
        val renderDenominator = (renderPointCount - 1).coerceAtLeast(1)
        val basisLastIndex = basis.pointCount - 1
        for (point in 0 until renderPointCount) {
            val basisIndex = point * basisLastIndex / renderDenominator
            amplitudes[point] = amplitudeAtBasis(basisIndex)
        }
        return renderPointCount
    }
}

internal fun waveformSmoothStep(value: Float): Float = value * value * (3f - 2f * value)

private class WaveformMorphBasis(size: Int) {
    val pointCount = size.coerceAtLeast(2)
    val u = FloatArray(pointCount)
    val circle = FloatArray(pointCount)
    val sin19 = FloatArray(pointCount)
    val cos19 = FloatArray(pointCount)
    val sin31 = FloatArray(pointCount)
    val cos31 = FloatArray(pointCount)
    val sin67 = FloatArray(pointCount)
    val cos67 = FloatArray(pointCount)
    val sin113 = FloatArray(pointCount)
    val cos113 = FloatArray(pointCount)

    init {
        val denominator = (pointCount - 1).toFloat()
        for (index in 0 until pointCount) {
            val value = index.toFloat() / denominator
            u[index] = value
            val x = (value - 0.5f) * 2f
            circle[index] = kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f))
            sin19[index] = kotlin.math.sin(value * 19f)
            cos19[index] = kotlin.math.cos(value * 19f)
            sin31[index] = kotlin.math.sin(value * 31f)
            cos31[index] = kotlin.math.cos(value * 31f)
            sin67[index] = kotlin.math.sin(value * 67f)
            cos67[index] = kotlin.math.cos(value * 67f)
            sin113[index] = kotlin.math.sin(value * 113f)
            cos113[index] = kotlin.math.cos(value * 113f)
        }
    }
}

private fun shiftedSin(
    baseSin: Float,
    baseCos: Float,
    phaseSin: Float,
    phaseCos: Float,
): Float = baseSin * phaseCos + baseCos * phaseSin

private inline fun withWaveformSample(
    values: ProgressiveWaveformSamples,
    u: Float,
    consume: (Float) -> Unit,
) {
    if (values.builtCount <= 0 || values.size == 0) return
    val position = u.coerceIn(0f, 1f) * values.lastIndex.toFloat()
    val first = position.toInt().coerceIn(0, values.lastIndex)
    if (first >= values.builtCount) return
    val second = minOf(first + 1, values.builtCount - 1, values.lastIndex)
    val fraction = (position - first.toFloat()).coerceIn(0f, 1f)
    consume(values[first] + (values[second] - values[first]) * fraction)
}
