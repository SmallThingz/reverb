package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.oppositeBufferSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverbHomeBufferMotionTest {
    @Test fun externalSelectionAfterCancelledSwipeStillSchedulesReducedMotionCompletion() {
        for (initial in BufferSlot.entries) {
            val destination = oppositeBufferSlot(initial)
            val motion = returningFromCancelledSwipe(initial)

            // A recorder-state reconciliation can change selection after animations are disabled.
            // Retargeting an already sampled tween then settles it inside bind(), before a frame.
            motion.bind(destination, isSaving = false, durationScale = 0f)
            assertEquals(destination, motion.renderedBuffer)
            assertEquals(initial, motion.displayedBuffer)
            assertTrue("HomeFrameLoop must schedule the outstanding terminal delivery", motion.running)

            assertNull(motion.frame(1_032_000_000L, 0f)) // External selection is not a navigation commit.
            assertEquals(destination, motion.displayedBuffer)
            assertFalse(motion.running)
            assertNeutralPose(motion)

            // Completion must retire the old target, so the next navigation is accepted.
            motion.requestSelection(initial, 0f)
            assertTrue(motion.running)
            assertEquals(initial, motion.frame(1_048_000_000L, 0f))
            assertEquals(initial, motion.displayedBuffer)
            assertFalse(motion.running)
            assertNull(motion.frame(1_064_000_000L, 0f))
        }
    }

    @Test fun repeatedBindingBeforeTerminalDoesNotLoseOrDuplicateCompletion() {
        val motion = returningFromCancelledSwipe(BufferSlot.ONE_SHOT)
        motion.bind(BufferSlot.LOOPING, isSaving = false, durationScale = 0f)
        motion.bind(BufferSlot.LOOPING, isSaving = true, durationScale = 0f)
        assertTrue(motion.running)
        assertNull(motion.frame(1_032_000_000L, 0f))
        assertEquals(BufferSlot.LOOPING, motion.displayedBuffer)
        assertFalse(motion.running)
        assertNull(motion.frame(1_048_000_000L, 0f))

        motion.bind(BufferSlot.LOOPING, isSaving = false, durationScale = 1f)
        motion.dragStarted()
        assertTrue(motion.dragged(100f, 1_000f))
    }

    @Test fun activeDragDoesNotScheduleAnimationOrRetireItsTarget() {
        val motion = ReverbHomeBufferMotion(BufferSlot.ONE_SHOT)
        motion.dragStarted()
        assertTrue(motion.dragged(-400f, 1_000f))
        assertFalse(motion.running)
        val pose = motion.flipFrame
        assertNull(motion.frame(1_000_000_000L, 0f))
        assertEquals(pose, motion.flipFrame)
        assertFalse(motion.running)
        assertTrue(motion.dragged(-100f, 1_000f))
    }

    @Test fun ordinaryCancelledSwipeRetiresWithoutNavigationCommit() {
        val motion = returningFromCancelledSwipe(BufferSlot.ONE_SHOT)
        assertTrue(motion.running)
        assertNull(motion.frame(1_260_000_000L, 1f))
        assertEquals(BufferSlot.ONE_SHOT, motion.displayedBuffer)
        assertFalse(motion.running)
        assertNeutralPose(motion)
        motion.requestSelection(BufferSlot.LOOPING, 1f)
        assertTrue(motion.running)
    }

    @Test fun cancelledSwipeReleasesTargetWhenAnimationsBecomeDisabled() {
        val motion = returningFromCancelledSwipe(BufferSlot.ONE_SHOT)
        assertNull(motion.frame(1_032_000_000L, 0f))
        assertEquals(BufferSlot.ONE_SHOT, motion.displayedBuffer)
        assertFalse(motion.running)
        assertNeutralPose(motion)
        assertNull(motion.frame(1_048_000_000L, 0f))
        motion.requestSelection(BufferSlot.LOOPING, 0f)
        assertEquals(BufferSlot.LOOPING, motion.frame(1_064_000_000L, 0f))
    }

    @Test fun tabNavigationDeliversExactlyOnceAtNormalAndZeroDurationScale() {
        for (scale in listOf(0f, 1f)) {
            val motion = ReverbHomeBufferMotion(BufferSlot.ONE_SHOT)
            motion.requestSelection(BufferSlot.LOOPING, scale)
            assertTrue(motion.running)
            val first = motion.frame(1_000_000_000L, scale)
            if (scale == 0f) {
                assertEquals(BufferSlot.LOOPING, first)
            } else {
                assertNull(first)
                assertEquals(BufferSlot.LOOPING, motion.frame(1_260_000_000L, scale))
            }
            assertEquals(BufferSlot.LOOPING, motion.displayedBuffer)
            assertFalse(motion.running)
            assertNeutralPose(motion)
            assertNull(motion.frame(1_520_000_000L, scale))
        }
    }

    @Test fun cancellingUnmovedDragDoesNotLeavePhantomFrameWork() {
        val motion = ReverbHomeBufferMotion(BufferSlot.ONE_SHOT)
        motion.dragStarted()
        motion.dragCancelled(0f)
        assertFalse(motion.running)
        assertNull(motion.frame(1_000_000_000L, 0f))
        motion.requestSelection(BufferSlot.LOOPING, 0f)
        assertEquals(BufferSlot.LOOPING, motion.frame(1_016_000_000L, 0f))
    }

    private fun returningFromCancelledSwipe(initial: BufferSlot): ReverbHomeBufferMotion =
        ReverbHomeBufferMotion(initial).apply {
            dragStarted()
            val delta = if (initial == BufferSlot.ONE_SHOT) -400f else 400f
            assertTrue(dragged(delta, 1_000f))
            dragCancelled(1f)
            assertNull(frame(1_000_000_000L, 1f))
            assertNull(frame(1_016_000_000L, 1f))
            assertTrue(running)
        }

    private fun assertNeutralPose(motion: ReverbHomeBufferMotion) {
        assertEquals(0f, motion.flipFrame.degrees, 0f)
        assertEquals(1f, motion.flipFrame.depthScale, 0f)
        assertEquals(.5f, motion.flipFrame.pivotFractionX, 0f)
    }
}
