/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.smallthingz.reverb.nativeui

import android.content.Context
import android.os.Build
import kotlin.math.sqrt
import androidx.annotation.RequiresApi
import app.smallthingz.reverb.nativeui.color.Cam

/**
 * Resolved sRGB roles for the released Material3 1.4.0 palette, without Compose types.
 *
 * The API 31/34 split and fallback values follow AndroidX exactly; they are not derived from
 * a platform widget theme. Resolve again when the configuration or system palette changes.
 * See color/SOURCE.md for the pinned upstream source and license.
 */
internal data class ReverbPalette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val inversePrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val surfaceTint: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val outline: Int,
    val outlineVariant: Int,
    val scrim: Int,
    val surfaceBright: Int,
    val surfaceDim: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainerLowest: Int,
    val primaryFixed: Int,
    val primaryFixedDim: Int,
    val onPrimaryFixed: Int,
    val onPrimaryFixedVariant: Int,
    val secondaryFixed: Int,
    val secondaryFixedDim: Int,
    val onSecondaryFixed: Int,
    val onSecondaryFixedVariant: Int,
    val tertiaryFixed: Int,
    val tertiaryFixedDim: Int,
    val onTertiaryFixed: Int,
    val onTertiaryFixedVariant: Int,
) {
    val field: Int get() = surfaceContainerLow
    val raised: Int get() = surfaceContainerHigh
    val ink: Int get() = onSurface
    val muted: Int get() = onSurfaceVariant
    val border: Int get() = reverbColorWithAlpha(onSurface, 0.14f)

    // Theme and retention selectors need a distinct selected surface, not another elevation tone.
    // Keep the paired content role so both dynamic Light and Dark palettes remain readable.
    val settingsSelection: Int get() = primary
    val settingsOnSelection: Int get() = onPrimary

    companion object {
        fun resolve(context: Context, darkTheme: Boolean): ReverbPalette =
            resolve(darkTheme, Build.VERSION.SDK_INT) { id ->
                context.resources.getColor(id, context.theme)
            }

        /** Resource injection also allows each SDK mapping to be checked against the oracle. */
        // The public entry supplies the real SDK. Tests inject resource IDs without Android APIs.
        @android.annotation.SuppressLint("NewApi")
        internal fun resolve(
            darkTheme: Boolean,
            sdkInt: Int,
            readColor: (Int) -> Int,
        ): ReverbPalette = when {
            sdkInt >= 34 -> dynamic34(darkTheme, readColor)
            sdkInt >= 31 -> dynamic31(darkTheme, readColor)
            else -> fallback(darkTheme)
        }

        fun fallback(darkTheme: Boolean): ReverbPalette = if (darkTheme) DARK else LIGHT

        private val LIGHT = ReverbPalette(
            primary = 0xFF6750A4.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFFEADDFF.toInt(),
            onPrimaryContainer = 0xFF21005D.toInt(),
            inversePrimary = 0xFFD0BCFF.toInt(),
            secondary = 0xFF625B71.toInt(),
            onSecondary = 0xFFFFFFFF.toInt(),
            secondaryContainer = 0xFFE8DEF8.toInt(),
            onSecondaryContainer = 0xFF1D192B.toInt(),
            tertiary = 0xFF7D5260.toInt(),
            onTertiary = 0xFFFFFFFF.toInt(),
            tertiaryContainer = 0xFFFFD8E4.toInt(),
            onTertiaryContainer = 0xFF31111D.toInt(),
            background = 0xFFFEF7FF.toInt(),
            onBackground = 0xFF1D1B20.toInt(),
            surface = 0xFFFEF7FF.toInt(),
            onSurface = 0xFF1D1B20.toInt(),
            surfaceVariant = 0xFFE7E0EC.toInt(),
            onSurfaceVariant = 0xFF49454F.toInt(),
            surfaceTint = 0xFF6750A4.toInt(),
            inverseSurface = 0xFF322F35.toInt(),
            inverseOnSurface = 0xFFF5EFF7.toInt(),
            error = 0xFFB3261E.toInt(),
            onError = 0xFFFFFFFF.toInt(),
            errorContainer = 0xFFF9DEDC.toInt(),
            onErrorContainer = 0xFF410E0B.toInt(),
            outline = 0xFF79747E.toInt(),
            outlineVariant = 0xFFCAC4D0.toInt(),
            scrim = 0xFF000000.toInt(),
            surfaceBright = 0xFFFEF7FF.toInt(),
            surfaceDim = 0xFFDED8E1.toInt(),
            surfaceContainer = 0xFFF3EDF7.toInt(),
            surfaceContainerHigh = 0xFFECE6F0.toInt(),
            surfaceContainerHighest = 0xFFE6E0E9.toInt(),
            surfaceContainerLow = 0xFFF7F2FA.toInt(),
            surfaceContainerLowest = 0xFFFFFFFF.toInt(),
            primaryFixed = 0xFFEADDFF.toInt(),
            primaryFixedDim = 0xFFD0BCFF.toInt(),
            onPrimaryFixed = 0xFF21005D.toInt(),
            onPrimaryFixedVariant = 0xFF4F378B.toInt(),
            secondaryFixed = 0xFFE8DEF8.toInt(),
            secondaryFixedDim = 0xFFCCC2DC.toInt(),
            onSecondaryFixed = 0xFF1D192B.toInt(),
            onSecondaryFixedVariant = 0xFF4A4458.toInt(),
            tertiaryFixed = 0xFFFFD8E4.toInt(),
            tertiaryFixedDim = 0xFFEFB8C8.toInt(),
            onTertiaryFixed = 0xFF31111D.toInt(),
            onTertiaryFixedVariant = 0xFF633B48.toInt(),
        )

        private val DARK = ReverbPalette(
            primary = 0xFFD0BCFF.toInt(),
            onPrimary = 0xFF381E72.toInt(),
            primaryContainer = 0xFF4F378B.toInt(),
            onPrimaryContainer = 0xFFEADDFF.toInt(),
            inversePrimary = 0xFF6750A4.toInt(),
            secondary = 0xFFCCC2DC.toInt(),
            onSecondary = 0xFF332D41.toInt(),
            secondaryContainer = 0xFF4A4458.toInt(),
            onSecondaryContainer = 0xFFE8DEF8.toInt(),
            tertiary = 0xFFEFB8C8.toInt(),
            onTertiary = 0xFF492532.toInt(),
            tertiaryContainer = 0xFF633B48.toInt(),
            onTertiaryContainer = 0xFFFFD8E4.toInt(),
            background = 0xFF141218.toInt(),
            onBackground = 0xFFE6E0E9.toInt(),
            surface = 0xFF141218.toInt(),
            onSurface = 0xFFE6E0E9.toInt(),
            surfaceVariant = 0xFF49454F.toInt(),
            onSurfaceVariant = 0xFFCAC4D0.toInt(),
            surfaceTint = 0xFFD0BCFF.toInt(),
            inverseSurface = 0xFFE6E0E9.toInt(),
            inverseOnSurface = 0xFF322F35.toInt(),
            error = 0xFFF2B8B5.toInt(),
            onError = 0xFF601410.toInt(),
            errorContainer = 0xFF8C1D18.toInt(),
            onErrorContainer = 0xFFF9DEDC.toInt(),
            outline = 0xFF938F99.toInt(),
            outlineVariant = 0xFF49454F.toInt(),
            scrim = 0xFF000000.toInt(),
            surfaceBright = 0xFF3B383E.toInt(),
            surfaceDim = 0xFF141218.toInt(),
            surfaceContainer = 0xFF211F26.toInt(),
            surfaceContainerHigh = 0xFF2B2930.toInt(),
            surfaceContainerHighest = 0xFF36343B.toInt(),
            surfaceContainerLow = 0xFF1D1B20.toInt(),
            surfaceContainerLowest = 0xFF0F0D13.toInt(),
            primaryFixed = 0xFFEADDFF.toInt(),
            primaryFixedDim = 0xFFD0BCFF.toInt(),
            onPrimaryFixed = 0xFF21005D.toInt(),
            onPrimaryFixedVariant = 0xFF4F378B.toInt(),
            secondaryFixed = 0xFFE8DEF8.toInt(),
            secondaryFixedDim = 0xFFCCC2DC.toInt(),
            onSecondaryFixed = 0xFF1D192B.toInt(),
            onSecondaryFixedVariant = 0xFF4A4458.toInt(),
            tertiaryFixed = 0xFFFFD8E4.toInt(),
            tertiaryFixedDim = 0xFFEFB8C8.toInt(),
            onTertiaryFixed = 0xFF31111D.toInt(),
            onTertiaryFixedVariant = 0xFF633B48.toInt(),
        )

    }
}

