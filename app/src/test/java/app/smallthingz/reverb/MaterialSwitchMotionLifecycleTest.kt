package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.animation.core.FloatSpringSpec

/** Exercises the actual private scalar owner without constructing an Android View on the host. */
class MaterialSwitchMotionLifecycleTest {
    @Test fun terminalDetachSettlesAnInterruptedMotionBeforeTheSameTargetRebind() {
        for ((from, to) in listOf(16f to 24f, 24f to 16f, 4f to 24f, 24f to 8f)) {
            val motion = Motion(from)
            motion.retarget(to)
            motion.frame(FRAME_ORIGIN)
            motion.frame(FRAME_ORIGIN + 16_000_000L)
            assertTrue(motion.running)
            assertTrue(motion.value != from && motion.value != to)

            // onDetachedFromWindow invokes this exact terminal method for both thumb motions.
            motion.stop()
            assertEquals(to.toRawBits(), motion.value.toRawBits())
            assertFalse(motion.running)
            motion.retarget(to)
            motion.frame(FRAME_ORIGIN + 10_000_000_000L)
            assertEquals(to.toRawBits(), motion.value.toRawBits())
            assertFalse(motion.running)
        }
    }

    @Test fun aDifferentTargetAfterDetachStartsFromTheSettledValue() {
        val motion = Motion(16f)
        motion.retarget(24f)
        motion.frame(FRAME_ORIGIN)
        motion.frame(FRAME_ORIGIN + 16_000_000L)
        motion.stop()
        motion.retarget(16f)
        val restartedAt = FRAME_ORIGIN + 1_000_000_000L
        motion.frame(restartedAt)
        assertEquals(24f.toRawBits(), motion.value.toRawBits())
        motion.frame(restartedAt + 16_000_000L)
        val expected = spring().getValueFromNanos(16_000_000L, 24f, 16f, 0f)
        assertEquals(expected.toRawBits(), motion.value.toRawBits())
    }

    @Test fun uninterruptedScaledSamplesAndTerminalValuesStayExact() {
        for (scale in listOf(0f, .5f, 1f, 2f)) {
            val motion = Motion(16f)
            val expected = spring()
            val duration = expected.getDurationNanos(16f, 24f, 0f)
            motion.retarget(24f, scale = scale)
            for (elapsed in listOf(0L, 8_000_000L, 16_000_000L, 40_000_000L, 160_000_000L, 2_000_000_000L)) {
                motion.frame(FRAME_ORIGIN + elapsed, scale)
                val playTime = if (scale == 0f) duration else (elapsed / scale).toLong()
                val value = if (playTime >= duration) 24f
                    else expected.getValueFromNanos(playTime, 16f, 24f, 0f)
                assertEquals(value.toRawBits(), motion.value.toRawBits())
            }
            assertFalse(motion.running)
        }
    }

    @Test fun interruptedRetargetKeepsTheExistingVelocityAndPressedSnapStillSettles() {
        val motion = Motion(16f)
        val expected = spring()
        motion.retarget(24f)
        motion.frame(FRAME_ORIGIN)
        motion.frame(FRAME_ORIGIN + 16_000_000L)
        val from = expected.getValueFromNanos(16_000_000L, 16f, 24f, 0f)
        val velocity = expected.getVelocityFromNanos(16_000_000L, 16f, 24f, 0f)
        motion.retarget(8f)
        motion.frame(FRAME_ORIGIN + 24_000_000L)
        assertEquals(expected.getValueFromNanos(8_000_000L, from, 8f, velocity).toRawBits(), motion.value.toRawBits())
        motion.retarget(28f, snap = true)
        assertEquals(28f.toRawBits(), motion.value.toRawBits())
        assertFalse(motion.running)
    }

    private class Motion(initial: Float) {
        private val owner = type.getDeclaredConstructor(java.lang.Float.TYPE).apply { isAccessible = true }
            .newInstance(initial)
        val value: Float get() = valueGetter.invoke(owner) as Float
        val running: Boolean get() = runningGetter.invoke(owner) as Boolean
        fun retarget(target: Float, snap: Boolean = false, scale: Float = 1f) {
            retargetMethod.invoke(owner, target, snap, scale)
        }
        fun frame(time: Long, scale: Float = 1f) { frameMethod.invoke(owner, time, scale) }
        fun stop() { stopMethod.invoke(owner) }

        companion object {
            private val type = Class.forName("app.smallthingz.reverb.nativeui.SwitchFloatMotion")
            private val valueGetter = type.getDeclaredMethod("getValue").apply { isAccessible = true }
            private val runningGetter = type.getDeclaredMethod("isRunning").apply { isAccessible = true }
            private val retargetMethod = type.getDeclaredMethod("retarget", java.lang.Float.TYPE,
                java.lang.Boolean.TYPE, java.lang.Float.TYPE).apply { isAccessible = true }
            private val frameMethod = type.getDeclaredMethod("frame", java.lang.Long.TYPE,
                java.lang.Float.TYPE).apply { isAccessible = true }
            private val stopMethod = type.getDeclaredMethod("stop").apply { isAccessible = true }
        }
    }

    private fun spring() = FloatSpringSpec(dampingRatio = .9f, stiffness = 1400f, visibilityThreshold = .01f)
    private companion object { const val FRAME_ORIGIN = 1_000_000_000L }
}
