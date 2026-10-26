package app.smallthingz.reverb.nativeui

import java.util.Random
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

class SrgbLuminanceTest {
    @Test fun everyChannelRetainsOriginalDoubleBits() {
        for (channel in 0..255) {
            assertEquals("channel=$channel", originalChannel(channel).toRawBits(),
                srgbLinearChannel(channel).toRawBits())
        }
    }

    @Test fun weightedChannelsAndIgnoredAlphaRetainOriginalBits() {
        val random = Random(0x4c554d494eL)
        repeat(65536) {
            val color = random.nextInt()
            assertEquals(originalLuminance(color).toRawBits(), srgbLuminance(color).toRawBits())
            assertEquals(srgbLuminance(color).toRawBits(),
                srgbLuminance(color xor -0x1000000).toRawBits())
        }
    }

    @Test fun mixedAlphaAndOverlappingStateLayersRetainContrastBits() {
        val random = Random(0x434f4e5452L)
        repeat(64) {
            val field = random.nextInt() or -0x1000000
            val fill = random.nextInt()
            val ink = random.nextInt()
            val indication = random.nextInt()
            for (alpha in 0..255) {
                val background = composite(fill, field, alpha)
                val overlaid = composite(indication, composite(indication, background, 26), 26)
                val foreground = composite(ink, overlaid, ink ushr 24)
                val expected = contrast(originalLuminance(foreground), originalLuminance(overlaid))
                val actual = contrast(srgbLuminance(foreground), srgbLuminance(overlaid))
                assertEquals(expected.toRawBits(), actual.toRawBits())
            }
        }
    }

    // Keep the original arithmetic independent of the production lookup table.
    private fun originalChannel(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private fun originalLuminance(color: Int): Double =
        0.2126 * originalChannel(color ushr 16 and 255) +
            0.7152 * originalChannel(color ushr 8 and 255) +
            0.0722 * originalChannel(color and 255)

    private fun contrast(a: Double, b: Double): Double =
        if (a >= b) (a + 0.05) / (b + 0.05) else (b + 0.05) / (a + 0.05)

    private fun composite(front: Int, back: Int, alpha: Int): Int {
        fun channel(shift: Int): Int =
            (((front ushr shift and 255) * alpha + (back ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }
}
