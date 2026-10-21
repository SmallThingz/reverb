package app.smallthingz.reverb.nativeui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The released Compose dependencies are the oracle, not another copy of the native constants. */
class ReverbPaletteTest {
    @Test
    fun fallbackRolesMatchReleasedComposeSchemes() {
        assertPalette(lightColorScheme(), ReverbPalette.fallback(false))
        assertPalette(darkColorScheme(), ReverbPalette.fallback(true))
    }

    @Test
    fun preDynamicAndroidDoesNotReadSystemPaletteResources() {
        for (dark in listOf(false, true)) {
            assertSame(ReverbPalette.fallback(dark), ReverbPalette.resolve(dark, 30) {
                throw AssertionError("Pre-31 resolver read a dynamic resource: $it")
            })
        }
    }

    @Test
    fun api34UsesSemanticResourcesAndPreservesFallbackErrorAndScrim() {
        val light = ReverbPalette.resolve(false, 34) { it }
        val dark = ReverbPalette.resolve(true, 34) { it }
        assertEquals(android.R.color.system_surface_light, light.surface)
        assertEquals(android.R.color.system_surface_dark, dark.surface)
        assertEquals(android.R.color.system_primary_dark, light.inversePrimary)
        assertEquals(android.R.color.system_primary_light, dark.inversePrimary)
        assertEquals(android.R.color.system_surface_container_low_light, light.field)
        assertEquals(android.R.color.system_surface_container_high_dark, dark.raised)
        for ((actual, baseline) in listOf(light to ReverbPalette.fallback(false), dark to ReverbPalette.fallback(true))) {
            assertEquals(baseline.error, actual.error)
            assertEquals(baseline.onError, actual.onError)
            assertEquals(baseline.errorContainer, actual.errorContainer)
            assertEquals(baseline.onErrorContainer, actual.onErrorContainer)
            assertEquals(baseline.scrim, actual.scrim)
        }
    }

    @Test
    fun chromeAliasesAndReplacementAlphaMatchCompose() {
        for (dark in listOf(false, true)) {
            val palette = ReverbPalette.fallback(dark)
            assertEquals(palette.surfaceContainerLow, palette.field)
            assertEquals(palette.surfaceContainerHigh, palette.raised)
            assertEquals(palette.onSurface, palette.ink)
            assertEquals(palette.onSurfaceVariant, palette.muted)
            assertEquals(Color(palette.onSurface).copy(alpha = 0.14f).toArgb(), palette.border)
        }
        val alphas = (0..255).map { it / 255f } +
            listOf(-1f, 0.14f, 0.12f, 0.38f, 0.84f, 2f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)
        for (channel in 0..255) {
            val input = (channel shl 24) or (channel shl 16) or ((255 - channel) shl 8) or 0x73
            for (alpha in alphas) {
                assertEquals("color=$input alpha=$alpha", Color(input).copy(alpha = alpha).toArgb(),
                    reverbColorWithAlpha(input, alpha))
            }
        }
    }

