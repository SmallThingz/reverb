package app.smallthingz.reverb.nativeui

import android.app.Application
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimation
import androidx.compose.animation.core.FloatDecayAnimationSpec as OracleFloatDecaySpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.generateDecayAnimationSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import smallthingz.ui.animation.splineBasedDecay
import smallthingz.ui.animation.core.FloatDecayAnimationSpec
import smallthingz.ui.animation.core.exponentialDecay
import smallthingz.ui.scroll.NativeVerticalFling
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeFloatDecayOracleParityTest {
    @Test fun splineFramesAndNaturalEndpointsMatchPinnedDecayAcrossCadences() {
        val signedVelocities = floatArrayOf(-10500f, -740f, -1.0001f, 1.0001f, 740f, 10500f)
        for (density in floatArrayOf(1f, 2.625f, 4f)) {
            val spec = splineBasedDecay(Density(density))
            for (velocity in signedVelocities) for (hz in intArrayOf(60, 90, 120, 0)) {
                compareFrames(spec, velocity, hz)
            }
        }
    }

    @Test fun partialConsumptionRetiresAtTheSampledVelocity() {
        val spec = splineBasedDecay(Density(2.625f))
        for (velocity in floatArrayOf(-2800f, 2800f)) {
            compareFrames(spec, velocity, hz = 0, stopAtFrame = 11)
        }
    }

    @Test fun negativeDurationFloorsToZeroAndSignedTerminalZeroSurvives() {
        val spec = object : FloatDecayAnimationSpec {
            override val absVelocityThreshold = 0f
            override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float =
                error("The zero-duration owner must sample the cached target")
            override fun getDurationNanos(initialValue: Float, initialVelocity: Float) = -10L
            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float {
                assertEquals(0L, playTimeNanos)
                return -0.0f
            }
            override fun getTargetValue(initialValue: Float, initialVelocity: Float) = 3f
        }
        compareFrames(spec, initialVelocity = -2f, hz = 60)
    }

    @Test fun completionCanAdmitReplacementFromNaturalEndOrInsideConsume() {
        val spec = splineBasedDecay(Density(2.625f))
        for (cancelInsideConsume in listOf(false, true)) {
            val completed = mutableListOf<Float>()
            val delivered = mutableListOf<Float>()
            lateinit var owner: NativeVerticalFling
            owner = NativeVerticalFling(spec, { delta ->
                delivered += delta
                if (cancelInsideConsume && delivered.size == 2) {
                    owner.cancel()
                    0f
                } else delta
            }, { velocity ->
                completed += velocity
                if (completed.size == 1) owner.start(-2400f)
            })
            val start = 213_221_404_409L
            val elapsed = if (cancelInsideConsume) 19_200_004L else 10_000_000_000L
            owner.start(2400f)
            owner.frame(start)
            owner.frame(start + elapsed)
            val end = oracle(spec, 2400f).getVelocityVectorFromNanos((elapsed / 1f).toLong()).value
            assertEquals(listOf(end.toRawBits()), completed.map(Float::toRawBits))
            assertTrue(owner.isRunning)
            owner.frame(start + elapsed + 16_000_000L)
            val replacement = oracle(spec, -2400f)
            assertEquals(replacement.getValueFromNanos(0L).toRawBits(), delivered.last().toRawBits())
            assertEquals(replacement.getVelocityVectorFromNanos(0L).value.toRawBits(), owner.remainingVelocity.toRawBits())
            assertTrue(owner.isRunning)
            assertEquals(1, completed.size)
        }
    }

    @Test fun firstFrameCutoffDistinguishesHalfPixelFromTheNextFloat() {
        val spec = exponentialDecay()
        val velocity = 2400f
        val reference = oracle(spec, velocity)
        assertEquals(0f.toRawBits(), reference.getValueFromNanos(0L).toRawBits())
        val next = Math.nextUp(0.5f)
        for (gap in floatArrayOf(0.5f, next)) {
            val completed = mutableListOf<Float>()
            val owner = NativeVerticalFling(spec, { delta -> delta - gap }, completed::add)
            owner.start(velocity)
            owner.frame(213_221_404_409L)
            assertEquals(gap == 0.5f, owner.isRunning)
            val expected = if (gap == 0.5f) emptyList()
                else listOf(reference.getVelocityVectorFromNanos(0L).value.toRawBits())
            assertEquals(expected, completed.map(Float::toRawBits))
        }
    }

    @Test fun saturatedSplineDurationsUseTheSameFinishedSamples() {
        val spec = splineBasedDecay(Density(2.625f))
        for (velocity in floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            compareFrames(spec, velocity, hz = 0)
        }
    }

    private fun compareFrames(spec: FloatDecayAnimationSpec, initialVelocity: Float, hz: Int, stopAtFrame: Int = -1) {
        val oracle = oracle(spec, initialVelocity)
        val completed = mutableListOf<Float>()
        val delivered = mutableListOf<Float>()
        val owner = NativeVerticalFling(spec, { delta ->
            delivered += delta
            if (delivered.lastIndex == stopAtFrame) delta - 1f else delta
        }, completed::add)
        val start = 213_221_404_409L
        var frameTime = start
        var lastValue = 0f
        owner.start(initialVelocity)
        for (frame in 0..1000) {
            val playTime = ((frameTime - start) / 1f).toLong()
            val expectedValue = oracle.getValueFromNanos(playTime)
            val expectedVelocity = oracle.getVelocityVectorFromNanos(playTime).value
            val finished = oracle.isFinishedFromNanos(playTime) || frame == stopAtFrame
            assertTrue(owner.isRunning)
            owner.frame(frameTime)
            val sample = "velocity=$initialVelocity, hz=$hz, frame=$frame, elapsed=$playTime"
            assertEquals(sample, frame + 1, delivered.size)
            assertEquals(sample, (expectedValue - lastValue).toRawBits(), delivered.last().toRawBits())
            assertEquals(sample, expectedVelocity.toRawBits(), owner.remainingVelocity.toRawBits())
            assertEquals(sample, finished, !owner.isRunning)
            if (finished) {
                assertEquals(listOf(expectedVelocity.toRawBits()), completed.map(Float::toRawBits))
                owner.frame(frameTime + 20_000_000L)
                assertFalse(owner.isRunning)
                assertEquals(frame + 1, delivered.size)
                assertEquals(1, completed.size)
                return
            }
            assertTrue(completed.isEmpty())
            lastValue = expectedValue
            val steps = IrregularSteps
            frameTime = if (hz == 0) frameTime + steps[frame % steps.size]
                else start + (frame + 1) * 1_000_000_000L / hz
        }
        error("Fling did not finish within the trace")
    }

    private fun oracle(spec: FloatDecayAnimationSpec, initialVelocity: Float) =
        DecayAnimation(OracleSpec(spec).generateDecayAnimationSpec<Float>(), Float.VectorConverter,
            initialValue = 0f, initialVelocityVector = AnimationVector1D(initialVelocity))

    // Delegate only the unchanged scalar math. The pinned binary owns the replaced generic trajectory.
    private class OracleSpec(private val spec: FloatDecayAnimationSpec) : OracleFloatDecaySpec {
        override val absVelocityThreshold: Float get() = spec.absVelocityThreshold
        override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float =
            spec.getValueFromNanos(playTimeNanos, initialValue, initialVelocity)
        override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float =
            spec.getVelocityFromNanos(playTimeNanos, initialValue, initialVelocity)
        override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long =
            spec.getDurationNanos(initialValue, initialVelocity)
        override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float =
            spec.getTargetValue(initialValue, initialVelocity)
    }

    private companion object {
        val IrregularSteps = longArrayOf(999_999L, 1L, 8_333_333L, 24_500_000L, 7_341_888L, 19_200_004L)
    }
}
