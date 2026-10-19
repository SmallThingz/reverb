package app.smallthingz.reverb

import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SILENCE_MIN_HZ = 20
internal const val SILENCE_MAX_HZ = 20_000
internal const val SILENCE_MIN_DB = -96
internal const val SILENCE_MAX_POINTS = 16
internal const val SILENCE_HOLD_MILLIS = 200
internal const val SILENCE_GRAPH_BINS = 96

internal data class SilencePoint(val hz: Int, val db: Int)
internal data class SilenceGateConfig(
    val enabled: Boolean = false,
    val points: List<SilencePoint> = listOf(
        SilencePoint(20, -50), SilencePoint(200, -50),
        SilencePoint(2_000, -50), SilencePoint(20_000, -50),
    ),
) {
    fun validate(): SilenceGateConfig {
        require(points.size in 2..SILENCE_MAX_POINTS)
        require(points.first().hz == SILENCE_MIN_HZ && points.last().hz == SILENCE_MAX_HZ)
        require(points.all { it.hz in SILENCE_MIN_HZ..SILENCE_MAX_HZ && it.db in SILENCE_MIN_DB..0 })
        require(points.zipWithNext().all { (a, b) -> a.hz < b.hz })
        return this
    }

    fun threshold(hz: Double): Double {
        if (hz <= points.first().hz) return points.first().db.toDouble()
        for (i in 1 until points.size) {
            val b = points[i]
            if (hz <= b.hz) {
                val a = points[i - 1]
                if (a.db == b.db) return a.db.toDouble()
                val t = (log10(hz) - log10(a.hz.toDouble())) /
                    (log10(b.hz.toDouble()) - log10(a.hz.toDouble()))
                return a.db + t * (b.db - a.db)
            }
        }
        return points.last().db.toDouble()
    }
}

internal fun silenceFrequencyFraction(hz: Double): Double =
    (log10(hz.coerceIn(20.0, 20_000.0) / 20.0) / 3.0).coerceIn(0.0, 1.0)

internal fun silenceFrequencyAt(fraction: Double): Int =
    (20.0 * 10.0.pow(3.0 * fraction.coerceIn(0.0, 1.0))).roundToInt().coerceIn(20, 20_000)

internal fun encodeSilenceGate(config: SilenceGateConfig): String = config.validate().let {
    "1;${if (it.enabled) 1 else 0};" + it.points.joinToString(",") { p -> "${p.hz}:${p.db}" }
}

internal fun decodeSilenceGate(encoded: String?): SilenceGateConfig {
    if (encoded == null) return SilenceGateConfig()
    require(encoded.length <= 512) { "Silence curve is too large" }
    val fields = encoded.split(';')
    require(fields.size == 3 && fields[0] == "1" && fields[1] in listOf("0", "1"))
    val points = fields[2].split(',').map {
        val pair = it.split(':')
        require(pair.size == 2)
        SilencePoint(pair[0].toInt(), pair[1].toInt())
    }
    return SilenceGateConfig(fields[1] == "1", points).validate()
}

