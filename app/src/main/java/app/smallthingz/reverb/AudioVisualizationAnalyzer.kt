package app.smallthingz.reverb

import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
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
    private var sequence = 0L

    fun reset() {
        real.fill(0f)
        imaginary.fill(0f)
        smoothedBins.fill(0f)
        waveformSums.fill(0f)
        waveformCounts.fill(0)
        smoothedActivity = 0f
        noiseFloor = INITIAL_NOISE_FLOOR
        sequence = 0L
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

        val frameCount = minOf(availableFrames, FFT_SIZE)
        val firstFrame = availableFrames - frameCount
        val destinationOffset = (FFT_SIZE - frameCount) / 2
        var sumSquares = 0.0

        for (frameIndex in 0 until frameCount) {
            val byteIndex = offset + (firstFrame + frameIndex) * bytesPerFrame
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
            val envelopeBin = (frameIndex * OUTPUT_BINS / frameCount).coerceIn(0, OUTPUT_BINS - 1)
            waveformSums[envelopeBin] += kotlin.math.abs(monoSample)
            waveformCounts[envelopeBin]++
            val fftIndex = destinationOffset + frameIndex
            real[fftIndex] = monoSample * window[fftIndex]
        }

        runFft()

        val rms = sqrt(sumSquares / frameCount).toFloat()
        if (rms < max(0.08f, noiseFloor * 2.5f)) {
            noiseFloor = (noiseFloor * 0.992f + rms * 0.008f)
                .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        }
        val targetActivity = ((rms - noiseFloor) * RMS_SENSITIVITY).coerceIn(0f, 1f)
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
            val cleaned = (normalized * SPECTRUM_GAIN - NOISE_FLOOR).coerceAtLeast(0f)
            val spectrum = cleaned.coerceAtMost(1f).pow(SPECTRUM_CURVE)
            val waveformAverage = if (waveformCounts[outputIndex] > 0) {
                waveformSums[outputIndex] / waveformCounts[outputIndex]
            } else {
                0f
            }
            val waveform = ((waveformAverage - noiseFloor * 0.55f) * WAVEFORM_GAIN)
                .coerceIn(0f, 1f)
                .pow(WAVEFORM_CURVE)
            val shaped = max(spectrum, waveform * (0.72f + smoothedActivity * 0.35f))
            smoothedBins[outputIndex] =
                smoothedBins[outputIndex] * BIN_SMOOTHING + shaped * (1f - BIN_SMOOTHING)
        }

        sequence++
        return ReverbService.VisualizationFrame(
            activity = smoothedActivity,
            bins = smoothedBins.copyOf(),
            sequence = sequence,
        )
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
        const val OUTPUT_BINS = 64
        private const val FFT_SIZE = 512
        private const val FFT_BITS = 9
        private const val RMS_SENSITIVITY = 13.5f
        private const val SPECTRUM_FRACTION = 0.42f
        private const val SPEECH_MAX_HZ = 10_000
        private const val SPECTRUM_GAIN = 11.0f
        private const val SPECTRUM_CURVE = 0.82f
        private const val NOISE_FLOOR = 0.02f
        private const val WAVEFORM_GAIN = 9.0f
        private const val WAVEFORM_CURVE = 0.72f
        private const val BIN_SMOOTHING = 0.72f
        private const val ACTIVITY_ATTACK_OLD = 0.55f
        private const val ACTIVITY_RELEASE_OLD = 0.88f
        private const val INITIAL_NOISE_FLOOR = 0.008f
        private const val MIN_NOISE_FLOOR = 0.0015f
        private const val MAX_NOISE_FLOOR = 0.045f
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
