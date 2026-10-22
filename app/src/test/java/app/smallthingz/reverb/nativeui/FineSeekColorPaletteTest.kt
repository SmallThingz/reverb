package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.graphics.Color
import app.smallthingz.nativeui.graphics.lerp
import app.smallthingz.nativeui.graphics.toArgb
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class FineSeekColorPaletteTest {
    @Test fun productionCachePreservesUncachedNativeMathAndPaintAlpha() {
        val random = Random(0x524556455242L)
        val fractions = floatArrayOf(-1f, -0f, 0f, .0001f, .01f, .1f, .3f, .5f, .7f, .99f,
            1f, 1.1f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)
        val directions = floatArrayOf(-1f, -0f, 0f, 1f, Float.NaN)
        repeat(512) {
            val primary = random.nextInt()
            val secondary = random.nextInt()
            val tertiary = random.nextInt()
            val cache = FineSeekColorPalette(primary, secondary, tertiary)
            for (fraction in fractions + random.nextFloat()) for (direction in directions) {
                val expected = uncachedFineSeekColor(primary, secondary, tertiary, direction, fraction)
                val actual = cache.color(direction, fraction)
                assertEquals("packed: palette=$it y=$direction magnitude=$fraction", expected.value, actual.value)
                assertEquals(expected.toArgb(), actual.toArgb())
                for (alpha in floatArrayOf(.018f, .035f, .055f, .12f, .25f, .31f))
                    assertEquals(expected.copy(alpha = alpha).toArgb(), actual.copy(alpha = alpha).toArgb())
            }
        }
    }

    @Test fun paletteUpdatesRefreshEachEndpointAndKeepZeroPullExact() {
        val primary = 0xff456789.toInt()
        val secondary = 0xff987654.toInt()
        val tertiary = 0xffabcdef.toInt()
        val cache = FineSeekColorPalette(primary, secondary, tertiary)
        val palettes = listOf(
            intArrayOf(primary, secondary, tertiary),
            intArrayOf(primary, secondary, tertiary), // ordinary rebind retains the palette
            intArrayOf(0xffd0bcff.toInt(), secondary, tertiary),
            intArrayOf(0xffd0bcff.toInt(), 0xff101010.toInt(), tertiary),
            intArrayOf(0xffd0bcff.toInt(), 0xff101010.toInt(), 0xff80ff40.toInt()),
            intArrayOf(primary, secondary, tertiary),
        )
        for (palette in palettes) {
            cache.update(palette[0], palette[1], palette[2])
            assertEquals(Color(palette[0]).value, cache.color(0f, .5f).value)
            for (direction in floatArrayOf(-1f, 1f)) for (fraction in floatArrayOf(0f, .17f, .5f, 1f))
                assertEquals(uncachedFineSeekColor(palette[0], palette[1], palette[2], direction, fraction).value,
                    cache.color(direction, fraction).value)
        }
    }
}

/** The actual previous onDraw expression, independent of the cache implementation. */
private fun uncachedFineSeekColor(primary: Int, secondary: Int, tertiary: Int,
                                 verticalPull: Float, magnitude: Float): Color = when {
    verticalPull < 0f -> lerp(Color(primary), Color(tertiary), magnitude)
    verticalPull > 0f -> lerp(Color(primary), Color(secondary), magnitude)
    else -> Color(primary)
}
