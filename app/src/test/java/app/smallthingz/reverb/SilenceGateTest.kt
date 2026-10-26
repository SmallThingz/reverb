package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SilenceGateTest {
    private fun flat(db: Int, enabled: Boolean = true) = SilenceGateConfig(enabled,
        listOf(SilencePoint(20, db), SilencePoint(20_000, db)))

    private fun pcm(rate: Int, frames: Int, frequency: Double, amplitude: Double,
                    channels: Int = 1, format: PcmSampleFormat = PcmSampleFormat.PCM_16, opposite: Boolean = false,
                    phase: Double = 0.0): ByteArray {
        val buffer = ByteBuffer.allocate(frames * channels * format.bytesPerSample).order(ByteOrder.nativeOrder())
        repeat(frames) { frame -> repeat(channels) { channel ->
            val value = amplitude * sin(2 * PI * frequency * frame / rate + phase) * if (opposite && channel == 1) -1 else 1
            when (format) {
                PcmSampleFormat.PCM_8 -> buffer.put((128 + value * 127).toInt().coerceIn(0, 255).toByte())
                PcmSampleFormat.PCM_16 -> buffer.putShort((value * 32767).toInt().toShort())
                PcmSampleFormat.PCM_FLOAT -> buffer.putFloat(value.toFloat())
            }
        } }
        return buffer.array()
    }

    private data class Run(val bytes: ByteArray, val spans: List<Pair<Long, Int>>, val skipped: Long, val gaps: Int)
    private fun run(bytes: ByteArray, config: SilenceGateConfig, rate: Int = 16_000, channels: Int = 1,
                    format: PcmSampleFormat = PcmSampleFormat.PCM_16, batchFrames: Int = 113): Run {
        val output = ByteArrayOutputStream()
        val spans = mutableListOf<Pair<Long, Int>>()
        var gaps = 0
        val stream = SilenceGateStream(rate, channels, format, config, object : SilenceGateOutput {
            override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                output.write(bytes, offset, count); spans += sourceFrame to count
            }
            override fun gap() { gaps++ }
        })
        val block = batchFrames * channels * format.bytesPerSample
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(block, bytes.size - offset)
            stream.accept(bytes, offset, count); offset += count
        }
        stream.finish(); stream.finish()
        return Run(output.toByteArray(), spans, stream.skippedFrames, gaps)
    }

    private fun burstPcm(totalFrames: Int, first: Int, length: Int = 32, channels: Int = 1,
                         amplitude: Double = 0.5): ByteArray =
        ByteBuffer.allocate(totalFrames * channels * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(totalFrames) { frame -> repeat(channels) { channel ->
                val relative = frame - first
                val value = if (relative in 0 until length && channel == channels - 1)
                    amplitude * sin(2 * PI * 1000 * relative / 16000) else 0.0
                putFloat(value.toFloat())
            } }
        }.array()

    private fun assertRetainedBurst(source: ByteArray, result: Run, first: Int, length: Int, channels: Int = 1) {
        val frameBytes = channels * 4
        val burstStart = first.toLong()
        val burstEnd = burstStart + length
        var next = burstStart
        val expected = ByteArrayOutputStream()
        var previousEnd = 0L
        for ((start, byteCount) in result.spans) {
            assertTrue("retained spans overlap", start >= previousEnd)
            val end = start + byteCount / frameBytes
            if (start <= next && end > next) next = minOf(end, burstEnd)
            expected.write(source, (start * frameBytes).toInt(), byteCount)
            previousEnd = end
        }
        assertEquals("burst [$burstStart,$burstEnd) was discarded; spans=${result.spans}", burstEnd, next)
        assertArrayEquals("retained PCM must be byte-exact", expected.toByteArray(), result.bytes)
    }

    @Test fun loudShortOnsetSurvivesCaptureBoundaryAndReadBatching() {
        val interior = burstPcm(2048, 512)
        assertRetainedBurst(interior, run(interior, flat(-40), format = PcmSampleFormat.PCM_FLOAT), 512, 32)
        val onset = burstPcm(2048, 0)
        for (batch in listOf(1, 113, 512, 16000)) {
            assertRetainedBurst(onset, run(onset, flat(-40), format = PcmSampleFormat.PCM_FLOAT,
                batchFrames = batch), 0, 32)
        }
    }

    @Test fun loudShortBurstSurvivesBothStreamEdgesAndInteriorHops() {
        for (first in listOf(0, 1, 16, 128, 255, 256, 400, 511, 512, 1023, 1024, 2016)) {
            val source = burstPcm(2048, first)
            assertRetainedBurst(source, run(source, flat(-40), format = PcmSampleFormat.PCM_FLOAT), first, 32)
        }
    }

    @Test fun shortCaptureFlushRetainsLoudBoundaryBurstsWithoutPaddingPcm() {
        for (frames in listOf(32, 113, 511, 512, 600, 1023)) for (first in listOf(0, frames - 32)) {
            val source = burstPcm(frames, first)
            assertRetainedBurst(source, run(source, flat(-40), format = PcmSampleFormat.PCM_FLOAT), first, 32)
        }
    }

    @Test fun boundaryTreatmentDoesNotInventQuietStereoBursts() {
        for (first in listOf(0, 512, 2016)) {
            val source = burstPcm(2048, first, channels = 2, amplitude = 0.009)
            val result = run(source, flat(-40), channels = 2, format = PcmSampleFormat.PCM_FLOAT)
            assertEquals("quiet burst at $first", 0, result.bytes.size)
        }
        val loud = burstPcm(2048, 0, channels = 2)
        assertRetainedBurst(loud, run(loud, flat(-40), channels = 2, format = PcmSampleFormat.PCM_FLOAT), 0, 32, 2)
    }

    @Test fun boundaryObservationKeepsQuietTonesAndDcQuiet() {
        for (frames in listOf(512, 600, 1023, 2048)) for (hz in listOf(20.0, 100.0, 1007.8125, 7996.09375)) {
            for (phase in listOf(0.0, PI / 2)) {
                val source = pcm(16000, frames, hz, Math.pow(10.0, -40.2 / 20),
                    channels = 2, format = PcmSampleFormat.PCM_FLOAT, opposite = true, phase = phase)
                assertEquals("frames=$frames frequency=$hz phase=$phase", 0,
                    run(source, flat(-40), channels = 2, format = PcmSampleFormat.PCM_FLOAT).bytes.size)
            }
        }
        val constant = ByteBuffer.allocate(2048 * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(2048) { putFloat(0.5f) }
        }.array()
        assertEquals(0, run(constant, flat(-40), format = PcmSampleFormat.PCM_FLOAT).bytes.size)
    }

    @Test fun boundaryObservationDoesNotPromoteTonesInIgnoredBands() {
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(400, -20),
            SilencePoint(600, -96), SilencePoint(2000, -96), SilencePoint(3000, -20), SilencePoint(20000, -20)))
        for (phase in listOf(0.0, PI / 2)) {
            val source = pcm(16000, 2048, 100.0, 0.02, format = PcmSampleFormat.PCM_FLOAT, phase = phase)
            assertEquals("phase=$phase", 0, run(source, curve, format = PcmSampleFormat.PCM_FLOAT).bytes.size)
        }
    }

    @Test fun localizedBoundaryBurstsRespectFrequencyCurvesAndAmplitudes() {
        val shaped = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(200, -20),
            SilencePoint(400, -40), SilencePoint(3000, -40), SilencePoint(5000, -20), SilencePoint(20000, -20)))
        for (curve in listOf(flat(-40), shaped)) for (frequency in listOf(500, 1000, 2000)) {
            for (amplitude in listOf(0.25, 0.5)) for (first in listOf(0, 2016)) {
                val source = ByteBuffer.allocate(2048 * 4).order(ByteOrder.nativeOrder()).apply {
                    repeat(2048) { frame ->
                        val relative = frame - first
                        putFloat(if (relative in 0 until 32)
                            (amplitude * sin(2 * PI * frequency * relative / 16000)).toFloat() else 0f)
                    }
                }.array()
                assertRetainedBurst(source, run(source, curve, format = PcmSampleFormat.PCM_FLOAT), first, 32)
            }
        }
    }

    @Test fun transientChannelCannotEnableOtherChannelsOrBorrowTheirAmplitude() {
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(400, -20),
            SilencePoint(600, -96), SilencePoint(2000, -96), SilencePoint(3000, -20), SilencePoint(20000, -20)))
        for (first in listOf(0, 2016)) {
            val source = ByteBuffer.allocate(2048 * 2 * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(2048) { frame ->
                    putFloat((0.02 * sin(2 * PI * 100 * frame / 16000)).toFloat())
                    val relative = frame - first
                    putFloat(if (relative in 0 until 32)
                        (1e-6 * sin(2 * PI * 1000 * relative / 16000)).toFloat() else 0f)
                }
            }.array()
            assertEquals(0, run(source, curve, channels = 2, format = PcmSampleFormat.PCM_FLOAT).bytes.size)
        }
    }

    @Test fun stationaryLowTonesStayQuietAcrossPartialWindowPhases() {
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(400, -20),
            SilencePoint(600, -96), SilencePoint(2000, -96), SilencePoint(3000, -20), SilencePoint(20000, -20)))
        for (rate in listOf(16000, 48000)) {
            val half = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).hop
            for (frames in listOf(half, half + 1)) for (hz in listOf(20.0, 30.0)) {
                for (phase in listOf(0.0, PI / 4, PI / 2)) {
                    val source = pcm(rate, frames, hz, 0.02, format = PcmSampleFormat.PCM_FLOAT, phase = phase)
                    assertEquals("rate=$rate frames=$frames hz=$hz phase=$phase", 0,
                        run(source, curve, rate = rate, format = PcmSampleFormat.PCM_FLOAT).bytes.size)
                }
            }
        }
    }

    @Test fun configurationRoundTripsAndStartsDisabled() {
        assertFalse(decodeSilenceGate(null).enabled)
        val config = SilenceGateConfig(true)
        assertEquals(config, decodeSilenceGate(encodeSilenceGate(config)))
        for (invalid in listOf("", "2;1;20:-50,20000:-50", "1;2;20:-50,20000:-50", "1;1;10:-50,20000:-50",
            "1;1;20:-97,20000:-50", "1;1;20:-50,20000:1", "1;1;20:-50,500:-50,100:-50,20000:-50",
            "1;1;20:-50,20:-50,20000:-50", "x".repeat(513))) {
            assertThrows(IllegalArgumentException::class.java) { decodeSilenceGate(invalid) }
        }
    }

    @Test fun logarithmicFrequencyAndAmplitudeAreIndependent() {
        val config = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(2000, -60), SilencePoint(20000, -30)))
        assertEquals(-40.0, config.threshold(200.0), 0.0001)
        assertEquals(20, silenceFrequencyAt(0.0))
        assertEquals(20000, silenceFrequencyAt(1.0))
        for (hz in listOf(20, 57, 900, 1234, 16000, 20000)) assertEquals(hz, silenceFrequencyAt(silenceFrequencyFraction(hz.toDouble())))
    }

    @Test fun disabledGatePreservesEveryByteAndArbitraryReadBoundaries() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val bytes = pcm(16000, 17037, 725.0, 0.07, channels, format)
            assertArrayEquals(bytes, run(bytes, flat(0, false), channels = channels, format = format).bytes)
        }
    }

    @Test fun silenceConsumesNoPcmAcrossAllFormatsAndRates() {
        for (rate in listOf(8000, 16000, 44100, 48000, 96000)) for (format in PcmSampleFormat.entries) {
            val bytes = pcm(rate, rate / 2 + 23, 1000.0, 0.0, 2, format)
            val result = run(bytes, flat(-80), rate, 2, format)
            assertEquals(0, result.bytes.size)
            assertEquals((rate / 2 + 23).toLong(), result.skipped)
            assertEquals(1, result.gaps)
        }
    }

    @Test fun gateSelectsFrequencyNotOnlyOverallLoudness() {
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(400, -20),
            SilencePoint(600, -60), SilencePoint(2000, -60), SilencePoint(3000, -20), SilencePoint(20000, -20)))
        val low = pcm(16000, 16000, 100.0, 0.02)
        val speechBand = pcm(16000, 16000, 1000.0, 0.02)
        assertEquals(0, run(low, curve).bytes.size)
        assertArrayEquals(speechBand, run(speechBand, curve).bytes)
    }

    @Test fun eitherStereoChannelCanTriggerEvenWithOppositePhase() {
        val bytes = pcm(48000, 48000, 1500.0, 0.1, 2, opposite = true)
        assertArrayEquals(bytes, run(bytes, flat(-40), 48000, 2).bytes)
    }

    @Test fun audibleUpperRangeIsSupportedButUltrasoundDoesNotTrigger() {
        val high = pcm(96000, 96000, 19000.0, 0.1)
        val ultrasound = pcm(96000, 96000, 28000.0, 0.1)
        assertArrayEquals(high, run(high, flat(-45), 96000).bytes)
        assertEquals(0, run(ultrasound, flat(-45), 96000).bytes.size)
    }

    @Test fun nyquistAmplitudeIsNotDoubled() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        val bytes = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(spectrum.size) { putFloat(if (it % 2 == 0) 0.1f else -0.1f) }
        }.array()
        spectrum.analyze(bytes, spectrum.size)
        assertEquals(0.01, spectrum.powers.last(), 0.00001)
        assertFalse(spectrum.exceeds(flat(-16)))
        assertTrue(spectrum.exceeds(flat(-24)))
    }

    @Test fun offBinToneUsesItsAmplitudeInsteadOfWindowScalloping() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        for (fraction in listOf(0.0, 0.1, 0.25, 0.5)) {
            val hz = (64 + fraction) * 16000 / spectrum.size
            for (db in listOf(-39.0, -41.0)) {
                spectrum.analyze(pcm(16000, spectrum.size, hz, Math.pow(10.0, db / 20),
                    format = PcmSampleFormat.PCM_FLOAT), spectrum.size)
                assertEquals("frequency=$hz amplitude=$db", db > -40, spectrum.exceeds(flat(-40)))
            }
        }
    }

    @Test fun edgeTonesUseCalibratedAmplitudeAndFrequency() {
        for (rate in listOf(16000, 48000)) {
            val spectrum = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT)
            val frequencies = mutableListOf(20.0, 21.0, 30.0)
            if (rate == 16000) frequencies += rate / 2.0 - rate.toDouble() / spectrum.size * 0.25
            for (hz in frequencies) for (db in listOf(-39.8, -40.2)) {
                spectrum.analyze(pcm(rate, spectrum.size, hz, Math.pow(10.0, db / 20),
                    format = PcmSampleFormat.PCM_FLOAT), spectrum.size)
                assertEquals("rate=$rate frequency=$hz amplitude=$db", db > -40, spectrum.exceeds(flat(-40)))
            }
        }
    }

    @Test fun finiteHannFrequencyErrorCannotExcludeTheSupportedUpperEndpoint() {
        val rate = 44100
        val spectrum = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT)
        for (db in listOf(-39.8, -40.2)) {
            spectrum.analyze(pcm(rate, spectrum.size, 20000.0, Math.pow(10.0, db / 20),
                format = PcmSampleFormat.PCM_FLOAT), spectrum.size)
            assertEquals(db > -40, spectrum.exceeds(flat(-40)))
        }
        val outside = 20000.0 + 4 * rate.toDouble() / spectrum.size
        spectrum.analyze(pcm(rate, spectrum.size, outside, Math.pow(10.0, -39.8 / 20),
            format = PcmSampleFormat.PCM_FLOAT), spectrum.size)
        assertFalse(spectrum.exceeds(flat(-40)))
    }

    @Test fun stopFlushCalibratesResolvablePartialEdgeWindows() {
        for (frames in listOf(512, 600, 768, 1023)) for (hz in listOf(20.0, 1007.8125, 7996.09375)) {
            for (db in listOf(-39.8, -40.2)) {
                val bytes = pcm(16000, frames, hz, Math.pow(10.0, db / 20),
                    format = PcmSampleFormat.PCM_FLOAT, phase = if (hz > 7000) PI / 2 else 0.0)
                val result = run(bytes, flat(-40), format = PcmSampleFormat.PCM_FLOAT)
                if (db > -40) assertArrayEquals("frames=$frames hz=$hz", bytes, result.bytes)
                else assertEquals("frames=$frames hz=$hz", 0, result.bytes.size)
            }
        }
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(400, -20),
            SilencePoint(600, -60), SilencePoint(2000, -60), SilencePoint(3000, -20), SilencePoint(20000, -20)))
        assertEquals(0, run(pcm(16000, 600, 100.0, 0.02), curve).bytes.size)
    }

    @Test fun quietLowEdgeDoesNotMaskAnAboveCurveNyquistEdge() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        for (db in listOf(-39.8, -40.2)) {
            val amplitude = Math.pow(10.0, db / 20)
            val bytes = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(spectrum.size) { frame ->
                    putFloat((amplitude * sin(2 * PI * 7996.09375 * frame / 16000) +
                        0.001 * sin(2 * PI * 20 * frame / 16000)).toFloat())
                }
            }.array()
            spectrum.analyze(bytes, spectrum.size)
            assertEquals(db > -40, spectrum.exceeds(flat(-40)))
            val result = run(bytes, flat(-40), format = PcmSampleFormat.PCM_FLOAT)
            if (db > -40) assertArrayEquals(bytes, result.bytes) else assertEquals(0, result.bytes.size)
        }
    }

    @Test fun partialPeakCalibrationKeepsQuieterRelevantBandsWithMultipleLouderTones() {
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -40), SilencePoint(1200, -40),
            SilencePoint(1800, -10), SilencePoint(20000, -10)))
        for (frames in listOf(512, 600, 768, 1023)) for (companions in listOf(listOf(2000.0),
                listOf(2000.0, 3000.0, 4000.0))) for (db in listOf(-39.8, -40.2)) {
            val amplitude = Math.pow(10.0, db / 20)
            val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(frames) { frame ->
                    var value = amplitude * sin(2 * PI * 1007.8125 * frame / 16000)
                    for (hz in companions) value += 0.1 * sin(2 * PI * hz * frame / 16000)
                    putFloat(value.toFloat())
                }
            }.array()
            val result = run(bytes, curve, format = PcmSampleFormat.PCM_FLOAT)
            if (db > -40) assertArrayEquals("frames=$frames companions=$companions", bytes, result.bytes)
            else assertEquals("frames=$frames companions=$companions", 0, result.bytes.size)
        }
    }

    @Test fun partialUpperBandToneCannotInventLowEdgeAudioOrHideARealCompanion() {
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -96), SilencePoint(1250, -96),
            SilencePoint(2500, -34), SilencePoint(5000, -34), SilencePoint(20000, -50)))
        for (frames in listOf(256, 352, 512)) for (hz in listOf(20.0, 31.25, 40.0)) {
            for (db in listOf(-100.0, -80.0)) for (phase in listOf(0.0, 1.0)) {
                val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder()).apply {
                    repeat(frames) { frame ->
                        putFloat((0.01 * sin(2 * PI * 3000 * frame / 8000) +
                            Math.pow(10.0, db / 20) * sin(2 * PI * hz * frame / 8000 + phase)).toFloat())
                    }
                }.array()
                val result = run(bytes, curve, rate = 8000, format = PcmSampleFormat.PCM_FLOAT)
                val label = "frames=$frames hz=$hz db=$db phase=$phase"
                if (db > -96) assertArrayEquals(label, bytes, result.bytes)
                else assertEquals(label, 0, result.bytes.size)
            }
        }
    }

    @Test fun unrelatedFrequencyEnergyDoesNotDisableEdgeAmplitudeCalibration() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        val amplitude = Math.pow(10.0, -39.8 / 20)
        for (companion in listOf(1000.0, 7996.09375)) {
            val bytes = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(spectrum.size) { frame ->
                    putFloat((amplitude * (sin(2 * PI * 20 * frame / 16000) +
                        sin(2 * PI * companion * frame / 16000))).toFloat())
                }
            }.array()
            val curve = if (companion > 7000) flat(-40) else SilenceGateConfig(true,
                listOf(SilencePoint(20, -40), SilencePoint(100, -40), SilencePoint(200, -10), SilencePoint(20000, -10)))
            spectrum.analyze(bytes, spectrum.size)
            assertTrue("companion=$companion", spectrum.exceeds(curve))
            assertArrayEquals(bytes, run(bytes, curve, format = PcmSampleFormat.PCM_FLOAT).bytes)
        }
    }

    @Test fun unobservableNyquistSineCoefficientCannotInventLoudAudio() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        for (slope in listOf(0.00001, 0.0001)) {
            val bytes = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(spectrum.size) { frame ->
                    putFloat(((0.007 + slope * frame / spectrum.size) * if (frame % 2 == 0) 1 else -1).toFloat())
                }
            }.array()
            spectrum.analyze(bytes, spectrum.size)
            assertFalse(spectrum.exceeds(flat(-40)))
            assertEquals(0, run(bytes, flat(-40), format = PcmSampleFormat.PCM_FLOAT).bytes.size)
        }
    }

    @Test fun unresolvedShortBlocksUseTheMinimumCurvePeakBound() {
        for (frames in listOf(1, 3, 17)) for (db in listOf(-39.0, -41.0)) {
            val value = Math.pow(10.0, db / 20).toFloat()
            val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder()).apply {
                repeat(frames) { putFloat(value) }
            }.array()
            val result = run(bytes, flat(-40), format = PcmSampleFormat.PCM_FLOAT)
            if (db > -40) assertArrayEquals(bytes, result.bytes) else assertEquals(0, result.bytes.size)
        }
        assertEquals(0, run(ByteArray(12), flat(-96), format = PcmSampleFormat.PCM_FLOAT).bytes.size)
    }

    @Test fun edgeAmplitudeCalibrationSurvivesPhaseDcAndQuietMixtures() {
        for (rate in listOf(16000, 48000)) {
            val spectrum = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT)
            val frequencies = if (rate == 16000) listOf(20.0, 21.0, 7996.09375) else listOf(20.0, 21.0)
            for (hz in frequencies) for (phase in listOf(0.0, PI / 2, PI)) for (db in listOf(-39.5, -40.5)) {
                val amplitude = Math.pow(10.0, db / 20)
                val random = java.util.Random(741)
                val bytes = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.nativeOrder()).apply {
                    repeat(spectrum.size) { frame ->
                        val tone = amplitude * sin(2 * PI * hz * frame / rate + phase)
                        val harmonic = amplitude * 0.1 * sin(2 * PI * 1000 * frame / rate)
                        val noise = amplitude * 0.1 * (random.nextDouble() * 2 - 1)
                        putFloat((tone + harmonic + noise + 0.05).toFloat())
                    }
                }.array()
                spectrum.analyze(bytes, spectrum.size)
                assertEquals("rate=$rate hz=$hz phase=$phase amplitude=$db", db > -40, spectrum.exceeds(flat(-40)))
            }
        }
    }

    @Test fun coherentSubAudibleToneDoesNotTriggerWindowSidelobes() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        spectrum.analyze(pcm(16000, spectrum.size, 10.0, 0.1,
            format = PcmSampleFormat.PCM_FLOAT), spectrum.size)
        assertFalse(spectrum.exceeds(flat(-96)))
    }

    @Test fun separatedSubthresholdTonesDoNotInventAnAboveCurvePeak() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        val bytes = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(spectrum.size) { frame ->
                putFloat((0.007 * sin(2 * PI * 1007.8125 * frame / 16000) +
                    0.007 * sin(2 * PI * 2011.71875 * frame / 16000)).toFloat())
            }
        }.array()
        spectrum.analyze(bytes, spectrum.size)
        assertFalse(spectrum.exceeds(flat(-40)))
    }

    @Test fun offBinStereoAndDcKeepIndependentCalibration() {
        val spectrum = SilenceSpectrum(16000, 2, PcmSampleFormat.PCM_FLOAT)
        val bytes = pcm(16000, spectrum.size, 1007.8125, Math.pow(10.0, -39.0 / 20),
            channels = 2, format = PcmSampleFormat.PCM_FLOAT, opposite = true)
        spectrum.analyze(bytes, spectrum.size)
        assertTrue(spectrum.exceeds(flat(-40)))
        val dc = ByteBuffer.allocate(spectrum.size * 8).order(ByteOrder.nativeOrder()).apply {
            repeat(spectrum.size) { putFloat(0.5f); putFloat(-0.5f) }
        }.array()
        spectrum.analyze(dc, spectrum.size)
        assertFalse(spectrum.exceeds(flat(-96)))
    }

    @Test fun quieterStereoChannelCanCrossItsOwnFrequencyCutoff() {
        val spectrum = SilenceSpectrum(48000, 2, PcmSampleFormat.PCM_FLOAT)
        val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -10), SilencePoint(2337, -10),
            SilencePoint(2338, -40), SilencePoint(2339, -10), SilencePoint(20000, -10)))
        fun signal(low: Boolean, high: Boolean): ByteArray =
            ByteBuffer.allocate(spectrum.size * 8).order(ByteOrder.nativeOrder()).apply {
                repeat(spectrum.size) { frame ->
                    putFloat(if (low) (Math.pow(10.0, -35.0 / 20) *
                        sin(2 * PI * 99.75 * frame / spectrum.size)).toFloat() else 0f)
                    putFloat(if (high) (Math.pow(10.0, -30.0 / 20) *
                        sin(2 * PI * 100.25 * frame / spectrum.size)).toFloat() else 0f)
                }
            }.array()
        spectrum.analyze(signal(true, false), spectrum.size)
        assertTrue(spectrum.exceeds(curve))
        spectrum.analyze(signal(false, true), spectrum.size)
        assertFalse(spectrum.exceeds(curve))
        spectrum.analyze(signal(true, true), spectrum.size)
        assertTrue(spectrum.exceeds(curve))
    }

    @Test fun graphKeepsBothChannelFrequenciesAcrossBucketBoundary() {
        val spectrum = SilenceSpectrum(16000, 2, PcmSampleFormat.PCM_FLOAT)
        val width = 16000.0 / spectrum.size
        val boundary = (1..90).map { 20.0 * Math.pow(10.0, 3.0 * (it + 0.5) / (SILENCE_GRAPH_BINS - 1)) }
            .first { it in 500.0..2000.0 && kotlin.math.abs(it / width - Math.round(it / width)) < 0.35 }
        val lower = boundary - width * 0.05
        val upper = boundary + width * 0.05
        val bytes = ByteBuffer.allocate(spectrum.size * 8).order(ByteOrder.nativeOrder()).apply {
            repeat(spectrum.size) { frame ->
                putFloat((0.02 * sin(2 * PI * lower * frame / 16000)).toFloat())
                putFloat((0.01 * sin(2 * PI * upper * frame / 16000)).toFloat())
            }
        }.array()
        spectrum.analyze(bytes, spectrum.size)
        val graph = spectrum.graph()
        val lowBucket = Math.round(silenceFrequencyFraction(lower) * (SILENCE_GRAPH_BINS - 1)).toInt()
        val highBucket = Math.round(silenceFrequencyFraction(upper) * (SILENCE_GRAPH_BINS - 1)).toInt()
        assertNotEquals(lowBucket, highBucket)
        assertEquals(-34f, graph[lowBucket], 0.1f)
        assertEquals(-40f, graph[highBucket], 0.1f)
    }

    @Test fun offBinTriggerPreservesOriginalPcmAcrossReadsAndFinalFlush() {
        val bytes = pcm(16000, 16037, 1007.8125, Math.pow(10.0, -39.0 / 20),
            format = PcmSampleFormat.PCM_FLOAT)
        assertArrayEquals(bytes, run(bytes, flat(-40), format = PcmSampleFormat.PCM_FLOAT).bytes)
    }

    @Test fun nearNyquistToneAndNyquistKeepTheirDistinctOneSidedGains() {
        val spectrum = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_FLOAT)
        for (bin in listOf(508.0, 508.5, 511.0)) {
            spectrum.analyze(pcm(16000, spectrum.size, bin * 16000 / spectrum.size, 0.1,
                format = PcmSampleFormat.PCM_FLOAT), spectrum.size)
            assertTrue(spectrum.exceeds(flat(-24)))
            assertFalse(spectrum.exceeds(flat(-16)))
        }
    }

    @Test fun sparseCaptureRemovesLongSilenceAndRetainsExactSourceSpans() {
        val bytes = ByteArray(32000) + pcm(16000, 8000, 1000.0, 0.2) + ByteArray(64000)
        val result = run(bytes, flat(-45))
        assertTrue(result.bytes.size in 16000..27000)
        assertTrue(result.skipped > 32000)
        val expected = ByteArrayOutputStream()
        for ((start, size) in result.spans) expected.write(bytes, (start * 2).toInt(), size)
        assertArrayEquals(expected.toByteArray(), result.bytes)
        assertTrue(result.gaps >= 2)
    }

    @Test fun batchSizeDoesNotChangeGatingOrRetention() {
        val bytes = ByteArray(16000) + pcm(16000, 12345, 1300.0, 0.2) + ByteArray(17000)
        val small = run(bytes, flat(-45), batchFrames = 1)
        val large = run(bytes, flat(-45), batchFrames = 16000)
        assertArrayEquals(small.bytes, large.bytes)
        assertEquals(small.spans, large.spans)
        assertEquals(small.skipped, large.skipped)
    }

    @Test fun failedSinkIsNotReplayedByFlush() {
        var writes = 0
        val stream = SilenceGateStream(16000, 1, PcmSampleFormat.PCM_16, flat(-50), object : SilenceGateOutput {
            override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                writes++; throw java.io.IOException("injected partial append")
            }
            override fun gap() = Unit
        })
        val bytes = pcm(16000, 16000, 1000.0, 0.2)
        assertThrows(java.io.IOException::class.java) { stream.accept(bytes, 0, bytes.size) }
        stream.finish(); stream.finish()
        assertEquals(1, writes)
    }

    @Test fun nonFinitePcmMustNotBeSilentlyDiscarded() {
        val bytes = pcm(16000, 2048, 1000.0, 0.0, format = PcmSampleFormat.PCM_FLOAT)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).putFloat(400, Float.NaN)
        assertTrue(run(bytes, flat(-45), format = PcmSampleFormat.PCM_FLOAT).bytes.isNotEmpty())
    }

    @Test fun samplingIsBoundedAndProducesEditableFrequencyCurve() {
        val sample = SilenceSampleRequest()
        val noise = pcm(16000, 8000, 300.0, 0.01)
        repeat(8) { sample.accept(noise, 0, noise.size, 16000, 1, PcmSampleFormat.PCM_16) }
        assertTrue(sample.state.value.complete)
        val before = sample.state.value
        sample.accept(noise, 0, noise.size, 16000, 1, PcmSampleFormat.PCM_16)
        assertEquals(before, sample.state.value)
        val points = sampledSilenceCurve(before.peaks, 8000)
        SilenceGateConfig(true, points).validate()
        assertTrue(points.size > 4)
        val analyzer = SilenceSpectrum(16000, 1, PcmSampleFormat.PCM_16)
        analyzer.analyze(noise, analyzer.size)
        assertFalse(analyzer.exceeds(SilenceGateConfig(true, points)))
    }

    @Test fun cancelledSamplingCannotPublishLateCompletion() {
        val sample = SilenceSampleRequest()
        sample.cancel()
        val bytes = pcm(16000, 48000, 1000.0, 0.1)
        sample.accept(bytes, 0, bytes.size, 16000, 1, PcmSampleFormat.PCM_16)
        assertFalse(sample.state.value.complete)
        assertEquals(0f, sample.state.value.progress)
    }

    @Test fun cancelledSamplingStillReportsNativeReleaseFailure() {
        val sample = SilenceSampleRequest().apply { cancel() }
        val releaseFailure = IllegalStateException("native release failed")
        val reports = mutableListOf<Throwable>()
        var stopCalls = 0
        var releaseCalls = 0
        val failure = releaseSilenceSampleRecorder(
            primaryFailure = null,
            stop = { stopCalls++ },
            release = { releaseCalls++; throw releaseFailure },
            onReleaseFailure = { reports += it },
        )
        failure?.let { sample.fail(requireNotNull(it.message)) }
        assertSame(releaseFailure, failure)
        assertEquals(listOf(releaseFailure), reports)
        assertEquals(1, stopCalls)
        assertEquals(1, releaseCalls)
        assertTrue(sample.state.value.cancelled)
        assertNull(sample.state.value.error)
    }

    @Test fun cancelledSamplingWithSuccessfulCleanupStaysNeutral() {
        val sample = SilenceSampleRequest().apply { cancel() }
        var releaseCalls = 0
        val failure = releaseSilenceSampleRecorder(
            primaryFailure = null,
            stop = {},
            release = { releaseCalls++ },
            onReleaseFailure = { fail("Successful cleanup must not report an incident") },
        )
        assertNull(failure)
        assertEquals(1, releaseCalls)
        assertTrue(sample.state.value.cancelled)
        assertNull(sample.state.value.error)
    }

    @Test fun samplingFailurePreservesBothCleanupFailuresAndReportsReleaseOnce() {
        val primary = java.io.IOException("sampling failed")
        val stopFailure = IllegalStateException("stop failed")
        val releaseFailure = IllegalStateException("native release failed")
        val reports = mutableListOf<Throwable>()
        var releaseCalls = 0
        val failure = releaseSilenceSampleRecorder(
            primaryFailure = primary,
            stop = { throw stopFailure },
            release = { releaseCalls++; throw releaseFailure },
            onReleaseFailure = { reports += it },
        )
        assertSame(primary, failure)
        assertEquals(listOf(stopFailure, releaseFailure), primary.suppressed.toList())
        assertEquals(listOf(releaseFailure), reports)
        assertEquals(1, releaseCalls)
    }

    @Test fun samplingStopBeforeStartFailureDoesNotBecomeAnUnexpectedReleaseIncident() {
        val stopFailure = IllegalStateException("recording never started")
        var releaseCalls = 0
        var reports = 0
        val failure = releaseSilenceSampleRecorder(
            primaryFailure = null,
            stop = { throw stopFailure },
            release = { releaseCalls++ },
            onReleaseFailure = { reports++ },
        )
        assertSame(stopFailure, failure)
        assertEquals(1, releaseCalls)
        assertEquals(0, reports)
    }

    @Test fun timestampedSpansRemainSeparatedAfterReopen() {
        val root = File(".tmp/silence-store-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val first = pcm(16000, 16000, 1000.0, 0.2)
        val second = pcm(16000, 16000, 2000.0, 0.2)
        try {
            PersistentAudioChunkStore(root).use { store ->
                store.configure(RetentionMode.SIZE, 1024 * 1024, 16000, 1, PcmSampleFormat.PCM_16)
                store.appendCapturedAt(first, 0, first.size, 1_000_000L)
                store.sealActiveChunk()
                store.appendCapturedAt(second, 0, second.size, 1_100_000L)
            }
            PersistentAudioChunkStore(root).use { store ->
                store.configure(RetentionMode.SIZE, 1024 * 1024, 16000, 1, PcmSampleFormat.PCM_16)
                assertEquals(2.0, store.durationSeconds(), 0.0001)
                requireNotNull(store.acquireRange(0.0, 2.0)).use { lease ->
                    assertEquals(1_000_000L, lease.startedAtMillis)
                    assertEquals(1_101_000L, lease.endedAtMillis)
                    val actual = ByteArrayOutputStream()
                    lease.readNormalized(16000, 1, PcmSampleFormat.PCM_16) { a, o, n -> actual.write(a, o, n); n }
                    assertArrayEquals(first + second, actual.toByteArray())
                }
            }
        } finally { root.deleteRecursively() }
    }
}
