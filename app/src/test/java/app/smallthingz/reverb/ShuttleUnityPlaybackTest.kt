package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttleUnityPlaybackTest {
    @Test fun steadyWallRateKeepsWholeOrderedStereoFramesAcrossCacheRefillsAndCommandJitter() {
        val sourceFormats = listOf(
            Triple(44_100, PcmSampleFormat.PCM_8, 96_000),
            Triple(48_000, PcmSampleFormat.PCM_16, 48_000),
            Triple(44_100, PcmSampleFormat.PCM_16, 96_000),
            Triple(96_000, PcmSampleFormat.PCM_FLOAT, 48_000),
        )
        for ((sourceRate, format, outputRate) in sourceFormats) {
            val file = stereoWav(sourceRate, format)
            try {
                file.inputStream().channel.use { channel ->
                    val layout = readWavPcmLayout(channel)
                    val lower = 0.021
                    val upper = 0.367
                    // The fixture has unrelated PCM on both sides of the selection.
                    val sourceBounds = PcmPreviewFrameRange(
                        (lower * sourceRate).roundToInt().toLong(), (upper * sourceRate).roundToInt().toLong(),
                    )
                    val firstOut = maxOf((lower * outputRate).roundToInt(),
                        kotlin.math.ceil(sourceBounds.first.toDouble() / sourceRate * outputRate).toInt())
                    val endOut = minOf((upper * outputRate).roundToInt(),
                        kotlin.math.floor(sourceBounds.endExclusive.toDouble() / sourceRate * outputRate).toInt())
                    val startSeconds = firstOut.toDouble() / outputRate
                    val endSeconds = endOut.toDouble() / outputRate
                    fun read(start: Double, end: Double): ByteArray =
                        readWavPcm16Range(channel, layout, start, end, outputRate, sourceBounds)
                    val continuous = read(startSeconds, endSeconds)
                    for (reverse in listOf(false, true)) {
                        val output = ByteArrayOutputStream()
                        val cursor = ShuttleUnityCursor(outputRate, reverse)
                        val pcm = ShuttleUnityPcm(outputRate, channelCount = 2)
                        val rate = if (reverse) -1f else 1f
                        val start = if (reverse) endSeconds else startSeconds
                        val commandIntervals = longArrayOf(9, 19, 14, 23, 11, 17)
                        var commandAtMs = 0L
                        var nextCommandMs = commandIntervals.first()
                        var intervalIndex = 0
                        var reads = 0
                        var maxAheadFrames = 0L
                        for (nowMs in 0L..900L step 2L) {
                            if (nowMs >= nextCommandMs) {
                                commandAtMs = nextCommandMs
                                intervalIndex = (intervalIndex + 1) % commandIntervals.size
                                nextCommandMs += commandIntervals[intervalIndex]
                            }
                            val target = shuttleUnityTargetSeconds(start + rate * commandAtMs / 1_000.0,
                                rate, commandAtMs * 1_000_000, nowMs * 1_000_000)
                            while (true) {
                                val block = cursor.next(target, startSeconds, endSeconds) ?: break
                                maxAheadFrames = maxOf(maxAheadFrames,
                                    if (reverse) (target * outputRate).toLong() - block.firstFrame else
                                        block.endFrame - (target * outputRate).toLong())
                                output.write(pcm.render(block) { from, until ->
                                    reads++
                                    assertTrue(from >= startSeconds && until <= endSeconds)
                                    read(from, until)
                                })
                            }
                        }
                        val actual = output.toByteArray()
                        val frames = endOut - firstOut
                        assertEquals(frames * 4, actual.size)
                        assertTrue("Should cross multiple bounded source refills: $reads", reads in 3..12)
                        assertTrue(maxAheadFrames <= (outputRate * .035).roundToInt())
                        val taper = (outputRate * .002).roundToInt()
                        for (frame in taper until frames - taper) {
                            val expectedFrame = if (reverse) frames - 1 - frame else frame
                            for (ch in 0..1) {
                                val expected = pcm16(continuous, expectedFrame * 2 + ch)
                                val rendered = pcm16(actual, frame * 2 + ch)
                                assertTrue("$sourceRate $format at $outputRate reverse=$reverse frame=$frame ch=$ch: " +
                                    "expected $expected, heard $rendered", abs(expected - rendered) <= 1)
                            }
                        }
                    }
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test fun primingAtAnEditableEdgeAuditionsInsideThenAppendsOnlyNewlyAdmittedFrames() {
        for (reverse in listOf(false, true)) {
            val cursor = ShuttleUnityCursor(48_000, reverse)
            val at = if (reverse) .5 else 1.0
            val first = checkNotNull(cursor.next(at, .5, 1.0))
            assertEquals(1_152L, first.endFrame - first.firstFrame)
            assertTrue(first.reachesBoundary)
            assertNull(cursor.next(at, .5, 1.0))
            val expanded = checkNotNull(if (reverse) cursor.next(.47, .47, 1.0) else cursor.next(1.03, .5, 1.03))
            if (reverse) assertEquals(first.firstFrame, expanded.endFrame)
            else assertEquals(first.endFrame, expanded.firstFrame)
        }
    }

    @Test fun stalledCommandDoesNotKeepGeneratingAudioAndTrueSeekReanchors() {
        val cursor = ShuttleUnityCursor(48_000, reverse = false)
        assertEquals(48_000L, checkNotNull(cursor.next(1.0, 0.0, 6.0)).firstFrame)
        val projected = shuttleUnityTargetSeconds(1.0, 1f, 0L, 900_000_000L)
        assertEquals(1.024, projected, 1e-9)
        var blocks = 0
        while (cursor.next(projected, 0.0, 6.0) != null) blocks++
        assertEquals(2, blocks)
        assertNull(cursor.next(projected, 0.0, 6.0))
        val reanchored = checkNotNull(cursor.next(3.0, 0.0, 6.0))
        assertEquals(144_000L, reanchored.firstFrame)
        assertTrue(reanchored.fadeIn)
    }

    @Test fun audiblePitchUsesAbsoluteSourceSecondsPerWallSecond() {
        for (direction in listOf(-1f, 1f)) {
            assertTrue(shuttleUsesUnityStream(direction))
            assertEquals(1f, shuttleAudibleSpeed(direction * .25f), 0f)
            assertEquals(1f, shuttleAudibleSpeed(direction), 0f)
            assertTrue(shuttleAudibleSpeed(direction * 2f) in 1.04f..1.08f)
            assertTrue(shuttleAudibleSpeed(direction * 8f) in 1.14f..1.25f)
            assertTrue(shuttleAudibleSpeed(direction * 4_096f) in 1.5f..2f)
        }
    }

    private fun pcm16(bytes: ByteArray, sample: Int): Int {
        val index = sample * 2
        return ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun stereoWav(sourceRate: Int, format: PcmSampleFormat): File {
        val frames = sourceRate / 2
        val data = ByteArray(frames * 2 * format.bytesPerSample)
        for (frame in 0 until frames) {
            val time = frame.toDouble() / sourceRate
            val left = (0.41 * sin(2.0 * Math.PI * (413.0 + 720.0 * time) * time)).toFloat()
            val right = if (frame % 307 < 15) -.62f else
                (0.33 * sin(2.0 * Math.PI * 703.0 * time)).toFloat()
            PcmNormalizer.encodeSample(data, frame * 2 * format.bytesPerSample, left, format)
            PcmNormalizer.encodeSample(data, (frame * 2 + 1) * format.bytesPerSample, right, format)
        }
        return File.createTempFile("shuttle-unity", ".wav").apply {
            outputStream().use { output ->
                output.write(buildWavHeaderBytes(sourceRate, 2, format, data.size.toLong()))
                output.write(data)
            }
        }
    }
}