/** Full PCM frames, either channel independently. Opposite-phase stereo must not cancel the detector. */
internal class SilenceSpectrum(val sampleRate: Int, val channels: Int, val format: PcmSampleFormat) {
    val size: Int = Integer.highestOneBit(max(256, (sampleRate + 29) / 30 - 1)) shl 1
    val hop: Int = size / 2
    val frameBytes: Int = channels * format.bytesPerSample
    val powers = DoubleArray(size / 2 + 1)
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val channelPowers = DoubleArray(powers.size)
    private val peakFrequencies = DoubleArray(powers.size)
    private val detectedPowers = DoubleArray(channels * powers.size)
    private val detectedFrequencies = DoubleArray(channels * powers.size)
    private val fittedPowers = DoubleArray(channels * 2)
    private val fittedFrequencies = DoubleArray(channels * 2)
    private val samples = DoubleArray(size)
    private val fittedSamples = DoubleArray(size)
    private var fittedPower = 0.0
    private var fittedEnergy = 0.0
    private var fittedCosine = 0.0
    private var fittedSine = 0.0
    private var shortBlockPeakPower = 0.0
    private val observedAmplitudePowers = DoubleArray(channels)
    private val firstHalfPowers = DoubleArray(channels)
    private val lastHalfPowers = DoubleArray(channels)
    private var boundaryObservationsAvailable = false
    private var analyzedFrames = 0
    private var responseCos = 1.0
    private var responseSin = 0.0
    private var responseGain = 1.0
    private var responseShift = size.toDouble() / (size - 1)
    private var edgeBins = 2
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val partialWindow = DoubleArray(size)
    private var analysisWindow = window
    private val reverse = IntArray(size) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(size)) }
    private val cosine = DoubleArray(size / 2) { cos(-2 * PI * it / size) }
    private val sine = DoubleArray(size / 2) { sin(-2 * PI * it / size) }
    private val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
    var invalidSamples: Boolean = false
        private set

    init { require(sampleRate in 7_000..192_000 && channels in 1..2) }

    fun analyze(bytes: ByteArray, frames: Int, observeBoundaries: Boolean = false) {
        require(frames in 1..size && bytes.size >= frames * frameBytes)
        powers.fill(0.0)
        fittedPowers.fill(0.0)
        for (bin in peakFrequencies.indices) peakFrequencies[bin] = frequency(bin)
        invalidSamples = false
        analyzedFrames = frames
        boundaryObservationsAvailable = observeBoundaries
        val pad = (size - frames) / 2
        val responseOffset = (size - frames) / 2 + (frames - 1) / 2.0 - (size - 1) / 2.0
        if (frames >= hop && frames < size) {
            // A complete Hann over the available block avoids hard truncation
            // edges and leakage from louder, unrelated bands into quiet peaks.
            for (i in 0 until frames) partialWindow[pad + i] = 0.5 - 0.5 * cos(2 * PI * i / (frames - 1))
            analysisWindow = partialWindow
            responseShift = size.toDouble() / (frames - 1)
            responseCos = 1.0; responseSin = 0.0
        } else {
            analysisWindow = window
            responseShift = size.toDouble() / (size - 1)
            responseCos = cos(2 * PI * responseOffset / (size - 1))
            responseSin = sin(2 * PI * responseOffset / (size - 1))
        }
        edgeBins = ceil(2.0 * size / frames.coerceAtLeast(hop)).toInt()
        responseGain = partialHannResponse(0.0)
        shortBlockPeakPower = 0.0
        // Very short blocks stay centred under the full window to avoid a zero-gain onset.
        var gain = 0.0
        for (i in 0 until frames) gain += analysisWindow[pad + i]
        val scale = 4.0 / (gain * gain)
        repeat(channels) { channel ->
            real.fill(0.0); imaginary.fill(0.0)
            var mean = 0.0
            var firstSum = 0.0; var firstSquared = 0.0; var lastSquared = 0.0
            val firstFrames = frames / 2
            var minimum = Double.POSITIVE_INFINITY
            var maximum = Double.NEGATIVE_INFINITY
            for (i in 0 until frames) {
                val value = sample(bytes, i * frameBytes + channel * format.bytesPerSample)
                if (!value.isFinite()) invalidSamples = true
                val safe = if (value.isFinite()) value else 0.0
                samples[i] = safe
                if (observeBoundaries) {
                    minimum = minOf(minimum, safe); maximum = maxOf(maximum, safe)
                    if (i < firstFrames) { firstSum += safe; firstSquared += safe * safe }
                    else lastSquared += safe * safe
                }
                shortBlockPeakPower = max(shortBlockPeakPower, safe * safe)
                real[pad + i] = safe; mean += safe
            }
            if (observeBoundaries) {
                val amplitude = (maximum - minimum) / 2
                observedAmplitudePowers[channel] = amplitude * amplitude
                val lastSum = mean - firstSum
                val lastFrames = frames - firstFrames
                val dc = mean / frames
                // Both halves use the same observed DC. Independently centering
                // each half can mistake a stationary slow tone for a transient.
                firstHalfPowers[channel] = if (firstFrames == 0) 0.0 else
                    (firstSquared / firstFrames - 2 * dc * firstSum / firstFrames + dc * dc).coerceAtLeast(0.0)
                lastHalfPowers[channel] =
                    (lastSquared / lastFrames - 2 * dc * lastSum / lastFrames + dc * dc).coerceAtLeast(0.0)
            }
            mean /= frames
            for (i in 0 until frames) real[pad + i] = (real[pad + i] - mean) * analysisWindow[pad + i]
            fft()
            for (i in powers.indices) {
                // DC and Nyquist have no mirrored negative-frequency partner to fold in.
                val binScale = if (i == 0 || i == powers.lastIndex) scale * 0.25 else scale
                channelPowers[i] = (real[i] * real[i] + imaginary[i] * imaginary[i]) * binScale
            }
            for (i in powers.indices) {
                var power = channelPowers[i]
                var hz = frequency(i)
                // A Hann-windowed tone between FFT bins loses up to 1.42 dB at the
                // strongest bin. Estimate its lobe centre and undo that window gain,
                // separately per channel so stereo neighbours cannot invent a peak.
                // Keep DC/Nyquist edge lobes on their existing one-sided calibration.
                if (frames >= hop && i in 2 until powers.lastIndex - 1 && power > 0.0 &&
                    power >= channelPowers[i - 1] && power >= channelPowers[i + 1]) {
                    val left = sqrt(channelPowers[i - 1])
                    val centre = sqrt(power)
                    val right = sqrt(channelPowers[i + 1])
                    val delta = if (frames == size)
                        (2.0 * (right - left) / (left + 2.0 * centre + right)).coerceIn(-0.5, 0.5)
                    else {
                        // Every partial-window peak uses its actual Hann
                        // response, so a louder unrelated band cannot own calibration.
                        var low = -0.5; var high = 0.5
                        repeat(16) {
                            val middle = (low + high) / 2
                            if (right * partialHannResponse(1 + middle) > left * partialHannResponse(1 - middle))
                                low = middle else high = middle
                        }
                        (low + high) / 2
                    }
                    val distance = abs(delta)
                    val gain = if (frames < size) partialHannResponse(delta) / responseGain else
                        if (distance < 1e-8) 1.0 else sin(PI * distance) / (PI * distance * (1.0 - distance * distance))
                    power /= gain * gain
                    hz = (i + delta) * sampleRate / size
                    // The finite Hann window uses N-1 while the lobe formula
                    // uses N. Its sub-bin frequency error must not move a real
                    // supported endpoint just outside the detection band.
                    val error = sampleRate.toDouble() / size / (size - 1)
                    if (hz > SILENCE_MAX_HZ && hz - SILENCE_MAX_HZ <= error) hz = SILENCE_MAX_HZ.toDouble()
                    if (hz < SILENCE_MIN_HZ && SILENCE_MIN_HZ - hz <= error) hz = SILENCE_MIN_HZ.toDouble()
                }
                if (power > powers[i]) {
                    powers[i] = power
                    peakFrequencies[i] = hz
                }
                detectedPowers[channel * powers.size + i] = power
                detectedFrequencies[channel * powers.size + i] = hz
            }
            if (frames >= hop) calibrateEdgeTone(channel, frames)
        }
        powers.fill(0.0)
        for (channel in 0 until channels) for (bin in powers.indices) {
            val index = channel * powers.size + bin
            if (detectedPowers[index] > powers[bin]) {
                powers[bin] = detectedPowers[index]
                peakFrequencies[bin] = detectedFrequencies[index]
            }
        }
        for (index in fittedPowers.indices) {
            val bin = (fittedFrequencies[index] * size / sampleRate).roundToInt().coerceIn(1, powers.lastIndex)
            if (fittedPowers[index] > powers[bin]) {
                powers[bin] = fittedPowers[index]
                peakFrequencies[bin] = fittedFrequencies[index]
            }
        }
    }

    /** Only a localized transient grants an extra boundary observation. Stationary
     * tones stay on the calibrated original window, avoiding extension sidelobes. */
    fun boundaryTransientChannels(atStart: Boolean, minimumPower: Double): Int {
        if (!boundaryObservationsAvailable || analyzedFrames < 2) return 0
        var eligible = 0
        for (channel in 0 until channels) {
            val edge = if (atStart) firstHalfPowers[channel] else lastHalfPowers[channel]
            val interior = if (atStart) lastHalfPowers[channel] else firstHalfPowers[channel]
            if (observedAmplitudePowers[channel] > minimumPower && edge > interior * 16.0)
                eligible = eligible or (1 shl channel)
        }
        return eligible
    }

    /** Exact response of the centred analysis window, including its zero padding. */
    private fun partialHannResponse(offset: Double): Double {
        val lower = partialDirichlet(offset - responseShift)
        val upper = partialDirichlet(offset + responseShift)
        val real = 0.5 * partialDirichlet(offset) + 0.25 * responseCos * (lower + upper)
        val imaginary = 0.25 * responseSin * (lower - upper)
        return sqrt(real * real + imaginary * imaginary)
    }

    private fun partialDirichlet(offset: Double): Double = if (abs(offset) < 1e-8) analyzedFrames.toDouble()
        else sin(PI * analyzedFrames * offset / size) / sin(PI * offset / size)

    // Conjugate Hann lobes overlap near DC/Nyquist, invalidating interior-bin
    // interpolation. Fit one coherent edge tone with a DC offset instead. The
    // fit is attempted only when an edge dominates, and uses fixed bounded work.
    private fun calibrateEdgeTone(channel: Int, frames: Int) {
        var lowPower = 0.0; var highPower = 0.0
        for (bin in 1..edgeBins) {
            lowPower = max(lowPower, channelPowers[bin])
            highPower = max(highPower, channelPowers[powers.lastIndex - bin + 1])
        }
        val low = lowPower > channelPowers[edgeBins + 1]
        val high = sampleRate / 2 <= SILENCE_MAX_HZ &&
            highPower > channelPowers[powers.lastIndex - edgeBins]
        if (!low && !high) return
        // Fit under the same Hann observation as the FFT. A rectangular fit can
        // correlate a distant stationary tone with a low edge tone at a partial
        // boundary, then manufacture a louder residual when that fit is removed.
        val pad = (size - frames) / 2
        var weight = 0.0
        var weightedSum = 0.0
        for (i in 0 until frames) {
            val w = analysisWindow[pad + i]
            weight += w
            weightedSum += w * samples[i]
        }
        val weightedMean = weightedSum / weight
        var energy = 0.0
        for (i in 0 until frames) {
            val value = samples[i] - weightedMean
            energy += analysisWindow[pad + i] * value * value
        }
        if (energy <= 1e-24) return
        // The two edges are independent. A quiet low-frequency companion must
        // not prevent a dominant near-Nyquist tone from being calibrated.
        if (low && calibrateEdgeTone(channel, frames, weightedMean, energy, low = true)) return
        if (high && calibrateEdgeTone(channel, frames, weightedMean, energy, low = false)) return
    }

    private fun calibrateEdgeTone(channel: Int, frames: Int, mean: Double, energy: Double, low: Boolean): Boolean {
        val binWidth = sampleRate.toDouble() / size
        var left = if (low) 0.1 else sampleRate / 2.0 - (edgeBins + 1) * binWidth
        var right = if (low) (edgeBins + 1) * binWidth else sampleRate / 2.0 - 0.00001
        // The widened edge band can contain sidelobe optima. Select the basin
        // on a half-bin grid before refining; an endpoint must not be discarded
        // merely because the first two probes landed outside its main lobe.
        val start = left
        val span = right - left
        val segments = ceil(span / (binWidth / 2)).toInt().coerceAtLeast(1)
        var best = 0
        var bestEnergy = -1.0
        for (segment in 0..segments) {
            fitTone(start + span * segment / segments, frames, mean)
            if (fittedEnergy > bestEnergy) {
                bestEnergy = fittedEnergy
                best = segment
            }
        }
        left = start + span * (best - 1).coerceAtLeast(0) / segments
        right = start + span * (best + 1).coerceAtMost(segments) / segments
        val ratio = 0.6180339887498949
        var a = right - ratio * (right - left)
        var b = left + ratio * (right - left)
        fitTone(a, frames, mean)
        var energyA = fittedEnergy; var powerA = fittedPower
        var cosineA = fittedCosine; var sineA = fittedSine
        fitTone(b, frames, mean)
        var energyB = fittedEnergy; var powerB = fittedPower
        var cosineB = fittedCosine; var sineB = fittedSine
        repeat(32) {
            if (energyA > energyB) {
                right = b; b = a; energyB = energyA; powerB = powerA
                cosineB = cosineA; sineB = sineA
                a = right - ratio * (right - left)
                fitTone(a, frames, mean); energyA = fittedEnergy; powerA = fittedPower
                cosineA = fittedCosine; sineA = fittedSine
            } else {
                left = a; a = b; energyA = energyB; powerA = powerB
                cosineA = cosineB; sineA = sineB
                b = left + ratio * (right - left)
                fitTone(b, frames, mean); energyB = fittedEnergy; powerB = fittedPower
                cosineB = fittedCosine; sineB = fittedSine
            }
        }
        var hz = if (energyA > energyB) a else b
        var power = if (energyA > energyB) powerA else powerB
        var cosine = if (energyA > energyB) cosineA else cosineB
        var sine = if (energyA > energyB) sineA else sineB
        val explained = max(energyA, energyB)
        if (explained <= 0.0 || !power.isFinite()) return false
        if (low && hz < SILENCE_MIN_HZ) {
            fitTone(SILENCE_MIN_HZ.toDouble(), frames, mean)
            // Noise can move a boundary tone slightly outside the supported band.
            // Keep the boundary interpretation only when it explains the same data.
            val uncertainty = 9.0 * (energy - explained).coerceAtLeast(0.0) / (frames - 3)
            if (explained - fittedEnergy <= uncertainty) {
                hz = SILENCE_MIN_HZ.toDouble(); power = fittedPower
                cosine = fittedCosine; sine = fittedSine
            }
        }
        if (!low) {
            val span = (PI - 2 * PI * hz / sampleRate) * (frames - 1)
            if (span < PI) {
                // At an unresolved Nyquist endpoint the sine coefficient can grow
                // arbitrarily while the observed envelope stays quiet. Use the
                // fitted envelope actually present in this finite block instead.
                val phase = atan2(sine, cosine)
                val extremum = ceil(phase / PI) * PI - phase
                if (extremum > span) {
                    val end = cosine * cos(span) - sine * sin(span)
                    power = max(cosine * cosine, end * end)
                }
            }
        }
        val frequency = if (abs(hz - SILENCE_MIN_HZ) < 0.0001) SILENCE_MIN_HZ.toDouble() else hz
        // Only an isolated tone grants authority to remove its window sidelobes.
        // Mixtures retain other audio as residual observations alongside this local projection.
        val isolated = explained >= energy * (1.0 - 1e-10)
        if (isolated) {
            fittedPowers.fill(0.0, channel * 2, channel * 2 + 2)
            for (i in 1..powers.lastIndex) detectedPowers[channel * powers.size + i] = 0.0
        } else {
            val first = if (low) 1 else powers.lastIndex - edgeBins
            val last = if (low) edgeBins else powers.lastIndex
            retainProjectedResidual(channel, frames, hz, cosine, sine, first, last)
        }
        val index = channel * 2 + if (low) 0 else 1
        fittedPowers[index] = power
        fittedFrequencies[index] = frequency
        return isolated
    }

    /** Replace the component's conjugate/window lobes with its independent peak,
     * while preserving other audio as complex residual observations in those bins. */
    private fun retainProjectedResidual(channel: Int, frames: Int, hz: Double, cosine: Double, sine: Double,
                                        first: Int, last: Int) {
        val angle = 2 * PI * hz / sampleRate
        val stepCos = cos(angle); val stepSin = sin(angle)
        val sum = sin(frames * angle / 2) / sin(angle / 2)
        val phase = (frames - 1) * angle / 2
        val mean = sum * (cosine * cos(phase) + sine * sin(phase)) / frames
        val pad = (size - frames) / 2
        var c = 1.0; var s = 0.0; var gain = 0.0
        for (i in 0 until frames) {
            val weight = analysisWindow[pad + i]
            fittedSamples[i] = (cosine * c + sine * s - mean) * weight
            gain += weight
            val nextC = c * stepCos - s * stepSin
            s = s * stepCos + c * stepSin; c = nextC
        }
        val scale = 4.0 / (gain * gain)
        for (bin in first..last) {
            val binAngle = -2 * PI * bin / size
            val binCos = cos(binAngle); val binSin = sin(binAngle)
            c = cos(binAngle * pad); s = sin(binAngle * pad)
            var projectedReal = 0.0; var projectedImaginary = 0.0
            for (i in 0 until frames) {
                projectedReal += fittedSamples[i] * c; projectedImaginary += fittedSamples[i] * s
                val nextC = c * binCos - s * binSin
                s = s * binCos + c * binSin; c = nextC
            }
            real[bin] -= projectedReal; imaginary[bin] -= projectedImaginary
            val binScale = if (bin == powers.lastIndex) scale * 0.25 else scale
            val index = channel * powers.size + bin
            detectedPowers[index] = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * binScale
            detectedFrequencies[index] = frequency(bin)
        }
    }

    private fun fitTone(hz: Double, frames: Int, mean: Double) {
        val angle = 2 * PI * hz / sampleRate
        val stepCos = cos(angle); val stepSin = sin(angle)
        var c = 1.0; var s = 0.0
        var cc = 0.0; var ss = 0.0; var cs = 0.0
        var sumC = 0.0; var sumS = 0.0; var xc = 0.0; var xs = 0.0
        var weight = 0.0
        var sumX = 0.0
        val pad = (size - frames) / 2
        for (i in 0 until frames) {
            val w = analysisWindow[pad + i]
            val value = samples[i] - mean
            weight += w; sumX += w * value
            cc += w * c * c; ss += w * s * s; cs += w * c * s
            sumC += w * c; sumS += w * s; xc += w * value * c; xs += w * value * s
            val nextC = c * stepCos - s * stepSin
            s = s * stepCos + c * stepSin; c = nextC
        }
        cc -= sumC * sumC / weight; ss -= sumS * sumS / weight
        cs -= sumC * sumS / weight
        xc -= sumX * sumC / weight; xs -= sumX * sumS / weight
        val determinant = cc * ss - cs * cs
        val trace = cc + ss
        val condition = determinant / (trace * trace)
        if (!condition.isFinite() || condition <= 1e-12) {
            fittedEnergy = 0.0; fittedPower = 0.0; fittedCosine = 0.0; fittedSine = 0.0; return
        }
        val cosine = (xc * ss - xs * cs) / determinant
        val sine = (xs * cc - xc * cs) / determinant
        fittedCosine = cosine; fittedSine = sine
        fittedPower = cosine * cosine + sine * sine
        fittedEnergy = cosine * xc + sine * xs
    }

    fun frequency(bin: Int): Double = bin.toDouble() * sampleRate / size
    fun exceeds(config: SilenceGateConfig): Boolean {
        return exceeds(thresholdPowers(config), config)
    }

    fun thresholdPowers(config: SilenceGateConfig): DoubleArray = DoubleArray(powers.size) { bin ->
        val hz = frequency(bin)
        if (hz in SILENCE_MIN_HZ.toDouble()..SILENCE_MAX_HZ.toDouble()) 10.0.pow(config.threshold(hz) / 10.0)
        else Double.POSITIVE_INFINITY
    }

    fun exceeds(thresholds: DoubleArray, config: SilenceGateConfig? = null): Boolean {
        if (invalidSamples) return true // Never silently discard malformed input as silence.
        val flat = config != null && config.points.all { it.db == config.points.first().db }
        // A sub-window cannot resolve every frequency. Only a peak below every
        // possible cutoff proves it quiet; retain unresolved higher content.
        if (config != null && analyzedFrames < hop &&
            shortBlockPeakPower > 10.0.pow(config.points.minOf { it.db } / 10.0)) return true
        for (channel in 0 until channels) for (bin in 1 until powers.size) {
            val index = channel * powers.size + bin
            val hz = detectedFrequencies[index]
            if (hz !in SILENCE_MIN_HZ.toDouble()..SILENCE_MAX_HZ.toDouble()) continue
            val threshold = if (config != null && (!flat || !thresholds[bin].isFinite()) && hz != frequency(bin))
                10.0.pow(config.threshold(hz) / 10.0) else thresholds[bin]
            if (detectedPowers[index] > threshold) return true
        }
        for (index in fittedPowers.indices) {
            val hz = fittedFrequencies[index]
            if (fittedPowers[index] <= 0.0 || hz !in SILENCE_MIN_HZ.toDouble()..SILENCE_MAX_HZ.toDouble()) continue
            val bin = (hz * size / sampleRate).roundToInt().coerceIn(1, powers.lastIndex)
            val threshold = if (config != null) 10.0.pow(config.threshold(hz) / 10.0) else thresholds[bin]
            if (fittedPowers[index] > threshold) return true
        }
        return false
    }

    fun graph(): List<Float> {
        val output = FloatArray(SILENCE_GRAPH_BINS) { -120f }
        for (channel in 0 until channels) for (bin in 1 until powers.size) {
            val index = channel * powers.size + bin
            val hz = detectedFrequencies[index]
            if (hz > SILENCE_MAX_HZ) continue
            if (hz < SILENCE_MIN_HZ) continue
            val bucket = (silenceFrequencyFraction(hz) * (output.size - 1)).roundToInt()
            output[bucket] = max(output[bucket], (10 * log10(detectedPowers[index].coerceAtLeast(1e-12))).toFloat())
        }
        for (index in fittedPowers.indices) {
            val hz = fittedFrequencies[index]
            if (fittedPowers[index] <= 0.0 || hz !in SILENCE_MIN_HZ.toDouble()..SILENCE_MAX_HZ.toDouble()) continue
            val bucket = (silenceFrequencyFraction(hz) * (output.size - 1)).roundToInt()
            output[bucket] = max(output[bucket], (10 * log10(fittedPowers[index].coerceAtLeast(1e-12))).toFloat())
        }
        return output.toList()
    }

    private fun sample(bytes: ByteArray, offset: Int): Double = when (format) {
        PcmSampleFormat.PCM_8 -> ((bytes[offset].toInt() and 255) - 128) / 128.0
        PcmSampleFormat.PCM_16 -> {
            val lo = bytes[offset + if (littleEndian) 0 else 1].toInt() and 255
            val hi = bytes[offset + if (littleEndian) 1 else 0].toInt()
            ((hi shl 8) or lo).toShort() / 32768.0
        }
        PcmSampleFormat.PCM_FLOAT -> {
            var bits = 0
            repeat(4) { i -> bits = bits or ((bytes[offset + if (littleEndian) i else 3 - i].toInt() and 255) shl (i * 8)) }
            Float.fromBits(bits).toDouble()
        }
    }

    private fun fft() {
        for (i in real.indices) if (reverse[i] > i) {
            val j = reverse[i]
            val r = real[i]; real[i] = real[j]; real[j] = r
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                for (j in 0 until half) {
                    val a = start + j; val b = a + half
                    val twiddle = j * step
                    val tr = cosine[twiddle] * real[b] - sine[twiddle] * imaginary[b]
                    val ti = cosine[twiddle] * imaginary[b] + sine[twiddle] * real[b]
                    real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                    real[a] += tr; imaginary[a] += ti
                }
                start += length
            }
            length *= 2
        }
    }
}

