package app.smallthingz.reverb.nativeui

import androidx.compose.animation.core.FastOutSlowInEasing
import smallthingz.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import smallthingz.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class LibraryRowMotionTest {
    @Test fun initiallyExpandedHasNoSyntheticEntrance() {
        val motion = LibraryExpansionMotion(true)
        motion.measured(IntSize(401, 233))
        assertEquals(IntSize(401, 233), motion.measuredSize)
        assertEquals(1f, motion.alpha, 0f)
        assertFalse(motion.running)
    }

    @Test fun enterRetainsSeventyMillisecondFadeDelayAndSeparateSizeDuration() {
        val motion = LibraryExpansionMotion(false)
        motion.setVisible(true)
        motion.measured(IntSize(401, 233))
        motion.frame(10_000_000L, 1f)
        motion.frame(79_000_000L, 1f)
        assertEquals(0f, motion.alpha, 0f)
        assertTrue(motion.measuredSize.height > 0)
        motion.frame(300_000_000L, 1f)
        assertEquals(1f, motion.alpha, 0f)
        assertTrue(motion.running)
        motion.frame(330_000_000L, 1f)
        assertEquals(IntSize(401, 233), motion.measuredSize)
        assertFalse(motion.running)
    }

    @Test fun exitKeepsContentUntilBothFadeAndShrinkComplete() {
        val motion = LibraryExpansionMotion(true)
        motion.measured(IntSize(401, 233))
        motion.setVisible(false)
        motion.frame(0L, 1f)
        motion.frame(140_000_000L, 1f)
        assertEquals(0f, motion.alpha, 0f)
        assertTrue(motion.measuredSize.height > 0)
        assertFalse(motion.exitCompleted)
        motion.frame(260_000_000L, 1f)
        assertEquals(IntSize(401, 0), motion.measuredSize)
        assertTrue(motion.exitCompleted)
        assertFalse(motion.running)
    }

    @Test fun interruptedExitReversesWithoutJumpingOrDisposing() {
        val motion = LibraryExpansionMotion(true)
        motion.measured(IntSize(401, 233))
        motion.setVisible(false)
        motion.frame(0L, 1f)
        motion.frame(90_000_000L, 1f)
        val size = motion.measuredSize
        val alpha = motion.alpha
        motion.setVisible(true)
        assertEquals(size, motion.measuredSize)
        assertEquals(alpha, motion.alpha, 0f)
        motion.frame(90_000_000L, 1f)
        assertEquals(size, motion.measuredSize)
        assertEquals(alpha, motion.alpha, 0f)
        motion.frame(3_000_000_000L, 1f)
        assertEquals(IntSize(401, 233), motion.measuredSize)
        assertEquals(1f, motion.alpha, 0f)
        assertFalse(motion.exitCompleted)
    }

    @Test fun reducedMotionFinishesWithoutWaitingForNominalDurations() {
        val motion = LibraryExpansionMotion(true)
        motion.measured(IntSize(401, 233))
        motion.setVisible(false)
        motion.frame(42L, 0f)
        assertTrue(motion.exitCompleted)
        assertEquals(IntSize(401, 0), motion.measuredSize)
        assertEquals(0f, motion.alpha, 0f)
    }

    @Test fun selectionMidpointUsesOklabAndPreservesPackedColorAtRetarget() {
        val start = Color(0xff2868bd)
        val target = Color(0xffd09a32)
        val channel = LibraryTweenValue.color(start)
        channel.target(target, 1f)
        channel.frame(0L, 1f)
        channel.frame(90_000_000L, 1f)
        val expected = lerp(androidx.compose.ui.graphics.Color(start.value),
            androidx.compose.ui.graphics.Color(target.value), FastOutSlowInEasing.transform(.5f))
        assertEquals(expected.value, channel.value.value)
        val before = channel.value
        channel.target(start, 1f)
        assertEquals(before, channel.value)
        channel.frame(90_000_000L, 1f)
        assertEquals(before, channel.value)
        channel.frame(270_000_000L, 1f)
        assertEquals(start, channel.value)
    }

    @Test fun contentSizeUsesItsOwnFullThreeHundredTwentyMillisecondTween() {
        val channel = LibraryTweenValue.size(IntSize(401, 68))
        channel.target(IntSize(401, 68), 1f)
        assertFalse(channel.running)
        channel.target(IntSize(401, 301), 1f)
        channel.frame(0L, 1f)
        channel.frame(160_000_000L, 1f)
        assertTrue(channel.running)
        assertTrue(channel.value.height in 69..300)
        channel.frame(320_000_000L, 1f)
        assertFalse(channel.running)
        assertEquals(IntSize(401, 301), channel.value)
    }
}