    private fun assertPalette(expected: ColorScheme, actual: ReverbPalette) {
        assertEquals("primary", expected.primary.toArgb(), actual.primary)
        assertEquals("onPrimary", expected.onPrimary.toArgb(), actual.onPrimary)
        assertEquals("primaryContainer", expected.primaryContainer.toArgb(), actual.primaryContainer)
        assertEquals("onPrimaryContainer", expected.onPrimaryContainer.toArgb(), actual.onPrimaryContainer)
        assertEquals("inversePrimary", expected.inversePrimary.toArgb(), actual.inversePrimary)
        assertEquals("secondary", expected.secondary.toArgb(), actual.secondary)
        assertEquals("onSecondary", expected.onSecondary.toArgb(), actual.onSecondary)
        assertEquals("secondaryContainer", expected.secondaryContainer.toArgb(), actual.secondaryContainer)
        assertEquals("onSecondaryContainer", expected.onSecondaryContainer.toArgb(), actual.onSecondaryContainer)
        assertEquals("tertiary", expected.tertiary.toArgb(), actual.tertiary)
        assertEquals("onTertiary", expected.onTertiary.toArgb(), actual.onTertiary)
        assertEquals("tertiaryContainer", expected.tertiaryContainer.toArgb(), actual.tertiaryContainer)
        assertEquals("onTertiaryContainer", expected.onTertiaryContainer.toArgb(), actual.onTertiaryContainer)
        assertEquals("background", expected.background.toArgb(), actual.background)
        assertEquals("onBackground", expected.onBackground.toArgb(), actual.onBackground)
        assertEquals("surface", expected.surface.toArgb(), actual.surface)
        assertEquals("onSurface", expected.onSurface.toArgb(), actual.onSurface)
        assertEquals("surfaceVariant", expected.surfaceVariant.toArgb(), actual.surfaceVariant)
        assertEquals("onSurfaceVariant", expected.onSurfaceVariant.toArgb(), actual.onSurfaceVariant)
        assertEquals("surfaceTint", expected.surfaceTint.toArgb(), actual.surfaceTint)
        assertEquals("inverseSurface", expected.inverseSurface.toArgb(), actual.inverseSurface)
        assertEquals("inverseOnSurface", expected.inverseOnSurface.toArgb(), actual.inverseOnSurface)
        assertEquals("error", expected.error.toArgb(), actual.error)
        assertEquals("onError", expected.onError.toArgb(), actual.onError)
        assertEquals("errorContainer", expected.errorContainer.toArgb(), actual.errorContainer)
        assertEquals("onErrorContainer", expected.onErrorContainer.toArgb(), actual.onErrorContainer)
        assertEquals("outline", expected.outline.toArgb(), actual.outline)
        assertEquals("outlineVariant", expected.outlineVariant.toArgb(), actual.outlineVariant)
        assertEquals("scrim", expected.scrim.toArgb(), actual.scrim)
        assertEquals("surfaceBright", expected.surfaceBright.toArgb(), actual.surfaceBright)
        assertEquals("surfaceDim", expected.surfaceDim.toArgb(), actual.surfaceDim)
        assertEquals("surfaceContainer", expected.surfaceContainer.toArgb(), actual.surfaceContainer)
        assertEquals("surfaceContainerHigh", expected.surfaceContainerHigh.toArgb(), actual.surfaceContainerHigh)
        assertEquals("surfaceContainerHighest", expected.surfaceContainerHighest.toArgb(), actual.surfaceContainerHighest)
        assertEquals("surfaceContainerLow", expected.surfaceContainerLow.toArgb(), actual.surfaceContainerLow)
        assertEquals("surfaceContainerLowest", expected.surfaceContainerLowest.toArgb(), actual.surfaceContainerLowest)
        assertEquals("primaryFixed", expected.primaryFixed.toArgb(), actual.primaryFixed)
        assertEquals("primaryFixedDim", expected.primaryFixedDim.toArgb(), actual.primaryFixedDim)
        assertEquals("onPrimaryFixed", expected.onPrimaryFixed.toArgb(), actual.onPrimaryFixed)
        assertEquals("onPrimaryFixedVariant", expected.onPrimaryFixedVariant.toArgb(), actual.onPrimaryFixedVariant)
        assertEquals("secondaryFixed", expected.secondaryFixed.toArgb(), actual.secondaryFixed)
        assertEquals("secondaryFixedDim", expected.secondaryFixedDim.toArgb(), actual.secondaryFixedDim)
        assertEquals("onSecondaryFixed", expected.onSecondaryFixed.toArgb(), actual.onSecondaryFixed)
        assertEquals("onSecondaryFixedVariant", expected.onSecondaryFixedVariant.toArgb(), actual.onSecondaryFixedVariant)
        assertEquals("tertiaryFixed", expected.tertiaryFixed.toArgb(), actual.tertiaryFixed)
        assertEquals("tertiaryFixedDim", expected.tertiaryFixedDim.toArgb(), actual.tertiaryFixedDim)
        assertEquals("onTertiaryFixed", expected.onTertiaryFixed.toArgb(), actual.onTertiaryFixed)
        assertEquals("onTertiaryFixedVariant", expected.onTertiaryFixedVariant.toArgb(), actual.onTertiaryFixedVariant)
    }
}