internal interface SilenceGateOutput {
    fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long)
    fun gap()
}

/** One overlapping analysis window of latency, bounded memory, and byte-exact retained PCM. */
internal class SilenceGateStream(
    val sampleRate: Int,
    val channels: Int,
    val format: PcmSampleFormat,
    val config: SilenceGateConfig,
    private val output: SilenceGateOutput,
    private val onSpectrum: ((SilenceSpectrum, Int) -> Unit)? = null,
) {
    private val spectrum = SilenceSpectrum(sampleRate, channels, format)
    private val thresholds = spectrum.thresholdPowers(config.validate())
    private val buffer = ByteArray(spectrum.size * spectrum.frameBytes)
    private val boundaryBuffer by lazy { ByteArray(buffer.size) }
    private val minimumThresholdPower = 10.0.pow(config.points.minOf { it.db } / 10.0)
    private var frames = 0
    private var sourceFrame = 0L
    private var holdFrames = 0L
    private var lastKept = true // The caller may already have an open pre-gate capture span.
    private var finished = false
    private var failed = false
    var retainedFrames = 0L
        private set
    var skippedFrames = 0L
        private set

    init { config.validate() }

    fun accept(bytes: ByteArray, offset: Int, count: Int) {
        check(!finished && !failed)
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count && count % spectrum.frameBytes == 0)
        var cursor = offset
        val end = offset + count
        try {
            while (cursor < end) {
                val copied = minOf(end - cursor, (spectrum.size - frames) * spectrum.frameBytes)
                bytes.copyInto(buffer, frames * spectrum.frameBytes, cursor, cursor + copied)
                frames += copied / spectrum.frameBytes
                cursor += copied
                if (frames == spectrum.size) emit(spectrum.hop)
            }
        } catch (error: Throwable) { failed = true; throw error }
    }

    private fun boundaryExceeds(atStart: Boolean, eligibleChannels: Int): Boolean {
        val boundary = boundaryBuffer
        boundary.fill(if (format == PcmSampleFormat.PCM_8) 128.toByte() else 0)
        val count = minOf(frames, spectrum.hop)
        val first = if (atStart) 0 else frames - count
        val destination = if (atStart) spectrum.hop else spectrum.hop - count
        for (frame in 0 until spectrum.size) {
            val source = (first + frame - destination).coerceIn(0, frames - 1)
            for (channel in 0 until channels) if ((eligibleChannels and (1 shl channel)) != 0) {
                val offset = source * spectrum.frameBytes + channel * format.bytesPerSample
                buffer.copyInto(boundary, frame * spectrum.frameBytes + channel * format.bytesPerSample,
                    offset, offset + format.bytesPerSample)
            }
        }
        spectrum.analyze(boundary, spectrum.size)
        onSpectrum?.invoke(spectrum, 0)
        return config.enabled && spectrum.exceeds(thresholds, config)
    }

    private fun emit(count: Int) {
        spectrum.analyze(buffer, frames,
            observeBoundaries = (config.enabled || onSpectrum != null) && (sourceFrame == 0L || finished))
        onSpectrum?.invoke(spectrum, count)
        var triggered = config.enabled && spectrum.exceeds(thresholds, config)
        // A localized onset/tail has no overlapping neighbour outside the stream.
        // Move only eligible transient channels to the window centre; stationary
        // channels must not gain artificial extension sidelobes. Require an actual
        // observed amplitude above the minimum cutoff as well as a strong energy
        // concentration before this extra observation can retain audio.
        // Snapshot both masks before the extra analysis replaces spectrum state.
        if (config.enabled || onSpectrum != null) {
            val startChannels = if (sourceFrame == 0L)
                spectrum.boundaryTransientChannels(atStart = true, minimumThresholdPower) else 0
            val endChannels = if (finished)
                spectrum.boundaryTransientChannels(atStart = false, minimumThresholdPower) else 0
            if (startChannels != 0) triggered = boundaryExceeds(atStart = true, startChannels) || triggered
            if (endChannels != 0) triggered = boundaryExceeds(atStart = false, endChannels) || triggered
        }
        val keep = !config.enabled || triggered || holdFrames > 0L
        if (triggered) holdFrames = sampleRate.toLong() * SILENCE_HOLD_MILLIS / 1000
        else holdFrames = (holdFrames - count).coerceAtLeast(0)
        if (keep) {
            output.audio(buffer, 0, count * spectrum.frameBytes, sourceFrame)
            retainedFrames += count
        } else {
            if (lastKept) output.gap()
            skippedFrames += count
        }
        lastKept = keep
        sourceFrame += count
        val bytes = count * spectrum.frameBytes
        buffer.copyInto(buffer, 0, bytes, frames * spectrum.frameBytes)
        frames -= count
    }

    /** Flush already accepted PCM once at Stop/export/config/destination boundaries. */
    fun finish() {
        if (finished) return
        finished = true
        if (failed) return // An uncertain append cannot be replayed.
        if (frames > 0) try { emit(frames) } catch (error: Throwable) { failed = true; throw error }
    }
}

