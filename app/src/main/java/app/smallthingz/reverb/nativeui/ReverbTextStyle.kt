/*
 * Line-height metric calculation adapted from AndroidX ui-text 1.11.2,
 * LineHeightStyleSpan.android.kt. Copyright 2019 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package app.smallthingz.reverb.nativeui

import kotlin.math.abs
import kotlin.math.ceil

internal enum class ReverbFontFamily { DEFAULT, SANS_SERIF }

internal enum class ReverbLineHeightAlignment { PROPORTIONAL, CENTER }

/**
 * Released 0.1.2 typography, without a UI-runtime dependency. Colors belong to [ReverbPalette].
 *
 * ReverbTheme creates six fresh TextStyles: they use the ui-text defaults (proportional line
 * height, trimmed first/last leading, default family), not the Material3 token defaults.
 * Inherited roles use Material3 1.4.0 TypographyTokens/TypeScaleTokens: centered, untrimmed,
 * sans-serif. All current roles have normal font style and fixed line-height mode.
 */
internal data class ReverbTextStyle(
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val fontWeight: Int,
    val letterSpacingSp: Float = 0f,
    val fontFamily: ReverbFontFamily = ReverbFontFamily.DEFAULT,
    val lineHeightAlignment: ReverbLineHeightAlignment = ReverbLineHeightAlignment.PROPORTIONAL,
    val trimLineHeightEdges: Boolean = true,
    val italic: Boolean = false,
)

private fun materialTextStyle(size: Float, height: Float, weight: Int = 400, tracking: Float = 0f) =
    ReverbTextStyle(
        fontSizeSp = size,
        lineHeightSp = height,
        fontWeight = weight,
        letterSpacingSp = tracking,
        fontFamily = ReverbFontFamily.SANS_SERIF,
        lineHeightAlignment = ReverbLineHeightAlignment.CENTER,
        trimLineHeightEdges = false,
    )

internal enum class ReverbTextRole(val style: ReverbTextStyle) {
    DISPLAY_LARGE(materialTextStyle(57f, 64f, tracking = -0.2f)),
    DISPLAY_MEDIUM(materialTextStyle(45f, 52f)),
    DISPLAY_SMALL(materialTextStyle(36f, 44f)),
    HEADLINE_LARGE(materialTextStyle(32f, 40f)),
    HEADLINE_MEDIUM(materialTextStyle(28f, 36f)),
    HEADLINE_SMALL(materialTextStyle(24f, 32f)),
    TITLE_LARGE(ReverbTextStyle(22f, 28f, 700)),
    TITLE_MEDIUM(ReverbTextStyle(16f, 24f, 500)),
    TITLE_SMALL(materialTextStyle(14f, 20f, 500, 0.1f)),
    BODY_LARGE(ReverbTextStyle(16f, 24f, 400)),
    BODY_MEDIUM(ReverbTextStyle(14f, 20f, 400)),
    BODY_SMALL(ReverbTextStyle(12f, 16f, 400)),
    LABEL_LARGE(ReverbTextStyle(14f, 20f, 600, 0.1f)),
    LABEL_MEDIUM(materialTextStyle(12f, 16f, 500, 0.5f)),
    LABEL_SMALL(materialTextStyle(11f, 16f, 500, 0.5f)),
}

/**
 * Required construction input: use the reference Density's conversions in the parity harness.
 * There is deliberately no platform-SP default. ui-unit 1.11.2 backports nonlinear font scaling
 * on every supported OS; TypedValue/TextView conversion is not an equivalent substitute.
 * The owner reapplies styles with a current adapter when density/font-scale changes.
 */
internal interface ReverbTextScale {
    val fontScale: Float
    fun spToPixels(sp: Float): Float
    fun pixelsToSp(px: Float): Float
}

internal data class ReverbTextMetrics(
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val letterSpacingEm: Float,
)

/** Mirrors ui-text 1.11.2's paragraph-level SP tracking and resolveLineHeightInPx. */
internal fun ReverbTextStyle.resolveMetrics(scale: ReverbTextScale): ReverbTextMetrics {
    val sizePx = scale.spToPixels(fontSizeSp)
    val heightPx = if (scale.fontScale > 1.05f) {
        // Nonlinear scaling preserves the requested line-height/font-size proportion.
        lineHeightSp / scale.pixelsToSp(sizePx) * sizePx
    } else {
        scale.spToPixels(lineHeightSp)
    }
    return ReverbTextMetrics(
        fontSizePx = sizePx,
        lineHeightPx = heightPx,
        letterSpacingEm = if (sizePx == 0f) 0f else scale.spToPixels(letterSpacingSp) / sizePx,
    )
}

internal data class ReverbLineHeightMetrics(
    val firstAscent: Int,
    val ascent: Int,
    val descent: Int,
    val lastDescent: Int,
)

/** Fixed-mode subset of AndroidX's line-height span; no baseline offsets or visual nudges. */
internal fun reverbLineHeightMetrics(
    ascent: Int,
    descent: Int,
    lineHeightPx: Float,
    alignment: ReverbLineHeightAlignment,
    trimEdges: Boolean,
): ReverbLineHeightMetrics {
    val currentHeight = descent - ascent
    if (currentHeight <= 0) return ReverbLineHeightMetrics(ascent, ascent, descent, descent)
    val requestedHeight = ceil(lineHeightPx).toInt()
    val difference = requestedHeight - currentHeight
    val topRatio = when (alignment) {
        ReverbLineHeightAlignment.PROPORTIONAL -> abs(ascent.toFloat()) / currentHeight
        ReverbLineHeightAlignment.CENTER -> 0.5f
    }
    val descentDifference = ceil(difference * if (difference <= 0) topRatio else 1f - topRatio).toInt()
    val targetDescent = descent + descentDifference
    val targetAscent = targetDescent - requestedHeight
    return ReverbLineHeightMetrics(
        firstAscent = if (trimEdges) ascent else targetAscent,
        ascent = targetAscent,
        descent = targetDescent,
        lastDescent = if (trimEdges) descent else targetDescent,
    )
}
