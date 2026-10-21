package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbTextStyleTest {
    @Test
    fun accessibilityBoldTextAdjustmentPreservesUndefinedAndClampsFontLimits() {
        assertEquals(400, adjustedReverbFontWeight(400, Int.MAX_VALUE))
        assertEquals(700, adjustedReverbFontWeight(400, 300))
        assertEquals(1000, adjustedReverbFontWeight(700, 400))
        assertEquals(1, adjustedReverbFontWeight(400, -500))
    }

    @Test
    fun freshAppOverridesDoNotAcquireMaterialTrackingOrUntrimmedLeading() {
        val title = ReverbTextRole.TITLE_MEDIUM.style
        assertEquals(0f, title.letterSpacingSp, 0f)
        assertEquals(ReverbFontFamily.DEFAULT, title.fontFamily)
        assertEquals(ReverbLineHeightAlignment.PROPORTIONAL, title.lineHeightAlignment)
        assertTrue(title.trimLineHeightEdges)

        val inherited = ReverbTextRole.TITLE_SMALL.style
        assertEquals(0.1f, inherited.letterSpacingSp, 0f)
        assertEquals(ReverbFontFamily.SANS_SERIF, inherited.fontFamily)
        assertEquals(ReverbLineHeightAlignment.CENTER, inherited.lineHeightAlignment)
        assertFalse(inherited.trimLineHeightEdges)
        assertEquals(600, ReverbTextRole.LABEL_LARGE.style.fontWeight)
    }

    @Test
    fun nonlinearScalingPreservesLineHeightRatioAndConvertsTrackingIndependently() {
        val requestedSp = mutableListOf<Float>()
        val scale = object : ReverbTextScale {
            override val fontScale = 1.3f
            override fun spToPixels(sp: Float): Float {
                requestedSp += sp
                return when (sp) {
                    14f -> 36f
                    0.1f -> 0.4f
                    else -> error("Unexpected direct SP conversion: $sp")
                }
            }
            override fun pixelsToSp(px: Float): Float {
                assertEquals(36f, px, 0f)
                return 14f
            }
        }
        val actual = ReverbTextRole.LABEL_LARGE.style.resolveMetrics(scale)
        assertEquals(36f, actual.fontSizePx, 0f)
        assertEquals(20f / 14f * 36f, actual.lineHeightPx, 0.00001f)
        assertEquals(0.4f / 36f, actual.letterSpacingEm, 0.00001f)
        assertEquals(listOf(14f, 0.1f), requestedSp)
    }

    @Test
    fun nonScalingPathUsesTheExplicitLineHeightConversion() {
        val scale = object : ReverbTextScale {
            override val fontScale = 1.05f
            override fun spToPixels(sp: Float) = sp * 2.5f
            override fun pixelsToSp(px: Float): Float = error("Nonlinear path must not run")
        }
        val actual = ReverbTextRole.BODY_MEDIUM.style.resolveMetrics(scale)
        assertEquals(35f, actual.fontSizePx, 0f)
        assertEquals(50f, actual.lineHeightPx, 0f)
        assertEquals(0f, actual.letterSpacingEm, 0f)
    }

    @Test
    fun proportionalLeadingKeepsOuterMetricsWhileChangingInteriorBaselines() {
        assertEquals(
            ReverbLineHeightMetrics(firstAscent = -13, ascent = -18, descent = 6, lastDescent = 4),
            reverbLineHeightMetrics(-13, 4, 24f, ReverbLineHeightAlignment.PROPORTIONAL, true),
        )
    }

    @Test
    fun centeredLeadingUsesCeilingForFractionalPhysicalHeight() {
        assertEquals(
            ReverbLineHeightMetrics(firstAscent = -17, ascent = -17, descent = 8, lastDescent = 8),
            reverbLineHeightMetrics(-13, 4, 24.01f, ReverbLineHeightAlignment.CENTER, false),
        )
    }

    @Test
    fun fixedTrimmedHeightRetainsFirstAndLastGlyphExtentsWhenRequestedHeightIsSmaller() {
        assertEquals(
            ReverbLineHeightMetrics(firstAscent = -13, ascent = -9, descent = 1, lastDescent = 4),
            reverbLineHeightMetrics(-13, 4, 10f, ReverbLineHeightAlignment.CENTER, true),
        )
    }
}
