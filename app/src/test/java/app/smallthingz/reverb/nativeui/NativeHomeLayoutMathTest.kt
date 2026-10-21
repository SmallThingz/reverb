package app.smallthingz.reverb.nativeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class NativeHomeLayoutMathTest {
    @Test fun constrained46dpSurfacePreservesOddMinimumResidualAt421dpi() {
        val density = 421f / 160f
        val visual = (46f * density).roundToInt()
        val minimum = (48f * density).roundToInt()
        assertEquals(121, visual)
        assertEquals(126, minimum)
        assertEquals(1, nativeMinimumInteractiveVisualOffset(visual, minimum))
    }

    @Test fun evenMinimumDifferenceCancelsAndLargerVisualNeedsNoOffset() {
        assertEquals(0, nativeMinimumInteractiveVisualOffset(46, 48))
        assertEquals(0, nativeMinimumInteractiveVisualOffset(92, 96))
        assertEquals(0, nativeMinimumInteractiveVisualOffset(128, 126))
        assertEquals(0, nativeMinimumInteractiveVisualOffset(126, 126))
    }

    @Test fun selectionWrapperCentersApparentSizeBeforeMinimumResidual() {
        val wrapperCenter = ((126 - 121) / 2f).roundToInt()
        assertEquals(4, wrapperCenter + nativeMinimumInteractiveVisualOffset(121, 126))
    }

    @Test fun rtlOddSlackUsesReversedPhysicalOrderNotMirroredRoundedLtr() {
        // 231px inner Row, 47px icon,18px spacer,151px text:15px slack.
        assertEquals(8, nativeCenteredRowChildOffset(231, 216, 0))
        assertEquals(177, nativeCenteredRowChildOffset(231, 216, 169))
        assertEquals(73, nativeCenteredRowChildOffset(231, 216, 65))
    }

    @Test fun physicalChildPositionsMatchPinnedArrangementForBothDirections() {
        val density = Density(421f / 160f)
        for (width in listOf(0, 1, 230, 231, 232, 400)) {
            for (sizes in listOf(intArrayOf(0, 0, 0), intArrayOf(47, 18, 151), intArrayOf(47, 18, 150))) {
                for (direction in LayoutDirection.entries) {
                    val expected = IntArray(sizes.size)
                    with(Arrangement.Center) { density.arrange(width, sizes, direction, expected) }
                    val indices = if (direction == LayoutDirection.Rtl) sizes.indices.reversed() else sizes.indices
                    var preceding = 0
                    for (index in indices) {
                        assertEquals("width=$width direction=$direction index=$index", expected[index],
                            nativeCenteredRowChildOffset(width, sizes.sum(), preceding))
                        preceding += sizes[index]
                    }
                }
            }
        }
    }
}