/** Equivalent to sRGB Color.copy(alpha = alpha).toArgb(): replace alpha, with half-up rounding. */
internal fun reverbColorWithAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

@RequiresApi(34)
private fun dynamic34(darkTheme: Boolean, readColor: (Int) -> Int): ReverbPalette =
    if (darkTheme) ReverbPalette.fallback(true).copy(
        primary = readColor(android.R.color.system_primary_dark),
        onPrimary = readColor(android.R.color.system_on_primary_dark),
        primaryContainer = readColor(android.R.color.system_primary_container_dark),
        onPrimaryContainer = readColor(android.R.color.system_on_primary_container_dark),
        inversePrimary = readColor(android.R.color.system_primary_light),
        secondary = readColor(android.R.color.system_secondary_dark),
        onSecondary = readColor(android.R.color.system_on_secondary_dark),
        secondaryContainer = readColor(android.R.color.system_secondary_container_dark),
        onSecondaryContainer = readColor(android.R.color.system_on_secondary_container_dark),
        tertiary = readColor(android.R.color.system_tertiary_dark),
        onTertiary = readColor(android.R.color.system_on_tertiary_dark),
        tertiaryContainer = readColor(android.R.color.system_tertiary_container_dark),
        onTertiaryContainer = readColor(android.R.color.system_on_tertiary_container_dark),
        background = readColor(android.R.color.system_background_dark),
        onBackground = readColor(android.R.color.system_on_background_dark),
        surface = readColor(android.R.color.system_surface_dark),
        onSurface = readColor(android.R.color.system_on_surface_dark),
        surfaceVariant = readColor(android.R.color.system_surface_variant_dark),
        onSurfaceVariant = readColor(android.R.color.system_on_surface_variant_dark),
        inverseSurface = readColor(android.R.color.system_surface_light),
        inverseOnSurface = readColor(android.R.color.system_on_surface_light),
        outline = readColor(android.R.color.system_outline_dark),
        outlineVariant = readColor(android.R.color.system_outline_variant_dark),
        surfaceBright = readColor(android.R.color.system_surface_bright_dark),
        surfaceDim = readColor(android.R.color.system_surface_dim_dark),
        surfaceContainer = readColor(android.R.color.system_surface_container_dark),
        surfaceContainerHigh = readColor(android.R.color.system_surface_container_high_dark),
        surfaceContainerHighest = readColor(android.R.color.system_surface_container_highest_dark),
        surfaceContainerLow = readColor(android.R.color.system_surface_container_low_dark),
        surfaceContainerLowest = readColor(android.R.color.system_surface_container_lowest_dark),
        surfaceTint = readColor(android.R.color.system_primary_dark),
        primaryFixed = readColor(android.R.color.system_primary_fixed),
        primaryFixedDim = readColor(android.R.color.system_primary_fixed_dim),
        onPrimaryFixed = readColor(android.R.color.system_on_primary_fixed),
        onPrimaryFixedVariant = readColor(android.R.color.system_on_primary_fixed_variant),
        secondaryFixed = readColor(android.R.color.system_secondary_fixed),
        secondaryFixedDim = readColor(android.R.color.system_secondary_fixed_dim),
        onSecondaryFixed = readColor(android.R.color.system_on_secondary_fixed),
        onSecondaryFixedVariant = readColor(android.R.color.system_on_secondary_fixed_variant),
        tertiaryFixed = readColor(android.R.color.system_tertiary_fixed),
        tertiaryFixedDim = readColor(android.R.color.system_tertiary_fixed_dim),
        onTertiaryFixed = readColor(android.R.color.system_on_tertiary_fixed),
        onTertiaryFixedVariant = readColor(android.R.color.system_on_tertiary_fixed_variant),
    ) else ReverbPalette.fallback(false).copy(
        primary = readColor(android.R.color.system_primary_light),
        onPrimary = readColor(android.R.color.system_on_primary_light),
        primaryContainer = readColor(android.R.color.system_primary_container_light),
        onPrimaryContainer = readColor(android.R.color.system_on_primary_container_light),
        inversePrimary = readColor(android.R.color.system_primary_dark),
        secondary = readColor(android.R.color.system_secondary_light),
        onSecondary = readColor(android.R.color.system_on_secondary_light),
        secondaryContainer = readColor(android.R.color.system_secondary_container_light),
        onSecondaryContainer = readColor(android.R.color.system_on_secondary_container_light),
        tertiary = readColor(android.R.color.system_tertiary_light),
        onTertiary = readColor(android.R.color.system_on_tertiary_light),
        tertiaryContainer = readColor(android.R.color.system_tertiary_container_light),
        onTertiaryContainer = readColor(android.R.color.system_on_tertiary_container_light),
        background = readColor(android.R.color.system_background_light),
        onBackground = readColor(android.R.color.system_on_background_light),
        surface = readColor(android.R.color.system_surface_light),
        onSurface = readColor(android.R.color.system_on_surface_light),
        surfaceVariant = readColor(android.R.color.system_surface_variant_light),
        onSurfaceVariant = readColor(android.R.color.system_on_surface_variant_light),
        inverseSurface = readColor(android.R.color.system_surface_dark),
        inverseOnSurface = readColor(android.R.color.system_on_surface_dark),
        outline = readColor(android.R.color.system_outline_light),
        outlineVariant = readColor(android.R.color.system_outline_variant_light),
        surfaceBright = readColor(android.R.color.system_surface_bright_light),
        surfaceDim = readColor(android.R.color.system_surface_dim_light),
        surfaceContainer = readColor(android.R.color.system_surface_container_light),
        surfaceContainerHigh = readColor(android.R.color.system_surface_container_high_light),
        surfaceContainerHighest = readColor(android.R.color.system_surface_container_highest_light),
        surfaceContainerLow = readColor(android.R.color.system_surface_container_low_light),
        surfaceContainerLowest = readColor(android.R.color.system_surface_container_lowest_light),
        surfaceTint = readColor(android.R.color.system_primary_light),
        primaryFixed = readColor(android.R.color.system_primary_fixed),
        primaryFixedDim = readColor(android.R.color.system_primary_fixed_dim),
        onPrimaryFixed = readColor(android.R.color.system_on_primary_fixed),
        onPrimaryFixedVariant = readColor(android.R.color.system_on_primary_fixed_variant),
        secondaryFixed = readColor(android.R.color.system_secondary_fixed),
        secondaryFixedDim = readColor(android.R.color.system_secondary_fixed_dim),
        onSecondaryFixed = readColor(android.R.color.system_on_secondary_fixed),
        onSecondaryFixedVariant = readColor(android.R.color.system_on_secondary_fixed_variant),
        tertiaryFixed = readColor(android.R.color.system_tertiary_fixed),
        tertiaryFixedDim = readColor(android.R.color.system_tertiary_fixed_dim),
        onTertiaryFixed = readColor(android.R.color.system_on_tertiary_fixed),
        onTertiaryFixedVariant = readColor(android.R.color.system_on_tertiary_fixed_variant),
    )

