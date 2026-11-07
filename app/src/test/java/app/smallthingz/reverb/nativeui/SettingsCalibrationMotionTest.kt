package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCalibrationMotionTest {
    @Test fun repeatedPcmTargetsKeepContinuousMonotoneFill() {
        val motion=calibrationProgressSpring()
        var previous=0f
        for(frame in 0..240) {
            if(frame%6==0) {
                val before=motion.value
                motion.retarget((frame/240f).coerceAtMost(1f))
                assertEquals(before,motion.value,.00001f)
            }
            motion.frame(frame*16_666_667L)
            assertTrue(motion.value>=previous-.00001f)
            assertTrue(motion.value<=motion.targetValue+.00001f)
            previous=motion.value
        }
        for(frame in 241..360)motion.frame(frame*16_666_667L)
        assertEquals(1f,motion.value,.0005f)
    }
    @Test fun disabledAnimationsSnapAndNewRunCanRestartAtZero() {
        val motion=calibrationProgressSpring()
        motion.retarget(.75f,0f);motion.frame(1L,0f)
        assertEquals(.75f,motion.value,0f)
        motion.snapTo(0f)
        assertEquals(0f,motion.value,0f)
        assertTrue(!motion.isRunning)
    }
}
