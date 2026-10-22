package app.smallthingz.nativeui.text

import app.smallthingz.nativeui.graphics.Color
import app.smallthingz.nativeui.text.font.FontWeight
import app.smallthingz.nativeui.text.style.LineHeightStyle
import app.smallthingz.nativeui.text.style.TextDecoration
import app.smallthingz.nativeui.unit.sp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStyleLayoutTest {
    @Test fun foregroundAndDecorationKeepCachedGeometry() {
        val original = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        val drawChange = original.copy(color = Color.Red, textDecoration = TextDecoration.Underline)
        assertTrue(original.hasSameLayoutAffectingAttributes(drawChange))
        assertTrue(drawChange.hasSameLayoutAffectingAttributes(original))
        assertFalse(original == drawChange)
    }

    @Test fun unresolvedDefaultsRemainDistinctForCacheInvalidation() {
        val unresolved = TextStyle(fontSize = 14.sp)
        assertFalse(unresolved.hasSameLayoutAffectingAttributes(unresolved.copy(fontWeight = FontWeight.Normal)))
        assertFalse(unresolved.hasSameLayoutAffectingAttributes(unresolved.copy(platformStyle = PlatformTextStyle(false))))
    }

    @Test fun lineHeightTrimmingAndFontTrackingInvalidateGeometry() {
        val original = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
        assertFalse(original.hasSameLayoutAffectingAttributes(original.copy(letterSpacing = .1.sp)))
        assertFalse(original.hasSameLayoutAffectingAttributes(original.copy(
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))))
        assertTrue(original.hasSameLayoutAffectingAttributes(original.copy()))
    }
}
