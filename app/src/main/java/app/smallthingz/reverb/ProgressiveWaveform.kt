package app.smallthingz.reverb

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RANGE_WAVEFORM_MIN_AMPLITUDE_FRACTION = 0.035f
private const val RANGE_WAVEFORM_MAX_AMPLITUDE_START_FRACTION = 0.50f
private const val RANGE_WAVEFORM_MAX_AMPLITUDE_MORPH_REDUCTION_FRACTION = 0.06f
internal const val RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION =
    2f * (
        RANGE_WAVEFORM_MIN_AMPLITUDE_FRACTION +
            RANGE_WAVEFORM_MAX_AMPLITUDE_START_FRACTION -
            RANGE_WAVEFORM_MAX_AMPLITUDE_MORPH_REDUCTION_FRACTION
        )

internal suspend fun buildProgressiveWaveform(
    readPass: (RangeWaveformPass, (Int, Float) -> Boolean) -> Unit,
    onPassStarted: (RangeWaveformPass) -> Unit = {},
    onSnapshot: (RangeWaveformPass, ProgressiveWaveformSnapshot) -> Unit,
) = coroutineScope {
    for (pass in RangeWaveformPass.entries) {
        if (pass == RangeWaveformPass.DETAIL) delay(280L)
        onPassStarted(pass)
        val updates = Channel<ProgressiveWaveformSnapshot>(Channel.CONFLATED)
        val worker = launch(Dispatchers.IO) {
            val accumulator = ProgressiveWaveformAccumulator(pass.bucketCount)
            try {
                readPass(pass) { index, magnitude ->
                    val update = accumulator.record(index, magnitude)
                    update == null || updates.trySend(update).isSuccess
                }
                accumulator.finish()?.let { updates.trySend(it) }
            } finally {
                updates.close()
            }
        }
        try {
            for (update in updates) onSnapshot(pass, update)
        } finally {
            worker.cancel()
            updates.close()
        }
    }
}

