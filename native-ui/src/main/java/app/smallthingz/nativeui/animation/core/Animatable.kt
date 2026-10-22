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

import app.smallthingz.nativeui.animation.core.AnimationEndReason.BoundReached
import app.smallthingz.nativeui.animation.core.AnimationEndReason.Finished
import app.smallthingz.nativeui.runtime.State
import app.smallthingz.nativeui.runtime.getValue
import app.smallthingz.nativeui.runtime.mutableStateOf
import app.smallthingz.nativeui.runtime.setValue
import app.smallthingz.nativeui.foundation.MutatorMutex
import kotlinx.coroutines.CancellationException

@Suppress("NotCloseable")
public class Animatable<T, V : AnimationVector>
constructor(
    initialValue: T,
    public val typeConverter: TwoWayConverter<T, V>,
    private val visibilityThreshold: T? = null,
    public val label: String = "Animatable",
) {

    @Deprecated(
        "Maintained for binary compatibility",
        replaceWith =
            ReplaceWith(
                "Animatable(initialValue, typeConverter, visibilityThreshold, \"Animatable\")"
            ),
        DeprecationLevel.HIDDEN,
    )
    public constructor(
        initialValue: T,
        typeConverter: TwoWayConverter<T, V>,
        visibilityThreshold: T? = null,
    ) : this(initialValue, typeConverter, visibilityThreshold, "Animatable")

    internal val internalState =
        AnimationState(typeConverter = typeConverter, initialValue = initialValue)

    
    public val value: T
        get() = internalState.value

    
    public val velocityVector: V
        get() = internalState.velocityVector

    
    public val velocity: T
        get() = typeConverter.convertFromVector(velocityVector)

    
    public var isRunning: Boolean by mutableStateOf(false)
        private set

    
    public var targetValue: T by mutableStateOf(initialValue)
        private set

    
    public var lowerBound: T? = null
        private set

    
    public var upperBound: T? = null
        private set

    private val mutatorMutex = MutatorMutex()
    internal val defaultSpringSpec: SpringSpec<T> =
        SpringSpec(visibilityThreshold = visibilityThreshold)

    @Suppress("UNCHECKED_CAST")
    private val negativeInfinityBounds: V =
        when (velocityVector) {
            is AnimationVector1D -> negativeInfinityBounds1D
            is AnimationVector2D -> negativeInfinityBounds2D
            is AnimationVector3D -> negativeInfinityBounds3D
            else -> negativeInfinityBounds4D
        }
            as V

    @Suppress("UNCHECKED_CAST")
    private val positiveInfinityBounds =
        when (velocityVector) {
            is AnimationVector1D -> positiveInfinityBounds1D
            is AnimationVector2D -> positiveInfinityBounds2D
            is AnimationVector3D -> positiveInfinityBounds3D
            else -> positiveInfinityBounds4D
        }
            as V

    private var lowerBoundVector: V = negativeInfinityBounds
    private var upperBoundVector: V = positiveInfinityBounds

    
    public fun updateBounds(lowerBound: T? = this.lowerBound, upperBound: T? = this.upperBound) {
        val lowerBoundVector =
            lowerBound?.run { typeConverter.convertToVector(this) } ?: negativeInfinityBounds

        val upperBoundVector =
            upperBound?.run { typeConverter.convertToVector(this) } ?: positiveInfinityBounds

        for (i in 0 until lowerBoundVector.size) {
            // TODO: is this check too aggressive?
            checkPrecondition(lowerBoundVector[i] <= upperBoundVector[i]) {
                "Lower bound must be no greater than upper bound on *all* dimensions. The " +
                    "provided lower bound: $lowerBoundVector is greater than upper bound " +
                    "$upperBoundVector on index $i"
            }
        }
        // After the correctness check:
        this.lowerBoundVector = lowerBoundVector
        this.upperBoundVector = upperBoundVector

        this.upperBound = upperBound
        this.lowerBound = lowerBound
        if (!isRunning) {
            val clampedValue = clampToBounds(value)
            if (clampedValue != value) {
                this.internalState.value = clampedValue
            }
        }
    }

    
    public suspend fun animateTo(
        targetValue: T,
        animationSpec: AnimationSpec<T> = defaultSpringSpec,
        initialVelocity: T = velocity,
        block: (Animatable<T, V>.() -> Unit)? = null,
    ): AnimationResult<T, V> {
        val anim =
            TargetBasedAnimation(
                animationSpec = animationSpec,
                initialValue = value,
                targetValue = targetValue,
                typeConverter = typeConverter,
                initialVelocity = initialVelocity,
            )
        return runAnimation(anim, initialVelocity, block)
    }

    
    public suspend fun animateDecay(
        initialVelocity: T,
        animationSpec: DecayAnimationSpec<T>,
        block: (Animatable<T, V>.() -> Unit)? = null,
    ): AnimationResult<T, V> {
        val anim =
            DecayAnimation(
                animationSpec = animationSpec,
                initialValue = value,
                initialVelocityVector = typeConverter.convertToVector(initialVelocity),
                typeConverter = typeConverter,
            )
        return runAnimation(anim, initialVelocity, block)
    }

    // All the different types of animation code paths eventually converge to this method.
    private suspend fun runAnimation(
        animation: Animation<T, V>,
        initialVelocity: T,
        block: (Animatable<T, V>.() -> Unit)?,
    ): AnimationResult<T, V> {

        // Store the start time before it's reset during job cancellation.
        val startTime = internalState.lastFrameTimeNanos
        return mutatorMutex.mutate {
            try {
                internalState.velocityVector = typeConverter.convertToVector(initialVelocity)
                targetValue = animation.targetValue
                isRunning = true

                val endState =
                    internalState.copy(finishedTimeNanos = AnimationConstants.UnspecifiedTime)
                var clampingNeeded = false
                endState.animate(animation, startTime) {
                    updateState(internalState)
                    val clamped = clampToBounds(value)
                    if (clamped != value) {
                        internalState.value = clamped
                        endState.value = clamped
                        block?.invoke(this@Animatable)
                        cancelAnimation()
                        clampingNeeded = true
                    } else {
                        block?.invoke(this@Animatable)
                    }
                }
                val endReason = if (clampingNeeded) BoundReached else Finished
                endAnimation()
                AnimationResult(endState, endReason)
            } catch (e: CancellationException) {
                // Clean up internal states first, then throw.
                endAnimation()
                throw e
            }
        }
    }

    private fun clampToBounds(value: T): T {
        if (
            lowerBoundVector == negativeInfinityBounds && upperBoundVector == positiveInfinityBounds
        ) {
            // Expect this to be the most common use case
            return value
        }
        val valueVector = typeConverter.convertToVector(value)
        var clamped = false
        for (i in 0 until valueVector.size) {
            if (valueVector[i] < lowerBoundVector[i] || valueVector[i] > upperBoundVector[i]) {
                clamped = true
                valueVector[i] = valueVector[i].coerceIn(lowerBoundVector[i], upperBoundVector[i])
            }
        }
        if (clamped) {
            return typeConverter.convertFromVector(valueVector)
        } else {
            return value
        }
    }

    private fun endAnimation() {
        // Reset velocity
        internalState.apply {
            velocityVector.reset()
            lastFrameTimeNanos = AnimationConstants.UnspecifiedTime
        }
        isRunning = false
    }

    
    public suspend fun snapTo(targetValue: T) {
        mutatorMutex.mutate {
            endAnimation()
            val clampedValue = clampToBounds(targetValue)
            internalState.value = clampedValue
            this.targetValue = clampedValue
        }
    }

    
    public suspend fun stop() {
        mutatorMutex.mutate { endAnimation() }
    }

    
    public fun asState(): State<T> = internalState
}

