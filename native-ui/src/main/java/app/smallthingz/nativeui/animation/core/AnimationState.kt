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

import app.smallthingz.nativeui.runtime.State
import app.smallthingz.nativeui.runtime.getValue
import app.smallthingz.nativeui.runtime.mutableStateOf
import app.smallthingz.nativeui.runtime.setValue

public class AnimationState<T, V : AnimationVector>(
    public val typeConverter: TwoWayConverter<T, V>,
    initialValue: T,
    initialVelocityVector: V? = null,
    lastFrameTimeNanos: Long = AnimationConstants.UnspecifiedTime,
    finishedTimeNanos: Long = AnimationConstants.UnspecifiedTime,
    isRunning: Boolean = false,
) : State<T> {
    
    override var value: T by mutableStateOf(initialValue)
        internal set

    
    public var velocityVector: V =
        initialVelocityVector?.copy() ?: typeConverter.createZeroVectorFrom(initialValue)
        internal set

    
    @get:Suppress("MethodNameUnits")
    public var lastFrameTimeNanos: Long = lastFrameTimeNanos
        internal set

    
    @get:Suppress("MethodNameUnits")
    public var finishedTimeNanos: Long = finishedTimeNanos
        internal set

    
    public var isRunning: Boolean = isRunning
        internal set

    
    public val velocity: T
        get() = typeConverter.convertFromVector(velocityVector)

    override fun toString(): String {
        return "AnimationState(" +
            "value=$value, " +
            "velocity=$velocity, " +
            "isRunning=$isRunning, " +
            "lastFrameTimeNanos=$lastFrameTimeNanos, " +
            "finishedTimeNanos=$finishedTimeNanos" +
            ")"
    }
}

public val AnimationState<*, *>.isFinished: Boolean
    get() = finishedTimeNanos != AnimationConstants.UnspecifiedTime

public class AnimationScope<T, V : AnimationVector>
internal constructor(
    initialValue: T,
    
    public val typeConverter: TwoWayConverter<T, V>,
    initialVelocityVector: V,
    lastFrameTimeNanos: Long,
    
    public val targetValue: T,
    
    @get:Suppress("MethodNameUnits") public val startTimeNanos: Long,
    isRunning: Boolean,
    private val onCancel: () -> Unit,
) {
    // Externally immutable fields
    
    public var value: T by mutableStateOf(initialValue)
        internal set

    
    public var velocityVector: V = initialVelocityVector.copy()
        internal set

    
    @get:Suppress("MethodNameUnits")
    public var lastFrameTimeNanos: Long = lastFrameTimeNanos
        internal set

    
    @get:Suppress("MethodNameUnits")
    public var finishedTimeNanos: Long = AnimationConstants.UnspecifiedTime
        internal set

    
    public var isRunning: Boolean by mutableStateOf(isRunning)
        internal set

    
    public val velocity: T
        get() = typeConverter.convertFromVector(velocityVector)

    
    public fun cancelAnimation() {
        isRunning = false
        onCancel()
    }

    
    public fun toAnimationState(): AnimationState<T, V> =
        AnimationState(
            typeConverter,
            value,
            velocityVector,
            lastFrameTimeNanos,
            finishedTimeNanos,
            isRunning,
        )
}

public fun <T, V : AnimationVector> AnimationState<T, V>.copy(
    value: T = this.value,
    velocityVector: V? = this.velocityVector.copy(),
    lastFrameTimeNanos: Long = this.lastFrameTimeNanos,
    finishedTimeNanos: Long = this.finishedTimeNanos,
    isRunning: Boolean = this.isRunning,
): AnimationState<T, V> =
    AnimationState(
        this.typeConverter,
        value,
        velocityVector,
        lastFrameTimeNanos,
        finishedTimeNanos,
        isRunning,
    )

public fun AnimationState<Float, AnimationVector1D>.copy(
    value: Float = this.value,
    velocity: Float = this.velocityVector.value,
    lastFrameTimeNanos: Long = this.lastFrameTimeNanos,
    finishedTimeNanos: Long = this.finishedTimeNanos,
    isRunning: Boolean = this.isRunning,
): AnimationState<Float, AnimationVector1D> =
    AnimationState(
        this.typeConverter,
        value,
        AnimationVector(velocity),
        lastFrameTimeNanos,
        finishedTimeNanos,
        isRunning,
    )

public fun AnimationState(
    initialValue: Float,
    initialVelocity: Float = 0f,
    lastFrameTimeNanos: Long = AnimationConstants.UnspecifiedTime,
    finishedTimeNanos: Long = AnimationConstants.UnspecifiedTime,
    isRunning: Boolean = false,
): AnimationState<Float, AnimationVector1D> {
    return AnimationState(
        Float.VectorConverter,
        initialValue,
        AnimationVector(initialVelocity),
        lastFrameTimeNanos,
        finishedTimeNanos,
        isRunning,
    )
}

public fun <T, V : AnimationVector> AnimationState(
    typeConverter: TwoWayConverter<T, V>,
    initialValue: T,
    initialVelocity: T,
    lastFrameTimeNanos: Long = AnimationConstants.UnspecifiedTime,
    finishedTimeNanos: Long = AnimationConstants.UnspecifiedTime,
    isRunning: Boolean = false,
): AnimationState<T, V> {
    return AnimationState(
        typeConverter,
        initialValue,
        typeConverter.convertToVector(initialVelocity),
        lastFrameTimeNanos,
        finishedTimeNanos,
        isRunning,
    )
}

public fun <T, V : AnimationVector> TwoWayConverter<T, V>.createZeroVectorFrom(value: T): V =
    convertToVector(value).also { it.reset() }
