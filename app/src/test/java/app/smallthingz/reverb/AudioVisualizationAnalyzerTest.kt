package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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
    fun captureMetadata_preservesAnalysisAndIndependentBins() {
        val analyzer = AudioVisualizationAnalyzer()
        val pcm = pcm16Tone(frequencyHz = 1_000.0, amplitude = 0.35)
        val sampleRate = SAMPLE_RATE.toInt()
        val expected = AudioVisualizationAnalyzer().analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, sampleRate)
        val frame = analyzer.analyze(
            pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, sampleRate,
            silenceSuppressed = true,
            bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
            captureCommandGeneration = 12L,
            captureContinuityGeneration = 7L,
        )

        assertTrue(frame.silenceSuppressed)
        assertEquals(ReverbService.BufferSlot.ONE_SHOT, frame.bufferSlot)
        assertEquals(12L, frame.captureCommandGeneration)
        assertEquals(7L, frame.captureContinuityGeneration)
        assertEquals(expected.activity, frame.activity, 0f)
        assertEquals(8, frame.bins.size)
        assertArrayEquals(expected.bins, frame.bins, 0f)

        val silence = ByteArray(pcm.size)
        val nextFrame = analyzer.analyze(silence, 0, silence.size, PcmSampleFormat.PCM_16, 1, sampleRate)
        assertNotSame(frame.bins, nextFrame.bins)
        assertArrayEquals(expected.bins, frame.bins, 0f)
        analyzer.reset()
        assertArrayEquals(expected.bins, frame.bins, 0f)
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

    @Test
    fun pcmFloatSanitization_matchesFiniteClampedInputAcrossCaptureSlices() {
        val boundaries = intArrayOf(
            0, Int.MIN_VALUE,
            1, 0x80000001.toInt(),
            0x007fffff, 0x807fffff.toInt(),
            0x00800000, 0x80800000.toInt(),
            0x3f7fffff, 0xbf7fffff.toInt(),
            0x3f800000, 0xbf800000.toInt(),
            0x3f800001, 0xbf800001.toInt(),
            0x7f7fffff, 0xff7fffff.toInt(),
            0x7f800000, 0xff800000.toInt(),
            0x7fc00000, 0xffc00000.toInt(),
            0x7f800001, 0xff800001.toInt(),
            0x7fffffff, 0xffffffff.toInt(),
        )
        val random = Random(0x50434dL)
        val bits = IntArray(4_096) { index ->
            if (index < boundaries.size) boundaries[index] else random.nextInt()
        }
        val prefixBytes = 12
        val raw = ByteBuffer.allocate(prefixBytes + bits.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                position(prefixBytes)
                bits.forEach(::putInt)
            }.array()
        val sanitized = ByteBuffer.allocate(raw.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                position(prefixBytes)
                bits.forEach { sampleBits ->
                    val value = Float.fromBits(sampleBits)
                    putFloat(when {
                        value.isNaN() || value.isInfinite() -> 0f
                        value < -1f -> -1f
                        value > 1f -> 1f
                        else -> value
                    })
                }
            }.array()

        for (channels in 1..2) {
            val actual = AudioVisualizationAnalyzer()
            val expected = AudioVisualizationAnalyzer()
            for (firstSample in bits.indices step 128) {
                val offset = prefixBytes + firstSample * Float.SIZE_BYTES
                val count = 128 * Float.SIZE_BYTES
                val actualFrame = actual.analyze(raw, offset, count, PcmSampleFormat.PCM_FLOAT, channels, 48_000)
                val expectedFrame = expected.analyze(sanitized, offset, count, PcmSampleFormat.PCM_FLOAT, channels, 48_000)
                assertEquals(expectedFrame.activity.toRawBits(), actualFrame.activity.toRawBits())
                actualFrame.bins.indices.forEach { index ->
                    assertEquals(expectedFrame.bins[index].toRawBits(), actualFrame.bins[index].toRawBits())
                }
            }
        }
    }

    private fun pcm16(frames: Int, sample: (Int) -> Double): ByteArray =
        ByteBuffer.allocate(frames * Short.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { putShort((sample(it) * Short.MAX_VALUE).toInt().toShort()) }
        }.array()

    private fun requireNeutral(frame: ReverbService.VisualizationFrame) {
        assertEquals(0f, frame.activity, 0f)
        assertTrue(frame.bins.all { it == 0f })
    }

    @Test
    fun coldRoomAndRoomReturningAfterZerosStayNeutral() {
        for (zeroReads in listOf(0, 250)) {
            val analyzer = AudioVisualizationAnalyzer()
            val zeros = ByteArray(384 * 2)
            repeat(zeroReads) {
                requireNeutral(analyzer.analyze(zeros, 0, zeros.size, PcmSampleFormat.PCM_16, 1, 48_000))
            }
            val noise = Random(9182)
            repeat(125) { read ->
                val pcm = pcm16(384) { i ->
                    val phase = 2 * PI * (384 * read + i) / 48_000
                    (noise.nextDouble() - 0.5) * 0.002 + 0.003 * sin(60 * phase) + 0.001 * sin(16_000 * phase)
                }
                requireNeutral(analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, 48_000))
            }
        }
    }

    @Test
    fun acquiresAboveGateBroadbandRoomThenShowsVoicedContrast() {
        for (zeroReads in listOf(0, 125)) {
            val analyzer = AudioVisualizationAnalyzer()
            val quiet = ByteArray(768)
            repeat(zeroReads) {
                analyzer.analyze(quiet, 0, quiet.size, PcmSampleFormat.PCM_16, 1, 48_000)
            }
            val random = Random(7833)
            var previous = 0.0
            var settledPeak = 0f
            var voicePeak = 0f
            repeat(500) { read ->
                val pcm = pcm16(384) { i ->
                    val white = 0.014 * (random.nextDouble() - 0.5)
                    val room = (previous + white) / 2
                    previous = white
                    val phase = 2 * PI * 200 * (384 * read + i) / 48_000
                    val voice = if (read in 400..424) {
                        0.006 * (sin(phase) + sin(3 * phase) + 0.5 * sin(5 * phase)) *
                            sin(PI * ((read - 400) * 384 + i) / (25 * 384))
                    } else 0.0
                    room + voice
                }
                val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, 48_000)
                if (read == 0) assertTrue("fixture must exceed the cold gate", frame.activity > 0.1f)
                if (read in 300..399 || read >= 475) settledPeak = maxOf(settledPeak, frame.activity)
                if (read in 400..424) voicePeak = maxOf(voicePeak, frame.activity)
            }
            assertTrue("settled room peak=$settledPeak", settledPeak < 0.02f)
            assertTrue("voice over acquired floor=$voicePeak", voicePeak > 0.12f)
        }
    }

    @Test
    fun fourMillisecondReadsAcquireTheSameRoomAtLowNormalAndHighRates() {
        // Interpolate a single seeded source in physical time at every capture rate.
        val random = Random(7203)
        val bed = DoubleArray(5 * 16_000 + 1) { (random.nextDouble() - 0.5) * 0.011 }
        for (rate in listOf(7_350, 16_000, 48_000, 96_000, 192_000)) {
            val analyzer = AudioVisualizationAnalyzer()
            var early = 0f
            var settled = 0f
            var voicePeak = 0f
            repeat(1_250) { read ->
                val start = read * rate / 250
                val end = (read + 1) * rate / 250
                val pcm = pcm16(end - start) { index ->
                    val t = (start + index).toDouble() / rate
                    val position = t * 16_000
                    val k = position.toInt()
                    val room = bed[k] + (bed[k + 1] - bed[k]) * (position - k)
                    val phase = 2 * PI * 180 * t
                    val envelope = if (t in 4.0..4.2) sin(PI * (t - 4.0) / 0.2) else 0.0
                    room + envelope * 0.009 * (sin(phase) + 0.6 * sin(3 * phase))
                }
                val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, rate)
                if (read < 25) early = maxOf(early, frame.activity)
                if (read in 750..999) settled += frame.activity / 250
                if (read in 1000..1049) voicePeak = maxOf(voicePeak, frame.activity)
            }
            assertTrue("above-gate room at $rate", early > 0.1f)
            assertTrue("mean settled activity at $rate = $settled", settled < 0.01f)
            assertTrue("voiced contrast at $rate = $voicePeak", voicePeak > 0.07f)
        }
    }

    @Test
    fun repeatedEarlyVoicedBurstsKeepContrastWithDiffuseQuietTails() {
        val analyzer = AudioVisualizationAnalyzer()
        val random = Random(809)
        var initial = 0f
        repeat(200) { read ->
            val pcm = pcm16(1_920) { i ->
                val burst = if (i < 960) 0.008 * sin(2 * PI * 800 * i / 48_000) else 0.0
                burst + (random.nextDouble() - 0.5) * 0.0015
            }
            val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, 48_000)
            if (read == 1) initial = frame.activity
            if (read == 199) {
                assertTrue(initial > 0.2f)
                assertEquals("eight seconds of recurring bursts", initial, frame.activity, 0.02f)
            }
        }
    }

    @Test
    fun pcm8OneCodeRoomFluctuationsSettleAndAudibleToneRemainsVisible() {
        val analyzer = AudioVisualizationAnalyzer()
        val random = Random(878)
        var settled = 0f
        var tonePeak = 0f
        repeat(500) { read ->
            val pcm = ByteArray(384) { i ->
                val room = random.nextInt(3) - 1
                val tone = if (read >= 400) (4 * sin(2 * PI * 750 * (384 * read + i) / 48_000)).toInt() else 0
                (128 + room + tone).toByte()
            }
            val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_8, 1, 48_000)
            if (read in 300..399) settled += frame.activity / 100
            if (read >= 490) tonePeak = maxOf(tonePeak, frame.activity)
        }
        assertTrue("settled one-code room=$settled", settled < 0.01f)
        assertTrue("four-code tone over room=$tonePeak", tonePeak > 0.2f)
    }

    @Test
    fun constantMicOffsetAndRumbleStayNeutral() {
        for (frequency in listOf(0, 30, 50, 60)) {
            val analyzer = AudioVisualizationAnalyzer()
            repeat(40) { read ->
                val pcm = pcm16(384) { i -> 0.1 + 0.006 * sin(2 * PI * frequency * (384 * read + i) / 48_000) }
                requireNeutral(analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, 48_000))
            }
        }
    }

    @Test
    fun midstreamBiasStepHasAnOnsetButConstantBiasDoesNotSustainMotion() {
        for (pulseFrames in listOf(1, Int.MAX_VALUE)) {
            val analyzer = AudioVisualizationAnalyzer()
            val silence = ByteArray(768)
            requireNeutral(analyzer.analyze(silence, 0, silence.size, PcmSampleFormat.PCM_16, 1, 48_000))
            repeat(64) { read ->
                val pcm = pcm16(384) { i -> if (read * 384 + i < pulseFrames) 0.1 else 0.0 }
                val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, 48_000)
                if (read == 0) assertTrue("onset pulseFrames=$pulseFrames", frame.activity > 0.1f)
                if (read == 63) {
                    // More than eleven 45 ms release constants after the discontinuity.
                    assertTrue("residual pulseFrames=$pulseFrames", frame.activity < 0.0001f)
                    assertTrue(frame.bins.all { it == 0f })
                }
            }
        }
    }

    @Test
    fun lowFundamentalVoiceWithWeakHarmonicsIsVisibleOverTheSameNoise() {
        val roomAnalyzer = AudioVisualizationAnalyzer()
        val voiceAnalyzer = AudioVisualizationAnalyzer()
        val noise = Random(1917)
        var peak = 0f
        repeat(100) { read ->
            val room = DoubleArray(384) { (noise.nextDouble() - 0.5) * 0.002 }
            val roomPcm = pcm16(384) { room[it] }
            val voicePcm = pcm16(384) { i ->
                val phase = 2 * PI * 123 * (384 * read + i) / 48_000
                val envelope = if (read in 50..74) {
                    sin(PI * ((read - 50) * 384 + i) / (25 * 384))
                } else 0.0
                room[i] + envelope * 0.006 * (sin(phase) + 0.25 * sin(2 * phase) + 0.12 * sin(3 * phase))
            }
            requireNeutral(roomAnalyzer.analyze(roomPcm, 0, roomPcm.size, PcmSampleFormat.PCM_16, 1, 48_000))
            val frame = voiceAnalyzer.analyze(voicePcm, 0, voicePcm.size, PcmSampleFormat.PCM_16, 1, 48_000)
            if (read < 50) requireNeutral(frame)
            if (read in 50..74) peak = maxOf(peak, frame.activity)
        }
        assertTrue("low fundamental peak=$peak", peak > 0.08f)
    }

    @Test
    fun quietVoicedBurstOverRoomMovesThenReleases() {
        val analyzer = AudioVisualizationAnalyzer()
        val noise = Random(109)
        var peak = 0f
        repeat(180) { read ->
            val pcm = pcm16(384) { i ->
                val t = (384 * read + i) / 48_000.0
                val voiced = if (read in 50..74) {
                    val phase = 2 * PI * 180 * t
                    0.003 * (sin(phase) + sin(3 * phase) + 0.5 * sin(6 * phase)) *
                        sin(PI * (t - 0.4) / 0.2)
                } else 0.0
                voiced + (noise.nextDouble() - 0.5) * 0.002
            }
            val frame = analyzer.analyze(pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, 48_000)
            if (read < 50) requireNeutral(frame)
            peak = maxOf(peak, frame.activity)
            if (read == 179) assertTrue(frame.activity < 0.001f)
        }
        assertTrue(peak > 0.15f)
    }

    @Test
    fun sustainedQuietToneKeepsContrastAcrossReadDurationsAndRates() {
        var reference = 0f
        for (rate in listOf(16_000, 48_000, 96_000)) {
            val pcm = pcm16(3 * rate) { i -> 0.002 * sin(2 * PI * 937 * i / rate) }
            for (ms in listOf(4, 8, 20)) {
                val analyzer = AudioVisualizationAnalyzer()
                val bytes = rate * ms / 1_000 * 2
                var onset = 0f
                var last = 0f
                for (offset in pcm.indices step bytes) {
                    last = analyzer.analyze(pcm, offset, bytes, PcmSampleFormat.PCM_16, 1, rate).activity
                    if (offset + bytes == rate * 2 / 25) onset = last // 40 ms after onset
                }
                assertTrue("rate=$rate ms=$ms activity=$last", last > 0.10f)
                // 0.02 of the 30 dB display range allows 0.6 dB of read-boundary variation.
                assertEquals(onset, last, 0.02f)
                if (rate == 16_000 && ms == 4) reference = last
                // Includes passband discretization at 16 kHz and fractional periods per read.
                assertEquals("rate=$rate ms=$ms", reference, last, 0.035f)
            }
        }
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
