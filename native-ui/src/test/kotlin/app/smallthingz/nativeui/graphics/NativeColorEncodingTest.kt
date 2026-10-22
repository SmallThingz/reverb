package app.smallthingz.nativeui.graphics

import app.smallthingz.nativeui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeColorEncodingTest {
    @Test fun everyHalfEncodingSurvivesFloatRoundTripWithCanonicalNaNs() {
        for (bits in 0..0xffff) {
            val expected = if (bits and 0x7c00 == 0x7c00 && bits and 0x03ff != 0)
                (bits and 0x8000) or 0x7e00 else bits
            assertEquals("half=$bits", expected, floatToHalf(halfToFloat(bits.toShort())).toInt() and 0xffff)
        }
    }

    @Test fun argbStorageAndIdentityConversionRetainEveryChannelByte() {
        for (value in 0..255) {
            val argb = (value shl 24) or ((255 - value) shl 16) or (value shl 8) or (value xor 0x55)
            val color = Color(argb)
            assertEquals(argb, color.toArgb())
            assertEquals(color.value, color.convert(ColorSpaces.Srgb).value)
            assertEquals(color.value, Color(color.red, color.green, color.blue, color.alpha).value)
        }
    }

    @Test fun unspecifiedIsDistinctFromTransparentAndOklabIdentityRetainsPackedBits() {
        assertEquals(0x10UL, Color.Unspecified.value)
        assertEquals(0UL, Color.Transparent.value)
        val color = Color(0.526f, -0.12f, 0.18f, 0.37f, ColorSpaces.Oklab)
        assertEquals(color.value, color.convert(ColorSpaces.Oklab).value)
        assertEquals(19UL, color.value and 0x3fUL)
    }
}
