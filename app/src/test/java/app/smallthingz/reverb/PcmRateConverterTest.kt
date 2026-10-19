package app.smallthingz.reverb

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class PcmRateConverterTest {
    private fun rms(values: FloatArray, skip: Int = 128): Double =
        sqrt((skip until values.size - skip).sumOf { values[it].toDouble() * values[it] } / (values.size - skip * 2))

    @Test fun supportedIntegerAndFractionalDecimationRetainsPassbandAndRejectsAliasing() {
        val pairs = listOf(96000 to 7350, 88200 to 44100, 64000 to 22050, 48000 to 16000,
            44100 to 16000, 32000 to 12000, 24000 to 8000, 22050 to 11025, 16000 to 7350)
        for ((sourceRate, targetRate) in pairs) {
            val inputFrames = sourceRate / 5
            val outputFrames = targetRate / 5
            val step = sourceRate.toDouble() / targetRate
            for (passband in listOf(true, false)) {
                val hz = if (passband) targetRate * 0.1 else minOf(targetRate * 0.6, sourceRate * 0.45)
                val input = FloatArray(inputFrames) { (0.5 * sin(2 * PI * hz * it / sourceRate)).toFloat() }
                val converter = PcmRateConverter(inputFrames, step) { frame, channel ->
                    input[frame] * if (channel == 0) 1 else -1
                }
                val output = FloatArray(outputFrames) { frame ->
                    val left = converter.sample(frame * step, 0)
                    assertEquals(-left, converter.sample(frame * step, 1), 0.000001f)
                    left
                }
                val measured = rms(output)
                if (passband) assertEquals("$sourceRate->$targetRate passband", 0.5 / sqrt(2.0), measured, 0.005)
                else assertTrue("$sourceRate->$targetRate aliases with RMS $measured", measured < 0.003)
            }
        }
    }

    @Test fun dcAndShortEndpointsStayFiniteWithBoundedSourceAccess() {
        for (frames in listOf(1, 2, 7, 127)) for (step in listOf(0.5, 1.0, 1.1, 3.0, 96000.0 / 7350)) {
            var reads = 0
            val converter = PcmRateConverter(frames, step) { frame, channel ->
                assertTrue(frame in 0 until frames)
                reads++
                if (channel == 0) 0.375f else -0.125f
            }
            for (position in listOf(0.0, (frames - 1) / 2.0, (frames - 1).toDouble())) {
                assertEquals(0.375f, converter.sample(position, 0), 0.000001f)
                assertEquals(-0.125f, converter.sample(position, 1), 0.000001f)
            }
            assertTrue(reads > 0)
        }
    }

    @Test fun extremeRatiosFailBeforeSourceAccessAndAllAppRatePairsAreAdmitted() {
        for (source in standardSampleRates()) for (target in standardSampleRates()) {
            assertTrue(pcmResamplingRadius(source.toDouble() / target) in 0..4096)
        }
        assertTrue(pcmResamplingRadius(655350.0 / 7350) <= 4096)
        for (step in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, Int.MAX_VALUE.toDouble(), Double.MAX_VALUE)) {
            assertThrows(IOException::class.java) {
                PcmRateConverter(1, step) { _, _ -> fail("Invalid ratio read PCM"); 0f }
            }
        }
    }

    @Test fun cancellationInterruptsFilteringWithoutClearingTheOwnersFlag() {
        val converter = PcmRateConverter(4800, 3.0) { _, _ -> 0.5f }
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { converter.sample(1200.0, 0) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun nestedAccessorsCannotOverwriteAnotherConvertersCoefficients() {
        val nested = PcmRateConverter(32, 1.1) { _, _ -> 0.25f }
        val outer = PcmRateConverter(32, 3.0) { _, _ -> nested.sample(7.5, 0) }
        assertEquals(0.25f, outer.sample(10.0, 0), 0.000001f)
    }
}
