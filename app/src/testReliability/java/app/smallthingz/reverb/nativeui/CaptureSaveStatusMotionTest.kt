package app.smallthingz.reverb.nativeui

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSaveStatusMotionTest {
    @Test fun initiallyVisibleHasNoEntryAndDoesNotAnimateNewMeasurements() {
        val value = CaptureSaveStatusMotion(true)
        value.measured(121)
        assertFalse(value.running)
        assertEquals(0, value.offsetY)
        assertEquals(1f, value.alpha, 0f)
        value.measured(200)
        assertFalse(value.running)
        assertEquals(0, value.offsetY)
    }

    @Test fun entryUsesHalfHeightIntegerOffsetAndPinnedSpringForBothTracks() {
        val value = CaptureSaveStatusMotion(false)
        value.setVisible(true)
        value.measured(121)
        assertEquals(60, value.offsetY)
        assertEquals(0f, value.alpha, 0f)
        val offset = TargetBasedAnimation(spring<IntOffset>(stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = IntOffset(1, 1)), IntOffset.VectorConverter, IntOffset(0, 60), IntOffset.Zero)
        val alpha = TargetBasedAnimation(spring<Float>(stiffness = Spring.StiffnessMediumLow),
            Float.VectorConverter, 0f, 1f)
        val start = 30_000_000L
        value.frame(start, 1f)
        for (elapsed in listOf(16_000_000L, 80_000_000L, 170_000_000L)) {
            value.frame(start + elapsed, 1f)
            assertEquals(offset.getValueFromNanos(elapsed).y, value.offsetY)
            assertEquals(alpha.getValueFromNanos(elapsed), value.alpha, 0f)
        }
    }

    @Test fun interruptedEntryStartsFullHeightExitFromCurrentVelocity() {
        val value = CaptureSaveStatusMotion(false)
        value.setVisible(true)
        value.measured(120)
        val entry = TargetBasedAnimation(spring<IntOffset>(stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = IntOffset(1, 1)), IntOffset.VectorConverter, IntOffset(0, 60), IntOffset.Zero)
        value.frame(0, 1f)
        val interruption = 80_000_000L
        value.frame(interruption, 1f)
        val current = entry.getValueFromNanos(interruption)
        val velocity: AnimationVector2D = entry.getVelocityVectorFromNanos(interruption)
        value.setVisible(false)
        assertEquals(current.y, value.offsetY)
        val exit = TargetBasedAnimation(spring<IntOffset>(stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = IntOffset(1, 1)), IntOffset.VectorConverter, current, IntOffset(0, 120),
            initialVelocityVector = velocity)
        value.frame(interruption + 50_000_000L, 1f)
        assertEquals(exit.getValueFromNanos(50_000_000L).y, value.offsetY)
        value.frame(3_000_000_000L, 1f)
        assertTrue(value.exitCompleted)
        assertFalse(value.running)
        assertEquals(120, value.offsetY)
        assertEquals(0f, value.alpha, 0f)
    }

    @Test fun zeroScaleCompletesExitAndReversalRetainsTheVisibleSubtree() {
        val value = CaptureSaveStatusMotion(true)
        value.measured(120)
        value.setVisible(false)
        value.frame(0, 1f)
        value.frame(50_000_000L, 1f)
        val partial = value.offsetY
        value.setVisible(true)
        assertFalse(value.exitCompleted)
        assertEquals(partial, value.offsetY)
        value.frame(60_000_000L, 0f)
        assertEquals(0, value.offsetY)
        assertEquals(1f, value.alpha, 0f)
        assertFalse(value.exitCompleted)
        value.setVisible(false)
        value.frame(70_000_000L, 0f)
        assertTrue(value.exitCompleted)
        assertEquals(120, value.offsetY)
    }
}
