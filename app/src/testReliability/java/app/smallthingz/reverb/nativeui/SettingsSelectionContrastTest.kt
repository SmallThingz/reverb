package app.smallthingz.reverb.nativeui

import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings selection is an intentional contrast improvement over released surface-elevation colors. */
class SettingsSelectionContrastTest {
    @Test
    fun lightAndDarkSelectionsRemainDistinctAndTheirLabelsReadable() {
        for (dark in listOf(false, true)) {
            val palette = ReverbPalette.fallback(dark)
            assertSelectionContrast(palette, "dark=$dark")
            // Selection must not recolor the containing field or unrelated elevated surfaces.
            assertEquals(palette.surfaceContainerLow, palette.field)
            assertEquals(palette.surfaceContainerHigh, palette.raised)
        }
    }

    @Test
    fun selectionRemainsReadableWithHoverFocusAndPressStateLayers() {
        for (dark in listOf(false, true)) {
            val palette = ReverbPalette.fallback(dark)
            // These are the selector's current hover/focus/ripple opacities. It has no drag state.
            for (alpha in listOf(0.08, 0.10)) {
                val background = composite(palette.settingsOnSelection, palette.settingsSelection, alpha)
                assertTrue("State label dark=$dark alpha=$alpha",
                    contrast(palette.settingsOnSelection, background) >= 4.5)
                assertTrue("State selection dark=$dark alpha=$alpha",
                    contrast(background, palette.field) >= 3.0)
            }
        }
    }

    @Test
    fun semanticDynamicPaletteUpdatesBothSelectionAndContent() {
        // Android 14+ resolves these resources from the current wallpaper/theme. Supply a different
        // valid high-contrast pair to catch accidental static fallback or surface-role coupling.
        for (dark in listOf(false, true)) {
            val fallback = ReverbPalette.fallback(dark)
            val background = if (dark) 0xFF9FD5B3.toInt() else 0xFF005233.toInt()
            val foreground = if (dark) 0xFF003822.toInt() else 0xFFFFFFFF.toInt()
            val palette = ReverbPalette.resolve(dark, 34) { resource ->
                when (resource) {
                    android.R.color.system_primary_dark, android.R.color.system_primary_light -> background
                    android.R.color.system_on_primary_dark, android.R.color.system_on_primary_light -> foreground
                    android.R.color.system_surface_container_low_dark,
                    android.R.color.system_surface_container_low_light -> fallback.field
                    else -> fallback.surface
                }
            }
            assertEquals(background, palette.settingsSelection)
            assertEquals(foreground, palette.settingsOnSelection)
            assertSelectionContrast(palette, "dynamic dark=$dark")
        }
    }

    @Test
    fun animatedForwardReverseAndInterruptedSelectionsKeepReadableInk() {
        val palettes = listOf(ReverbPalette.fallback(false), ReverbPalette.fallback(true),
            ReverbPalette.fallback(true).copy(primary = 0xFF9FD5B3.toInt(), onPrimary = 0xFF003822.toInt()))
        for (palette in palettes) {
            val ink = SettingsSelectionInk(palette)
            assertEquals(palette.muted, ink.foreground(0))
            assertEquals(palette.settingsOnSelection, ink.foreground(palette.settingsSelection))
            for (startsSelected in listOf(false, true)) {
                val start = if (startsSelected) palette.settingsSelection else 0
                val target = if (startsSelected) 0 else palette.settingsSelection
                val fill = ReleasedSpringValue.color(Color(start))
                fill.retarget(Color(target))
                for (frame in 0..240) {
                    // Include a reversal before the first spring has settled, retaining its velocity.
                    if (frame == 17) fill.retarget(Color(start))
                    fill.frame(frame * 4_000_000L)
                    assertAnimatedInk(palette, ink, fill.value.toArgb())
                }
                fill.retarget(Color(target))
                for (frame in 241..480) {
                    fill.frame(frame * 4_000_000L)
                    assertAnimatedInk(palette, ink, fill.value.toArgb())
                }
            }
            // Exercise every opacity independently, including transparent fill over the field.
            for (alpha in 0..255) {
                assertAnimatedInk(palette, ink, (palette.settingsSelection and 0xFFFFFF) or (alpha shl 24))
            }
        }
    }

    @Test
    fun themePreviewRebindKeepsContrastDuringOldToNewFillAnimation() {
        for (dark in listOf(false, true)) {
            val old = ReverbPalette.fallback(!dark)
            val next = ReverbPalette.fallback(dark)
            val ink = SettingsSelectionInk(next)
            val fill = ReleasedSpringValue.color(Color(old.settingsSelection))
            fill.retarget(Color(next.settingsSelection))
            for (frame in 0..240) {
                fill.frame(frame * 4_000_000L)
                assertAnimatedInk(next, ink, fill.value.toArgb())
            }
        }
    }

    private fun assertAnimatedInk(palette: ReverbPalette, ink: SettingsSelectionInk, fill: Int) {
        val background = composite(fill, palette.field, (fill ushr 24) / 255.0)
        val foreground = ink.foreground(fill)
        assertTrue("Animated label fill=${fill.toUInt().toString(16)}",
            contrast(foreground, background) >= 4.5)
        // State overlays sit below label/icon content, and focus plus press can coincide.
        for (layerAlpha in listOf(20 / 255.0, 26 / 255.0)) {
            val state = composite(ink.indicationColor, background, layerAlpha)
            val pressedState = composite(ink.indicationColor, state, 26 / 255.0)
            assertTrue("Animated state label fill=${fill.toUInt().toString(16)}",
                contrast(foreground, state) >= 4.5)
            assertTrue("Animated pressed state label fill=${fill.toUInt().toString(16)}",
                contrast(foreground, pressedState) >= 4.5)
        }
    }

    private fun assertSelectionContrast(palette: ReverbPalette, label: String) {
        assertTrue("$label selected/unselected contrast",
            contrast(palette.settingsSelection, palette.field) >= 3.0)
        assertTrue("$label selected label contrast",
            contrast(palette.settingsOnSelection, palette.settingsSelection) >= 4.5)
    }

    private fun contrast(a: Int, b: Int): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = (color ushr shift and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun composite(foreground: Int, background: Int, alpha: Double): Int {
        var color = 0xFF000000.toInt()
        for (shift in listOf(16, 8, 0)) {
            val front = foreground ushr shift and 0xFF
            val back = background ushr shift and 0xFF
            color = color or ((front * alpha + back * (1.0 - alpha)).roundToInt() shl shift)
        }
        return color
    }
}
