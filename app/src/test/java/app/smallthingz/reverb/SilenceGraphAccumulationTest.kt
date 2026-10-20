package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SilenceGraphAccumulationTest {
    private fun array(spectrum: SilenceSpectrum, name: String): DoubleArray =
        SilenceSpectrum::class.java.getDeclaredField(name).apply { isAccessible = true }.get(spectrum) as DoubleArray

    // Frozen pre-accumulator graph implementation: independent of graph() and its new shared core.
    private fun legacyGraph(spectrum: SilenceSpectrum): List<Float> {
        val detectedPowers = array(spectrum, "detectedPowers")
        val detectedFrequencies = array(spectrum, "detectedFrequencies")
        val fittedPowers = array(spectrum, "fittedPowers")
        val fittedFrequencies = array(spectrum, "fittedFrequencies")
        val output = FloatArray(SILENCE_GRAPH_BINS) { -120f }
        for (channel in 0 until spectrum.channels) for (bin in 1 until spectrum.powers.size) {
            val index = channel * spectrum.powers.size + bin
            val hz = detectedFrequencies[index]
            if (hz > SILENCE_MAX_HZ) continue
            if (hz < SILENCE_MIN_HZ) continue
            val bucket = (silenceFrequencyFraction(hz) * (output.size - 1)).roundToInt()
            output[bucket] = max(output[bucket], (10 * log10(detectedPowers[index].coerceAtLeast(1e-12))).toFloat())
        }
        for (index in fittedPowers.indices) {
            val hz = fittedFrequencies[index]
            if (fittedPowers[index] <= 0.0 || hz !in SILENCE_MIN_HZ.toDouble()..SILENCE_MAX_HZ.toDouble()) continue
            val bucket = (silenceFrequencyFraction(hz) * (output.size - 1)).roundToInt()
            output[bucket] = max(output[bucket], (10 * log10(fittedPowers[index].coerceAtLeast(1e-12))).toFloat())
        }
        return output.toList()
    }

    private fun initialPeaks(): FloatArray {
        val values = floatArrayOf(Float.NEGATIVE_INFINITY, -200f, -120f, -40f, -0f, 0f,
            Float.POSITIVE_INFINITY, Float.fromBits(0x7fc00001))
        return FloatArray(SILENCE_GRAPH_BINS) { values[it % values.size] }
    }

    private fun assertSameBits(message: String, expected: FloatArray, actual: FloatArray) {
        assertArrayEquals(message, expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
    }

    private fun checkGraph(spectrum: SilenceSpectrum, message: String) {
        val expected = legacyGraph(spectrum)
        assertSameBits("$message presentation", expected.toFloatArray(), spectrum.graph().toFloatArray())
        val original = initialPeaks()
        val actual = original.copyOf()
        spectrum.accumulateGraphPeaks(actual)
        assertSameBits("$message merge", FloatArray(SILENCE_GRAPH_BINS) { maxOf(original[it], expected[it]) }, actual)
    }

    private fun pcm(spectrum: SilenceSpectrum, frames: Int, fixture: Int): ByteArray {
        val out = ByteBuffer.allocate(frames * spectrum.frameBytes).order(ByteOrder.nativeOrder())
        repeat(frames) { frame -> repeat(spectrum.channels) { channel ->
            val t = frame.toDouble() / spectrum.sampleRate
            val sample = when (fixture) {
                0 -> 0.0
                1 -> 0.125
                2 -> 0.125 * sin(2 * PI * 20 * t) + 0.02 * sin(2 * PI * minOf(20_000.0, spectrum.sampleRate / 2.0) * t)
                3 -> 0.2 * sin(2 * PI * 997 * t)
                4 -> (((frame * 1103515245 + 12345) ushr 16) / 65535.0 - 0.5) * 0.2
                else -> if (frame == 0 || frame == frames - 1) 0.5 else 0.0
            } * if (channel == 0) 1 else -1
            when (spectrum.format) {
                PcmSampleFormat.PCM_8 -> out.put((128 + sample * 127).toInt().toByte())
                PcmSampleFormat.PCM_16 -> out.putShort((sample * 32767).toInt().toShort())
                PcmSampleFormat.PCM_FLOAT -> out.putFloat(sample.toFloat())
            }
        } }
        return out.array()
    }

    @Test(timeout = 60_000)
    fun graphAndAccumulationMatchLegacyAcrossPcmWindows() {
        for (rate in listOf(8_000, 11_025, 48_000, 192_000)) {
            for (channels in 1..2) for (format in PcmSampleFormat.entries) {
                val spectrum = SilenceSpectrum(rate, channels, format)
                for (frames in listOf(1, spectrum.hop - 1, spectrum.hop, spectrum.hop + 1, spectrum.size)) {
                    for (fixture in 0..5) {
                        spectrum.analyze(pcm(spectrum, frames, fixture), frames, observeBoundaries = true)
                        assertFalse(spectrum.invalidSamples)
                        checkGraph(spectrum, "$rate/$channels/$format/$frames/$fixture")
                    }
                }
            }
        }
    }

    @Test fun floorsEmptyBucketsAndPreservesExactBandEdges() {
        val spectrum = SilenceSpectrum(48_000, 2, PcmSampleFormat.PCM_FLOAT)
        val detectedPowers = array(spectrum, "detectedPowers")
        val detectedFrequencies = array(spectrum, "detectedFrequencies")
        val frequencies = doubleArrayOf(19.999, 20.0, 20.001, 19_999.999, 20_000.0, 20_000.001)
        for (i in frequencies.indices) {
            detectedFrequencies[i + 1] = frequencies[i]
            detectedPowers[i + 1] = if (i == 0 || i == frequencies.lastIndex) 1.0 else 1e-4
        }
        array(spectrum, "fittedFrequencies").apply { this[0] = 20.0; this[1] = 20_000.0 }
        array(spectrum, "fittedPowers").apply { this[0] = 1e-3; this[1] = 1e-2 }
        val expected = legacyGraph(spectrum)
        assertEquals(-30f, expected.first(), 0f)
        assertEquals(-20f, expected.last(), 0f)
        assertEquals(-120f, expected[SILENCE_GRAPH_BINS / 2], 0f)
        checkGraph(spectrum, "band edges and empty bins")
    }

    @Test fun invalidFloatSamplesRetainDetectionAndGraphBehavior() {
        val spectrum = SilenceSpectrum(16_000, 1, PcmSampleFormat.PCM_FLOAT)
        val bytes = ByteBuffer.allocate(spectrum.size * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(spectrum.size) { putFloat(when (it % 3) { 0 -> Float.NaN; 1 -> Float.POSITIVE_INFINITY; else -> Float.NEGATIVE_INFINITY }) }
        }.array()
        spectrum.analyze(bytes, spectrum.size)
        assertTrue(spectrum.invalidSamples)
        checkGraph(spectrum, "invalid samples")
        assertTrue(spectrum.invalidSamples)
    }

    @Test(timeout = 20_000)
    fun repeatedStreamingHopsAccumulateTheSamePeaks() {
        for (format in PcmSampleFormat.entries) {
            val source = SilenceSpectrum(48_000, 2, format)
            val bytes = pcm(source, source.size * 3 + 137, 3)
            val expected = initialPeaks()
            val actual = expected.copyOf()
            var callbacks = 0
            val stream = SilenceGateStream(source.sampleRate, source.channels, format, SilenceGateConfig(), object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
                override fun gap() = Unit
            }) { spectrum, _ ->
                val graph = legacyGraph(spectrum)
                for (i in expected.indices) expected[i] = maxOf(expected[i], graph[i])
                spectrum.accumulateGraphPeaks(actual)
                assertSameBits("$format hop $callbacks", expected, actual)
                callbacks++
            }
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(137 * source.frameBytes, bytes.size - offset)
                stream.accept(bytes, offset, count)
                offset += count
            }
            stream.finish()
            assertTrue(callbacks > 1)
        }
    }
}
