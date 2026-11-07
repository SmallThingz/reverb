package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SilenceGateContinuityTest {
    private val rate = 16_000
    private val config = SilenceGateConfig(true, listOf(SilencePoint(20, -40), SilencePoint(20_000, -40)))
    private data class Span(val first: Long, val frames: Int)
    private data class Result(val pcm: ByteArray, val spans: List<Span>, val lastBaselineEnd: Long,
        val retained: Long, val skipped: Long, val gaps: Int)

    private fun pcm(vararg sections: Pair<Int, Double>): ByteArray {
        val result = ByteBuffer.allocate(sections.sumOf { it.first } * 4).order(ByteOrder.nativeOrder())
        var frame = 0
        for ((count, db) in sections) repeat(count) {
            val amplitude = if (db == Double.NEGATIVE_INFINITY) 0.0 else 10.0.pow(db / 20)
            result.putFloat((amplitude * sin(2 * PI * 1000 * frame++ / rate)).toFloat())
        }
        return result.array()
    }

    private fun run(source: ByteArray, batchFrames: Int = 113): Result {
        val output = ByteArrayOutputStream()
        val spans = mutableListOf<Span>()
        var sourceFrame = 0L
        var lastBaselineEnd = -1L
        var gaps = 0
        val stream = SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT, config,
            object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                    spans += Span(sourceFrame, count / 4)
                    output.write(bytes, offset, count)
                }
                override fun gap() { gaps++ }
            },
            onSpectrum = { spectrum, count ->
                if (count > 0) {
                    // Independent original-threshold observation, never the continuation threshold.
                    if (spectrum.exceeds(config)) lastBaselineEnd = sourceFrame + count
                    sourceFrame += count
                }
            })
        var offset = 0
        while (offset < source.size) {
            val count = minOf(batchFrames * 4, source.size - offset)
            stream.accept(source, offset, count)
            offset += count
        }
        stream.finish()
        val expected = ByteArrayOutputStream()
        var previousEnd = 0L
        for (span in spans) {
            assertTrue("No duplicate or backwards source frames", span.first >= previousEnd)
            assertTrue("No invented or out-of-range PCM", span.frames > 0 && span.first + span.frames <= source.size / 4)
            expected.write(source, span.first.toInt() * 4, span.frames * 4)
            previousEnd = span.first + span.frames
        }
        assertArrayEquals("Every retained sample must be byte-exact accepted PCM", expected.toByteArray(), output.toByteArray())
        assertEquals(source.size / 4L, stream.retainedFrames + stream.skippedFrames)
        return Result(output.toByteArray(), spans, lastBaselineEnd, stream.retainedFrames, stream.skippedFrames, gaps)
    }

    @Test fun subBaselineRoomToneCannotOpenTheGate() {
        val result = run(pcm(48_000 to -43.0))
        assertEquals(-1L, result.lastBaselineEnd)
        assertEquals(0L, result.retained)
        assertEquals(48_000L, result.skipped)
    }

    @Test fun softerSpeechContinuesBeyondTheGuaranteedTail() {
        val result = run(pcm(2048 to -20.0, 8000 to -43.0, 16_000 to Double.NEGATIVE_INFINITY))
        val keptThrough = result.spans.last().let { it.first + it.frames }
        assertTrue(result.lastBaselineEnd > 0)
        assertTrue("Soft speech after 300 ms must survive", keptThrough >= result.lastBaselineEnd + rate * 3 / 10)
        assertTrue("Release must remain finite", keptThrough <= result.lastBaselineEnd + rate)
    }

    @Test fun continuationCannotRearmAndEndsAtExactOneSecondSampleBoundary() {
        val source = pcm(2048 to -20.0, 48_000 to -40.02)
        val result = run(source)
        val keptThrough = result.spans.last().let { it.first + it.frames }
        assertEquals("Sub-baseline continuation cannot reset the one-second clock", result.lastBaselineEnd + rate, keptThrough)
        assertTrue("Exercise a deadline within a normal 512-frame hop", result.spans.last().frames < 512)
        assertEquals(1, result.gaps)
        assertArrayEquals(source.copyOfRange(0, keptThrough.toInt() * 4), result.pcm)
    }

    @Test fun onlyANewBaselineCrossingRearmsTheRelease() {
        val result = run(pcm(2048 to -20.0, 8000 to -40.02, 1024 to -20.0, 48_000 to -40.02))
        assertTrue("The second true crossing must own the release", result.lastBaselineEnd > 10_048)
        assertEquals(result.lastBaselineEnd + rate, result.spans.last().let { it.first + it.frames })
        assertEquals("The sub-baseline bridge must stay continuous until the final deadline", 1, result.gaps)
        assertEquals(0L, result.spans.first().first)
        assertEquals(result.spans.last().let { it.first + it.frames }, result.retained)
    }

    @Test fun closedGateCannotReopenOnNoiseWithinTheOldReleaseClock() {
        val softStart = 2048 + 6000
        val result = run(pcm(2048 to -20.0, 6000 to Double.NEGATIVE_INFINITY, 8000 to -40.02))
        assertTrue(result.spans.all { it.first + it.frames <= softStart })
        assertEquals(1, result.gaps)
    }

    @Test fun malformedPcmKeepsItsExistingFailOpenTailWithoutInventingSamples() {
        val source = pcm(16_000 to Double.NEGATIVE_INFINITY)
        ByteBuffer.wrap(source).order(ByteOrder.nativeOrder()).putFloat(100 * 4, Float.NaN)
        val result = run(source)
        val keptThrough = result.spans.last().let { it.first + it.frames }
        assertTrue(keptThrough >= result.lastBaselineEnd + rate / 5)
        assertTrue(keptThrough < result.lastBaselineEnd + rate / 5 + 512)
        assertArrayEquals(source.copyOfRange(0, keptThrough.toInt() * 4), result.pcm)
    }

    @Test fun finishingDuringSoftContinuationFlushesOnlyAcceptedTail() {
        val source = pcm(2048 to -20.0, 5000 to -40.02)
        assertArrayEquals(source, run(source).pcm)
    }

    @Test fun decisionsAndExactPcmDoNotDependOnMicrophoneReadBatchSize() {
        val source = pcm(2048 to -20.0, 24_000 to -40.02, 1024 to -20.0, 8000 to -43.0,
            8000 to Double.NEGATIVE_INFINITY)
        val baseline = run(source, 512)
        for (batch in listOf(1, 113, 16_000)) {
            val actual = run(source, batch)
            assertArrayEquals("batch=$batch", baseline.pcm, actual.pcm)
            assertEquals(baseline.spans, actual.spans)
            assertEquals(baseline.gaps, actual.gaps)
        }
    }

    @Test fun stereoIntegerAndFloatDeadlinesStaySampleExactAtCommonRates() {
        for (sampleRate in listOf(44_100, 48_000)) for (format in listOf(PcmSampleFormat.PCM_16, PcmSampleFormat.PCM_FLOAT)) {
            val curve = SilenceGateConfig(true, listOf(SilencePoint(20, -20), SilencePoint(20_000, -20)))
            val window = SilenceSpectrum(sampleRate, 2, format).size
            val totalFrames = window * 2 + sampleRate * 2
            val frameBytes = 2 * format.bytesPerSample
            val source = ByteBuffer.allocate(totalFrames * frameBytes).order(ByteOrder.nativeOrder()).apply {
                repeat(totalFrames) { frame ->
                    val amplitude = 10.0.pow((if (frame < window * 2) -6.0 else -20.002) / 20)
                    val value = amplitude * sin(2 * PI * 64 * frame / window)
                    for (channel in 0..1) {
                        val signed = if (channel == 0) value else -value
                        if (format == PcmSampleFormat.PCM_16) putShort((signed * 32767).roundToInt().toShort())
                        else putFloat(signed.toFloat())
                    }
                }
            }.array()
            var cursor = 0L
            var baselineEnd = -1L
            var keptEnd = 0L
            val output = ByteArrayOutputStream()
            val gate = SilenceGateStream(sampleRate, 2, format, curve, object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                    assertEquals("No gap or duplicate before the deadline", keptEnd, sourceFrame)
                    keptEnd += count / frameBytes
                    output.write(bytes, offset, count)
                }
                override fun gap() = Unit
            }, onSpectrum = { spectrum, count ->
                if (count > 0) {
                    if (spectrum.exceeds(curve)) baselineEnd = cursor + count
                    cursor += count
                }
            })
            gate.accept(source, 0, source.size)
            gate.finish()
            assertEquals("$sampleRate/$format deadline", baselineEnd + sampleRate, keptEnd)
            assertEquals(totalFrames.toLong(), gate.retainedFrames + gate.skippedFrames)
            assertArrayEquals(source.copyOfRange(0, keptEnd.toInt() * frameBytes), output.toByteArray())
        }
    }
}
