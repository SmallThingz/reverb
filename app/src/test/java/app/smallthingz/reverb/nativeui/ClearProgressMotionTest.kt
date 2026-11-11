package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class ClearProgressMotionTest {
    @Test fun firstBindUsesReportedProgressAndSameOperationAdvancesSmoothly() {
        val motion = ClearProgressMotion()
        motion.bind(7L, .25f, false, 1f)
        assertEquals(.25f, motion.value, 0f)
        assertFalse(motion.running)
        motion.bind(7L, .75f, false, 1f)
        assertEquals(.75f, motion.progress, 0f)
        assertEquals(.25f, motion.value, 0f)
        motion.frame(0L, 1f)
        motion.frame(100_000_000L, 1f)
        assertTrue(motion.value > .25f && motion.value < .75f)
        motion.frame(10_000_000_000L, 1f)
        assertEquals(.75f, motion.value, 0f)
        assertFalse(motion.running)
    }

    @Test fun repeatedUpdatesRetainPoseAndNeverExceedAuthoritativeProgress() {
        val motion = ClearProgressMotion()
        motion.bind(7L, 0f, false, 1f)
        var previous = 0f
        for (frame in 0..100) {
            if (frame % 5 == 0) {
                val before = motion.value
                motion.bind(7L, frame / 100f, false, 1f)
                assertEquals(before, motion.value, .000001f)
            }
            motion.frame(frame * 16_666_667L, 1f)
            assertTrue(motion.value >= previous - .000001f)
            assertTrue(motion.value <= motion.progress)
            previous = motion.value
        }
    }

    @Test fun newOperationAndRegressingSampleSnapInsteadOfReversingOldMotion() {
        val motion = ClearProgressMotion()
        motion.bind(7L, .1f, false, 1f)
        motion.bind(7L, .9f, false, 1f)
        motion.frame(0L, 1f); motion.frame(100_000_000L, 1f)
        motion.bind(8L, .2f, false, 1f)
        assertEquals(.2f, motion.value, 0f)
        assertFalse(motion.running)
        motion.bind(8L, .9f, false, 1f)
        motion.frame(200_000_000L, 1f)
        motion.bind(8L, .1f, false, 1f)
        assertEquals(.1f, motion.value, 0f)
        assertFalse(motion.running)
    }

    @Test fun terminalSuccessCancellationAndFailureUseExactReportedFraction() {
        for (terminalFraction in listOf(1f, .6f, .4f)) {
            val motion = ClearProgressMotion()
            motion.bind(7L, .1f, false, 1f)
            motion.bind(7L, terminalFraction, false, 1f)
            motion.frame(0L, 1f)
            motion.bind(7L, terminalFraction, true, 1f)
            assertEquals(terminalFraction, motion.value, 0f)
            assertFalse(motion.running)
        }
    }

    @Test fun zeroScaleSnapsSameTargetAtBindOrFrameAndDetachSnapRetiresMotion() {
        for (atBind in listOf(true, false)) {
            val motion = ClearProgressMotion()
            motion.bind(7L, 0f, false, 1f)
            motion.bind(7L, .8f, false, 1f)
            motion.frame(0L, 1f)
            if (atBind) motion.bind(7L, .8f, false, 0f) else motion.frame(1L, 0f)
            assertEquals(.8f, motion.value, 0f)
            assertFalse(motion.running)
            motion.bind(7L, .9f, false, 1f)
            motion.snap()
            assertEquals(.9f, motion.value, 0f)
            assertFalse(motion.running)
        }
    }
}
