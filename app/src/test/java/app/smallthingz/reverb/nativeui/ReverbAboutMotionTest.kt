package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbAboutMotionTest {
    @Test fun closeBeforeFirstMeasureStillSchedulesOneTerminalDelivery() {
        val motion = ReverbAboutVisibilityMotion()
        motion.requestExit()
        motion.setHeight(503)
        assertTrue(motion.finished)
        assertTrue(motion.needsFrame(predictiveContinuation = false, exitDelivered = false))
        motion.frame(0, 1f)
        assertTrue(motion.exiting && motion.finished)
        assertEquals(0f, motion.alpha, 0f)
        assertFalse(motion.needsFrame(predictiveContinuation = false, exitDelivered = true))
    }

    @Test fun settledOpenThenLaterCloseStartsANewClock() {
        val motion = ReverbAboutVisibilityMotion()
        motion.setHeight(503)
        motion.frame(1_000_000_000L, 1f)
        motion.frame(1_220_000_000L, 1f)
        assertTrue(motion.finished)
        assertEquals(IntOffset.Zero, motion.translation)
        assertEquals(1f, motion.alpha, 0f)

        // Five seconds of user reading must not consume the closing animation.
        motion.requestExit()
        motion.frame(6_220_000_000L, 1f)
        assertFalse(motion.finished)
        assertEquals(IntOffset.Zero, motion.translation)
        assertEquals(1f, motion.alpha, 0f)
        motion.frame(6_315_000_000L, 1f)
        assertTrue(motion.translation.y in -502..-1)
        motion.frame(6_410_000_000L, 1f)
        assertTrue(motion.finished)
        assertEquals(IntOffset(0, -503), motion.translation)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun closeDuringEntranceRetainsLastPresentedPositionAndAlpha() {
        val motion = ReverbAboutVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 1f)
        motion.frame(80_000_000, 1f)
        val shownOffset = motion.translation
        val shownAlpha = motion.alpha
        motion.requestExit()
        motion.frame(80_000_000, 1f)
        assertEquals(shownOffset, motion.translation)
        assertEquals(shownAlpha, motion.alpha, 0f)
        motion.frame(10_000_000_000, 1f)
        assertTrue(motion.finished)
        assertEquals(IntOffset(0, -503), motion.translation)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun reducedMotionSettlesEachRequestedTransition() {
        val motion = ReverbAboutVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 0f)
        assertTrue(motion.finished)
        assertEquals(0f, motion.translation.y.toFloat(), 0f)
        motion.requestExit()
        motion.frame(10_000_000, 0f)
        assertTrue(motion.finished)
        assertEquals(-503, motion.translation.y)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun predictiveCommitOnlyDeliversAfterRemainingDistanceCompletes() {
        val motion = ReverbAboutPredictiveMotion()
        motion.started()
        motion.progressed(.75f)
        motion.committed()
        assertFalse(motion.frame(0, 1f))
        assertFalse(motion.frame(54_000_000, 1f))
        assertTrue(motion.frame(55_000_000, 1f))
        assertEquals(1f, motion.progress, 0f)
        assertTrue(motion.active) // The callback observes the terminal pose.
        motion.finishCommitCallback()
        assertFalse(motion.active)
        assertFalse(motion.frame(80_000_000, 1f)) // No duplicate terminal delivery.
    }

    @Test fun dispatcherCancellationRetiresGestureImmediatelyWithoutReturnTween() {
        val motion = ReverbAboutPredictiveMotion()
        motion.started()
        motion.progressed(.6f)
        motion.cancelled()
        assertFalse(motion.active)
        assertFalse(motion.hasContinuation)
        assertEquals(.6f, motion.progress, 0f) // Source retains value, but inactive projection is neutral.
        assertFalse(motion.frame(1_000_000_000, 1f))
        assertFalse(motion.commitCompleted)
    }

    @Test fun repeatedOrdinaryBackStartsAFreshEmptyStreamAtZero() {
        val motion = ReverbAboutPredictiveMotion()
        assertFalse(motion.ordinaryBack())
        assertFalse(motion.frame(0, 1f))
        assertFalse(motion.frame(110_000_000, 1f))
        assertTrue(motion.progress > 0f)
        assertFalse(motion.ordinaryBack())
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.frame(500_000_000, 1f))
        assertFalse(motion.frame(719_000_000, 1f))
        assertTrue(motion.frame(720_000_000, 1f))
    }

    @Test fun scaleRewindDoesNotResampleAlreadyFinishedAlphaChannel() {
        val motion = ReverbAboutVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 1f)
        motion.frame(160_000_000, 1f) // Fade has finished; offset remains in flight.
        assertEquals(1f, motion.alpha, 0f)
        motion.frame(170_000_000, 10f)
        assertEquals(1f, motion.alpha, 0f)
    }

    @Test fun interruptedChannelHoldsItsStartWhenScaleRewindsBehindStartDelay() {
        val motion = ReverbAboutVisibilityMotion()
        motion.setHeight(503)
        motion.frame(0, 1f)
        motion.frame(80_000_000, 1f)
        val offset = motion.translation
        val alpha = motion.alpha
        motion.requestExit()
        motion.frame(90_000_000, 10f) // 9ms global play time is earlier than the 80ms interruption.
        assertEquals(offset, motion.translation)
        assertEquals(alpha, motion.alpha, 0f)
    }

    @Test fun explicitCloseRetiresPredictiveContinuation() {
        val motion = ReverbAboutPredictiveMotion()
        motion.started()
        motion.progressed(.6f)
        motion.committed()
        motion.frame(0, 1f)
        motion.disable()
        assertFalse(motion.active)
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.frame(10_000_000_000, 1f))
    }

    @Test fun keyboardBackUsesTheSameFullCommitDuration() {
        val motion = ReverbAboutPredictiveMotion()
        motion.committed()
        assertFalse(motion.frame(0, 1f))
        assertFalse(motion.frame(219_000_000, 1f))
        assertTrue(motion.frame(220_000_000, 1f))
    }
    @Test fun terminalPredictiveProgressCommitsWithoutRequestingAnotherFrame() {
        val motion = ReverbAboutPredictiveMotion()
        motion.started()
        motion.progressed(1f)
        assertTrue(motion.committed())
        assertTrue(motion.commitCompleted)
        assertFalse(motion.hasContinuation)
        assertEquals(1f, motion.progress, 0f)
        assertFalse(motion.frame(0, 1f))
    }

    @Test fun positiveRemainingProgressRetainsAtLeastOneMillisecond() {
        val motion = ReverbAboutPredictiveMotion()
        motion.started()
        motion.progressed(.999f)
        assertFalse(motion.committed())
        assertFalse(motion.frame(0, 1f))
        assertTrue(motion.frame(1_000_000, 1f))
    }

    @Test fun predictiveClockRetainsAnimatableFloatScaleArithmetic() {
        val motion = ReverbAboutPredictiveMotion()
        motion.started()
        motion.progressed(.75f)
        assertFalse(motion.committed())
        assertFalse(motion.frame(0, 1f))
        // Pinned SuspendAnimation converts Long/Float before toLong: this rounds to55,000,000.
        assertTrue(motion.frame(54_999_999, 1f))
    }

}
