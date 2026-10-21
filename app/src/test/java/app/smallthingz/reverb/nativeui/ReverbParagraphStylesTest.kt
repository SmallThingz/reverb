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
            val expected = when (role) {
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
            assertEquals(role.name, expected, ReverbParagraphStyles[role])
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
}
