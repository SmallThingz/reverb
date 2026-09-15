package app.smallthingz.reverb

import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
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
    private val smoothedBins = FloatArray(OUTPUT_BINS)
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

    private var smoothedActivity = 0f
    private var noiseFloor = INITIAL_NOISE_FLOOR

    fun reset() {
        real.fill(0f)
        imaginary.fill(0f)
        smoothedBins.fill(0f)
        waveformSums.fill(0f)
        waveformCounts.fill(0)
        smoothedActivity = 0f
        noiseFloor = INITIAL_NOISE_FLOOR
    }

    fun analyze(
        array: ByteArray,
        offset: Int,
        count: Int,
        sampleFormat: PcmSampleFormat,
        channelCount: Int,
        sampleRate: Int,
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
        val fftFrameCount = minOf(availableFrames, FFT_SIZE)
        val firstFftFrame = availableFrames - fftFrameCount
        val destinationOffset = (FFT_SIZE - fftFrameCount) / 2
        var sumSquares = 0.0

        for (frameIndex in 0 until availableFrames) {
            val byteIndex = offset + frameIndex * bytesPerFrame
            var monoSample = 0f
            for (channel in 0 until channels) {
                monoSample += readSample(
                    array = array,
                    index = byteIndex + channel * sampleFormat.bytesPerSample,
                    sampleFormat = sampleFormat,
                )
            }
            monoSample = (monoSample / channels).coerceIn(-1f, 1f)
            sumSquares += monoSample * monoSample
            val envelopeBin = (frameIndex * OUTPUT_BINS / availableFrames).coerceIn(0, OUTPUT_BINS - 1)
            waveformSums[envelopeBin] += kotlin.math.abs(monoSample)
            waveformCounts[envelopeBin]++
            if (frameIndex >= firstFftFrame) {
                val fftIndex = destinationOffset + frameIndex - firstFftFrame
                real[fftIndex] = monoSample * window[fftIndex]
            }
        }

        runFft()

        val rms = sqrt(sumSquares / availableFrames).toFloat()
        updateNoiseFloor(rms)
        val activityGate = noiseFloor * ACTIVITY_NOISE_MULTIPLIER + ABSOLUTE_ACTIVITY_GATE
        // Human loudness is roughly logarithmic in acoustic amplitude. Drive the blob in
        // decibels above the adaptive room/device floor so a 10x PCM amplitude jump does not
        // become a 10x visual jump, while quiet speech still remains visible.
        val targetActivity = perceivedLoudnessLevel(
            signal = rms,
            reference = activityGate,
            dynamicRangeDb = ACTIVITY_DYNAMIC_RANGE_DB,
        )
        smoothedActivity = if (targetActivity > smoothedActivity) {
            smoothedActivity * ACTIVITY_ATTACK_OLD + targetActivity * (1f - ACTIVITY_ATTACK_OLD)
        } else {
            smoothedActivity * ACTIVITY_RELEASE_OLD + targetActivity * (1f - ACTIVITY_RELEASE_OLD)
        }

        val fractionalMaxBin = ((FFT_SIZE / 2 - 1) * SPECTRUM_FRACTION).toInt()
        val speechMaxBin = if (sampleRate > 0) {
            (SPEECH_MAX_HZ * FFT_SIZE / sampleRate).coerceAtLeast(2)
        } else {
            fractionalMaxBin
        }
        val maxSourceBin = minOf(fractionalMaxBin, speechMaxBin).coerceAtLeast(2)
        for (outputIndex in 0 until OUTPUT_BINS) {
            val sourceStart = 1 + outputIndex * (maxSourceBin - 1) / OUTPUT_BINS
            val sourceEndExclusive = max(
                sourceStart + 1,
                1 + (outputIndex + 1) * (maxSourceBin - 1) / OUTPUT_BINS,
            )
            var magnitudeSum = 0f
            var magnitudeCount = 0
            for (sourceIndex in sourceStart until sourceEndExclusive.coerceAtMost(maxSourceBin + 1)) {
                val re = real[sourceIndex]
                val im = imaginary[sourceIndex]
                magnitudeSum += sqrt(re * re + im * im)
                magnitudeCount++
            }
            val normalized = if (magnitudeCount > 0) {
                (magnitudeSum / magnitudeCount) / (FFT_SIZE * 0.5f)
            } else {
                0f
            }
            val spectrumGate = max(MIN_SPECTRUM_GATE, noiseFloor * SPECTRUM_NOISE_MULTIPLIER)
            val sourceCenter = (sourceStart + sourceEndExclusive.coerceAtMost(maxSourceBin + 1) - 1) * 0.5f
            val centerFrequencyHz = if (sampleRate > 0) sourceCenter * sampleRate / FFT_SIZE else 1_000f
            val spectrum = perceivedLoudnessLevel(
                signal = normalized,
                reference = spectrumGate,
                dynamicRangeDb = SPECTRUM_DYNAMIC_RANGE_DB,
            ) * aWeightingGain(centerFrequencyHz)
            val waveformAverage = if (waveformCounts[outputIndex] > 0) {
                waveformSums[outputIndex] / waveformCounts[outputIndex]
            } else {
                0f
            }
            val waveform = perceivedLoudnessLevel(
                signal = waveformAverage,
                reference = noiseFloor * WAVEFORM_NOISE_MULTIPLIER + ABSOLUTE_WAVEFORM_GATE,
                dynamicRangeDb = WAVEFORM_DYNAMIC_RANGE_DB,
            )
            val shaped = max(spectrum, waveform * (0.72f + smoothedActivity * 0.35f))
            smoothedBins[outputIndex] =
                smoothedBins[outputIndex] * BIN_SMOOTHING + shaped * (1f - BIN_SMOOTHING)
        }

        return ReverbService.VisualizationFrame(
            activity = smoothedActivity,
            bins = smoothedBins.copyOf(),
        )
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

    private fun updateNoiseFloor(rms: Float) {
        val target = rms.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        val mix = if (target < noiseFloor) NOISE_FLOOR_FALL_MIX else NOISE_FLOOR_RISE_MIX
        noiseFloor += (target - noiseFloor) * mix
        noiseFloor = noiseFloor.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
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
            Float.fromBits(bits).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        }
    }

    companion object {
        const val OUTPUT_BINS = 16
        private const val FFT_SIZE = 512
        private const val FFT_BITS = 9
        // Phone microphone capture can legitimately sit around 5e-4 RMS for normal room audio.
        // Reactivity is expressed in dB above that adaptive floor, matching perceived loudness
        // much better than linear PCM amplitude. Frequency deformation gets A-weighted as well.
        private const val ACTIVITY_NOISE_MULTIPLIER = 1.25f
        private const val ABSOLUTE_ACTIVITY_GATE = 0.00005f
        private const val ACTIVITY_DYNAMIC_RANGE_DB = 30f
        private const val SPECTRUM_FRACTION = 0.42f
        private const val SPEECH_MAX_HZ = 10_000
        private const val SPECTRUM_DYNAMIC_RANGE_DB = 32f
        private const val MIN_SPECTRUM_GATE = 0.000025f
        private const val SPECTRUM_NOISE_MULTIPLIER = 0.08f
        private const val WAVEFORM_DYNAMIC_RANGE_DB = 28f
        private const val WAVEFORM_NOISE_MULTIPLIER = 0.85f
        private const val ABSOLUTE_WAVEFORM_GATE = 0.000025f
        private const val PERCEPTUAL_EPSILON = 1e-7f
        private const val MIN_A_WEIGHT_GAIN = 0.08f
        private const val MAX_A_WEIGHT_GAIN = 1.15f
        private const val LN_10 = 2.302585092994046
        private const val BIN_SMOOTHING = 0.62f
        private const val ACTIVITY_ATTACK_OLD = 0.42f
        private const val ACTIVITY_RELEASE_OLD = 0.84f
        private const val INITIAL_NOISE_FLOOR = 0.00055f
        private const val MIN_NOISE_FLOOR = 0.00005f
        private const val MAX_NOISE_FLOOR = 0.02f
        private const val NOISE_FLOOR_FALL_MIX = 0.16f
        private const val NOISE_FLOOR_RISE_MIX = 0.004f
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