@Composable
internal fun ProgressiveWaveformCanvas(
    coarseWaveform: FloatArray,
    coarseBuiltCount: Int,
    detailWaveform: FloatArray,
    detailBuiltCount: Int,
    waveformPass: RangeWaveformPass,
    startFraction: Float,
    endFraction: Float,
    loading: Boolean,
    morphProgress: () -> Float,
    modifier: Modifier = Modifier,
    morphStartColor: Color? = null,
) {
    val colors = MaterialTheme.colorScheme
    var wobblePhase by remember { mutableFloatStateOf(0f) }
    val coarseAvailableFraction = if (coarseWaveform.isEmpty()) 0f else
        (coarseBuiltCount.toFloat() / coarseWaveform.size.toFloat()).coerceIn(0f, 1f)
    val detailAvailableFraction = if (detailWaveform.isEmpty()) 0f else
        (detailBuiltCount.toFloat() / detailWaveform.size.toFloat()).coerceIn(0f, 1f)
    // Availability animation is independent from the opening morph. Draw-time gates below
    // turn it into the same left-to-right sweep without reading morph state in composition.
    val coarseTarget = coarseAvailableFraction
    val detailTarget = if (waveformPass == RangeWaveformPass.DETAIL) detailAvailableFraction else 0f
    val visibleCoarse = animateFloatAsState(
        targetValue = coarseTarget,
        animationSpec = tween(durationMillis = 430, easing = FastOutSlowInEasing),
        label = "rangeWaveformCoarseReveal",
    )
    val visibleDetail = animateFloatAsState(
        targetValue = detailTarget,
        animationSpec = tween(durationMillis = 330, easing = FastOutSlowInEasing),
        label = "rangeWaveformDetailReveal",
    )
    val waveformPath = remember { Path() }
    val morphBasis = remember(detailWaveform.size) { WaveformMorphBasis(detailWaveform.size) }
    val amplitudeScratch = remember(detailWaveform.size) { FloatArray(detailWaveform.size) }
    val builtBrush = remember(colors.primary) {
        Brush.horizontalGradient(
            listOf(
                colors.primary.copy(alpha = 0.24f),
                colors.primary.copy(alpha = 0.32f),
                colors.primary.copy(alpha = 0.24f),
            ),
        )
    }
    val selectedBrush = remember(colors.primary) {
        Brush.horizontalGradient(
            listOf(
                colors.primary.copy(alpha = 0.94f),
                colors.primary,
                colors.primary.copy(alpha = 0.94f),
            ),
        )
    }
    val selectedLightBrush = remember(colors.primary) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.18f),
                Color.Transparent,
                colors.primary.copy(alpha = 0.10f),
            ),
        )
    }

    val unresolvedBrush = remember(colors.primary, morphStartColor) {
        if (morphStartColor == null) {
            Brush.horizontalGradient(
                listOf(
                    colors.primary.copy(alpha = 0.08f),
                    colors.primary.copy(alpha = 0.14f),
                    colors.primary.copy(alpha = 0.08f),
                ),
            )
        } else {
            // Range zero-pass is the final timeline material, at full strength from frame zero.
            SolidColor(colors.primary)
        }
    }
    val unresolvedStrokeColor = remember(colors.primary, morphStartColor) {
        if (morphStartColor == null) colors.primary.copy(alpha = 0.08f) else Color.Transparent
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                withFrameNanos { frameNanos ->
                    wobblePhase = (frameNanos / 1_000_000_000.0).toFloat()
                }
            }
        } else {
            repeat(14) {
                withFrameNanos { frameNanos ->
                    wobblePhase = (frameNanos / 1_000_000_000.0).toFloat()
                }
            }
        }
    }

    Canvas(modifier) {
        val morph = morphProgress().coerceIn(0f, 1f)
        if (morphStartColor != null && morph <= 0f) {
            // The graphics layer maps this full-bounds analytic oval to the measured source
            // circle exactly; do not approximate the renderer handoff with sampled vertices.
            drawOval(
                color = colors.primary,
                topLeft = Offset.Zero,
                size = size,
            )
            return@Canvas
        }
        val availableCoarse = visibleCoarse.value
        val availableDetail = minOf(visibleDetail.value, availableCoarse)
        // Multiplying the already animated availability front by a draw-time morph gate keeps
        // the progressive sweep while avoiding per-frame Compose recomposition.
        val coarseGate = smoothStep(((morph - 0.56f) / 0.16f).coerceIn(0f, 1f))
        val detailGate = smoothStep(((morph - 0.86f) / 0.12f).coerceIn(0f, 1f))
        val effectiveVisibleCoarse = availableCoarse * coarseGate
        val effectiveVisibleDetail = minOf(availableDetail * detailGate, effectiveVisibleCoarse)
        val path = waveformConstructionPath(
            path = waveformPath,
            amplitudes = amplitudeScratch,
            basis = morphBasis,
            coarseWaveform = coarseWaveform,
            coarseBuiltCount = coarseBuiltCount,
            detailWaveform = detailWaveform,
            detailBuiltCount = detailBuiltCount,
            visibleCoarseFraction = effectiveVisibleCoarse,
            visibleDetailFraction = effectiveVisibleDetail,
            morphProgress = morph,
            phase = wobblePhase,
            width = size.width,
            height = size.height,
        ) ?: return@Canvas
        val centerY = size.height * 0.5f
        val builtRight = size.width * effectiveVisibleCoarse.coerceIn(0f, 1f)
        val detailRight = size.width * effectiveVisibleDetail.coerceIn(0f, 1f)
        val selectedLeft = size.width * startFraction.coerceIn(0f, 1f)
        val selectedRight = size.width * endFraction.coerceIn(startFraction, 1f)
        val materialT = (((morph - 0.06f) / 0.82f).coerceIn(0f, 1f)).let { t ->
            t * t * (3f - 2f * t)
        }
        val finalLayerAlpha = if (morphStartColor == null) {
            1f
        } else {
            smoothStep(((morph - 0.78f) / 0.22f).coerceIn(0f, 1f))
        }
        drawLine(
            color = colors.onSurfaceVariant.copy(
                alpha = if (morphStartColor == null) 0.10f else 0.10f * materialT,
            ),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx(),
        )

        // Only the not-yet-built suffix remains unresolved. Keeping the zero-pass under the
        // already-built prefix made range export stay full-strength outside Start/End even after
        // settling, unlike Library playback/trim. Once a prefix is built, its selected/dim
        // material owns those pixels completely.
        if (builtRight < size.width) {
            clipRect(left = builtRight.coerceAtLeast(0f), right = size.width) {
                drawPath(path = path, brush = unresolvedBrush)
                drawPath(
                    path = path,
                    color = unresolvedStrokeColor,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        if (builtRight > 0f && finalLayerAlpha > 0.01f) {
            clipRect(left = 0f, right = builtRight) {
                drawPath(
                    path = path,
                    brush = builtBrush,
                    alpha = finalLayerAlpha,
                )
                drawPath(
                    path = path,
                    color = colors.primary.copy(alpha = 0.16f * finalLayerAlpha),
                    style = Stroke(width = 1.25.dp.toPx()),
                )
                clipRect(left = selectedLeft, right = selectedRight) {
                    drawPath(
                        path = path,
                        brush = selectedBrush,
                        alpha = finalLayerAlpha,
                    )
                    drawPath(
                        path = path,
                        brush = selectedLightBrush,
                        alpha = finalLayerAlpha,
                    )
                    drawPath(
                        path = path,
                        color = colors.primary.copy(alpha = 0.24f * finalLayerAlpha),
                        style = Stroke(width = 1.7.dp.toPx()),
                    )
                }
            }
        }

        // A soft construction front makes the left-to-right materialization read as a sweep,
        // rather than a hard clip edge. The second pass uses a smaller polishing front.
        if (finalLayerAlpha > 0.01f && effectiveVisibleCoarse in 0.002f..0.998f) {
            drawLine(
                color = colors.primary.copy(alpha = 0.12f * finalLayerAlpha),
                start = Offset(builtRight, size.height * 0.10f),
                end = Offset(builtRight, size.height * 0.90f),
                strokeWidth = 13.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.36f * finalLayerAlpha),
                start = Offset(builtRight, size.height * 0.13f),
                end = Offset(builtRight, size.height * 0.87f),
                strokeWidth = 1.15.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (
            finalLayerAlpha > 0.01f &&
            waveformPass == RangeWaveformPass.DETAIL &&
            effectiveVisibleDetail in 0.002f..0.998f &&
            effectiveVisibleCoarse > 0.95f
        ) {
            drawLine(
                color = colors.tertiary.copy(alpha = 0.13f * finalLayerAlpha),
                start = Offset(detailRight, size.height * 0.13f),
                end = Offset(detailRight, size.height * 0.87f),
                strokeWidth = 9.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.28f * finalLayerAlpha),
                start = Offset(detailRight, size.height * 0.16f),
                end = Offset(detailRight, size.height * 0.84f),
                strokeWidth = 0.9.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

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

private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

internal fun rangeWaveformRenderPointCount(totalPoints: Int, morphProgress: Float): Int {
    val total = totalPoints.coerceAtLeast(2)
    return when {
        morphProgress < 0.85f -> minOf(total, 128)
        morphProgress < 0.995f -> minOf(total, 256)
        else -> total
    }
}

private fun shiftedSin(
    baseSin: Float,
    baseCos: Float,
    phaseSin: Float,
    phaseCos: Float,
): Float = baseSin * phaseCos + baseCos * phaseSin

private fun waveformConstructionPath(
    path: Path,
    amplitudes: FloatArray,
    basis: WaveformMorphBasis,
    coarseWaveform: FloatArray,
    coarseBuiltCount: Int,
    detailWaveform: FloatArray,
    detailBuiltCount: Int,
    visibleCoarseFraction: Float,
    visibleDetailFraction: Float,
    morphProgress: Float,
    phase: Float,
    width: Float,
    height: Float,
): Path? {
    if (
        detailWaveform.size < 2 ||
        coarseWaveform.size < 2 ||
        amplitudes.size < basis.pointCount ||
        width <= 0f ||
        height <= 0f
    ) return null

    val center = height * 0.5f
    val visibleCoarse = visibleCoarseFraction.coerceIn(0f, 1f)
    val visibleDetail = visibleDetailFraction.coerceIn(0f, visibleCoarse)
    val coarseAvailable = (coarseBuiltCount.toFloat() / coarseWaveform.size.toFloat()).coerceIn(0f, 1f)
    val detailAvailable = (detailBuiltCount.toFloat() / detailWaveform.size.toFloat()).coerceIn(0f, 1f)
    val ribbonProgress = ((morphProgress - 0.08f) / 0.92f).coerceIn(0f, 1f)
    val morph = smoothStep(ribbonProgress)
    // At morph=0 this is a mathematically exact circle: no waveform floor and a half-height
    // radius. The floor and final 44% amplitude are introduced continuously with the morph.
    val minimumAmplitude = height * RANGE_WAVEFORM_MIN_AMPLITUDE_FRACTION * morph
    val maxAmplitude = height * (
        RANGE_WAVEFORM_MAX_AMPLITUDE_START_FRACTION -
            RANGE_WAVEFORM_MAX_AMPLITUDE_MORPH_REDUCTION_FRACTION * morph
        )
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

    fun sampledValue(values: FloatArray, builtCount: Int, u: Float): Float? {
        if (builtCount <= 0 || values.isEmpty()) return null
        val position = u.coerceIn(0f, 1f) * values.lastIndex.toFloat()
        val first = position.toInt().coerceIn(0, values.lastIndex)
        if (first >= builtCount) return null
        val second = minOf(first + 1, builtCount - 1, values.lastIndex)
        val fraction = (position - first.toFloat()).coerceIn(0f, 1f)
        return values[first] + (values[second] - values[first]) * fraction
    }

    fun revealWeight(front: Float, u: Float, feather: Float): Float {
        if (front >= 0.999f) return 1f
        val distance = front - u
        if (distance >= 0f) return 1f
        if (distance <= -feather) return 0f
        return smoothStep(((distance + feather) / feather).coerceIn(0f, 1f))
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

    fun amplitudeAt(index: Int): Float {
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
            val coarse = sampledValue(coarseWaveform, coarseBuiltCount, u)
            if (coarse != null) {
                val fixedShape = blobEnvelope + (coarse.coerceIn(0f, 1f) - blobEnvelope) * morph
                sample += (fixedShape - sample) * revealWeight(visibleCoarse, u, 0.036f)
            }
        }
        if (u <= detailAvailable + 0.03f) {
            val detail = sampledValue(detailWaveform, detailBuiltCount, u)
            if (detail != null) {
                val fixedShape = blobEnvelope + (detail.coerceIn(0f, 1f) - blobEnvelope) * morph
                sample += (fixedShape - sample) * revealWeight(visibleDetail, u, 0.024f)
            }
        }
        return minimumAmplitude + maxAmplitude * sample.coerceIn(0f, 1f)
    }

    path.reset()
    // A moving 1080px-wide ribbon does not benefit visually from 512 path vertices. Use an
    // evenly sampled 256-point contour during the morph, then restore every detail point once
    // settled. This halves path commands and envelope math on the animation hot path.
    val renderPointCount = rangeWaveformRenderPointCount(basis.pointCount, morphProgress)
    val renderDenominator = (renderPointCount - 1).coerceAtLeast(1)
    val basisLastIndex = basis.pointCount - 1
    for (point in 0 until renderPointCount) {
        val basisIndex = point * basisLastIndex / renderDenominator
        val x = width * point.toFloat() / renderDenominator.toFloat()
        val amplitude = amplitudeAt(basisIndex)
        amplitudes[point] = amplitude
        if (point == 0) path.moveTo(x, center - amplitude) else path.lineTo(x, center - amplitude)
    }
    for (point in renderPointCount - 1 downTo 0) {
        val x = width * point.toFloat() / renderDenominator.toFloat()
        path.lineTo(x, center + amplitudes[point])
    }
    path.close()
    return path
}
