package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed-level correlated noise and manual-curve diagnostics using the actual gate. */
class SilenceGateNoiseDiagnosticTest {
    private val rate = 16_000
    private val discard = object : SilenceGateOutput {
        override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
        override fun gap() = Unit
    }

    private fun noise(seconds: Int, seed: Long, kind: String): ByteArray {
        val random = Random(seed)
        val hop = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).hop
        val values = DoubleArray(rate * seconds)
        // A bounded six-pole mixture supplies correlated low-frequency noise.
        val poles = doubleArrayOf(0.0, 0.5, 0.9, 0.98, 0.995, 0.999)
        val states = DoubleArray(poles.size)
        for (i in values.indices) {
            val white = random.nextGaussian()
            var pink = 0.0
            for (j in poles.indices) {
                states[j] = poles[j] * states[j] + sqrt(1.0 - poles[j] * poles[j]) * white
                pink += states[j]
            }
            values[i] = when (kind) {
                "pink" -> pink
                "hum" -> 2.0 * sin(2.0 * PI * 60 * i / rate) +
                    0.7 * sin(2.0 * PI * 120 * i / rate) + white * 0.15
                else -> white
            }
        }
        // Exactly equal RMS per hop removes overall level drift as an explanation.
        for (start in values.indices step hop) {
            val end = minOf(start + hop, values.size)
            var energy = 0.0
            for (i in start until end) energy += values[i] * values[i]
            val scale = 0.01 / sqrt(energy / (end - start))
            for (i in start until end) values[i] *= scale
        }
        return ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).apply {
            values.forEach { putFloat(it.toFloat()) }
        }.array()
    }

    private fun peaks(source: ByteArray): List<Float> {
        val peaks = FloatArray(SILENCE_GRAPH_BINS) { -120f }
        val stream = SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT,
            SilenceGateConfig(), discard, onSpectrum = { spectrum, _ -> spectrum.accumulateGraphPeaks(peaks) })
        stream.accept(source, 0, source.size)
        stream.finish()
        return peaks.toList()
    }

    private data class Result(val retained: Long, val crossings: Int, val gaps: Int,
        val opens: Int, val longestKept: Long)

    private fun observe(source: ByteArray, config: SilenceGateConfig): Result {
        var crossings = 0
        var gaps = 0
        var opens = 0
        var previousEnd = -1L
        var run = 0L
        var longest = 0L
        val thresholds = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).thresholdPowers(config)
        val stream = SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT, config,
            object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                    if (sourceFrame != previousEnd) { opens++; run = 0 }
                    run += count / 4
                    longest = maxOf(longest, run)
                    previousEnd = sourceFrame + count / 4
                }
                override fun gap() { gaps++ }
            }, onSpectrum = { spectrum, count ->
                if (count > 0 && spectrum.exceeds(thresholds, config)) crossings++
            })
        stream.accept(source, 0, source.size)
        stream.finish()
        assertEquals(source.size / 4L, stream.retainedFrames + stream.skippedFrames)
        return Result(stream.retainedFrames, crossings, gaps, opens, longest)
    }

    @Test fun compareCalibratedAndManualThresholdsOnConstantLevelNoise() {
        for (kind in listOf("pink", "hum")) {
            val measured = peaks(noise(3, 741L, kind))
            val source = noise(27, 742L, kind)
            for (clearance in listOf(-6f, 0f, 6f, 18f)) {
                val curve = SilenceGateConfig(true, sampledSilenceCurve(measured, rate / 2, clearance), clearance)
                val result = observe(source, curve)
                println("DIAGNOSTIC kind=$kind clearance=$clearance $result curve=${curve.points}")
                if (clearance >= 6f) assertEquals("$kind clearance=$clearance", 0L, result.retained)
            }
            for (db in listOf(-52, -49, -46)) {
                val curve = SilenceGateConfig(true, listOf(SilencePoint(20, db), SilencePoint(20_000, db)))
                println("DIAGNOSTIC kind=$kind manual=$db ${observe(source, curve)}")
            }
        }
    }
}
