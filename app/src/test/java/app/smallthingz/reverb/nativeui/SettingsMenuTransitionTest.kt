package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMenuTransitionTest {
    @Test fun collapsedHasNoWindow() {
        val motion=SettingsMenuTransition()
        assertFalse(motion.shouldExist)
        assertFalse(motion.isRunning)
    }

    @Test fun closingUnsettledEntryRemovesWindowBeforeExitTransition() {
        val motion=SettingsMenuTransition()
        motion.retarget(true)
        motion.frame(100L,1f)
        motion.retarget(false)
        assertFalse(motion.shouldExist)
    }

    @Test fun completedEntryRetainsWindowUntilExitSettlesEvenWithAnimationsOff() {
        val motion=SettingsMenuTransition()
        motion.retarget(true)
        motion.frame(100L,0f)
        assertTrue(motion.currentExpanded)
        motion.retarget(false)
        assertTrue(motion.shouldExist)
        motion.frame(200L,0f)
        assertFalse(motion.shouldExist)
    }

    @Test fun reopeningExitUpdatesCurrentStateToItsPriorTarget() {
        val motion=SettingsMenuTransition()
        motion.retarget(true)
        motion.frame(100L,0f)
        motion.retarget(false)
        motion.frame(200L,1f)
        motion.retarget(true)
        assertFalse(motion.currentExpanded)
        assertTrue(motion.shouldExist)
        motion.retarget(false)
        assertFalse(motion.shouldExist)
    }
}
