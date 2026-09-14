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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

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
    morphProgress: Float,
    morphStartColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var wobblePhase by remember { mutableFloatStateOf(0f) }
    val coarseAvailableFraction = if (coarseWaveform.isEmpty()) 0f else
        (coarseBuiltCount.toFloat() / coarseWaveform.size.toFloat()).coerceIn(0f, 1f)
    val detailAvailableFraction = if (detailWaveform.isEmpty()) 0f else
        (detailBuiltCount.toFloat() / detailWaveform.size.toFloat()).coerceIn(0f, 1f)
    // Let the blob become a single provisional ribbon before committing the sampled shape.
    // The worker may finish early, but visual construction still reads as one left-to-right sweep.
    val coarseTarget = if (morphProgress >= 0.64f) coarseAvailableFraction else 0f
    val detailTarget = if (morphProgress >= 0.90f && waveformPass == RangeWaveformPass.DETAIL) {
        detailAvailableFraction
    } else 0f
    val visibleCoarse by animateFloatAsState(
        targetValue = coarseTarget,
        animationSpec = tween(durationMillis = 430, easing = FastOutSlowInEasing),
        label = "rangeWaveformCoarseReveal",
    )
    val visibleDetailRaw by animateFloatAsState(
        targetValue = detailTarget,
        animationSpec = tween(durationMillis = 330, easing = FastOutSlowInEasing),
        label = "rangeWaveformDetailReveal",
    )
    val visibleDetail = minOf(visibleDetailRaw, visibleCoarse)

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
        val path = waveformConstructionPath(
            coarseWaveform = coarseWaveform,
            coarseBuiltCount = coarseBuiltCount,
            detailWaveform = detailWaveform,
            detailBuiltCount = detailBuiltCount,
            visibleCoarseFraction = visibleCoarse,
            visibleDetailFraction = visibleDetail,
            morphProgress = morphProgress,
            phase = wobblePhase,
            width = size.width,
            height = size.height,
        ) ?: return@Canvas
        val centerY = size.height * 0.5f
        val builtRight = size.width * visibleCoarse.coerceIn(0f, 1f)
        val detailRight = size.width * visibleDetail.coerceIn(0f, 1f)
        val selectedLeft = size.width * startFraction.coerceIn(0f, 1f)
        val selectedRight = size.width * endFraction.coerceIn(startFraction, 1f)
        val morph = morphProgress.coerceIn(0f, 1f)
        val materialT = (((morph - 0.06f) / 0.82f).coerceIn(0f, 1f)).let { t ->
            t * t * (3f - 2f * t)
        }
        val unresolvedColor = morphStartColor?.let { lerp(it, colors.primary, materialT) }
            ?: colors.primary
        val unresolvedCenterAlpha = if (morphStartColor == null) {
            0.14f
        } else {
            0.92f + (0.14f - 0.92f) * materialT
        }
        val unresolvedEdgeAlpha = if (morphStartColor == null) {
            0.08f
        } else {
            0.78f + (0.08f - 0.78f) * materialT
        }
        val unresolvedStrokeAlpha = if (morphStartColor == null) {
            0.08f
        } else {
            0.30f + (0.08f - 0.30f) * materialT
        }

        drawLine(
            color = colors.onSurfaceVariant.copy(
                alpha = if (morphStartColor == null) 0.10f else 0.10f * materialT,
            ),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx(),
        )

        // The unresolved suffix remains live material. It contracts from the original blob
        // silhouette into a ribbon while fixed audio is progressively committed from the left.
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(
                    unresolvedColor.copy(alpha = unresolvedEdgeAlpha),
                    unresolvedColor.copy(alpha = unresolvedCenterAlpha),
                    unresolvedColor.copy(alpha = unresolvedEdgeAlpha),
                ),
            ),
        )
        drawPath(
            path = path,
            color = unresolvedColor.copy(alpha = unresolvedStrokeAlpha),
            style = Stroke(width = 1.dp.toPx()),
        )

        if (builtRight > 0f) {
            clipRect(left = 0f, right = builtRight) {
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            colors.primary.copy(alpha = 0.24f),
                            colors.primary.copy(alpha = 0.32f),
                            colors.primary.copy(alpha = 0.24f),
                        ),
                    ),
                )
                drawPath(
                    path = path,
                    color = colors.primary.copy(alpha = 0.16f),
                    style = Stroke(width = 1.25.dp.toPx()),
                )
                clipRect(left = selectedLeft, right = selectedRight) {
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            listOf(
                                colors.primary.copy(alpha = 0.94f),
                                colors.primary,
                                colors.primary.copy(alpha = 0.94f),
                            ),
                        ),
                    )
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.Transparent,
                                colors.primary.copy(alpha = 0.10f),
                            ),
                        ),
                    )
                    drawPath(
                        path = path,
                        color = colors.primary.copy(alpha = 0.24f),
                        style = Stroke(width = 1.7.dp.toPx()),
                    )
                }
            }
        }

        // A soft construction front makes the left-to-right materialization read as a sweep,
        // rather than a hard clip edge. The second pass uses a smaller polishing front.
        if (visibleCoarse in 0.002f..0.998f) {
            drawLine(
                color = colors.primary.copy(alpha = 0.12f),
                start = Offset(builtRight, size.height * 0.10f),
                end = Offset(builtRight, size.height * 0.90f),
                strokeWidth = 13.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.36f),
                start = Offset(builtRight, size.height * 0.13f),
                end = Offset(builtRight, size.height * 0.87f),
                strokeWidth = 1.15.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (
            waveformPass == RangeWaveformPass.DETAIL &&
            visibleDetail in 0.002f..0.998f &&
            visibleCoarse > 0.95f
        ) {
            drawLine(
                color = colors.tertiary.copy(alpha = 0.13f),
                start = Offset(detailRight, size.height * 0.13f),
                end = Offset(detailRight, size.height * 0.87f),
                strokeWidth = 9.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(detailRight, size.height * 0.16f),
                end = Offset(detailRight, size.height * 0.84f),
                strokeWidth = 0.9.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun waveformConstructionPath(
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
    if (detailWaveform.size < 2 || coarseWaveform.size < 2 || width <= 0f || height <= 0f) return null
    val center = height * 0.5f
    val maxAmplitude = height * 0.44f
    val minimumAmplitude = height * 0.035f
    val visibleCoarse = visibleCoarseFraction.coerceIn(0f, 1f)
    val visibleDetail = visibleDetailFraction.coerceIn(0f, visibleCoarse)
    val coarseAvailable = (coarseBuiltCount.toFloat() / coarseWaveform.size.toFloat()).coerceIn(0f, 1f)
    val detailAvailable = (detailBuiltCount.toFloat() / detailWaveform.size.toFloat()).coerceIn(0f, 1f)
    val ribbonProgress = ((morphProgress - 0.08f) / 0.92f).coerceIn(0f, 1f)
    val morph = ribbonProgress * ribbonProgress * (3f - 2f * ribbonProgress)
    val path = Path()

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
        val t = ((distance + feather) / feather).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun blobEnvelopeAt(u: Float): Float {
        val x = (u - 0.5f) * 2f
        val circle = kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f))
        val organic = 1f + 0.018f * kotlin.math.sin(u * 19f + phase * 0.70f)
        return (circle * organic).coerceIn(0f, 1f)
    }

    fun provisionalValue(u: Float): Float {
        val blobEnvelope = blobEnvelopeAt(u)
        val ribbonWobble = (
            0.27f +
                0.070f * kotlin.math.sin(u * 31f + phase * 2.25f) +
                0.040f * kotlin.math.sin(u * 67f - phase * 1.62f) +
                0.022f * kotlin.math.sin(u * 113f + phase * 1.08f)
            ).coerceIn(0.08f, 0.68f)
        return blobEnvelope + (ribbonWobble - blobEnvelope) * morph
    }

    fun resolvedDuringMorph(u: Float, resolved: Float): Float {
        val blobEnvelope = blobEnvelopeAt(u)
        return blobEnvelope + (resolved.coerceIn(0f, 1f) - blobEnvelope) * morph
    }

    fun amplitudeAt(index: Int): Float {
        val u = index.toFloat() / detailWaveform.lastIndex.toFloat()
        var sample = provisionalValue(u)

        if (u <= coarseAvailable + 0.04f) {
            val coarse = sampledValue(coarseWaveform, coarseBuiltCount, u)
            if (coarse != null) {
                val fixedShape = resolvedDuringMorph(u, coarse)
                val weight = revealWeight(visibleCoarse, u, 0.036f)
                sample += (fixedShape - sample) * weight
            }
        }
        if (u <= detailAvailable + 0.03f) {
            val detail = sampledValue(detailWaveform, detailBuiltCount, u)
            if (detail != null) {
                val fixedShape = resolvedDuringMorph(u, detail)
                val weight = revealWeight(visibleDetail, u, 0.024f)
                sample += (fixedShape - sample) * weight
            }
        }
        return minimumAmplitude + maxAmplitude * sample.coerceIn(0f, 1f)
    }

    for (index in detailWaveform.indices) {
        val x = width * index.toFloat() / detailWaveform.lastIndex.toFloat()
        val amplitude = amplitudeAt(index)
        if (index == 0) path.moveTo(x, center - amplitude) else path.lineTo(x, center - amplitude)
    }
    for (index in detailWaveform.lastIndex downTo 0) {
        val x = width * index.toFloat() / detailWaveform.lastIndex.toFloat()
        path.lineTo(x, center + amplitudeAt(index))
    }
    path.close()
    return path
}
