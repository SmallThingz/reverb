package app.smallthingz.reverb.nativeui

import smallthingz.ui.graphics.toArgb as nativeArgb
import androidx.compose.ui.graphics.Color as ReleasedColor
import androidx.compose.ui.graphics.lerp as releasedLerp
import androidx.compose.ui.graphics.toArgb as releasedArgb
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** Production native cache versus the independent released Compose graphics engine. */
class FineSeekColorPaletteReferenceTest {
    @Test fun cachedProductionOutputsMatchReleasedPackedColorsBeforeAndAfterPaletteChanges() {
        val random = Random(0x4f4b4c41424cL)
        val fractions = floatArrayOf(-.1f, -0f, 0f, .0001f, .01f, .1f, .3f, .5f, .7f, .99f,
            1f, 1.1f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)
        val cache = FineSeekColorPalette(0, 0, 0)
        repeat(512) { paletteIndex ->
            val primary = random.nextInt()
            val secondary = random.nextInt()
            val tertiary = random.nextInt()
            cache.update(primary, secondary, tertiary)
            for (magnitude in fractions + random.nextFloat()) for (vertical in floatArrayOf(-1f, -0f, 0f, 1f, Float.NaN)) {
                val released = when {
                    vertical < 0f -> releasedLerp(ReleasedColor(primary), ReleasedColor(tertiary), magnitude)
                    vertical > 0f -> releasedLerp(ReleasedColor(primary), ReleasedColor(secondary), magnitude)
                    else -> ReleasedColor(primary)
                }
                val native = cache.color(vertical, magnitude)
                assertEquals("palette=$paletteIndex y=$vertical magnitude=$magnitude", released.value, native.value)
                assertEquals(released.releasedArgb(), native.nativeArgb())
                for (alpha in floatArrayOf(.018f, .035f, .055f, .12f, .25f, .31f))
                    assertEquals(released.copy(alpha = alpha).releasedArgb(), native.copy(alpha = alpha).nativeArgb())
            }
        }
    }
}