internal fun sampledSilenceCurve(peaks: List<Float>, maxHz: Int): List<SilencePoint> {
    require(peaks.size == SILENCE_GRAPH_BINS && peaks.all { it.isFinite() })
    require(maxHz in SILENCE_MIN_HZ..SILENCE_MAX_HZ)
    val frequencies = listOf(20, 40, 80, 160, 315, 630, 1_250, 2_500, 5_000, 10_000, 20_000)
    return frequencies.mapIndexed { index, hz ->
        // Adjacent points both cover the interval: interpolation cannot undercut measured room peaks.
        val low = frequencies[(index - 1).coerceAtLeast(0)]
        val high = frequencies[(index + 1).coerceAtMost(frequencies.lastIndex)]
        val first = floor(silenceFrequencyFraction(low.toDouble()) * (peaks.size - 1)).toInt()
        val last = ceil(silenceFrequencyFraction(minOf(high, maxHz).toDouble()) * (peaks.size - 1)).toInt()
        // The first knot beyond Nyquist still bounds interpolation inside the measured band.
        // Only knots whose entire adjacent interval is above it use the unmeasured default.
        val measured = if (low > maxHz || first > last) -56f else (first..last).maxOf { peaks[it] }
        SilencePoint(hz, (measured + 6).roundToInt().coerceIn(SILENCE_MIN_DB, -6))
    }
}
