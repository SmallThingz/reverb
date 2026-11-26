package app.smallthingz.reverb

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bounded phase search inside the existing verified cache; transport anchors and pitch are unchanged. */
internal fun alignedShuttleStartFrame(
    window: ByteArray,
    channelCount: Int,
    nominalStart: Int,
    grainFrames: Int,
    previousTail: ByteArray,
    signedRate: Float,
    sampleRate: Int,
    allowAlignment: Boolean,
    selectedFirstFrame: Int = 0,
    selectedEndFrame: Int = window.size / (channelCount * 2),
    maximumContinuousShiftFrames: Int? = null,
): Int {
    val frameBytes = channelCount * 2
    require(channelCount in 1..2)
    val windowFrames = window.size / frameBytes
    require(nominalStart >= 0 && grainFrames > 0 && nominalStart.toLong() + grainFrames <= windowFrames)
    val magnitude = abs(signedRate)
    if (!allowAlignment || previousTail.isEmpty() || !signedRate.isFinite() || magnitude == 1f ||
        (magnitude < 1f && maximumContinuousShiftFrames == null) || (sampleRate != 48000 && sampleRate != 96000) ||
        selectedFirstFrame < 0 || selectedEndFrame > windowFrames ||
        nominalStart < selectedFirstFrame || nominalStart.toLong() + grainFrames > selectedEndFrame
    ) return nominalStart
    checkPcmConversionNotInterrupted()
    val speed = shuttleAudibleSpeed(signedRate)
    val outputFrames = (grainFrames / speed).roundToInt().coerceIn(1, grainFrames)
    val overlap = minOf(previousTail.size / frameBytes, outputFrames / 3)
    if (overlap < 16 || outputFrames < 2) return nominalStart
    val radius = minOf((sampleRate * .004).roundToInt(), maximumContinuousShiftFrames ?: Int.MAX_VALUE)
    if (radius <= 0) return nominalStart
    val first = maxOf(selectedFirstFrame, nominalStart - radius)
    val last = minOf(selectedEndFrame - grainFrames, nominalStart + radius)
    if (first == last) return nominalStart
    val sourceSpan = (grainFrames - 1).toDouble()
    val outputSpan = (outputFrames - 1).toDouble()
    fun read(bytes: ByteArray, frame: Int, channel: Int): Double {
        val index = (frame * channelCount + channel) * 2
        return ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 255)).toShort().toDouble()
    }
    // 2 denotes silence on both sides; NaN denotes a one-sided onset or loss of signal.
    fun channelScore(start: Int, channel: Int, count: Int): Double {
        var aa = 0.0; var bb = 0.0; var ab = 0.0; var aSum = 0.0; var bSum = 0.0
        for (point in 0 until count) {
            val frame = point * (overlap - 1) / (count - 1)
            val sourcePosition = frame.toDouble() * sourceSpan / outputSpan
            val a = read(previousTail, frame, channel)
            val position = if (signedRate >= 0f) sourcePosition else sourceSpan - sourcePosition
            val base = position.toInt().coerceIn(0, grainFrames - 1)
            val x = read(window, start + base, channel)
            val y = read(window, start + minOf(base + 1, grainFrames - 1), channel)
            val b = x + (y - x) * (position - base)
            aa += a * a; bb += b * b; ab += a * b; aSum += a; bSum += b
        }
        val aVariance = (aa - aSum * aSum / count).coerceAtLeast(0.0)
        val bVariance = (bb - bSum * bSum / count).coerceAtLeast(0.0)
        val floor = count * 32.0 * 32.0
        if (aVariance < floor && bVariance < floor) return 2.0
        if (aVariance < floor || bVariance < floor) return Double.NaN
        return (ab - aSum * bSum / count) / sqrt(aVariance * bVariance)
    }
    val count = minOf(48, overlap)
    val baseLeft = channelScore(nominalStart, 0, count)
    val baseRight = if (channelCount == 2) channelScore(nominalStart, 1, count) else baseLeft
    if (!baseLeft.isFinite() || !baseRight.isFinite() || minOf(baseLeft, baseRight) > .98) return nominalStart
    fun score(start: Int): Double {
        val left = channelScore(start, 0, count)
        val right = if (channelCount == 2) channelScore(start, 1, count) else left
        if (!left.isFinite() || !right.isFinite() || (baseLeft == 2.0) != (left == 2.0) ||
            (baseRight == 2.0) != (right == 2.0) || left < baseLeft || right < baseRight
        ) return -2.0
        return minOf(left, right)
    }
    var best = nominalStart
    var bestScore = minOf(baseLeft, baseRight)
    val coarseStride = sampleRate / 12000
    for (pass in 0..1) {
        val center = best
        val low = if (pass == 0) first else maxOf(first, center - coarseStride + 1)
        val high = if (pass == 0) last else minOf(last, center + coarseStride - 1)
        val stride = if (pass == 0) coarseStride else 1
        var start = low
        while (true) {
            val value = score(start)
            if (value > bestScore + 1e-6 || abs(value - bestScore) <= 1e-6 &&
                abs(start - nominalStart) < abs(best - nominalStart)
            ) { best = start; bestScore = value }
            if (start == high) break
            start = minOf(high, start + stride)
        }
    }
    if (best == nominalStart || bestScore < .85 || bestScore - minOf(baseLeft, baseRight) < .1) return nominalStart
    // A single full candidate check shares the same per-frame kernel across stereo channels.
    // Near-unity correlation makes this a conservative phase repair; transient-heavy overlaps
    // retain their original source position instead of risking shifted or repeated attacks.
    val nativeSamples = ShuttlePcmNative.filter(window, best, grainFrames, channelCount, signedRate < 0f,
        outputFrames, overlap, sourceSpan / outputSpan)
    val converter = if (nativeSamples != null) null else
        PcmRateConverter(grainFrames, sourceSpan / outputSpan, PcmFrameSamples { frame, channel ->
        val oriented = if (signedRate >= 0f) frame else grainFrames - 1 - frame
        read(window, best + oriented, channel).toFloat()
    })
    var aaL = 0.0; var bbL = 0.0; var abL = 0.0; var aSumL = 0.0; var bSumL = 0.0
    var aaR = 0.0; var bbR = 0.0; var abR = 0.0; var aSumR = 0.0; var bSumR = 0.0
    var aMinL = Double.POSITIVE_INFINITY; var aMaxL = Double.NEGATIVE_INFINITY
    var bMinL = Double.POSITIVE_INFINITY; var bMaxL = Double.NEGATIVE_INFINITY
    var aMinR = Double.POSITIVE_INFINITY; var aMaxR = Double.NEGATIVE_INFINITY
    var bMinR = Double.POSITIVE_INFINITY; var bMaxR = Double.NEGATIVE_INFINITY
    for (frame in 0 until overlap) {
        if ((frame and 127) == 0) checkPcmConversionNotInterrupted()
        val position = frame.toDouble() * sourceSpan / outputSpan
        for (channel in 0 until channelCount) {
            val a = read(previousTail, frame, channel)
            val b = (nativeSamples?.get(frame * channelCount + channel) ?: converter!!.sample(position, channel)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toDouble()
            if (channel == 0) {
                aaL += a * a; bbL += b * b; abL += a * b; aSumL += a; bSumL += b
                aMinL = minOf(aMinL, a); aMaxL = maxOf(aMaxL, a)
                bMinL = minOf(bMinL, b); bMaxL = maxOf(bMaxL, b)
            } else {
                aaR += a * a; bbR += b * b; abR += a * b; aSumR += a; bSumR += b
                aMinR = minOf(aMinR, a); aMaxR = maxOf(aMaxR, a)
                bMinR = minOf(bMinR, b); bMaxR = maxOf(bMaxR, b)
            }
        }
    }
    fun coherent(
        aa: Double, bb: Double, ab: Double, aSum: Double, bSum: Double,
        aMin: Double, aMax: Double, bMin: Double, bMax: Double,
    ): Boolean {
        val aMean = aSum / overlap; val bMean = bSum / overlap
        val aVariance = (aa - aSum * aMean).coerceAtLeast(0.0)
        val bVariance = (bb - bSum * bMean).coerceAtLeast(0.0)
        val floor = overlap * 32.0 * 32.0
        if (aVariance < floor && bVariance < floor) return true
        if (aVariance < floor || bVariance < floor) return false
        val aPeak = maxOf(abs(aMin - aMean), abs(aMax - aMean))
        val bPeak = maxOf(abs(bMin - bMean), abs(bMax - bMean))
        if (aPeak * aPeak * overlap > 9.0 * aVariance || bPeak * bPeak * overlap > 9.0 * bVariance) return false
        return (ab - aSum * bMean) / sqrt(aVariance * bVariance) >= .98
    }
    if (!coherent(aaL, bbL, abL, aSumL, bSumL, aMinL, aMaxL, bMinL, bMaxL)) return nominalStart
    if (channelCount == 2 && !coherent(aaR, bbR, abR, aSumR, bSumR, aMinR, aMaxR, bMinR, bMaxR)) return nominalStart
    return best
}
