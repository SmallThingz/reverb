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

package app.smallthingz.nativeui.animation.core

import androidx.annotation.FloatRange
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

public interface FloatDecayAnimationSpec {
    
    public val absVelocityThreshold: Float

    
    public fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float

    
    @Suppress("MethodNameUnits")
    public fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long

    
    public fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float

    
    public fun getTargetValue(initialValue: Float, initialVelocity: Float): Float
}

private const val ExponentialDecayFriction = -4.2f

public class FloatExponentialDecaySpec(
    @FloatRange(from = 0.0, fromInclusive = false) frictionMultiplier: Float = 1f,
    @FloatRange(from = 0.0, fromInclusive = false) absVelocityThreshold: Float = 0.1f,
) : FloatDecayAnimationSpec {

    override val absVelocityThreshold: Float = max(0.0000001f, abs(absVelocityThreshold))
    private val friction: Float = ExponentialDecayFriction * max(0.0001f, frictionMultiplier)

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float {
        // TODO: Properly support nanos
        val playTimeMillis = playTimeNanos / MillisToNanos
        return initialValue - initialVelocity / friction +
            initialVelocity / friction * exp(friction * playTimeMillis / 1000f)
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float,
    ): Float {
        // TODO: Properly support nanos
        val playTimeMillis = playTimeNanos / MillisToNanos
        return (initialVelocity * exp(((playTimeMillis / 1000f) * friction)))
    }

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long {
        // Inverse of getVelocity
        return (1000f * ln(absVelocityThreshold / abs(initialVelocity)) / friction).toLong() *
            MillisToNanos
    }

    override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float {
        if (abs(initialVelocity) <= absVelocityThreshold) {
            return initialValue
        }
        val duration: Double =
            ln(abs(absVelocityThreshold / initialVelocity).toDouble()) / friction * 1000

        return initialValue - initialVelocity / friction +
            initialVelocity / friction * exp((friction * duration / 1000f)).toFloat()
    }
}

internal fun FloatDecayAnimationSpec.createAnimation(
    startValue: Float,
    startVelocity: Float = 0f,
): Animation<Float, AnimationVector1D> {
    return DecayAnimation(this, startValue, startVelocity)
}
