package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOnboardingPagerMathTest {
    @Test
    fun currentPageKeepsEarlierPageAtHalfButClosestSnapUsesLaterBound() {
        assertEquals(0, nativePagerCurrentPage(500, 1000))
        assertEquals(1, nativePagerCurrentPage(501, 1000))
        assertEquals(1, nativePagerCurrentPage(1500, 1000))
        assertEquals(2, nativePagerCurrentPage(1501, 1000))
        assertEquals(500f, snap(NativePagerSnapBounds(-500f, 500f)), 0f)
    }

    @Test
    fun lowVelocityUsesStrictHalfPageOverflowAndInclusive56DpThreshold() {
        val bounds = NativePagerSnapBounds(-600f, 400f)
        assertEquals(400f, snap(bounds, downX = -55f), 0f)
        assertEquals(-600f, snap(bounds, downX = -56f), 0f)
        assertEquals(-600f, snap(bounds, downX = -500f), 0f)
        assertEquals(400f, snap(bounds, downX = -501f), 0f)
        assertEquals(-600f, snap(bounds, downX = -1056f), 0f)
        assertEquals(400f, snap(bounds, downX = -1501f), 0f)
    }

    @Test
    fun flingThresholdIsDensityScaledAndOverridesGestureDirection() {
        val bounds = NativePagerSnapBounds(-600f, 400f)
        assertEquals(-600f, snap(bounds, velocity = 399f, downX = -56f), 0f)
        assertEquals(400f, snap(bounds, velocity = 400f, downX = 56f), 0f)
        assertEquals(-600f, snap(bounds, velocity = -400f, downX = -56f), 0f)
        assertEquals(-600f, snap(bounds, velocity = 799f, downX = -112f, density = 2f), 0f)
        assertEquals(400f, snap(bounds, velocity = 800f, downX = 112f, density = 2f), 0f)
    }

    @Test
    fun rtlReversesGestureSnapDirectionButNotLogicalVelocityDirection() {
        val bounds = NativePagerSnapBounds(-600f, 400f)
        assertEquals(400f, snap(bounds, downX = 501f, rtl = true), 0f)
        assertEquals(-600f, snap(bounds, downX = -501f, rtl = true), 0f)
        assertEquals(400f, snap(bounds, velocity = 400f, downX = -501f, rtl = true), 0f)
    }

    @Test
    fun snapBoundsUseVisiblePagesAndClampAtEitherEnd() {
        assertEquals(NativePagerSnapBounds(-250f, 750f),
            nativePagerSnapBounds(1250, 1000, true, true, true))
        assertEquals(NativePagerSnapBounds(0f, 0f),
            nativePagerSnapBounds(1000, 1000, true, true, true))
        assertEquals(NativePagerSnapBounds(0f, 0f),
            nativePagerSnapBounds(0, 1000, true, false, false))
        assertEquals(NativePagerSnapBounds(0f, 0f),
            nativePagerSnapBounds(2000, 1000, false, true, true))
    }

    @Test
    fun smallPagesCapPositionThresholdAndMeasuredZeroWidthSelectsFinalPage() {
        assertEquals(0.5f, nativePagerPositionThresholdFraction(50, 1f), 0f)
        assertEquals(0.112f, nativePagerPositionThresholdFraction(1000, 2f), 0f)
        assertTrue(nativePagerPositionThresholdFraction(0, 1f).isNaN())
        assertEquals(2, nativePagerCurrentPage(0, 0))
    }

    @Test
    fun animationClampsOnlyTowardTargetAsReleasedSnapDoes() {
        assertEquals(50f, nativePagerCoerceAnimationValue(75f, 50f), 0f)
        assertEquals(-25f, nativePagerCoerceAnimationValue(-25f, 50f), 0f)
        assertEquals(-50f, nativePagerCoerceAnimationValue(-75f, -50f), 0f)
        assertEquals(25f, nativePagerCoerceAnimationValue(25f, -50f), 0f)
        assertEquals(0f, nativePagerCoerceAnimationValue(25f, 0f), 0f)
    }

    private fun snap(
        bounds: NativePagerSnapBounds,
        velocity: Float = 0f,
        downX: Float = 0f,
        density: Float = 1f,
        rtl: Boolean = false,
    ) = nativePagerFinalSnapDistance(bounds, velocity, downX, 0f, 1000, density, rtl)
}
