package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class AudioVisualizationStereoTest {
    private val rate = 48_000

    private fun pcm(format: PcmSampleFormat, frames: Int, channels: Int,
        sample: (Int, Int) -> Double): ByteArray =
        ByteBuffer.allocate(12 + frames * channels * format.bytesPerSample).order(ByteOrder.nativeOrder()).apply {
            position(12)
            repeat(frames) { frame -> repeat(channels) { channel ->
                val value = sample(frame, channel)
                when (format) {
                    PcmSampleFormat.PCM_8 -> put((128 + (value * 128).roundToInt()).toByte())
                    PcmSampleFormat.PCM_16 -> putShort((value * 32768).roundToInt().toShort())
                    PcmSampleFormat.PCM_FLOAT -> putFloat(value.toFloat())
                }
            } }
        }.array()

    private fun analyze(analyzer: AudioVisualizationAnalyzer, bytes: ByteArray,
        format: PcmSampleFormat, channels: Int): ReverbService.VisualizationFrame =
        analyzer.analyze(bytes, 12, bytes.size - 12, format, channels, rate)

    private fun assertFrame(expected: ReverbService.VisualizationFrame,
        actual: ReverbService.VisualizationFrame, label: String) {
        assertEquals("$label activity", expected.activity, actual.activity, 0.00001f)
        assertArrayEquals("$label bins", expected.bins, actual.bins, 0.00001f)
    }

    @Test fun oppositePhaseAndOneSidedStereoRemainAsVisibleAsMono() {
        for (format in PcmSampleFormat.entries) for (frames in listOf(93, 256, 4096)) {
            val tone = { i: Int -> 0.02 * sin(2 * PI * 1234 * i / rate + 0.7) }
            val monoPcm = pcm(format, frames, 1) { i, _ -> tone(i) }
            for (mode in listOf("same", "opposite", "left", "right")) {
                val stereoPcm = pcm(format, frames, 2) { i, channel ->
                    when {
                        mode == "opposite" && channel == 1 -> -tone(i)
                        mode == "left" && channel == 1 || mode == "right" && channel == 0 -> 0.0
                        else -> tone(i)
                    }
                }
                val mono = AudioVisualizationAnalyzer()
                val stereo = AudioVisualizationAnalyzer()
                repeat(5) {
                    assertFrame(analyze(mono, monoPcm, format, 1), analyze(stereo, stereoPcm, format, 2),
                        "$format/$frames/$mode/$it")
                }
            }
        }
    }

    @Test fun independentStereoTonesAreInvariantToChannelSwapAndPolarity() {
        for (frames in listOf(127, 256, 1024)) {
            val left = { i: Int -> 0.007 * sin(2 * PI * 713 * i / rate + 0.31) }
            val right = { i: Int -> 0.011 * sin(2 * PI * 6347 * i / rate + 1.7) }
            val reference = pcm(PcmSampleFormat.PCM_FLOAT, frames, 2) { i, c -> if (c == 0) left(i) else right(i) }
            val swapped = pcm(PcmSampleFormat.PCM_FLOAT, frames, 2) { i, c -> if (c == 0) -right(i) else left(i) }
            val a = AudioVisualizationAnalyzer()
            val b = AudioVisualizationAnalyzer()
            repeat(5) {
                assertFrame(analyze(a, reference, PcmSampleFormat.PCM_FLOAT, 2),
                    analyze(b, swapped, PcmSampleFormat.PCM_FLOAT, 2), "swap/$frames/$it")
            }
        }
    }

    @Test fun packedPowersMatchIndependentMonoTransformsIncludingDcAndNyquist() {
        // Inspect the existing FFT work arrays to compare against two independent
        // mono transforms, including bins outside the final displayed speech bands.
        val realField = AudioVisualizationAnalyzer::class.java.getDeclaredField("real").apply { isAccessible = true }
        val imaginaryField = AudioVisualizationAnalyzer::class.java.getDeclaredField("imaginary").apply { isAccessible = true }
        val powerMethod = AudioVisualizationAnalyzer::class.java.getDeclaredMethod("spectrumPower",
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        for (format in PcmSampleFormat.entries) for (frames in listOf(1, 93, 256, 4096)) {
            for (kind in listOf("dc", "nyquist", "tones")) {
                val signal = { i: Int, c: Int -> when (kind) {
                    "dc" -> if (c == 0) 0.03 else -0.07
                    "nyquist" -> (if (i % 2 == 0) 1 else -1) * (if (c == 0) 0.04 else -0.08)
                    else -> if (c == 0) 0.08 * sin(2 * PI * 731 * i / rate + 0.4)
                        else 0.03 * sin(2 * PI * 5432 * i / rate + 1.7)
                } }
                val left = AudioVisualizationAnalyzer()
                val right = AudioVisualizationAnalyzer()
                val stereo = AudioVisualizationAnalyzer()
                analyze(left, pcm(format, frames, 1) { i, _ -> signal(i, 0) }, format, 1)
                analyze(right, pcm(format, frames, 1) { i, _ -> signal(i, 1) }, format, 1)
                analyze(stereo, pcm(format, frames, 2, signal), format, 2)
                val lr = realField.get(left) as FloatArray
                val li = imaginaryField.get(left) as FloatArray
                val rr = realField.get(right) as FloatArray
                val ri = imaginaryField.get(right) as FloatArray
                for (bin in 0..lr.size / 2) {
                    val expected = max(lr[bin] * lr[bin] + li[bin] * li[bin], rr[bin] * rr[bin] + ri[bin] * ri[bin])
                    val actual = powerMethod.invoke(stereo, bin, true) as Float
                    assertEquals("$format/$frames/$kind/bin=$bin", expected, actual, max(1e-8f, expected * 1e-4f))
                }
            }
        }
    }

    @Test fun switchingFromStereoToMonoCannotLeakThePreviousImaginaryInput() {
        val format = PcmSampleFormat.PCM_FLOAT
        val stereo = pcm(format, 256, 2) { i, c -> 0.05 * sin(2 * PI * (700 + c * 2000) * i / rate) }
        val mono = pcm(format, 93, 1) { i, _ -> 0.02 * sin(2 * PI * 1200 * i / rate) }
        val reused = AudioVisualizationAnalyzer()
        analyze(reused, stereo, format, 2)
        // Reset removes smoothing history as well as the previous packed FFT input.
        reused.reset()
        assertFrame(analyze(AudioVisualizationAnalyzer(), mono, format, 1), analyze(reused, mono, format, 1), "reset mono")
        val left = AudioVisualizationAnalyzer()
        val right = AudioVisualizationAnalyzer()
        analyze(left, stereo, format, 2)
        analyze(right, stereo, format, 2)
        // Both have identical smoothing history; a zero imaginary channel is the mono oracle.
        val stereoMono = pcm(format, 93, 2) { i, c -> if (c == 0) 0.02 * sin(2 * PI * 1200 * i / rate) else 0.0 }
        assertFrame(analyze(left, stereoMono, format, 2), analyze(right, mono, format, 1), "stereo to mono")
    }

    @Test fun malformedStereoFloatsMatchExplicitFiniteSanitization() {
        val values = doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.0, -2.0, 0.01, -0.02)
        val raw = pcm(PcmSampleFormat.PCM_FLOAT, 385, 2) { i, c -> values[(i + c) % values.size] }
        val sanitized = pcm(PcmSampleFormat.PCM_FLOAT, 385, 2) { i, c ->
            val value = values[(i + c) % values.size]
            if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
        }
        val a = AudioVisualizationAnalyzer()
        val b = AudioVisualizationAnalyzer()
        repeat(3) {
            assertFrame(analyze(a, sanitized, PcmSampleFormat.PCM_FLOAT, 2),
                analyze(b, raw, PcmSampleFormat.PCM_FLOAT, 2), "sanitization/$it")
        }
    }

    @Test fun legacyMultichannelFallbackStillMatchesItsMonoAverage() {
        val format = PcmSampleFormat.PCM_FLOAT
        val source = pcm(format, 1024, 3) { i, c -> (c - 1.0) * 0.02 * sin(2 * PI * 700 * i / rate) }
        val mono = pcm(format, 1024, 1) { _, _ -> 0.0 }
        assertFrame(analyze(AudioVisualizationAnalyzer(), mono, format, 1),
            analyze(AudioVisualizationAnalyzer(), source, format, 3), "legacy three-channel cancellation")
    }
}
