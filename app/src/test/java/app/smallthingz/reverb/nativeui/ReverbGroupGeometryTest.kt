package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbGroupGeometryTest {
    @Test
    fun explicitCornerRadiusKeepsTheSameDensityAndStrokeRules() {
        val geometry = reverbGroupGeometry(300f, 200f, 2.625f, cornerRadiusDp = 20f)
        assertEquals(52.5f, geometry.radius, 0f)
        assertEquals(3f, geometry.strokeWidth, 0f)
        assertEquals(51f, geometry.strokeRadius, 0f)
    }

    @Test
    fun dpOutlineRoundsUpButCornerRetainsFractionalDensity() {
        val geometry = reverbGroupGeometry(300f, 200f, 2.625f)
        assertEquals(42f, geometry.radius, 0f)
        assertEquals(3f, geometry.strokeWidth, 0f)
        assertEquals(40.5f, geometry.strokeRadius, 0f)
        assertFalse(geometry.fillBorder)
    }

    @Test
    fun smallGroupsConstrainCornersAndOutlineToTheirActualBounds() {
        val geometry = reverbGroupGeometry(5f, 3f, 3f)
        assertEquals(1.5f, geometry.radius, 0f)
        assertEquals(2f, geometry.strokeWidth, 0f)
        assertTrue(geometry.fillBorder)
        assertTrue(geometry.contains(2.5f, 1.5f))
        assertFalse(geometry.contains(0f, 0f))
    }

    @Test
    fun wholeRowBoundsStayClickableExceptClippedOuterCorners() {
        val geometry = reverbGroupGeometry(120f, 160f, 1f)
        assertFalse(geometry.contains(0f, 0f))
        assertFalse(geometry.contains(119.9f, 0f))
        assertFalse(geometry.contains(0f, 159.9f))
        assertFalse(geometry.contains(119.9f, 159.9f))
        assertTrue(geometry.contains(0f, 80f))
        assertTrue(geometry.contains(119.9f, 80f))
        assertTrue(geometry.contains(60f, 0f))
        assertTrue(geometry.contains(60f, 159.9f))
        assertTrue(geometry.contains(16f, 0f))
        assertFalse(geometry.contains(-0.1f, 80f))
        assertFalse(geometry.contains(120f, 80f))
        assertFalse(geometry.contains(60f, 160f))
    }

    @Test
    fun emptyGroupsAndInvalidPointerCoordinatesHaveNoHitTarget() {
        assertFalse(reverbGroupGeometry(0f, 100f, 2f).contains(0f, 50f))
        assertFalse(reverbGroupGeometry(100f, 0f, 2f).contains(50f, 0f))
        val geometry = reverbGroupGeometry(100f, 100f, 1f)
        assertFalse(geometry.contains(Float.NaN, 50f))
        assertFalse(geometry.contains(50f, Float.POSITIVE_INFINITY))
        assertEquals(0f, reverbGroupGeometry(0f, 0f, 2f).strokeWidth, 0f)
    }

    @Test
    fun startDividerRemainsHalfAPhysicalPixelInEitherDirection() {
        for (density in floatArrayOf(1f, 1.5f, 2.625f, 3f)) {
            val width = 120f * density
            assertEquals(0.5f, reverbGroupStartDividerX(width, rtl = false), 0f)
            assertEquals(width - 0.5f, reverbGroupStartDividerX(width, rtl = true), 0f)
        }
    }

    @Test
    fun fullSizeGroupsClipEveryPointerTypeAtTheirOuterCorners() {
        val geometry = reverbGroupGeometry(240f, 160f, 1f)
        assertFalse(geometry.acceptsPointer(0f, 0f, touch = true, minimumTarget = 48f))
        assertFalse(geometry.acceptsPointer(0f, 0f, touch = false, minimumTarget = 48f))
        assertTrue(geometry.acceptsPointer(0f, 80f, touch = true, minimumTarget = 48f))
    }

    @Test
    fun undersizedClippedGroupsRetainTouchOnlyMinimumTargetAdmission() {
        val geometry = reverbGroupGeometry(24f, 80f, 1f)
        assertTrue(geometry.acceptsPointer(0f, 0f, touch = true, minimumTarget = 48f))
        assertFalse(geometry.acceptsPointer(0f, 0f, touch = false, minimumTarget = 48f))
        assertTrue(geometry.acceptsPointer(-12f, 40f, touch = true, minimumTarget = 48f))
        assertFalse(geometry.acceptsPointer(-12.1f, 40f, touch = true, minimumTarget = 48f))
        assertFalse(geometry.acceptsPointer(12f, -0.1f, touch = true, minimumTarget = 48f))
    }
}
