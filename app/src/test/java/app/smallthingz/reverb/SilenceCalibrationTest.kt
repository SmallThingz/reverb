package app.smallthingz.reverb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SilenceCalibrationTest {
    private fun pcm(rate: Int, channels: Int, format: PcmSampleFormat, amplitude: Double, frequencyHz: Int = 1_000): ByteArray {
        val buffer = ByteBuffer.allocate(rate * 3 * channels * format.bytesPerSample).order(ByteOrder.nativeOrder())
        repeat(rate * 3) { frame ->
            repeat(channels) { channel ->
                val value = amplitude * sin(2 * PI * frequencyHz * frame / rate) * if (channel == 0) 1 else -1
                when (format) {
                    PcmSampleFormat.PCM_8 -> buffer.put((128 + value * 127).toInt().coerceIn(0, 255).toByte())
                    PcmSampleFormat.PCM_16 -> buffer.putShort((value * 32767).toInt().toShort())
                    PcmSampleFormat.PCM_FLOAT -> buffer.putFloat(value.toFloat())
                }
            }
        }
        return buffer.array()
    }

    private fun measured(rate: Int = 16_000, channels: Int = 1,
        format: PcmSampleFormat = PcmSampleFormat.PCM_16, amplitude: Double = 0.02): SilenceSampleState {
        val request = SilenceSampleRequest()
        val source = pcm(rate, channels, format, amplitude)
        val batch = 137 * channels * format.bytesPerSample
        var offset = 0
        while (offset < source.size) {
            val count = minOf(batch, source.size - offset)
            request.accept(source, offset, count, rate, channels, format)
            offset += count
        }
        return request.state.value
    }

    private class FakeSampler(val startAction: (SilenceSampleRequest) -> Unit = {}) : SilenceSampler {
        override val request = SilenceSampleRequest()
        var starts = 0
        var closes = 0
        var closeFailure: Exception? = null
        override fun start() { starts++; startAction(request) }
        override fun close() {
            closes++
            request.cancel()
            closeFailure?.let { throw it }
        }
    }

    @Test(timeout = 10_000)
    fun measuredRoomCurvePreservesLouderSoundAcrossPcmFormatsAndStereo() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val result = silenceCalibrationResult(measured(channels = channels, format = format))
            val config = SilenceGateConfig(enabled = true, points = result.points).validate()
            val analyzer = SilenceSpectrum(16_000, channels, format)
            analyzer.analyze(pcm(16_000, channels, format, 0.02), analyzer.size)
            assertFalse("Room itself must remain below its curve: $format/$channels", analyzer.exceeds(config))
            analyzer.analyze(pcm(16_000, channels, format, 0.15), analyzer.size)
            assertTrue("Louder sound must be retained: $format/$channels", analyzer.exceeds(config))
            assertEquals(20, result.points.first().hz)
            assertEquals(20_000, result.points.last().hz)
            assertTrue(result.points.all { it.db in -96..-12 })
        }
    }

    @Test(timeout = 20_000)
    fun calibratedUpperBandRoomPcmIsSkippedAndLouderPcmIsRetained() {
        for ((rate, frequency) in listOf(16_000 to 7_000, 8_000 to 3_000)) {
            for (format in PcmSampleFormat.entries) {
                val room = pcm(rate, 1, format, 0.01, frequency)
                val request = SilenceSampleRequest()
                var position = 0
                while (position < room.size) {
                    val count = minOf(137 * format.bytesPerSample, room.size - position)
                    request.accept(room, position, count, rate, 1, format)
                    position += count
                }
                val measured = silenceCalibrationResult(request.state.value)
                val config = SilenceGateConfig(enabled = true, points = measured.points).validate()
                fun filter(source: ByteArray): Pair<SilenceGateStream, ByteArray> {
                    val kept = java.io.ByteArrayOutputStream()
                    val gate = SilenceGateStream(rate, 1, format, config, object : SilenceGateOutput {
                        override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                            kept.write(bytes, offset, count)
                        }
                        override fun gap() = Unit
                    })
                    var cursor = 0
                    while (cursor < source.size) {
                        val count = minOf(193 * format.bytesPerSample, source.size - cursor)
                        gate.accept(source, cursor, count)
                        cursor += count
                    }
                    gate.finish()
                    return gate to kept.toByteArray()
                }
                val (quietGate, quietBytes) = filter(room)
                assertEquals("$rate Hz/$frequency Hz/$format room", 0L, quietGate.retainedFrames)
                assertEquals(rate * 3L, quietGate.skippedFrames)
                assertEquals(0, quietBytes.size)
                val louder = pcm(rate, 1, format, 0.1, frequency)
                val (loudGate, loudBytes) = filter(louder)
                assertEquals("$rate Hz/$frequency Hz/$format louder", rate * 3L, loudGate.retainedFrames)
                assertArrayEquals(louder, loudBytes)
            }
        }
    }

    @Test(timeout = 10_000)
    fun lowAndHighRatesProduceBoundedEditableCurves() {
        for (rate in listOf(8_000, 44_100, 96_000, 192_000)) {
            val result = silenceCalibrationResult(measured(rate = rate))
            SilenceGateConfig(points = result.points).validate()
            assertEquals(rate, result.sampleRate)
            assertTrue(result.points.size <= SILENCE_MAX_POINTS)
            assertTrue(result.points.zipWithNext().all { (a, b) -> a.hz < b.hz })
        }
    }

    @Test(timeout = 5_000)
    fun digitalQuietIsValidButLoudInterruptedOrInvalidSamplesAreNotCalibration() {
        val quiet = silenceCalibrationResult(measured(amplitude = 0.0))
        assertEquals(-96, quiet.points.first().db)
        assertThrows(SilenceCalibrationTooLoud::class.java) {
            silenceCalibrationResult(measured(amplitude = 0.5))
        }
        val complete = SilenceSampleState(progress = 1f, sampleRate = 16_000,
            peaks = List(SILENCE_GRAPH_BINS) { -50f }, complete = true)
        for (invalid in listOf(complete.copy(complete = false), complete.copy(cancelled = true),
            complete.copy(error = "failed"), complete.copy(progress = 0.5f), complete.copy(sampleRate = 0),
            complete.copy(peaks = List(SILENCE_GRAPH_BINS) { Float.NaN }))) {
            assertThrows(IllegalArgumentException::class.java) { silenceCalibrationResult(invalid) }
        }
    }

    @Test(timeout = 5_000)
    fun cancellationDuringPreparationNeverAcquiresMicrophoneOwner() = runBlocking {
        var acquired = 0
        var preparing = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            calibrateSilence(createSampler = { acquired++; FakeSampler() },
                onPreparing = { preparing = it }, onProgress = {})
        }
        assertEquals(3, preparing)
        job.cancelAndJoin()
        assertEquals(0, acquired)
    }

    @Test(timeout = 5_000)
    fun cancellationDuringSamplingClosesOnceAndRejectsLateCompletion() = runBlocking {
        val sampler = FakeSampler()
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            calibrateSilence({ sampler }, {}, {}, preparationMillis = 0)
            delivered = true
        }
        assertEquals(1, sampler.starts)
        job.cancelAndJoin()
        val bytes = pcm(16_000, 1, PcmSampleFormat.PCM_16, 0.02)
        sampler.request.accept(bytes, 0, bytes.size, 16_000, 1, PcmSampleFormat.PCM_16)
        assertFalse(delivered)
        assertEquals(1, sampler.closes)
        assertTrue(sampler.request.state.value.cancelled)
        assertFalse(sampler.request.state.value.complete)
    }

    @Test(timeout = 5_000)
    fun completedSamplingProducesOnlyAReviewResultAndClosesOwner() = runBlocking {
        val bytes = pcm(16_000, 1, PcmSampleFormat.PCM_16, 0.02)
        val sampler = FakeSampler { it.accept(bytes, 0, bytes.size, 16_000, 1, PcmSampleFormat.PCM_16) }
        val result = calibrateSilence({ sampler }, {}, {}, preparationMillis = 0)
        assertEquals(16_000, result.sampleRate)
        assertEquals(1, sampler.starts)
        assertEquals(1, sampler.closes)
        assertTrue(result.points.isNotEmpty())
    }

    @Test(timeout = 5_000)
    fun stalledSamplerTimesOutAsFailureAndClosesOwner() = runBlocking {
        val sampler = FakeSampler()
        try {
            calibrateSilence({ sampler }, {}, {}, preparationMillis = 0, samplingTimeoutMillis = 10)
            fail("A stalled input must not produce a curve")
        } catch (_: SilenceCalibrationTimedOut) {
            assertEquals(1, sampler.closes)
            assertTrue(sampler.request.cancelled.get())
        }
    }

    @Test(timeout = 5_000)
    fun sampleFailureIsPreservedWhenCleanupAlsoFails() = runBlocking {
        val cleanup = IOException("cleanup")
        val sampler = FakeSampler { it.fail("input unavailable") }.apply { closeFailure = cleanup }
        try {
            calibrateSilence({ sampler }, {}, {}, preparationMillis = 0)
            fail("Failed input must not produce a curve")
        } catch (failure: IOException) {
            assertEquals("input unavailable", failure.message)
            assertEquals(listOf(cleanup), failure.suppressed.toList())
            assertEquals(1, sampler.closes)
        }
    }
}
