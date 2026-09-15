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
    fun perceivedLoudness_usesLogarithmicDbScaling() {
        val analyzer = AudioVisualizationAnalyzer()
        val reference = 0.001f

        val fiveDb = analyzer.perceivedLoudnessLevel(reference * 1.7782794f, reference, 30f)
        val tenDb = analyzer.perceivedLoudnessLevel(reference * 3.1622777f, reference, 30f)
        val twentyDb = analyzer.perceivedLoudnessLevel(reference * 10f, reference, 30f)

        assertEquals(1f / 6f, fiveDb, 0.01f)
        assertEquals(1f / 3f, tenDb, 0.01f)
        assertEquals(2f / 3f, twentyDb, 0.01f)
        assertTrue(twentyDb < tenDb * 2.1f)
    }

    @Test
    fun perceivedLoudness_aWeightsHumanHearingSensitivity() {
        val analyzer = AudioVisualizationAnalyzer()

        val bass100Hz = analyzer.aWeightingGain(100f)
        val reference1Khz = analyzer.aWeightingGain(1_000f)
        val presence4Khz = analyzer.aWeightingGain(4_000f)

        assertTrue(bass100Hz < reference1Khz * 0.25f)
        assertEquals(1f, reference1Khz, 0.08f)
        assertTrue(presence4Khz >= reference1Khz)
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
    fun quietVoiceLevelInput_isStillVisible() {
        val analyzer = AudioVisualizationAnalyzer()
        val pcm = pcm16Tone(frequencyHz = 440.0, amplitude = 0.02)

        val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, SAMPLE_RATE.toInt())

        assertTrue(frame.activity > 0.02f)
        assertTrue(frame.bins.maxOrNull()!! > 0.01f)
    }

    @Test
    fun veryQuietPhoneMicLevelInput_isVisible() {
        val analyzer = AudioVisualizationAnalyzer()
        val pcm = pcm16Tone(frequencyHz = 440.0, amplitude = 0.002)

        val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, SAMPLE_RATE.toInt())

        assertTrue(frame.activity > 0.04f)
        assertTrue(frame.bins.maxOrNull()!! > 0.02f)
    }

    @Test
    fun transientOutsideFinalFftWindow_stillMovesBlob() {
        val analyzer = AudioVisualizationAnalyzer()
        val samples = ShortArray(7_056)
        repeat(1_024) { index ->
            samples[index] = (sin(2.0 * PI * 700.0 * index / SAMPLE_RATE) * 0.02 * Short.MAX_VALUE)
                .toInt()
                .toShort()
        }
        val pcm = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putShort) }
            .array()

        val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, SAMPLE_RATE.toInt())

        assertTrue(frame.activity > 0.08f)
        assertTrue(frame.bins.maxOrNull()!! > 0.015f)
    }


    @Test
    fun oneLowLatencyCaptureSlice_movesBlobOnFirstFrame() {
        val analyzer = AudioVisualizationAnalyzer()
        val silence = ByteArray(384 * Short.SIZE_BYTES)
        analyzer.analyze(silence, 0, silence.size, PcmSampleFormat.PCM_16, 1, SAMPLE_RATE.toInt())
        val pcm = pcm16Tone(frequencyHz = 700.0, amplitude = 0.01, sampleCount = 384)

        val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, SAMPLE_RATE.toInt())

        assertTrue(frame.activity > 0.35f)
        assertTrue(frame.bins.maxOrNull()!! > 0.12f)
    }

    @Test
    fun highSampleRateInput_stillUsesSpeechEnergy() {
        val analyzer = AudioVisualizationAnalyzer()
        val sampleRate = 192_000.0
        val pcm = pcm16Tone(frequencyHz = 1_000.0, amplitude = 0.25, sampleRate = sampleRate)

        val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, sampleRate.toInt())

        assertTrue(frame.activity > 0.1f)
        assertTrue(frame.bins.maxOrNull()!! > 0.05f)
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

    private fun pcm16Tone(
        frequencyHz: Double,
        amplitude: Double,
        sampleRate: Double = SAMPLE_RATE,
        sampleCount: Int = SAMPLE_COUNT,
    ): ByteArray {
        return ByteBuffer.allocate(sampleCount * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(sampleCount) { index ->
                    putShort(
                        (sin(2.0 * PI * frequencyHz * index / sampleRate) * amplitude * Short.MAX_VALUE)
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
