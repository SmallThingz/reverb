package app.smallthingz.reverb.nativeui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// Oracle constructor copied verbatim from released ReverbTheme; provenance pins its source.
private val releasedTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
)

class ReverbParagraphStylesTest {
    @Test fun allRolesEqualReleasedUnresolvedStyles() {
        for (role in ReverbTextRole.entries) {
            val expected = releasedRoleStyle(role)
            assertReleasedStyle(role.name, expected, ReverbParagraphStyles[role])
        }
    }

    @Test fun layoutCacheComparisonMatchesReleasedRolesAndDrawOnlyChanges() {
        for (first in ReverbTextRole.entries) for (second in ReverbTextRole.entries) {
            val expected = releasedRoleStyle(first).hasSameLayoutAffectingAttributes(releasedRoleStyle(second))
            val actual = ReverbParagraphStyles[first].hasSameLayoutAffectingAttributes(ReverbParagraphStyles[second])
            assertEquals("$first -> $second", expected, actual)
        }
        for (role in ReverbTextRole.entries) {
            val source = releasedRoleStyle(role)
            val native = ReverbParagraphStyles[role]
            assertEquals(source.hasSameLayoutAffectingAttributes(source.copy(
                color = androidx.compose.ui.graphics.Color.Red,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)),
                native.hasSameLayoutAffectingAttributes(native.copy(
                    color = app.smallthingz.nativeui.graphics.Color.Red,
                    textDecoration = app.smallthingz.nativeui.text.style.TextDecoration.Underline)))
            assertEquals(source.hasSameLayoutAffectingAttributes(source.copy(lineHeight = 17.sp)),
                native.hasSameLayoutAffectingAttributes(native.copy(
                    lineHeight = app.smallthingz.nativeui.unit.TextUnit(17f,
                        app.smallthingz.nativeui.unit.TextUnitType.Sp))))
        }
    }

    @Test fun freshOverridesDoNotAcquireMaterialDefaults() {
        for (role in listOf(
            ReverbTextRole.TITLE_LARGE, ReverbTextRole.TITLE_MEDIUM,
            ReverbTextRole.BODY_LARGE, ReverbTextRole.BODY_MEDIUM,
            ReverbTextRole.BODY_SMALL, ReverbTextRole.LABEL_LARGE,
        )) {
            val style = ReverbParagraphStyles[role]
            assertNull(role.name, style.fontFamily)
            assertNull(role.name, style.fontStyle)
            assertNull(role.name, style.platformStyle)
            assertNull(role.name, style.lineHeightStyle)
        }
    }

    @Test fun repeatedBindingsReuseImmutableStyles() {
        for (role in ReverbTextRole.entries) {
            assertSame(ReverbParagraphStyles[role], ReverbParagraphStyles[role])
        }
    }

    @Test fun footerDefaultTextInheritsMaterialParagraphFieldsWithoutChangingExplicitRoles() {
        val fresh = ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE]
        val footer = reverbButtonTextStyle(fresh)
        val pinnedMaterial = Typography().bodyLarge
        assertEquals(pinnedMaterial.platformStyle?.paragraphStyle?.includeFontPadding, footer.platformStyle?.includeFontPadding)
        assertLineHeightStyle("footer", pinnedMaterial.lineHeightStyle, footer.lineHeightStyle)
        assertEquals(14f, footer.fontSize.value, 0f)
        assertEquals(20f, footer.lineHeight.value, 0f)
        assertEquals(FontWeight.SemiBold.weight, footer.fontWeight?.weight)
        assertEquals(0.1f, footer.letterSpacing.value, 0f)
        assertNull(footer.fontFamily)
        assertNull(fresh.platformStyle)
        assertNull(fresh.lineHeightStyle)
    }
}

// Compare independent oracle metadata field-by-field across the production/native type boundary.
private fun assertReleasedStyle(name: String, expected: TextStyle,
                                actual: app.smallthingz.nativeui.text.TextStyle) {
    assertEquals(name, expected.color.value, actual.color.value)
    assertEquals(name, expected.fontSize.isSp, actual.fontSize.isSp)
    assertEquals(name, expected.fontSize.value, actual.fontSize.value, 0f)
    assertEquals(name, expected.letterSpacing.isSp, actual.letterSpacing.isSp)
    assertEquals(name, expected.letterSpacing.value, actual.letterSpacing.value, 0f)
    assertEquals(name, expected.lineHeight.isSp, actual.lineHeight.isSp)
    assertEquals(name, expected.lineHeight.value, actual.lineHeight.value, 0f)
    assertEquals(name, expected.fontWeight?.weight, actual.fontWeight?.weight)
    assertEquals(name, expected.fontStyle == null, actual.fontStyle == null)
    assertEquals(name, expected.fontSynthesis == null, actual.fontSynthesis == null)
    assertEquals(name, expected.fontFamily == null, actual.fontFamily == null)
    if (expected.fontFamily != null) {
        assertEquals(name, androidx.compose.ui.text.font.FontFamily.SansSerif, expected.fontFamily)
        assertEquals(name, app.smallthingz.nativeui.text.font.FontFamily.SansSerif, actual.fontFamily)
    }
    assertEquals(name, expected.platformStyle?.paragraphStyle?.includeFontPadding, actual.platformStyle?.includeFontPadding)
    assertLineHeightStyle(name, expected.lineHeightStyle, actual.lineHeightStyle)
    assertNull(name, actual.textDecoration)
    assertNull(name, actual.layoutDirection)
}

private fun assertLineHeightStyle(name: String, expected: androidx.compose.ui.text.style.LineHeightStyle?,
                                 actual: app.smallthingz.nativeui.text.style.LineHeightStyle?) {
    assertEquals(name, expected == null, actual == null)
    if (expected == null || actual == null) return
    assertEquals(name, expected.alignment == androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        actual.alignment == app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Center)
    assertEquals(name, expected.trim == androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        actual.trim == app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.None)
    assertEquals(name, expected.mode == androidx.compose.ui.text.style.LineHeightStyle.Mode.Fixed,
        actual.mode == app.smallthingz.nativeui.text.style.LineHeightStyle.Mode.Fixed)
}

private fun releasedRoleStyle(role: ReverbTextRole): TextStyle = when (role) {
                ReverbTextRole.DISPLAY_LARGE -> releasedTypography.displayLarge
                ReverbTextRole.DISPLAY_MEDIUM -> releasedTypography.displayMedium
                ReverbTextRole.DISPLAY_SMALL -> releasedTypography.displaySmall
                ReverbTextRole.HEADLINE_LARGE -> releasedTypography.headlineLarge
                ReverbTextRole.HEADLINE_MEDIUM -> releasedTypography.headlineMedium
                ReverbTextRole.HEADLINE_SMALL -> releasedTypography.headlineSmall
                ReverbTextRole.TITLE_LARGE -> releasedTypography.titleLarge
                ReverbTextRole.TITLE_MEDIUM -> releasedTypography.titleMedium
                ReverbTextRole.TITLE_SMALL -> releasedTypography.titleSmall
                ReverbTextRole.BODY_LARGE -> releasedTypography.bodyLarge
                ReverbTextRole.BODY_MEDIUM -> releasedTypography.bodyMedium
                ReverbTextRole.BODY_SMALL -> releasedTypography.bodySmall
                ReverbTextRole.LABEL_LARGE -> releasedTypography.labelLarge
                ReverbTextRole.LABEL_MEDIUM -> releasedTypography.labelMedium
                ReverbTextRole.LABEL_SMALL -> releasedTypography.labelSmall
            }
