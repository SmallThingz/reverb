package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class NativeActionSheetGeometryTest {
    @Test fun predictivePivotIsTheRootRelativeBottomAndInnerCompensationUsesItsOwnHeight() {
        val outer=nativeSheetPredictiveTransform(400f,300f,500f,500f,1f,1f)
        assertEquals(.88f,outer.scaleX,0f)
        assertEquals(.92f,outer.scaleY,0f)
        assertEquals(800f,outer.pivotY,0f)
        val inner=nativeSheetPredictiveTransform(400f,280f,500f,500f,1f,1f)
        assertEquals(.88f/(1f-24f/280f),inner.childScaleY,0f)
    }
    @Test fun overflowLayersUseEachLayersOwnHeightRatherThanOneSharedReciprocal() {
        val outer=nativeSheetPredictiveTransform(400f,300f,490f,500f,1f,0f)
        val inner=nativeSheetPredictiveTransform(400f,280f,490f,500f,1f,0f)
        assertEquals(310f/300f,outer.overflowScaleY,0f)
        assertEquals(1f/(290f/280f),inner.overflowChildScaleY,0f)
        assertNotEquals(1f/outer.overflowScaleY,inner.overflowChildScaleY)
    }
    @Test fun tooltipUsesIntegerCenterThenAboveOrBelowWithoutInventedScreenPadding() {
        assertEquals(NativeTooltipPlacement(80,66),nativeTooltipAbove(100,100,133,148,73,30,400,4))
        assertEquals(NativeTooltipPlacement(80,24),nativeTooltipAbove(100,2,133,20,73,30,400,4))
        assertEquals(NativeTooltipPlacement(2,66),nativeTooltipAbove(2,100,35,148,73,30,400,4))
        assertEquals(NativeTooltipPlacement(327,66),nativeTooltipAbove(367,100,400,148,73,30,400,4))
    }
    @Test fun tooltipRetainsTheWindowThroughExitAndEndsAtSourceValuesWhenMotionIsDisabled() {
        val motion=NativeSheetTooltipMotion()
        assertFalse(motion.shouldExist)
        motion.target(true)
        assertTrue(motion.shouldExist)
        motion.frame(1_000L,0f)
        assertEquals(1f,motion.alpha,0f);assertEquals(1f,motion.sizeScale,0f)
        motion.target(false)
        assertTrue(motion.shouldExist)
        motion.frame(2_000L,0f)
        assertFalse(motion.shouldExist)
        assertEquals(0f,motion.alpha,0f);assertEquals(.8f,motion.sizeScale,0f)
    }
}
