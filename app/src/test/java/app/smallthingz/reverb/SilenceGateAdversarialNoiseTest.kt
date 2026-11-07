package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Actual gate characterization for short room samples and speech-like mixtures. */
class SilenceGateAdversarialNoiseTest {
    private val rate = 16_000
    private fun pcm(seconds: Double, signal: (Int) -> Double): ByteArray =
        ByteBuffer.allocate((rate * seconds).toInt() * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(capacity() / 4) { putFloat(signal(it).toFloat()) }
        }.array()
    private fun noise(seconds: Double, seed: Long, pink: Boolean): ByteArray {
        val random = Random(seed)
        var low = 0.0
        var slower = 0.0
        return pcm(seconds) {
            val white = random.nextGaussian()
            low = .9 * low + .1 * white
            slower = .99 * slower + .01 * white
            // Deterministic low-frequency-weighted fan-like noise, not claimed ideal 1/f.
            .005 * if (pink) white * .2 + low + slower * 2 else white
        }
    }
    private data class Result(val kept: Long, val skipped: Long, val spans: List<Pair<Long, Int>>)
    private fun run(source: ByteArray, config: SilenceGateConfig, batch: Int): Result {
        val spans = mutableListOf<Pair<Long, Int>>()
        val stream = SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT, config,
            object : SilenceGateOutput {
                override fun gap() = Unit
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                    spans += sourceFrame to count / 4
                    for (i in 0 until count) assertEquals(source[sourceFrame.toInt() * 4 + i], bytes[offset + i])
                }
            })
        var cursor = 0
        while (cursor < source.size) {
            val count = minOf(batch * 4, source.size - cursor)
            stream.accept(source, cursor, count)
            cursor += count
        }
        stream.finish()
        assertEquals(source.size / 4L, stream.retainedFrames + stream.skippedFrames)
        return Result(stream.retainedFrames, stream.skippedFrames, spans)
    }
    private fun calibrate(source: ByteArray, clearance: Float): SilenceGateConfig {
        val peaks = FloatArray(SILENCE_GRAPH_BINS) { -120f }
        val sampler = SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT, SilenceGateConfig(),
            object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
                override fun gap() = Unit
            }, onSpectrum = { spectrum, _ -> spectrum.accumulateGraphPeaks(peaks) })
        sampler.accept(source, 0, source.size)
        sampler.finish()
        return SilenceGateConfig(true, sampledSilenceCurve(peaks.toList(), rate / 2, clearance), clearance)
    }
    @Test fun fanLikeNoiseCharacterizationAcrossSampleLengthsAndExistingClearance() {
        for (pink in listOf(false, true)) for (seconds in listOf(.5, 3.5)) for (clearance in listOf(6f, 12f)) {
            val config = calibrate(noise(seconds, 741, pink), clearance)
            val source = noise(8.0, 1907, pink)
            val foreground = run(source, config, 128)
            val background = run(source, config, rate)
            assertEquals(foreground, background)
            // Characterize before setting a product false-positive budget; do not assume stochastic zero.
            println("fanLike=$pink calibrationSeconds=$seconds clearance=$clearance kept=${foreground.kept}")
        }
    }
    @Test fun multipleSubThresholdTonesAndSpeechEnvelopeAreBatchInvariant() {
        val config = SilenceGateConfig(true, listOf(SilencePoint(20,-40), SilencePoint(20_000,-40)))
        for (phase in listOf(0.0, PI / 4, PI / 2)) {
            val source = pcm(5.0) { frame ->
                val time = frame.toDouble() / rate
                val speech = if (time in 1.0..2.0) .03 * sin(2 * PI * 173.3 * time) *
                    (.6 + .4 * sin(2 * PI * 4 * time)) else 0.0
                speech + .004 * sin(2 * PI * 121.7 * time + phase) +
                    .004 * sin(2 * PI * 1003.1 * time - phase)
            }
            val result = run(source, config, 128)
            assertEquals(result, run(source, config, rate))
            assertTrue("Speech must retain PCM", result.kept > rate / 2)
            assertTrue("Room tone alone must not retain the entire stream", result.skipped > rate)
        }
    }
}
