/*
 * Copyright 2020 The Android Open Source Project
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
import app.smallthingz.nativeui.unit.IntOffset

public interface DecayAnimationSpec<T> {

    
    public fun <V : AnimationVector> vectorize(
        typeConverter: TwoWayConverter<T, V>
    ): VectorizedDecayAnimationSpec<V>
}

public fun <T, V : AnimationVector> DecayAnimationSpec<T>.calculateTargetValue(
    typeConverter: TwoWayConverter<T, V>,
    initialValue: T,
    initialVelocity: T,
): T {
    val vectorizedSpec = vectorize(typeConverter)
    val targetVector =
        vectorizedSpec.getTargetValue(
            typeConverter.convertToVector(initialValue),
            typeConverter.convertToVector(initialVelocity),
        )
    return typeConverter.convertFromVector(targetVector)
}

public fun DecayAnimationSpec<Float>.calculateTargetValue(
    initialValue: Float,
    initialVelocity: Float,
): Float {
    val vectorizedSpec = vectorize(Float.VectorConverter)
    val targetVector =
        vectorizedSpec.getTargetValue(
            AnimationVector(initialValue),
            AnimationVector(initialVelocity),
        )
    return targetVector.value
}

public fun <T> exponentialDecay(
    @FloatRange(from = 0.0, fromInclusive = false) frictionMultiplier: Float = 1f,
    @FloatRange(from = 0.0, fromInclusive = false) absVelocityThreshold: Float = 0.1f,
): DecayAnimationSpec<T> =
    FloatExponentialDecaySpec(frictionMultiplier, absVelocityThreshold).generateDecayAnimationSpec()

public fun <T> FloatDecayAnimationSpec.generateDecayAnimationSpec(): DecayAnimationSpec<T> {
    return DecayAnimationSpecImpl(this)
}

private class DecayAnimationSpecImpl<T>(private val floatDecaySpec: FloatDecayAnimationSpec) :
    DecayAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(
        typeConverter: TwoWayConverter<T, V>
    ): VectorizedDecayAnimationSpec<V> = VectorizedFloatDecaySpec(floatDecaySpec)
}

private class VectorizedFloatDecaySpec<V : AnimationVector>(
    val floatDecaySpec: FloatDecayAnimationSpec
) : VectorizedDecayAnimationSpec<V> {
    private lateinit var valueVector: V
    private lateinit var velocityVector: V
    private lateinit var targetVector: V
    override val absVelocityThreshold: Float = floatDecaySpec.absVelocityThreshold

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, initialVelocity: V): V {
        if (!::valueVector.isInitialized) {
            valueVector = initialValue.newInstance()
        }
        for (i in 0 until valueVector.size) {
            valueVector[i] =
                floatDecaySpec.getValueFromNanos(playTimeNanos, initialValue[i], initialVelocity[i])
        }
        return valueVector
    }

    override fun getDurationNanos(initialValue: V, initialVelocity: V): Long {
        var maxDuration = 0L
        if (!::velocityVector.isInitialized) {
            velocityVector = initialValue.newInstance()
        }
        for (i in 0 until velocityVector.size) {
            maxDuration =
                maxOf(
                    maxDuration,
                    floatDecaySpec.getDurationNanos(initialValue[i], initialVelocity[i]),
                )
        }
        return maxDuration
    }

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, initialVelocity: V): V {
        if (!::velocityVector.isInitialized) {
            velocityVector = initialValue.newInstance()
        }
        for (i in 0 until velocityVector.size) {
            velocityVector[i] =
                floatDecaySpec.getVelocityFromNanos(
                    playTimeNanos,
                    initialValue[i],
                    initialVelocity[i],
                )
        }
        return velocityVector
    }

    override fun getTargetValue(initialValue: V, initialVelocity: V): V {
        if (!::targetVector.isInitialized) {
            targetVector = initialValue.newInstance()
        }
        for (i in 0 until targetVector.size) {
            targetVector[i] = floatDecaySpec.getTargetValue(initialValue[i], initialVelocity[i])
        }
        return targetVector
    }
}
