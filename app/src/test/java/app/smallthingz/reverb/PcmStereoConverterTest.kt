package app.smallthingz.reverb

import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class PcmStereoConverterTest {
    private fun set(target: PcmStereoFrame, left: Float, right: Float) {
        target.left = left
        target.right = right
    }

    @Test fun pairedChannelsPreserveScalarBitsAndEncodedPcmAcrossRatiosAndEdges() {
        val bits = intArrayOf(0, Int.MIN_VALUE, 0x3f400000, 0xbf000000.toInt(), 1, 0x7f800000, 0xff800000.toInt(), 0x7fc01234)
        for (frames in listOf(1, 2, 7, 571)) for (step in listOf(0.5, 1.0, 1.1, 3.0, 4.0, 96000.0 / 7350)) {
            for (special in listOf(false, true)) {
                val samples = FloatArray(frames * 2) { i ->
                    if (special) Float.fromBits(bits[i % bits.size]) else ((i * 37 % 257) - 128) / 128f
                }
                var scalarReads = 0
                var pairedReads = 0
                val scalar = PcmRateConverter(frames, step) { frame, channel -> scalarReads++; samples[frame * 2 + channel] }
                val paired = PcmRateConverter(frames, step) { _, _ -> error("paired path used scalar accessor") }
                val accessor = PcmStereoFrames { frame, target -> pairedReads++; set(target, samples[frame * 2], samples[frame * 2 + 1]) }
                val actual = FloatArray(2)
                for (position in listOf(-1.25, 0.0, 0.125, 0.5, 1.75, (frames - 1) / 2.0, frames - 1.125, frames + 0.5)) {
                    val expected = floatArrayOf(scalar.sample(position, 0), scalar.sample(position, 1))
                    paired.sampleStereo(position, accessor, actual)
                    for (channel in 0..1) {
                        val message = "frames=$frames step=$step position=$position channel=$channel"
                        // Arithmetic NaN payload/sign is unspecified; all non-NaN results stay bit exact.
                        if (expected[channel].isNaN()) assertTrue(message, actual[channel].isNaN())
                        else assertEquals(message, expected[channel].toRawBits(), actual[channel].toRawBits())
                    }
                    for (format in PcmSampleFormat.entries) {
                        if (expected.any { !it.isFinite() } && format != PcmSampleFormat.PCM_FLOAT) continue
                        val a = ByteArray(format.bytesPerSample * 2)
                        val b = ByteArray(a.size)
                        for (channel in 0..1) {
                            PcmNormalizer.encodeSample(a, channel * format.bytesPerSample, expected[channel], format)
                            PcmNormalizer.encodeSample(b, channel * format.bytesPerSample, actual[channel], format)
                        }
                        assertArrayEquals(a, b)
                    }
                }
                assertEquals(scalarReads, pairedReads * 2)
            }
        }
    }

    @Test fun pairedWorkspaceSurvivesReentrantScalarAndPairedAccessors() {
        val nested = PcmRateConverter(32, 1.1) { _, channel -> if (channel == 0) 0.25f else -0.125f }
        val nestedOutput = FloatArray(2)
        val accessor = PcmStereoFrames { _, target ->
            nested.sampleStereo(7.5, PcmStereoFrames { _, inner -> set(inner, 0.25f, -0.125f) }, nestedOutput)
            set(target, nested.sample(7.5, 0), nestedOutput[1])
        }
        val paired = PcmRateConverter(32, 3.0) { _, _ -> error("unexpected scalar") }
        val scalar = PcmRateConverter(32, 3.0) { _, channel -> nested.sample(7.5, channel) }
        val output = FloatArray(2)
        paired.sampleStereo(10.125, accessor, output)
        for (channel in 0..1) assertEquals(scalar.sample(10.125, channel).toRawBits(), output[channel].toRawBits())
    }

    @Test fun pairedCancellationKeepsInterruptFlagAndDoesNotPublishOutput() {
        val converter = PcmRateConverter(4800, 4.0) { _, _ -> 0f }
        val output = floatArrayOf(13f, 17f)
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) {
                converter.sampleStereo(1200.0, PcmStereoFrames { _, _ -> error("cancelled conversion read PCM") }, output)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertArrayEquals(floatArrayOf(13f, 17f), output, 0f)
        } finally { Thread.interrupted() }
    }

    @Test fun secondObservationCancellationDoesNotPublishEitherChannel() {
        val converter = PcmRateConverter(7, 4.0) { _, _ -> 0.25f }
        repeat(255) { converter.sample(2.0, 0) }
        val output = floatArrayOf(13f, 17f)
        try {
            assertThrows(InterruptedIOException::class.java) {
                converter.sampleStereo(2.0, PcmStereoFrames { _, target ->
                    Thread.currentThread().interrupt()
                    set(target, 0.25f, -0.125f)
                }, output)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertArrayEquals(floatArrayOf(13f, 17f), output, 0f)
        } finally { Thread.interrupted() }
    }

    @Test fun decodedNonfiniteSourceRetainsExactEncodedOutput() {
        val bits = intArrayOf(0x7fc01234, 0x7f800000, 0xff800000.toInt(), Int.MIN_VALUE, 0x3f400000, 0xbf000000.toInt())
        val raw = java.nio.ByteBuffer.allocate(bits.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bits.forEach { raw.putInt(it) }
        val samples = FloatArray(bits.size) { PcmNormalizer.decodeSample(raw.array(), it * 4, PcmSampleFormat.PCM_FLOAT) }
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), samples.copyOfRange(0, 3), 0f)
        val scalar = PcmRateConverter(3, 4.0) { frame, channel -> samples[frame * 2 + channel] }
        val paired = PcmRateConverter(3, 4.0) { _, _ -> error("unexpected scalar") }
        val output = FloatArray(2)
        for (position in listOf(0.0, 0.25, 1.5, 2.0)) {
            paired.sampleStereo(position, PcmStereoFrames { frame, target -> set(target, samples[frame * 2], samples[frame * 2 + 1]) }, output)
            for (format in PcmSampleFormat.entries) {
                val expected = ByteArray(format.bytesPerSample * 2)
                val actual = ByteArray(expected.size)
                for (channel in 0..1) {
                    PcmNormalizer.encodeSample(expected, channel * format.bytesPerSample, scalar.sample(position, channel), format)
                    PcmNormalizer.encodeSample(actual, channel * format.bytesPerSample, output[channel], format)
                }
                assertArrayEquals(expected, actual)
            }
        }
    }

    @Test fun reentrantHolderIsIsolatedForLinearAndFilteredBranches() {
        for (step in listOf(0.5, 4.0)) {
            val converter = PcmRateConverter(32, step) { _, _ -> error("unexpected scalar") }
            val scalar = PcmRateConverter(32, step) { _, channel -> if (channel == 0) 0.375f else -0.125f }
            val innerOutput = FloatArray(2)
            val output = FloatArray(2)
            val reader = PcmStereoFrames { _, target ->
                target.left = 0.375f
                converter.sampleStereo(7.5, PcmStereoFrames { _, inner ->
                    assertNotSame(target, inner)
                    set(inner, 0.75f, -0.5f)
                }, innerOutput)
                target.right = -0.125f
            }
            converter.sampleStereo(10.125, reader, output)
            for (channel in 0..1) assertEquals(scalar.sample(10.125, channel).toRawBits(), output[channel].toRawBits())
        }
    }

    @Test fun failingHolderAccessorReleasesWorkspaceWithoutPublishing() {
        for (step in listOf(0.5, 4.0)) {
            val converter = PcmRateConverter(32, step) { _, _ -> 0.25f }
            val output = floatArrayOf(13f, 17f)
            assertThrows(IllegalStateException::class.java) {
                converter.sampleStereo(10.125, PcmStereoFrames { _, target ->
                    target.left = 0.75f
                    error("reader failed")
                }, output)
            }
            assertArrayEquals(floatArrayOf(13f, 17f), output, 0f)
            converter.sampleStereo(10.125, PcmStereoFrames { _, target -> set(target, 0.25f, 0.25f) }, output)
            assertEquals(converter.sample(10.125, 0).toRawBits(), output[0].toRawBits())
            assertEquals(output[0].toRawBits(), output[1].toRawBits())
        }
    }
}
