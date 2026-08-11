package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVisualizationAnalyzerTest {
    @Test
    fun pcm16Tone_producesActivityAndSpectrum() {
        val analyzer = AudioVisualizationAnalyzer()
        val pcm = pcm16Tone(frequencyHz = 1_000.0, amplitude = 0.35)

        val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, SAMPLE_RATE.toInt())

        assertTrue(frame.activity > 0.1f)
        assertTrue(frame.bins.maxOrNull()!! > 0.05f)
        assertEquals(AudioVisualizationAnalyzer.OUTPUT_BINS, frame.bins.size)
    }

    @Test
    fun silence_staysFlat() {
        val analyzer = AudioVisualizationAnalyzer()
        val silence = ByteArray(2_048)

        val frame = analyzer.analyze(silence, 0, silence.size, PcmSampleFormat.PCM_16, 1, SAMPLE_RATE.toInt())

        assertEquals(0f, frame.activity, 0f)
        assertTrue(frame.bins.all { it == 0f })
    }

    @Test
    fun allSupportedPcmFormats_feedTheVisualizer() {
        val analyzer = AudioVisualizationAnalyzer()

        val pcm8 = ByteArray(SAMPLE_COUNT) { index ->
            (128 + sin(2.0 * PI * 700.0 * index / SAMPLE_RATE) * 50.0).toInt().toByte()
        }
        assertTrue(analyzer.analyze(pcm8, 0, pcm8.size, PcmSampleFormat.PCM_8, 1, SAMPLE_RATE.toInt()).activity > 0.1f)

        analyzer.reset()
        val floatPcm = ByteBuffer.allocate(SAMPLE_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(SAMPLE_COUNT) { index ->
                    putFloat((sin(2.0 * PI * 1_400.0 * index / SAMPLE_RATE) * 0.25).toFloat())
                }
            }
            .array()
        assertTrue(analyzer.analyze(floatPcm, 0, floatPcm.size, PcmSampleFormat.PCM_FLOAT, 1, SAMPLE_RATE.toInt()).activity > 0.1f)
    }

    private fun pcm16Tone(frequencyHz: Double, amplitude: Double): ByteArray {
        return ByteBuffer.allocate(SAMPLE_COUNT * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(SAMPLE_COUNT) { index ->
                    putShort(
                        (sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE) * amplitude * Short.MAX_VALUE)
                            .toInt()
                            .toShort(),
                    )
                }
            }
            .array()
    }

    companion object {
        private const val SAMPLE_RATE = 48_000.0
        private const val SAMPLE_COUNT = 4_096
    }
}
