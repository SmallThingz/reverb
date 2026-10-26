package app.smallthingz.reverb

import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRateConverterInteriorTest {
    @Test fun interiorAndEndpointAccessMatchScalarClampingAtEveryTap() {
        for (step in listOf(1.00001, 2.0, 44100.0 / 16000, 96000.0 / 7350, 115.19)) {
            val radius = pcmResamplingRadius(step)
            for (frames in listOf(1, radius, 2 * radius, 2 * radius + 1, 2 * radius + 3, Int.MAX_VALUE)) {
                for (center in listOf(0, radius - 1, radius, frames - radius - 1, frames - radius, frames - 1)) {
                    for (fraction in listOf(0.0, 0.25, 0.5, 0.999999)) {
                        val position = center.toDouble() + fraction
                        val base = floor(position).toInt().coerceIn(0, frames - 1)
                        var tap = 0
                        val converter = PcmRateConverter(frames, step) { frame, channel ->
                            val expected = (base.toLong() + tap - radius).coerceIn(0L, frames - 1L).toInt()
                            assertEquals("frames=$frames step=$step position=$position tap=$tap", expected, frame)
                            assertEquals(1, channel)
                            tap++
                            0.375f
                        }
                        assertEquals(0.375f.toRawBits(), converter.sample(position, 1).toRawBits())
                        assertEquals(2 * radius + 1, tap)
                    }
                }
            }
        }
    }

    @Test fun accessorFailureReleasesInteriorWorkspaceForTheNextConversion() {
        val failed = PcmRateConverter(1000, 2.0) { _, _ -> throw IllegalStateException("reader failed") }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { failed.sample(400.25, 0) }
        val next = PcmRateConverter(1000, 3.0) { _, _ -> 0.125f }
        assertEquals(0.125f.toRawBits(), next.sample(500.5, 0).toRawBits())
    }
}
