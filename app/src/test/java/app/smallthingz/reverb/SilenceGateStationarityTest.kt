package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Real detector regressions for stationary stochastic input, rather than only pure tones. */
class SilenceGateStationarityTest {
    private val discard = object : SilenceGateOutput {
        override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
        override fun gap() = Unit
    }

    private fun fixedRmsNoise(rate: Int, seconds: Int, seed: Long): ByteArray {
        val hop = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).hop
        val random = Random(seed)
        val block = DoubleArray(hop)
        val result = ByteBuffer.allocate(rate * seconds * 4).order(ByteOrder.nativeOrder())
        while (result.hasRemaining()) {
            val count = minOf(hop, result.remaining() / 4)
            var sum = 0.0
            repeat(count) { index ->
                block[index] = random.nextGaussian()
                sum += block[index]
            }
            val mean = sum / count
            var energy = 0.0
            repeat(count) { index ->
                block[index] -= mean
                energy += block[index] * block[index]
            }
            val scale = 0.01 / sqrt(energy / count)
            repeat(count) { result.putFloat((block[it] * scale).toFloat()) }
        }
        return result.array()
    }

    private fun calibration(rate: Int, source: ByteArray): SilenceGateConfig {
        val peaks = FloatArray(SILENCE_GRAPH_BINS) { -120f }
        val stream = SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT,
            SilenceGateConfig(), discard, onSpectrum = { spectrum, _ -> spectrum.accumulateGraphPeaks(peaks) })
        stream.accept(source, 0, source.size)
        stream.finish()
        return SilenceGateConfig(true, sampledSilenceCurve(peaks.toList(), minOf(rate / 2, SILENCE_MAX_HZ)))
    }

    private data class Observation(val retained: Long, val skipped: Long, val crossings: Int,
        val gaps: Int, val spans: List<Pair<Long, Int>>)

    private fun observe(rate: Int, source: ByteArray, config: SilenceGateConfig, batch: Int): Observation {
        var crossings = 0
        var gaps = 0
        val spans = mutableListOf<Pair<Long, Int>>()
        val thresholds = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).thresholdPowers(config)
        val stream = SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT, config,
            object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                    spans += sourceFrame to count / 4
                    for (i in 0 until count) assertEquals(source[sourceFrame.toInt() * 4 + i], bytes[offset + i])
                }
                override fun gap() { gaps++ }
            }, onSpectrum = { spectrum, count ->
                if (count > 0 && spectrum.exceeds(thresholds, config)) crossings++
            })
        var offset = 0
        while (offset < source.size) {
            val count = minOf(batch * 4, source.size - offset)
            stream.accept(source, offset, count)
            offset += count
        }
        stream.finish()
        assertEquals(source.size / 4L, stream.retainedFrames + stream.skippedFrames)
        return Observation(stream.retainedFrames, stream.skippedFrames, crossings, gaps, spans)
    }

    @Test fun calibratedStationaryNoiseStaysSuppressedAcrossIndependentWindowsAndRates() {
        for (rate in listOf(16_000, 44_100, 48_000)) for (seed in listOf(741L, 1907L)) {
            val config = calibration(rate, fixedRmsNoise(rate, 3, seed))
            val source = fixedRmsNoise(rate, 27, seed + 1)
            val observed = observe(rate, source, config, rate)
            val label = "rate=$rate seed=$seed crossings=${observed.crossings} gaps=${observed.gaps} retained=${observed.retained}"
            println("Stationary calibrated noise: $label")
            assertEquals(label, 0L, observed.retained)
            assertEquals(label, 0, observed.crossings)
        }
    }

    @Test fun stationaryNoiseDecisionsAndSourceSpansDoNotDependOnReadBatching() {
        val rate = 16_000
        val source = fixedRmsNoise(rate, 5, 741)
        // Deliberately near the strongest stochastic bins to exercise both states.
        val config = SilenceGateConfig(true, listOf(SilencePoint(20, -52), SilencePoint(20_000, -52)))
        val foreground = observe(rate, source, config, 128)
        val background = observe(rate, source, config, rate)
        println("Stationary near-threshold noise: $foreground")
        assertEquals(foreground, background)
    }
}
