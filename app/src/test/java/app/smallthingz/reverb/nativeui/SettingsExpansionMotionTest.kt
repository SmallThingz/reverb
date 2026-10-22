package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class SettingsExpansionMotionTest {
    @Test fun initiallyVisibleMeasuresAtFullSizeWithoutEntrance() {
        val motion=SettingsExpansionMotion(true)
        motion.measure(IntSize(300,112))
        assertEquals(IntSize(300,112),motion.measuredSize)
        assertEquals(1f,motion.alpha,0f)
        assertFalse(motion.running)
        motion.measure(IntSize(310,140))
        assertEquals(IntSize(310,140),motion.measuredSize)
        assertFalse(motion.running)
    }

    @Test fun entryStartsCollapsedAndAnimationsOffCompletesBothChannels() {
        val motion=SettingsExpansionMotion(false)
        motion.visible(true)
        motion.measure(IntSize(300,112))
        assertEquals(IntSize(300,0),motion.measuredSize)
        assertEquals(0f,motion.alpha,0f)
        assertTrue(motion.present)
        motion.frame(100L,0f)
        assertEquals(IntSize(300,112),motion.measuredSize)
        assertEquals(1f,motion.alpha,0f)
        assertFalse(motion.running)
    }

    @Test fun exitKeepsContentUntilBothChannelsHaveFinished() {
        val motion=SettingsExpansionMotion(true)
        motion.measure(IntSize(300,112))
        motion.visible(false)
        assertTrue(motion.present)
        motion.frame(100L,1f)
        assertTrue(motion.present)
        motion.frame(4_000_000_100L,1f)
        assertEquals(IntSize(300,0),motion.measuredSize)
        assertEquals(0f,motion.alpha,0f)
        assertFalse(motion.present)
    }

    @Test fun interruptionKeepsCurrentPoseAndCanReturnToVisible() {
        val motion=SettingsExpansionMotion(false)
        motion.visible(true)
        motion.measure(IntSize(300,112))
        motion.frame(1_000_000_000L,1f)
        motion.frame(1_100_000_000L,1f)
        val size=motion.measuredSize
        val alpha=motion.alpha
        assertTrue(size.height in 1 until 112)
        assertTrue(alpha>0f&&alpha<1f)
        motion.visible(false)
        assertEquals(size,motion.measuredSize)
        assertEquals(alpha,motion.alpha,0f)
        motion.frame(1_120_000_000L,1f)
        motion.visible(true)
        motion.frame(1_130_000_000L,0f)
        assertEquals(IntSize(300,112),motion.measuredSize)
        assertEquals(1f,motion.alpha,0f)
        assertTrue(motion.present)
    }

    @Test fun cancellationBeforeFirstMeasureNeverFlashesOpaqueContent() {
        val motion=SettingsExpansionMotion(false)
        motion.visible(true)
        motion.visible(false)
        motion.measure(IntSize(300,112))
        assertEquals(0f,motion.alpha,0f)
        motion.frame(100L,0f)
        assertFalse(motion.present)
        assertEquals(IntSize(300,0),motion.measuredSize)
    }
}