@RequiresApi(31)
private fun dynamic31(darkTheme: Boolean, readColor: (Int) -> Int): ReverbPalette {
    // Material3 1.4.0 uses neutral2 (chroma 8), not neutral1, on Android 12/13.
    val neutralBase = Cam.fromInt(readColor(android.R.color.system_neutral2_600))
    fun neutralTone(tone: Float): Int = Cam.getInt(neutralBase.hue, neutralBase.chroma, tone)
    val neutralSurface = neutralTone(if (darkTheme) 6f else 98f)
    return if (darkTheme) ReverbPalette.fallback(true).copy(
        primary = readColor(android.R.color.system_accent1_200),
        onPrimary = readColor(android.R.color.system_accent1_800),
        primaryContainer = readColor(android.R.color.system_accent1_700),
        onPrimaryContainer = readColor(android.R.color.system_accent1_100),
        inversePrimary = readColor(android.R.color.system_accent1_600),
        secondary = readColor(android.R.color.system_accent2_200),
        onSecondary = readColor(android.R.color.system_accent2_800),
        secondaryContainer = readColor(android.R.color.system_accent2_700),
        onSecondaryContainer = readColor(android.R.color.system_accent2_100),
        tertiary = readColor(android.R.color.system_accent3_200),
        onTertiary = readColor(android.R.color.system_accent3_800),
        tertiaryContainer = readColor(android.R.color.system_accent3_700),
        onTertiaryContainer = readColor(android.R.color.system_accent3_100),
        background = neutralSurface,
        onBackground = readColor(android.R.color.system_neutral2_100),
        surface = neutralSurface,
        onSurface = readColor(android.R.color.system_neutral2_100),
        surfaceVariant = readColor(android.R.color.system_neutral2_700),
        onSurfaceVariant = readColor(android.R.color.system_neutral2_200),
        inverseSurface = readColor(android.R.color.system_neutral2_100),
        inverseOnSurface = readColor(android.R.color.system_neutral2_800),
        outline = readColor(android.R.color.system_neutral2_400),
        outlineVariant = readColor(android.R.color.system_neutral2_700),
        scrim = readColor(android.R.color.system_neutral2_1000),
        surfaceBright = neutralTone(24f),
        surfaceDim = neutralSurface,
        surfaceContainer = neutralTone(12f),
        surfaceContainerHigh = neutralTone(17f),
        surfaceContainerHighest = neutralTone(22f),
        surfaceContainerLow = readColor(android.R.color.system_neutral2_900),
        surfaceContainerLowest = neutralTone(4f),
        surfaceTint = readColor(android.R.color.system_accent1_200),
        primaryFixed = readColor(android.R.color.system_accent1_100),
        primaryFixedDim = readColor(android.R.color.system_accent1_200),
        onPrimaryFixed = readColor(android.R.color.system_accent1_900),
        onPrimaryFixedVariant = readColor(android.R.color.system_accent1_700),
        secondaryFixed = readColor(android.R.color.system_accent2_100),
        secondaryFixedDim = readColor(android.R.color.system_accent2_200),
        onSecondaryFixed = readColor(android.R.color.system_accent2_900),
        onSecondaryFixedVariant = readColor(android.R.color.system_accent2_700),
        tertiaryFixed = readColor(android.R.color.system_accent3_100),
        tertiaryFixedDim = readColor(android.R.color.system_accent3_200),
        onTertiaryFixed = readColor(android.R.color.system_accent3_900),
        onTertiaryFixedVariant = readColor(android.R.color.system_accent3_700),
    ) else ReverbPalette.fallback(false).copy(
        primary = readColor(android.R.color.system_accent1_600),
        onPrimary = readColor(android.R.color.system_accent1_0),
        primaryContainer = readColor(android.R.color.system_accent1_100),
        onPrimaryContainer = readColor(android.R.color.system_accent1_900),
        inversePrimary = readColor(android.R.color.system_accent1_200),
        secondary = readColor(android.R.color.system_accent2_600),
        onSecondary = readColor(android.R.color.system_accent2_0),
        secondaryContainer = readColor(android.R.color.system_accent2_100),
        onSecondaryContainer = readColor(android.R.color.system_accent2_900),
        tertiary = readColor(android.R.color.system_accent3_600),
        onTertiary = readColor(android.R.color.system_accent3_0),
        tertiaryContainer = readColor(android.R.color.system_accent3_100),
        onTertiaryContainer = readColor(android.R.color.system_accent3_900),
        background = neutralSurface,
        onBackground = readColor(android.R.color.system_neutral2_900),
        surface = neutralSurface,
        onSurface = readColor(android.R.color.system_neutral2_900),
        surfaceVariant = readColor(android.R.color.system_neutral2_100),
        onSurfaceVariant = readColor(android.R.color.system_neutral2_700),
        inverseSurface = readColor(android.R.color.system_neutral2_800),
        inverseOnSurface = readColor(android.R.color.system_neutral2_50),
        outline = readColor(android.R.color.system_neutral2_500),
        outlineVariant = readColor(android.R.color.system_neutral2_200),
        scrim = readColor(android.R.color.system_neutral2_1000),
        surfaceBright = neutralSurface,
        surfaceDim = neutralTone(87f),
        surfaceContainer = neutralTone(94f),
        surfaceContainerHigh = neutralTone(92f),
        surfaceContainerHighest = readColor(android.R.color.system_neutral2_100),
        surfaceContainerLow = neutralTone(96f),
        surfaceContainerLowest = readColor(android.R.color.system_neutral2_0),
        surfaceTint = readColor(android.R.color.system_accent1_600),
        primaryFixed = readColor(android.R.color.system_accent1_100),
        primaryFixedDim = readColor(android.R.color.system_accent1_200),
        onPrimaryFixed = readColor(android.R.color.system_accent1_900),
        onPrimaryFixedVariant = readColor(android.R.color.system_accent1_700),
        secondaryFixed = readColor(android.R.color.system_accent2_100),
        secondaryFixedDim = readColor(android.R.color.system_accent2_200),
        onSecondaryFixed = readColor(android.R.color.system_accent2_900),
        onSecondaryFixedVariant = readColor(android.R.color.system_accent2_700),
        tertiaryFixed = readColor(android.R.color.system_accent3_100),
        tertiaryFixedDim = readColor(android.R.color.system_accent3_200),
        onTertiaryFixed = readColor(android.R.color.system_accent3_900),
        onTertiaryFixedVariant = readColor(android.R.color.system_accent3_700),
    )
}

