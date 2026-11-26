package app.smallthingz.reverb

import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, allocation-light PCM analyser used only while the capture UI is visible.
 *
 * The recorder owns the microphone. This analyser taps the recorder's existing PCM
 * chunks, so enabling the visualizer never creates a second AudioRecord instance.
 */
internal class AudioVisualizationAnalyzer {
    private val real = FloatArray(FFT_SIZE)
    private val imaginary = FloatArray(FFT_SIZE)
    private val shapeBins = FloatArray(OUTPUT_BINS)
    private val waveformSums = FloatArray(OUTPUT_BINS)
    private val waveformCounts = IntArray(OUTPUT_BINS)
    private val window = FloatArray(FFT_SIZE) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))).toFloat()
    }
    private val bitReverse = IntArray(FFT_SIZE) { reverseBits(it, FFT_BITS) }
    private val cosine = FloatArray(FFT_SIZE / 2) { index ->
        cos(-2.0 * PI * index / FFT_SIZE).toFloat()
    }
    private val sine = FloatArray(FFT_SIZE / 2) { index ->
        sin(-2.0 * PI * index / FFT_SIZE).toFloat()
    }
    private val spectrumStarts = IntArray(OUTPUT_BINS)
    private val spectrumEndsExclusive = IntArray(OUTPUT_BINS)
    private val aWeightingGains = FloatArray(OUTPUT_BINS)
    private var spectrumLayoutSampleRate = Int.MIN_VALUE
    private var spectrumLayoutMaxSourceBin = Int.MIN_VALUE

    // Two high-pass sections reject offset and rumble; a low-pass reduces mic hiss.
    // These are relative digital levels, independent of the handset's unknown acoustic gain.
    private class ListeningFilter {
        private var initialized = false
        private var low = 0f
        private var secondLow = 0f
        private var output = 0f

        fun reset() {
            initialized = false
        }

        fun sample(input: Float, highPassMix: Float, lowPassMix: Float): Float {
            if (!initialized) {
                low = input
                secondLow = 0f
                output = 0f
                initialized = true
            }
            low += highPassMix * (input - low)
            val high = input - low
            secondLow += highPassMix * (high - secondLow)
            output += lowPassMix * (high - secondLow - output)
            return output
        }
    }

    private val leftFilter = ListeningFilter()
    private val rightFilter = ListeningFilter()
    private var filterRate = 0
    private var highPassMix = 0f
    private var lowPassMix = 0f
    private var rightFilterActive = false
    private var smoothedActivity = 0f
    private var backgroundSeconds = 0.0
    private var candidateRms = 0f
    private var noiseFloor = INITIAL_NOISE_FLOOR

    fun reset() {
        real.fill(0f)
        imaginary.fill(0f)
        shapeBins.fill(0f)
        waveformSums.fill(0f)
        waveformCounts.fill(0)
        smoothedActivity = 0f
        backgroundSeconds = 0.0
        candidateRms = 0f
        filterRate = 0
        noiseFloor = INITIAL_NOISE_FLOOR
    }

    fun analyze(
        array: ByteArray,
        offset: Int,
        count: Int,
        sampleFormat: PcmSampleFormat,
        channelCount: Int,
        sampleRate: Int,
        silenceSuppressed: Boolean = false,
        bufferSlot: ReverbService.BufferSlot? = null,
        captureCommandGeneration: Long = Long.MIN_VALUE,
        captureContinuityGeneration: Long = Long.MIN_VALUE,
    ): ReverbService.VisualizationFrame {
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerFrame = (sampleFormat.bytesPerSample * channels).coerceAtLeast(1)
        val availableFrames = count / bytesPerFrame
        if (availableFrames <= 0) return ReverbService.VisualizationFrame.EMPTY

        real.fill(0f)
        imaginary.fill(0f)
        waveformSums.fill(0f)
        waveformCounts.fill(0)

        // Capture reads are deliberately much larger than the FFT window. Use the whole read
        // for loudness/envelope so a short word or transient cannot land outside the final
        // ~10 ms FFT slice and make the blob look dead. Only the spectral FFT remains bounded.
        val rate = if (sampleRate > 0) sampleRate else 48_000
        // Keep resolution in physical Hz at high capture rates. Average groups in
        // the already low-passed tail into the same single, bounded transform.
        val groupSize = minOf(availableFrames, (rate / 48_000).coerceAtLeast(1))
        val spectralRate = rate / groupSize
        val fftFrameCount = minOf(availableFrames / groupSize, FFT_SIZE)
        val firstFftFrame = availableFrames - fftFrameCount * groupSize
        var fftIndex = (FFT_SIZE - fftFrameCount) / 2
        var groupRemaining = groupSize
        var groupLeft = 0f
        var groupRight = 0f
        var prefixLeftPower = 0.0
        var prefixRightPower = 0.0
        var sumSquares = 0.0
        var rightSumSquares = 0.0
        val stereo = channels == 2

        if (rate != filterRate) {
            filterRate = rate
            highPassMix = (1.0 - exp(-2 * PI * 220 / rate)).toFloat()
            lowPassMix = (1.0 - exp(-2 * PI * 4_000 / rate)).toFloat()
            leftFilter.reset()
            rightFilter.reset()
            rightFilterActive = false
        }
        rightFilterActive = rightFilterActive || stereo

        for (frameIndex in 0 until availableFrames) {
            val byteIndex = offset + frameIndex * bytesPerFrame
            val leftSample: Float
            var rightSample = 0f
            if (stereo) {
                leftSample = readSample(array, byteIndex, sampleFormat)
                rightSample = readSample(array, byteIndex + sampleFormat.bytesPerSample, sampleFormat)
            } else {
                // Preserve mono and the legacy fallback for unsupported channel counts.
                var monoSample = 0f
                for (channel in 0 until channels) {
                    monoSample += readSample(
                        array = array,
                        index = byteIndex + channel * sampleFormat.bytesPerSample,
                        sampleFormat = sampleFormat,
                    )
                }
                leftSample = (monoSample / channels).coerceIn(-1f, 1f)
            }
            val left = leftFilter.sample(leftSample, highPassMix, lowPassMix)
            val right = if (rightFilterActive) rightFilter.sample(rightSample, highPassMix, lowPassMix) else 0f
            if (frameIndex == firstFftFrame) {
                prefixLeftPower = sumSquares
                prefixRightPower = rightSumSquares
            }
            sumSquares += left * left
            rightSumSquares += right * right
            val envelope = max(kotlin.math.abs(left), kotlin.math.abs(right))
            val envelopeBin = (frameIndex * OUTPUT_BINS / availableFrames).coerceIn(0, OUTPUT_BINS - 1)
            waveformSums[envelopeBin] += envelope
            waveformCounts[envelopeBin]++
            if (frameIndex >= firstFftFrame) {
                groupLeft += left
                groupRight += right
                groupRemaining--
                if (groupRemaining == 0) {
                    val scale = window[fftIndex] / groupSize
                    real[fftIndex] = groupLeft * scale
                    imaginary[fftIndex] = groupRight * scale
                    fftIndex++
                    groupRemaining = groupSize
                    groupLeft = 0f
                    groupRight = 0f
                }
            }
        }

        runFft()

        val rms = sqrt(max(sumSquares, rightSumSquares) / availableFrames).toFloat()
        val seconds = availableFrames.toDouble() / rate
        val activityGate = noiseFloor * ACTIVITY_NOISE_MULTIPLIER + ABSOLUTE_ACTIVITY_GATE
        // Human loudness is roughly logarithmic in acoustic amplitude. Drive the blob in
        // decibels above the adaptive room/device floor so a 10x PCM amplitude jump does not
        // become a 10x visual jump, while quiet speech still remains visible.
        val targetActivity = perceivedLoudnessLevel(
            signal = rms,
            reference = activityGate,
            dynamicRangeDb = ACTIVITY_DYNAMIC_RANGE_DB,
        )
        // An above-gate room bed needs independent evidence before moving the floor.
        // A Hann-windowed sinusoid concentrates its power in neighboring FFT bins.
        // Require dispersed energy AND a persistent, roughly stable full-read level.
        val stable = candidateRms > 0f && rms >= candidateRms * 0.75f && rms <= candidateRms * 1.33f
        if (!stable) backgroundSeconds = 0.0
        val rightDominant = rightSumSquares > sumSquares
        val tailPower = if (rightDominant) rightSumSquares - prefixRightPower else sumSquares - prefixLeftPower
        val tailRms = sqrt(tailPower / (availableFrames - firstFftFrame)).toFloat()
        val aboveGate = rms > activityGate
        if (aboveGate && tailRms >= rms * 0.75f && hasDiffuseSpectrum(fftFrameCount, rightFilterActive, rightDominant)) {
            // One tail FFT is one observation even after a very long read.
            backgroundSeconds = minOf(0.12, backgroundSeconds + minOf(seconds, 0.02))
        } else {
            backgroundSeconds = maxOf(0.0, backgroundSeconds - 2 * seconds)
        }
        candidateRms = if (stable) candidateRms + (rms - candidateRms) *
            (1.0 - exp(-seconds / 0.1)).toFloat() else rms
        if (rms > 0f && (!aboveGate || backgroundSeconds >= 0.12)) {
            val mix = (1.0 - exp(-seconds / 0.5)).toFloat()
            val backgroundRms = if (aboveGate) minOf(rms, tailRms) else rms
            noiseFloor += (backgroundRms.coerceIn(INITIAL_NOISE_FLOOR, MAX_NOISE_FLOOR) - noiseFloor) * mix
        }
        val timeConstant = if (targetActivity > smoothedActivity) 0.003 else 0.045
        val response = (1.0 - exp(-seconds / timeConstant)).toFloat()
        smoothedActivity += (targetActivity - smoothedActivity) * response

        val fractionalMaxBin = ((FFT_SIZE / 2 - 1) * SPECTRUM_FRACTION).toInt()
        val speechMaxBin = (SPEECH_MAX_HZ * FFT_SIZE / spectralRate).coerceAtLeast(2)
        val maxSourceBin = minOf(fractionalMaxBin, speechMaxBin).coerceAtLeast(2)
        ensureSpectrumLayout(spectralRate, maxSourceBin)
        var strongest = 0f
        for (outputIndex in 0 until OUTPUT_BINS) {
            val sourceStart = spectrumStarts[outputIndex]
            val sourceEndExclusive = spectrumEndsExclusive[outputIndex]
            var magnitudeSquaredSum = 0f
            var magnitudeCount = 0
            for (sourceIndex in sourceStart until sourceEndExclusive) {
                magnitudeSquaredSum += spectrumPower(sourceIndex, rightFilterActive)
                magnitudeCount++
            }
            val normalized = if (magnitudeCount > 0) {
                sqrt(magnitudeSquaredSum / magnitudeCount) / (FFT_SIZE * 0.5f)
            } else {
                0f
            }
            val waveformAverage = if (waveformCounts[outputIndex] > 0) {
                waveformSums[outputIndex] / waveformCounts[outputIndex]
            } else 0f
            // Spectrum describes the last slice. The filtered time envelope supplies
            // shape for an earlier transient even when the final FFT contains silence.
            val shape = max(normalized * aWeightingGains[outputIndex], waveformAverage * 0.25f)
            shapeBins[outputIndex] = shape
            strongest = max(strongest, shape)
        }
        for (index in shapeBins.indices) {
            shapeBins[index] = if (targetActivity > 0f && strongest > 0f) shapeBins[index] / strongest else 0f
        }

        return ReverbService.VisualizationFrame(
            activity = smoothedActivity,
            bins = shapeBins.copyOf(),
            silenceSuppressed = silenceSuppressed,
            bufferSlot = bufferSlot,
            captureCommandGeneration = captureCommandGeneration,
            captureContinuityGeneration = captureContinuityGeneration,
        )
    }


    private fun hasDiffuseSpectrum(frames: Int, stereo: Boolean, right: Boolean): Boolean {
        // Three bins cover the main Hann lobe at full resolution. Short windows
        // spread a tone farther, so compare bins one effective resolution apart.
        val stride = (FFT_SIZE + frames - 1) / frames
        var total = 0f
        var strongestGroup = 0f
        var previous = 0f
        var older = 0f
        for (bin in 1 until FFT_SIZE / 2 step stride) {
            // Inspect the channel driving RMS. Two independent steady tones on
            // opposite channels must not masquerade as broadband background.
            val mirror = FFT_SIZE - bin
            val re: Float
            val im: Float
            if (!stereo) {
                re = real[bin]
                im = imaginary[bin]
            } else if (right) {
                re = imaginary[bin] + imaginary[mirror]
                im = real[mirror] - real[bin]
            } else {
                re = real[bin] + real[mirror]
                im = imaginary[bin] - imaginary[mirror]
            }
            val power = re * re + im * im
            total += power
            strongestGroup = max(strongestGroup, older + previous + power)
            older = previous
            previous = power
        }
        return total > 0f && strongestGroup < total * 0.55f
    }

    private fun spectrumPower(bin: Int, stereo: Boolean): Float {
        val re = real[bin]
        val im = imaginary[bin]
        if (!stereo) return re * re + im * im
        // Conjugate pairs separate the packed transforms. Either channel may drive
        // a visual band, matching capture's either-channel policy rather than a mix.
        val mirror = (FFT_SIZE - bin) % FFT_SIZE
        val leftReal = (re + real[mirror]) * 0.5f
        val leftImaginary = (im - imaginary[mirror]) * 0.5f
        val rightReal = (im + imaginary[mirror]) * 0.5f
        val rightImaginary = (real[mirror] - re) * 0.5f
        return max(leftReal * leftReal + leftImaginary * leftImaginary,
            rightReal * rightReal + rightImaginary * rightImaginary)
    }

    private fun ensureSpectrumLayout(sampleRate: Int, maxSourceBin: Int) {
        if (spectrumLayoutSampleRate == sampleRate && spectrumLayoutMaxSourceBin == maxSourceBin) return
        spectrumLayoutSampleRate = sampleRate
        spectrumLayoutMaxSourceBin = maxSourceBin
        for (outputIndex in 0 until OUTPUT_BINS) {
            val start = 1 + outputIndex * (maxSourceBin - 1) / OUTPUT_BINS
            val end = max(
                start + 1,
                1 + (outputIndex + 1) * (maxSourceBin - 1) / OUTPUT_BINS,
            ).coerceAtMost(maxSourceBin + 1)
            spectrumStarts[outputIndex] = start
            spectrumEndsExclusive[outputIndex] = end
            val centerBin = (start + end - 1) * 0.5f
            val centerFrequencyHz = if (sampleRate > 0) centerBin * sampleRate / FFT_SIZE else 1_000f
            aWeightingGains[outputIndex] = aWeightingGain(centerFrequencyHz)
        }
    }

    internal fun perceivedLoudnessLevel(
        signal: Float,
        reference: Float,
        dynamicRangeDb: Float = ACTIVITY_DYNAMIC_RANGE_DB,
    ): Float {
        if (!signal.isFinite() || !reference.isFinite() || !dynamicRangeDb.isFinite() || dynamicRangeDb <= 0f) return 0f
        val safeReference = reference.coerceAtLeast(PERCEPTUAL_EPSILON)
        if (signal <= safeReference) return 0f
        val decibelsAboveFloor = (20.0 * ln((signal / safeReference).toDouble()) / LN_10).toFloat()
        return (decibelsAboveFloor / dynamicRangeDb).coerceIn(0f, 1f)
    }

    internal fun aWeightingGain(frequencyHz: Float): Float {
        if (!frequencyHz.isFinite() || frequencyHz <= 0f) return 0f
        val f = frequencyHz.toDouble()
        val f2 = f * f
        val c20 = 20.6 * 20.6
        val c107 = 107.7 * 107.7
        val c738 = 737.9 * 737.9
        val c12200 = 12_200.0 * 12_200.0
        val numerator = c12200 * f2 * f2
        val denominator = (f2 + c20) * kotlin.math.sqrt((f2 + c107) * (f2 + c738)) * (f2 + c12200)
        if (denominator <= 0.0) return 0f
        val aDb = 2.0 + 20.0 * kotlin.math.log10(numerator / denominator)
        return 10.0.pow(aDb / 20.0).toFloat().coerceIn(MIN_A_WEIGHT_GAIN, MAX_A_WEIGHT_GAIN)
    }

    private fun runFft() {
        for (index in 0 until FFT_SIZE) {
            val reversed = bitReverse[index]
            if (reversed > index) {
                val tempReal = real[index]
                real[index] = real[reversed]
                real[reversed] = tempReal
                val tempImaginary = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = tempImaginary
            }
        }

        var length = 2
        while (length <= FFT_SIZE) {
            val halfLength = length / 2
            val twiddleStep = FFT_SIZE / length
            var blockStart = 0
            while (blockStart < FFT_SIZE) {
                for (j in 0 until halfLength) {
                    val twiddleIndex = j * twiddleStep
                    val wr = cosine[twiddleIndex]
                    val wi = sine[twiddleIndex]
                    val evenIndex = blockStart + j
                    val oddIndex = evenIndex + halfLength
                    val oddReal = real[oddIndex]
                    val oddImaginary = imaginary[oddIndex]
                    val tr = wr * oddReal - wi * oddImaginary
                    val ti = wr * oddImaginary + wi * oddReal
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]
                    real[evenIndex] = evenReal + tr
                    imaginary[evenIndex] = evenImaginary + ti
                    real[oddIndex] = evenReal - tr
                    imaginary[oddIndex] = evenImaginary - ti
                }
                blockStart += length
            }
            length *= 2
        }
    }

    private fun readSample(
        array: ByteArray,
        index: Int,
        sampleFormat: PcmSampleFormat,
    ): Float = when (sampleFormat) {
        PcmSampleFormat.PCM_8 -> ((array[index].toInt() and 0xff) - 128) / 128f
        PcmSampleFormat.PCM_16 -> {
            val bits = if (NATIVE_LITTLE_ENDIAN) {
                (array[index].toInt() and 0xff) or (array[index + 1].toInt() shl 8)
            } else {
                (array[index + 1].toInt() and 0xff) or (array[index].toInt() shl 8)
            }
            bits.toShort() / 32768f
        }
        PcmSampleFormat.PCM_FLOAT -> {
            val bits = if (NATIVE_LITTLE_ENDIAN) {
                (array[index].toInt() and 0xff) or
                    ((array[index + 1].toInt() and 0xff) shl 8) or
                    ((array[index + 2].toInt() and 0xff) shl 16) or
                    (array[index + 3].toInt() shl 24)
            } else {
                (array[index + 3].toInt() and 0xff) or
                    ((array[index + 2].toInt() and 0xff) shl 8) or
                    ((array[index + 1].toInt() and 0xff) shl 16) or
                    (array[index].toInt() shl 24)
            }
            val sample = Float.fromBits(bits)
            if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
        }
    }

    companion object {
        // AudioBlobView renders eight radial bands; keep the analyzer one-to-one with the
        // renderer instead of producing sixteen bins only to max-pool adjacent pairs on main.
        const val OUTPUT_BINS = 8
        private const val FFT_SIZE = 256
        private const val FFT_BITS = 8
        // Phone microphone capture can legitimately sit around 5e-4 RMS for normal room audio.
        // Reactivity is expressed in dB above that adaptive floor, matching perceived loudness
        // much better than linear PCM amplitude. Frequency deformation gets A-weighted as well.
        private const val ACTIVITY_NOISE_MULTIPLIER = 1.25f
        private const val ABSOLUTE_ACTIVITY_GATE = 0.00005f
        private const val ACTIVITY_DYNAMIC_RANGE_DB = 30f
        private const val SPECTRUM_FRACTION = 0.42f
        private const val SPEECH_MAX_HZ = 10_000
        private const val PERCEPTUAL_EPSILON = 1e-7f
        private const val MIN_A_WEIGHT_GAIN = 0.08f
        private const val MAX_A_WEIGHT_GAIN = 1.15f
        private const val LN_10 = 2.302585092994046
        private const val INITIAL_NOISE_FLOOR = 0.00055f
        private const val MAX_NOISE_FLOOR = 0.02f
        private val NATIVE_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

        private fun reverseBits(value: Int, bitCount: Int): Int {
            var input = value
            var output = 0
            repeat(bitCount) {
                output = (output shl 1) or (input and 1)
                input = input ushr 1
            }
            return output
        }
    }
}
