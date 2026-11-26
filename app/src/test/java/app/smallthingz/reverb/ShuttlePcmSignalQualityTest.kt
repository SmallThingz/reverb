package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttlePcmSignalQualityTest {
    @Test fun unitRateStreamPreservesNormalPcmAcrossWritesAndSourceWindowRefills() {
        for (sampleRate in listOf(24_000, 48_000, 96_000)) for (direction in listOf(1, -1)) {
            val channels = 2
            val frameBytes = channels * 2
            val first = sampleRate / 7 + 3
            // End one frame beyond the regular block lattice. The fade should still
            // have a full edge interval instead of collapsing to that last sample.
            val count = sampleRate * 504 / 1000 + 1
            val end = first + count
            val frames = end + 41
            val attacks = intArrayOf(24, 88, 152, 248, 408).map { sampleRate * it / 1000 }
            val source = pcm16(frames, channels) { frame, channel ->
                if (frame < first || frame >= end) return@pcm16 if (channel == 0) 32_700 else -32_700
                val displayedFrame = if (direction > 0) frame - first else end - 1 - frame
                // Attacks cross several write seams. Unrelated channel tones also expose
                // missing/repeated frames, channel swaps, and gain modulation on a sustained run.
                val transient = attacks.sumOf { seam ->
                    raisedCosine(displayedFrame - seam, sampleRate / 500) * 18_500
                }
                val phase = 2 * PI * frame / sampleRate
                val value = if (channel == 0) 10_500 * sin(731 * phase) + transient
                    else -9_000 * sin(1_811 * phase + .37) - transient * .43
                value.roundToInt()
            }
            val output = ByteArrayOutputStream()
            val cursor = ShuttleUnityCursor(sampleRate, reverse = direction < 0)
            val pcm = ShuttleUnityPcm(sampleRate, channels)
            val lower = (first - .25) / sampleRate
            val upper = (end + .25) / sampleRate
            var blocks = 0
            var reads = 0
            var expectedEdge = if (direction > 0) first.toLong() else end.toLong()
            for (elapsedMs in 0..560 step 8) {
                val elapsed = (sampleRate * elapsedMs / 1000).coerceAtMost(count)
                val expectedTarget = if (direction > 0) first + elapsed else end - elapsed
                val target = expectedTarget.toDouble() / sampleRate
                // An immediate-consumption sink gives the cursor its worst case for read-ahead.
                for (attempt in 0 until 4) {
                    val block = cursor.next(target, lower, upper) ?: break
                    if (direction > 0) assertEquals(expectedEdge, block.firstFrame)
                    else assertEquals(expectedEdge, block.endFrame)
                    assertEquals(direction < 0, block.reverse)
                    assertEquals(blocks == 0, block.fadeIn)
                    expectedEdge = if (direction > 0) block.endFrame else block.firstFrame
                    val next = pcm.render(block) { from, until ->
                        val readFirst = (from * sampleRate).roundToInt()
                        val readEnd = (until * sampleRate).roundToInt()
                        assertTrue("Read escaped selected PCM", readFirst >= first && readEnd <= end)
                        assertTrue("Refill exceeds 160 ms", readEnd - readFirst <= sampleRate * 160 / 1000)
                        reads++
                        source.copyOfRange(readFirst * frameBytes, readEnd * frameBytes)
                    }
                    output.write(next)
                    blocks++
                }
            }
            assertTrue("Must cross multiple output blocks and use the bounded source cache", blocks >= 16 && reads in 3 until blocks)
            val reference = normalPcm(source, first, count, channels)
            val oriented = if (direction > 0) reference else reverseFrames(reference, channels)
            val observed = output.toByteArray()
            assertEquals(oriented.size, observed.size)
            // Treat the short admission/end taper separately from the steady audio contract.
            val edgeFrames = sampleRate * 2 / 1000
            val edgeBytes = edgeFrames * frameBytes
            assertArrayEquals("rate=$sampleRate direction=$direction", oriented.copyOfRange(edgeBytes, observed.size - edgeBytes),
                observed.copyOfRange(edgeBytes, observed.size - edgeBytes))
            for (channel in 0 until channels) {
                val expected = samples(oriented, channels, channel)
                val actual = samples(observed, channels, channel)
                assertEquals(0, actual.first().toInt())
                assertEquals(0, actual.last().toInt())
                for (frame in (0 until edgeFrames) + (count - edgeFrames until count)) {
                    assertTrue("Start/end taper clipped or inverted frame $frame",
                        abs(actual[frame].toInt()) <= abs(expected[frame].toInt()) && actual[frame] * expected[frame] >= 0)
                }
                val nearEdge = edgeFrames / 4
                for (frame in (0 until nearEdge) + (count - nearEdge until count)) {
                    assertTrue("Abrupt edge at frame $frame",
                        abs(actual[frame].toInt()) <= abs(expected[frame].toInt()) * .20 + 1)
                }
            }
        }
    }

    @Test fun rendererRealizesTheCommandedPitchAndKeepsStereoTonesSeparated() {
        for (sampleRate in listOf(48_000, 96_000)) for (direction in listOf(1, -1)) {
            val source = pcm16(sampleRate / 16, 2) { frame, channel ->
                val tone = if (channel == 0) 731.0 else 1_811.0
                (13_000 * sin(2 * PI * tone * frame / sampleRate + channel * .37)).roundToInt()
            }
            for (speed in listOf(.5f, 1f, 2f, 8f, 40f)) {
                val signedRate = speed * direction
                val pitch = shuttleAudibleSpeed(signedRate).toDouble()
                val result = transformShuttlePcm16(source, signedRate, channelCount = 2)
                val left = samples(result, 2, 0)
                val right = samples(result, 2, 1)
                for ((channel, baseHz) in listOf(left to 731.0, right to 1_811.0)) {
                    val expectedHz = baseHz * pitch
                    val actualHz = risingCrossingFrequency(channel, sampleRate)
                    assertEquals("pitch at $signedRate, $sampleRate Hz", expectedHz, actualHz, expectedHz * .008)
                    val rms = rms(channel)
                    assertTrue("RMS at $signedRate, $sampleRate Hz was $rms", rms in 8_750.0..9_650.0)
                    assertTrue("Unexpected clipping at $signedRate, $sampleRate Hz", channel.all { abs(it.toInt()) <= 14_000 })
                }
            }
        }
    }

    @Test fun cappedPitchRejectsAliasingPerChannelAndDoesNotLeakAcrossStereo() {
        for (sampleRate in listOf(48_000, 96_000)) for (rate in listOf(4096f, -4096f)) {
            val pitch = shuttleAudibleSpeed(rate).toDouble()
            assertTrue("The high-speed fixture must exercise a pitch change", pitch > 1.02)
            val stopbandCyclesPerFrame = (0.5 / pitch + 0.5) / 2
            val source = pcm16(sampleRate / 16, 2) { frame, channel ->
                // At maximum pitch the right-hand tone is in the converter's stopband. Without
                // anti-aliasing it folds into a strong audible tone; the left stays in band.
                val cyclesPerFrame = if (channel == 0) 1_101.0 / sampleRate else stopbandCyclesPerFrame
                (14_000 * sin(2 * PI * cyclesPerFrame * frame)).roundToInt()
            }
            val output = transformShuttlePcm16(source, rate, channelCount = 2)
            val passband = rms(samples(output, 2, 0))
            val stopband = rms(samples(output, 2, 1))
            assertTrue("passband at $rate, $sampleRate Hz was $passband", passband in 9_350.0..10_450.0)
            assertTrue("alias or leakage at $rate, $sampleRate Hz was $stopband", stopband < passband * .02)
        }
    }

    private fun normalPcm(source: ByteArray, first: Int, count: Int, channels: Int): ByteArray {
        val frameBytes = channels * 2
        val output = ByteArrayOutputStream()
        streamBoundedPcmFrames(PcmPreviewFrameRange(first.toLong(), (first + count).toLong()),
            frameBytes, maximumBatchFrames = 127, checkCurrent = {},
            readFrames = { offset, frames -> source.copyOfRange(offset.toInt() * frameBytes,
                (offset.toInt() + frames) * frameBytes) },
            write = { bytes, offset, length ->
                val accepted = minOf(length, 47 * frameBytes)
                output.write(bytes, offset, accepted)
                accepted
            })
        return output.toByteArray()
    }

    private fun reverseFrames(input: ByteArray, channels: Int): ByteArray {
        val frameBytes = channels * 2
        val frameCount = input.size / frameBytes
        val output = ByteArray(input.size)
        repeat(frameCount) { index ->
            val start = (frameCount - 1 - index) * frameBytes
            input.copyInto(output, index * frameBytes, start, start + frameBytes)
        }
        return output
    }

    private fun pcm16(frames: Int, channels: Int, sample: (Int, Int) -> Int): ByteArray =
        ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame -> repeat(channels) { channel -> putShort(sample(frame, channel).toShort()) } }
        }.array()

    private fun samples(bytes: ByteArray, channels: Int, channel: Int): ShortArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(input.limit() / channels) { input.get(it * channels + channel) }
    }

    private fun raisedCosine(distance: Int, radius: Int): Double =
        if (abs(distance) >= radius) 0.0 else (1.0 + cos(PI * distance / radius)) / 2.0

    private fun risingCrossingFrequency(values: ShortArray, sampleRate: Int): Double {
        val crossings = mutableListOf<Double>()
        for (index in 65 until values.size - 64) {
            val prior = values[index - 1].toDouble()
            val current = values[index].toDouble()
            if (prior < 0 && current >= 0) crossings += index - 1 - prior / (current - prior)
        }
        assertTrue("Insufficient fundamental cycles", crossings.size >= 8)
        return (crossings.size - 1) * sampleRate / (crossings.last() - crossings.first())
    }

    private fun rms(values: ShortArray): Double {
        var sum = 0.0
        val end = values.size - 64
        require(end > 64)
        for (index in 64 until end) sum += values[index].toDouble() * values[index]
        return sqrt(sum / (end - 64))
    }
}
