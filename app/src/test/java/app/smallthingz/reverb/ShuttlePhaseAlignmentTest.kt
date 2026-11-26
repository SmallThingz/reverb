package app.smallthingz.reverb

import java.io.InterruptedIOException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttlePhaseAlignmentTest {
    // These 2x transport grains are 3.5 cycles apart at the expected 1.06x audible pitch.
    private val opposingPhaseHz = 3.5 / (.032 - .016 * 1.06)

    private data class Fixture(
        val pcm: ByteArray,
        val tail: ByteArray,
        val start: Double,
        val end: Double,
        val sampleRate: Int,
        val channels: Int,
        val rate: Float,
    ) {
        fun slice(aligned: Boolean, lower: Double = .05, upper: Double = .95): ByteArray = sliceShuttlePcm16(
            pcm, 0.0, start, end, sampleRate, channels,
            previousTail = if (aligned) tail else null,
            signedRate = rate,
            selectedStartSeconds = lower,
            selectedEndSeconds = upper,
        )
    }

    private fun fixture(
        sampleRate: Int = 48_000,
        channels: Int = 1,
        rate: Float = 2f,
        signal: (Double, Int) -> Double = { time, channel ->
            .6 * sin(2 * PI * opposingPhaseHz * time + channel * .73)
        },
    ): Fixture {
        val pcm = ByteArray(sampleRate * channels * 2)
        for (frame in 0 until sampleRate) for (channel in 0 until channels) {
            val value = (signal(frame.toDouble() / sampleRate, channel) * 32767).roundToInt()
            val index = (frame * channels + channel) * 2
            pcm[index] = value.toByte()
            pcm[index + 1] = (value shr 8).toByte()
        }
        val duration = .024 * shuttleAudibleSpeed(rate).toDouble()
        val anchor = if (rate > 0f) .25 else .65
        val firstStart = if (rate > 0f) anchor else anchor - duration
        val first = transformShuttlePcm16(
            sliceShuttlePcm16(pcm, 0.0, firstStart, firstStart + duration, sampleRate, channels), rate, channels,
        )
        val overlapBytes = (sampleRate * .008).roundToInt() * channels * 2
        val tail = first.copyOfRange(first.size - overlapBytes, first.size)
        val nextStart = firstStart + if (rate > 0f) .032 else -.032
        return Fixture(pcm, tail, nextStart, nextStart + duration, sampleRate, channels, rate)
    }

    private fun sample(pcm: ByteArray, frame: Int, channel: Int, channels: Int): Double {
        val index = (frame * channels + channel) * 2
        return ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 255)).toShort().toDouble()
    }

    private fun correlation(first: ByteArray, second: ByteArray, channels: Int, channel: Int): Double {
        val count = first.size / (channels * 2)
        var aa = 0.0; var bb = 0.0; var ab = 0.0; var sa = 0.0; var sb = 0.0
        for (frame in 0 until count) {
            val a = sample(first, frame, channel, channels)
            val b = sample(second, frame, channel, channels)
            aa += a * a; bb += b * b; ab += a * b; sa += a; sb += b
        }
        return (ab - sa * sb / count) / sqrt((aa - sa * sa / count) * (bb - sb * sb / count))
    }

    @Test fun opposingRenderedPhaseIsAlignedWithoutChangingPitchOrFrameCount() {
        for (sampleRate in listOf(48_000, 96_000)) for (rate in listOf(2f, -2f)) for (channels in 1..2) {
            val fixture = fixture(sampleRate, channels, rate)
            val before = transformShuttlePcm16(fixture.slice(false), rate, channels)
            val after = transformShuttlePcm16(fixture.slice(true), rate, channels)
            assertEquals(before.size, after.size)
            assertEquals(1.06f, shuttleAudibleSpeed(rate), 1e-6f)
            for (channel in 0 until channels) {
                assertTrue(correlation(fixture.tail, before, channels, channel) < -.98)
                assertTrue(correlation(fixture.tail, after, channels, channel) >= .98)
            }
            val overlap = fixture.tail.size / (channels * 2)
            assertEquals(
                crossfadeShuttlePcm16(fixture.tail, before, overlap, channels).output.size,
                crossfadeShuttlePcm16(fixture.tail, after, overlap, channels).output.size,
            )
        }
    }

    @Test fun unitSpeedAndQuietSamplesRemainByteIdentical() {
        for (rate in listOf(1f, -1f)) {
            val fixture = fixture(rate = rate)
            assertArrayEquals(fixture.slice(false), fixture.slice(true))
        }
        for (amplitude in listOf(0.0, .0002)) {
            val fixture = fixture { time, _ -> amplitude * sin(2 * PI * opposingPhaseHz * time) }
            assertArrayEquals(fixture.slice(false), fixture.slice(true))
        }
    }

    @Test fun dcOffsetDoesNotHideOpposingAcPhase() {
        val fixture = fixture { time, _ -> .4 + .1 * sin(2 * PI * opposingPhaseHz * time) }
        val after = transformShuttlePcm16(fixture.slice(true), fixture.rate)
        assertTrue(correlation(fixture.tail, after, 1, 0) >= .98)
    }

    @Test fun hardPannedStereoAlignsActiveChannelWithoutCreatingSignalInSilentChannel() {
        val fixture = fixture(channels = 2) { time, channel ->
            if (channel == 0) .6 * sin(2 * PI * opposingPhaseHz * time) else 0.0
        }
        val after = transformShuttlePcm16(fixture.slice(true), fixture.rate, 2)
        assertTrue(correlation(fixture.tail, after, 2, 0) >= .98)
        for (frame in 0 until after.size / 4) assertEquals(0.0, sample(after, frame, 1, 2), 0.0)
    }

    @Test fun transientHeavyOverlapKeepsOriginalSourceBytes() {
        for (rate in listOf(2f, -2f)) {
            val fixture = fixture(rate = rate) { time, _ ->
                val phase = ((time * 137) % 1 + 1) % 1
                .6 * exp(-((phase - .5) / .035) * ((phase - .5) / .035)) - .037
            }
            assertArrayEquals(fixture.slice(false), fixture.slice(true))
        }
    }

    @Test fun selectedEdgesAndEmptyOrOneFrameRangesKeepOriginalBounds() {
        val fixture = fixture()
        assertArrayEquals(fixture.slice(false), fixture.slice(true, fixture.start, fixture.end))
        val empty = sliceShuttlePcm16(fixture.pcm, 0.0, fixture.start, fixture.start, fixture.sampleRate,
            previousTail = fixture.tail, signedRate = fixture.rate, selectedStartSeconds = .05, selectedEndSeconds = .95)
        assertEquals(0, empty.size)
        val end = fixture.start + 1.0 / fixture.sampleRate
        val tiny = sliceShuttlePcm16(fixture.pcm, 0.0, fixture.start, end, fixture.sampleRate,
            previousTail = fixture.tail, signedRate = fixture.rate, selectedStartSeconds = fixture.start, selectedEndSeconds = end)
        assertEquals(2, tiny.size)
    }

    @Test fun cappedFastRatesPreserveFilteredPitchFrameCountAndSelectedEdges() {
        for (sampleRate in listOf(48_000, 96_000)) for (channels in 1..2) {
            for (rate in listOf(8f, -8f, 21f, -21f, 4096f, -4096f, Float.MAX_VALUE, -Float.MAX_VALUE)) {
                val fixture = fixture(sampleRate, channels, rate)
                val nominal = fixture.slice(false)
                val aligned = fixture.slice(true)
                val before = transformShuttlePcm16(nominal, rate, channels)
                val after = transformShuttlePcm16(aligned, rate, channels)
                assertEquals(before.size, after.size)
                assertEquals((sampleRate * .024).roundToInt() * channels * 2, after.size)
                val audible = shuttleAudibleSpeed(rate)
                assertTrue(audible in 1.14f..3f)
                if (kotlin.math.abs(rate) == Float.MAX_VALUE) assertEquals(3f, audible, 0f)
                else assertTrue(audible <= 1.75f)
                for (channel in 0 until channels) {
                    // Any accepted shift must survive the actual anti-aliasing/PCM converter.
                    if (!nominal.contentEquals(aligned)) {
                        assertTrue(correlation(fixture.tail, after, channels, channel) >= .98)
                    } else assertArrayEquals(before, after)
                }
                assertArrayEquals(nominal, fixture.slice(true, fixture.start, fixture.end))
                if (kotlin.math.abs(rate) == Float.MAX_VALUE) {
                    val capped = fixture.copy(rate = if (rate > 0f) 1e12f else -1e12f)
                    assertArrayEquals(capped.slice(true), aligned)
                    assertArrayEquals(transformShuttlePcm16(capped.slice(true), capped.rate, channels), after)
                }
            }
        }
    }

    @Test fun nonfiniteRatesKeepNominalSourceWithoutPhaseSearch() {
        val fixture = fixture(channels = 2)
        for (rate in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertArrayEquals(fixture.slice(false), fixture.copy(rate = rate).slice(true))
        }
    }

    @Test fun interruptedWorkerDoesNotStartPhaseSearch() {
        val fixture = fixture()
        Thread.currentThread().interrupt()
        try {
            fixture.slice(true)
            throw AssertionError("Interrupted phase search completed")
        } catch (_: InterruptedIOException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
