package app.smallthingz.reverb.nativeui

import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.colorspace.ColorSpaces
import smallthingz.ui.graphics.lerp

/**
 * Palette endpoints do not change during a drag. Retain their packed Oklab conversions rather than
 * recomputing two nonlinear conversions for every draw. Interpolation and its final sRGB conversion
 * retain the released quantization order; zero vertical pull still returns the original ARGB color.
 */
internal class FineSeekColorPalette(
    private var primary: Int,
    private var secondary: Int,
    private var tertiary: Int,
) {
    private var primaryLab = Color(primary).convert(ColorSpaces.Oklab)
    private var secondaryLab = Color(secondary).convert(ColorSpaces.Oklab)
    private var tertiaryLab = Color(tertiary).convert(ColorSpaces.Oklab)

    fun update(primary: Int, secondary: Int, tertiary: Int) {
        if (this.primary != primary) {
            primaryLab = Color(primary).convert(ColorSpaces.Oklab)
            this.primary = primary
        }
        if (this.secondary != secondary) {
            secondaryLab = Color(secondary).convert(ColorSpaces.Oklab)
            this.secondary = secondary
        }
        if (this.tertiary != tertiary) {
            tertiaryLab = Color(tertiary).convert(ColorSpaces.Oklab)
            this.tertiary = tertiary
        }
    }

    fun color(verticalPull: Float, magnitude: Float): Color = when {
        verticalPull < 0f -> lerp(primaryLab, tertiaryLab, magnitude).convert(ColorSpaces.Srgb)
        verticalPull > 0f -> lerp(primaryLab, secondaryLab, magnitude).convert(ColorSpaces.Srgb)
        else -> Color(primary)
    }
}