public fun Animatable(
    initialValue: Float,
    visibilityThreshold: Float = Spring.DefaultDisplacementThreshold,
): Animatable<Float, AnimationVector1D> =
    Animatable(initialValue, Float.VectorConverter, visibilityThreshold)

// TODO: Consider some version of @Composable fun<T, V: AnimationVector> Animatable<T, V>.animateTo

public class AnimationResult<T, V : AnimationVector>(
    
    public val endState: AnimationState<T, V>,
    
    public val endReason: AnimationEndReason,
) {
    override fun toString(): String = "AnimationResult(endReason=$endReason, endState=$endState)"
}

private val positiveInfinityBounds1D = AnimationVector(Float.POSITIVE_INFINITY)
private val positiveInfinityBounds2D =
    AnimationVector(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
private val positiveInfinityBounds3D =
    AnimationVector(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
private val positiveInfinityBounds4D =
    AnimationVector(
        Float.POSITIVE_INFINITY,
        Float.POSITIVE_INFINITY,
        Float.POSITIVE_INFINITY,
        Float.POSITIVE_INFINITY,
    )

private val negativeInfinityBounds1D = AnimationVector(Float.NEGATIVE_INFINITY)
private val negativeInfinityBounds2D =
    AnimationVector(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
private val negativeInfinityBounds3D =
    AnimationVector(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
private val negativeInfinityBounds4D =
    AnimationVector(
        Float.NEGATIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
    )
