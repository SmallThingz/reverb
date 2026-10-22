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

import app.smallthingz.nativeui.runtime.withFrameNanos
import app.smallthingz.nativeui.MotionDurationScale
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException

public suspend fun animate(
    initialValue: Float,
    targetValue: Float,
    initialVelocity: Float = 0f,
    animationSpec: AnimationSpec<Float> = spring(),
    block: (value: Float, velocity: Float) -> Unit,
) {
    animate(Float.VectorConverter, initialValue, targetValue, initialVelocity, animationSpec, block)
}

public suspend fun animateDecay(
    initialValue: Float,
    initialVelocity: Float,
    animationSpec: FloatDecayAnimationSpec,
    block: (value: Float, velocity: Float) -> Unit,
) {
    val anim = DecayAnimation(animationSpec, initialValue, initialVelocity)
    AnimationState(initialValue, initialVelocity).animate(anim) {
        block(value, velocityVector.value)
    }
}

public suspend fun <T, V : AnimationVector> animate(
    typeConverter: TwoWayConverter<T, V>,
    initialValue: T,
    targetValue: T,
    initialVelocity: T? = null,
    animationSpec: AnimationSpec<T> = spring(),
    block: (value: T, velocity: T) -> Unit,
) {
    val initialVelocityVector =
        initialVelocity?.let { typeConverter.convertToVector(it) }
            ?: typeConverter.convertToVector(initialValue).newInstance()
    val anim =
        TargetBasedAnimation(
            animationSpec = animationSpec,
            initialValue = initialValue,
            targetValue = targetValue,
            typeConverter = typeConverter,
            initialVelocityVector = initialVelocityVector,
        )
    AnimationState(typeConverter, initialValue, initialVelocityVector).animate(anim) {
        block(value, typeConverter.convertFromVector(velocityVector))
    }
}

public suspend fun <T, V : AnimationVector> AnimationState<T, V>.animateTo(
    targetValue: T,
    animationSpec: AnimationSpec<T> = spring(),
    sequentialAnimation: Boolean = false,
    block: AnimationScope<T, V>.() -> Unit = {},
) {
    val anim =
        TargetBasedAnimation(
            animationSpec = animationSpec,
            initialValue = value,
            targetValue = targetValue,
            typeConverter = typeConverter,
            initialVelocityVector = velocityVector,
        )
    animate(
        anim,
        if (sequentialAnimation) lastFrameTimeNanos else AnimationConstants.UnspecifiedTime,
        block,
    )
}

public suspend fun <T, V : AnimationVector> AnimationState<T, V>.animateDecay(
    animationSpec: DecayAnimationSpec<T>,
    sequentialAnimation: Boolean = false,
    block: AnimationScope<T, V>.() -> Unit = {},
) {
    val anim =
        DecayAnimation<T, V>(
            animationSpec = animationSpec,
            initialValue = value,
            initialVelocityVector = velocityVector,
            typeConverter = typeConverter,
        )
    animate(
        anim,
        if (sequentialAnimation) lastFrameTimeNanos else AnimationConstants.UnspecifiedTime,
        block,
    )
}

