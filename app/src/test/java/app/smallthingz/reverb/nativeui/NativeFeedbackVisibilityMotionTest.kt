package app.smallthingz.reverb.nativeui

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFeedbackVisibilityMotionTest {
    @Test fun firstEntryUsesIntegerHalfHeightAndSourceZeroAlpha() {
        val motion = NativeFeedbackVisibilityMotion()
        motion.target(true, 101)
        assertEquals(IntOffset(0, 50), motion.offset)
        assertEquals(0f, motion.alpha, 0f)
        motion.frame(9_000_000L, 1f)
        assertEquals(IntOffset(0, 50), motion.offset)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun idleHeadReplacementDoesNotReplayEntry() {
        val motion = NativeFeedbackVisibilityMotion()
        motion.target(true, 100)
        motion.frame(1L, 0f)
        assertFalse(motion.running)
        motion.target(true, 220)
        assertEquals(1f, motion.alpha, 0f)
        assertEquals(IntOffset.Zero, motion.offset)
        assertFalse(motion.running)
    }

    @Test fun replacementDuringEntryKeepsItsCurrentPose() {
        val motion = NativeFeedbackVisibilityMotion()
        motion.target(true, 101)
        motion.frame(0L, 1f)
        motion.frame(60_000_000L, 1f)
        val alpha = motion.alpha
        val offset = motion.offset
        motion.target(true, 220)
        assertEquals(alpha, motion.alpha, 0f)
        assertEquals(offset, motion.offset)
        assertTrue(motion.running)
    }

    @Test fun contentFreeExitRemainsAReversibleTransitionUntilComplete() {
        val motion = NativeFeedbackVisibilityMotion()
        motion.target(true, 100)
        motion.frame(0L, 0f)
        motion.target(false, 0)
        motion.frame(50_000_000L, 1f)
        motion.frame(100_000_000L, 1f)
        val alpha = motion.alpha
        assertTrue(alpha > 0f && alpha < 1f)
        motion.target(true, 150)
        assertEquals(alpha, motion.alpha, 0f)
        assertEquals(IntOffset.Zero, motion.offset)
        motion.frame(110_000_000L, 0f)
        assertEquals(1f, motion.alpha, 0f)
        assertTrue(motion.composed)
    }

    @Test fun completedEmptyExitDisposesAndNextEventStartsFresh() {
        val motion = NativeFeedbackVisibilityMotion()
        motion.target(true, 100)
        motion.frame(0L, 0f)
        motion.target(false, 0)
        motion.frame(20_000_000L, 0f)
        assertFalse(motion.composed)
        motion.target(true, 201)
        assertEquals(IntOffset(0, 100), motion.offset)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun UndoOrFocusSuppressionDisposesWithoutAnExit() {
        val motion = NativeFeedbackVisibilityMotion()
        motion.target(true, 100)
        motion.frame(0L, 1f)
        motion.frame(50_000_000L, 1f)
        motion.reset()
        assertFalse(motion.composed)
        assertFalse(motion.running)
        assertEquals(0f, motion.alpha, 0f)
        motion.target(true, 61)
        assertEquals(IntOffset(0, 30), motion.offset)
    }
}
