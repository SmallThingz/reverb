package app.smallthingz.reverb.nativeui

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects pinned paragraph-cache reuse; Android font/layout results remain native fixture work. */
class ReverbParagraphRelayoutTest {
    private val original = Constraints(minWidth = 20, maxWidth = 200, maxHeight = 100)

    @Test fun unmeasuredOriginalHasNoReusableLayout() {
        assertTrue(needsNativeParagraphRelayout(null, original, null, false))
        assertTrue(needsNativeParagraphRelayout(original, original, null, false))
    }

    @Test fun identicalConstraintsReuseEvenAnEllipsizedParagraph() {
        assertFalse(needsNativeParagraphRelayout(original, original, 80f, true))
    }

    @Test fun increasedAvailableHeightDoesNotRebuildUnclippedText() {
        val larger = Constraints(minWidth = 20, maxWidth = 200, maxHeight = 160)
        assertFalse(needsNativeParagraphRelayout(original, larger, 80f, false))
    }

    @Test fun changedMinimumHeightOnlyChangesMeasuredBox() {
        val minimum = Constraints(minWidth = 20, maxWidth = 200, minHeight = 90, maxHeight = 100)
        assertFalse(needsNativeParagraphRelayout(original, minimum, 80f, false))
    }

    @Test fun clippingOrPreviousMaxLinesRequiresNewLayoutWhenConstraintsChange() {
        val clipped = Constraints(minWidth = 20, maxWidth = 200, maxHeight = 79)
        val taller = Constraints(minWidth = 20, maxWidth = 200, maxHeight = 160)
        assertTrue(needsNativeParagraphRelayout(original, clipped, 80f, false))
        assertTrue(needsNativeParagraphRelayout(original, taller, 80f, true))
    }

    @Test fun eitherWidthBoundCanChangeWrappingAndRequiresLayout() {
        assertTrue(needsNativeParagraphRelayout(
            original, Constraints(minWidth = 21, maxWidth = 200, maxHeight = 100), 80f, false,
        ))
        assertTrue(needsNativeParagraphRelayout(
            original, Constraints(minWidth = 20, maxWidth = 201, maxHeight = 100), 80f, false,
        ))
    }
}