// TODO: This method uses AnimationState and Animation at the same time, it's potentially confusing
//  as to which is the source of truth for initial value/velocity. Consider letting [Animation] have
//  some suspend fun differently.
internal suspend fun <T, V : AnimationVector> AnimationState<T, V>.animate(
    animation: Animation<T, V>,
    startTimeNanos: Long = AnimationConstants.UnspecifiedTime,
    block: AnimationScope<T, V>.() -> Unit = {},
) {
    val initialValue = animation.getValueFromNanos(0)
    val initialVelocityVector = animation.getVelocityVectorFromNanos(0)
    var lateInitScope: AnimationScope<T, V>? = null
    try {
        if (startTimeNanos == AnimationConstants.UnspecifiedTime) {
            val durationScale = coroutineContext.durationScale
            animation.callWithFrameNanos {
                lateInitScope =
                    AnimationScope(
                            initialValue = initialValue,
                            typeConverter = animation.typeConverter,
                            initialVelocityVector = initialVelocityVector,
                            lastFrameTimeNanos = it,
                            targetValue = animation.targetValue,
                            startTimeNanos = it,
                            isRunning = true,
                            onCancel = { isRunning = false },
                        )
                        .apply {
                            // First frame
                            doAnimationFrameWithScale(
                                it,
                                durationScale,
                                animation,
                                this@animate,
                                block,
                            )
                        }
            }
        } else {
            lateInitScope =
                AnimationScope(
                        initialValue = initialValue,
                        typeConverter = animation.typeConverter,
                        initialVelocityVector = initialVelocityVector,
                        lastFrameTimeNanos = startTimeNanos,
                        targetValue = animation.targetValue,
                        startTimeNanos = startTimeNanos,
                        isRunning = true,
                        onCancel = { isRunning = false },
                    )
                    .apply {
                        // First frame
                        doAnimationFrameWithScale(
                            startTimeNanos,
                            coroutineContext.durationScale,
                            animation,
                            this@animate,
                            block,
                        )
                    }
        }
        // Subsequent frames
        while (lateInitScope!!.isRunning) {
            val durationScale = coroutineContext.durationScale
            animation.callWithFrameNanos {
                lateInitScope!!.doAnimationFrameWithScale(it, durationScale, animation, this, block)
            }
        }
        // End of animation
    } catch (e: CancellationException) {
        lateInitScope?.isRunning = false
        if (lateInitScope?.lastFrameTimeNanos == lastFrameTimeNanos) {
            // There hasn't been another animation.
            isRunning = false
        }
        throw e
    }
}

private suspend fun <R, T, V : AnimationVector> Animation<T, V>.callWithFrameNanos(
    onFrame: (frameTimeNanos: Long) -> R
): R {
    return if (isInfinite) {
        withInfiniteAnimationFrameNanos(onFrame)
    } else {
        withFrameNanos { onFrame.invoke(it / AnimationDebugDurationScale) }
    }
}

// Pinned animation-core debug multiplier; release behavior is one-to-one frame time.
internal const val AnimationDebugDurationScale = 1

internal val CoroutineContext.durationScale: Float
    get() {
        val scale = this[MotionDurationScale]?.scaleFactor ?: 1f
        checkPrecondition(scale >= 0f) { "negative scale factor" }
        return scale
    }

internal fun <T, V : AnimationVector> AnimationScope<T, V>.updateState(
    state: AnimationState<T, V>
) {
    state.value = value
    state.velocityVector.copyFrom(velocityVector)
    state.finishedTimeNanos = finishedTimeNanos
    state.lastFrameTimeNanos = lastFrameTimeNanos
    state.isRunning = isRunning
}

private fun <T, V : AnimationVector> AnimationScope<T, V>.doAnimationFrameWithScale(
    frameTimeNanos: Long,
    durationScale: Float,
    anim: Animation<T, V>,
    state: AnimationState<T, V>,
    block: AnimationScope<T, V>.() -> Unit,
) {
    val playTimeNanos =
        if (durationScale == 0f) {
            anim.durationNanos
        } else {
            ((frameTimeNanos - startTimeNanos) / durationScale).toLong()
        }
    doAnimationFrame(frameTimeNanos, playTimeNanos, anim, state, block)
}

// Impl detail, invoked every frame.
private fun <T, V : AnimationVector> AnimationScope<T, V>.doAnimationFrame(
    frameTimeNanos: Long,
    playTimeNanos: Long,
    anim: Animation<T, V>,
    state: AnimationState<T, V>,
    block: AnimationScope<T, V>.() -> Unit,
) {
    lastFrameTimeNanos = frameTimeNanos
    value = anim.getValueFromNanos(playTimeNanos)
    velocityVector = anim.getVelocityVectorFromNanos(playTimeNanos)
    val isLastFrame = anim.isFinishedFromNanos(playTimeNanos)
    if (isLastFrame) {
        // TODO: This could probably be a little more granular
        // TODO: end time isn't necessarily last frame time
        finishedTimeNanos = lastFrameTimeNanos
        isRunning = false
    }
    updateState(state)
    block()
}
