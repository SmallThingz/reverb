/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")

package app.smallthingz.nativeui.animation.core

import app.smallthingz.nativeui.animation.core.AnimationConstants.DefaultDurationMillis
import app.smallthingz.nativeui.util.fastCoerceIn

public interface FloatAnimationSpec : AnimationSpec<Float> {
    
    public fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float

    
    public fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float

    
    public fun getEndVelocity(
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float =
        getVelocityFromNanos(
            getDurationNanos(initialValue, targetValue, initialVelocity),
            initialValue,
            targetValue,
            initialVelocity,
        )

    
    @Suppress("MethodNameUnits")
    public fun getDurationNanos(
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Long

    
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<Float, V>
    ): VectorizedFloatAnimationSpec<V> = VectorizedFloatAnimationSpec<V>(this)
}

public class FloatSpringSpec(
    public val dampingRatio: Float = Spring.DampingRatioNoBouncy,
    public val stiffness: Float = Spring.StiffnessMedium,
    private val visibilityThreshold: Float = Spring.DefaultDisplacementThreshold,
) : FloatAnimationSpec {

    private val spring =
        SpringSimulation(1f).also {
            it.dampingRatio = dampingRatio
            it.stiffness = stiffness
        }

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float {
        // TODO: Properly support Nanos in the spring impl
        val playTimeMillis = playTimeNanos / MillisToNanos
        spring.finalPosition = targetValue
        return spring.updateValues(initialValue, initialVelocity, playTimeMillis).value
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float {
        // TODO: Properly support Nanos in the spring impl
        val playTimeMillis = playTimeNanos / MillisToNanos
        spring.finalPosition = targetValue
        return spring.updateValues(initialValue, initialVelocity, playTimeMillis).velocity
    }

    override fun getEndVelocity(
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float = 0f

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Long =
        estimateAnimationDurationMillis(
            stiffness = spring.stiffness,
            dampingRatio = spring.dampingRatio,
            initialDisplacement = (initialValue - targetValue) / visibilityThreshold,
            initialVelocity = initialVelocity / visibilityThreshold,
            delta = 1f,
        ) * MillisToNanos
}

public class FloatTweenSpec(
    public val duration: Int = DefaultDurationMillis,
    public val delay: Int = 0,
    private val easing: Easing = FastOutSlowInEasing,
) : FloatAnimationSpec {
    private val durationNanos: Long = duration * MillisToNanos

    private val delayNanos: Long = delay * MillisToNanos

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float {
        val clampedPlayTimeNanos = clampPlayTimeNanos(playTimeNanos)
        val rawFraction = if (duration == 0) 1f else clampedPlayTimeNanos / durationNanos.toFloat()
        val fraction = easing.transform(rawFraction)
        return lerp(initialValue, targetValue, fraction)
    }

    private inline fun clampPlayTimeNanos(playTimeNanos: Long): Long {
        return (playTimeNanos - delayNanos).fastCoerceIn(0, durationNanos)
    }

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Long {
        return delayNanos + durationNanos
    }

    // Calculate velocity by difference between the current value and the value 1 ms ago. This is a
    // preliminary way of calculating velocity used by easing curve based animations, and keyframe
    // animations. Physics-based animations give a much more accurate velocity.
    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float {
        val clampedPlayTimeNanos = clampPlayTimeNanos(playTimeNanos)
        if (clampedPlayTimeNanos == 0L) {
            return initialVelocity
        }
        val startNum =
            getValueFromNanos(
                clampedPlayTimeNanos - MillisToNanos,
                initialValue,
                targetValue,
                initialVelocity,
            )
        val endNum =
            getValueFromNanos(clampedPlayTimeNanos, initialValue, targetValue, initialVelocity)
        return (endNum - startNum) * 1000f
    }
}