/** Keeps selection text readable while the fill crosses from a dark to a light surface or back.
 * Interpolating both foreground and fill crosses their luminance and briefly hides the label.
 * Semantic colors own the endpoints; neutral transition ink covers the gap where neither role
 * meets text contrast. This policy is shared by Theme and Time/Size, with no frame allocations.
 */
internal class SettingsSelectionInk(palette: ReverbPalette) {
    private val field = palette.field
    private val selectedFill = palette.settingsSelection
    private val idleIndication = palette.ink
    private val selected = palette.settingsOnSelection
    private val idle = palette.muted
    private val selectedLuminance = srgbLuminance(selected)
    private val idleLuminance = srgbLuminance(idle)
    private val crossover = sqrt((selectedLuminance + 0.05) * (idleLuminance + 0.05)) - 0.05
    private val lighter = if (selectedLuminance >= idleLuminance) selected else idle
    private val darker = if (selectedLuminance >= idleLuminance) idle else selected

    var indicationColor: Int = idleIndication
        private set

    fun foreground(animatedFill: Int): Int {
        val alpha = animatedFill ushr 24
        val background = composite(animatedFill, field, alpha)
        val backgroundLuminance = srgbLuminance(background)
        val preferred = when {
            alpha == 0 -> idle
            animatedFill == selectedFill -> selected
            backgroundLuminance <= crossover -> lighter
            else -> darker
        }
        fun contrast(foreground: Int): Double {
            val luminance = if (foreground == selected) selectedLuminance else idleLuminance
            return if (backgroundLuminance >= luminance)
                (backgroundLuminance + 0.05) / (luminance + 0.05)
            else (luminance + 0.05) / (backgroundLuminance + 0.05)
        }
        val alternate = if (preferred == selected) idle else selected
        val ink = when {
            contrast(preferred) >= 4.5 -> preferred
            contrast(alternate) >= 4.5 -> alternate
            backgroundLuminance <= NEUTRAL_CROSSOVER -> LIGHT_TRANSITION_INK
            else -> DARK_TRANSITION_INK
        }
        val inkLuminance = when (ink) {
            selected -> selectedLuminance
            idle -> idleLuminance
            DARK_TRANSITION_INK -> 0.0
            else -> 1.0
        }
        val indication = if (alpha == 0) idleIndication else ink
        // Focus and press can overlap: each current layer is 10%, rounded to alpha 26/255.
        // The indication is behind the glyphs, so only the surface receives these overlays.
        val stateBackground = composite(indication, composite(indication, background, 26), 26)
        val stateLuminance = srgbLuminance(stateBackground)
        val stateContrast = if (stateLuminance >= inkLuminance)
            (stateLuminance + 0.05) / (inkLuminance + 0.05)
        else (inkLuminance + 0.05) / (stateLuminance + 0.05)
        indicationColor = if (stateContrast >= 4.5) indication
            else if (backgroundLuminance < inkLuminance) DARK_TRANSITION_INK else LIGHT_TRANSITION_INK
        return ink
    }

    private fun composite(foreground: Int, background: Int, alpha: Int): Int {
        fun channel(shift: Int): Int =
            (((foreground ushr shift and 255) * alpha +
                (background ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    private companion object {
        const val LIGHT_TRANSITION_INK = -1
        const val DARK_TRANSITION_INK = -0x1000000
        val NEUTRAL_CROSSOVER = sqrt(1.05 * 0.05) - 0.05
    }
}
