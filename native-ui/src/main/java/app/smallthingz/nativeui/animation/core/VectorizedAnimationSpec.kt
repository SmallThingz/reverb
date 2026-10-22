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

import app.smallthingz.nativeui.animation.core.AnimationConstants.DefaultDurationMillis
import app.smallthingz.nativeui.util.fastCoerceIn

public interface VectorizedAnimationSpec<V : AnimationVector> {
    
    public val isInfinite: Boolean

    
    public fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V

    
    public fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V

    
    @Suppress("MethodNameUnits")
    public fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long

    
    public fun getEndVelocity(initialValue: V, targetValue: V, initialVelocity: V): V =
        getVelocityFromNanos(
            getDurationNanos(initialValue, targetValue, initialVelocity),
            initialValue,
            targetValue,
            initialVelocity,
        )
}

internal fun <V : AnimationVector> VectorizedAnimationSpec<V>.getDurationMillis(
    initialValue: V,
    targetValue: V,
    initialVelocity: V,
): Long = getDurationNanos(initialValue, targetValue, initialVelocity) / MillisToNanos

// TODO: Move tests off this API
internal fun <V : AnimationVector> VectorizedAnimationSpec<V>.getValueFromMillis(
    playTimeMillis: Long,
    start: V,
    end: V,
    startVelocity: V,
): V = getValueFromNanos(playTimeMillis * MillisToNanos, start, end, startVelocity)

public interface VectorizedFiniteAnimationSpec<V : AnimationVector> : VectorizedAnimationSpec<V> {
    override val isInfinite: Boolean
        get() = false
}

public interface VectorizedDurationBasedAnimationSpec<V : AnimationVector> :
    VectorizedFiniteAnimationSpec<V> {
    
    public val durationMillis: Int

    
    public val delayMillis: Int

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        (delayMillis + durationMillis) * MillisToNanos
}

internal fun VectorizedDurationBasedAnimationSpec<*>.clampPlayTime(playTime: Long): Long {
    return (playTime - delayMillis).fastCoerceIn(0, durationMillis.toLong())
}



public class VectorizedSnapSpec<V : AnimationVector>(override val delayMillis: Int = 0) :
    VectorizedDurationBasedAnimationSpec<V> {

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        return if (playTimeNanos < delayMillis * MillisToNanos) {
            initialValue
        } else {
            targetValue
        }
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        return initialVelocity
    }

    override val durationMillis: Int
        get() = 0
}

public class VectorizedInfiniteRepeatableSpec<V : AnimationVector>(
    private val animation: VectorizedDurationBasedAnimationSpec<V>,
    private val repeatMode: RepeatMode = RepeatMode.Restart,
    initialStartOffset: StartOffset = StartOffset(0),
) : VectorizedAnimationSpec<V> {
    @Deprecated(
        level = DeprecationLevel.HIDDEN,
        message =
            "This method has been deprecated in favor of the constructor that" +
                " accepts start offset.",
    )
    public constructor(
        animation: VectorizedDurationBasedAnimationSpec<V>,
        repeatMode: RepeatMode = RepeatMode.Restart,
    ) : this(animation, repeatMode, StartOffset(0))

    override val isInfinite: Boolean
        get() = true

    
    internal val durationNanos: Long =
        (animation.delayMillis + animation.durationMillis) * MillisToNanos

    private val initialOffsetNanos = initialStartOffset.value * MillisToNanos

    private fun repetitionPlayTimeNanos(playTimeNanos: Long): Long {
        if (playTimeNanos + initialOffsetNanos <= 0) {
            return 0
        } else {
            val postOffsetPlayTimeNanos = playTimeNanos + initialOffsetNanos
            val repeatsCount = postOffsetPlayTimeNanos / durationNanos
            if (repeatMode == RepeatMode.Restart || repeatsCount % 2 == 0L) {
                return postOffsetPlayTimeNanos - repeatsCount * durationNanos
            } else {
                return (repeatsCount + 1) * durationNanos - postOffsetPlayTimeNanos
            }
        }
    }

    private fun repetitionStartVelocity(
        playTimeNanos: Long,
        start: V,
        startVelocity: V,
        end: V,
    ): V =
        if (playTimeNanos + initialOffsetNanos > durationNanos) {
            // Start velocity of the 2nd and subsequent iteration will be the velocity at the end
            // of the first iteration, instead of the initial velocity.
            animation.getVelocityFromNanos(
                playTimeNanos = durationNanos - initialOffsetNanos,
                initialValue = start,
                targetValue = end,
                initialVelocity = startVelocity,
            )
        } else {
            startVelocity
        }

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        return animation.getValueFromNanos(
            repetitionPlayTimeNanos(playTimeNanos),
            initialValue,
            targetValue,
            repetitionStartVelocity(playTimeNanos, initialValue, initialVelocity, targetValue),
        )
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        return animation.getVelocityFromNanos(
            repetitionPlayTimeNanos(playTimeNanos),
            initialValue,
            targetValue,
            repetitionStartVelocity(playTimeNanos, initialValue, initialVelocity, targetValue),
        )
    }

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        Long.MAX_VALUE
}

public object Spring {
    
    public const val StiffnessHigh: Float = 10_000f

    
    public const val StiffnessMedium: Float = 1500f

    
    public const val StiffnessMediumLow: Float = 400f

    
    public const val StiffnessLow: Float = 200f

    
    public const val StiffnessVeryLow: Float = 50f

    
    public const val DampingRatioHighBouncy: Float = 0.2f

    
    public const val DampingRatioMediumBouncy: Float = 0.5f

    
    public const val DampingRatioLowBouncy: Float = 0.75f

    
    public const val DampingRatioNoBouncy: Float = 1f

    
    public const val DefaultDisplacementThreshold: Float = 0.01f
}

internal interface Animations {
    operator fun get(index: Int): FloatAnimationSpec
}

public class VectorizedSpringSpec<V : AnimationVector>
private constructor(
    public val dampingRatio: Float,
    public val stiffness: Float,
    anims: Animations,
) : VectorizedFiniteAnimationSpec<V> by VectorizedFloatAnimationSpec(anims) {

    
    public constructor(
        dampingRatio: Float = Spring.DampingRatioNoBouncy,
        stiffness: Float = Spring.StiffnessMedium,
        visibilityThreshold: V? = null,
    ) : this(
        dampingRatio,
        stiffness,
        createSpringAnimations(visibilityThreshold, dampingRatio, stiffness),
    )
}

private fun <V : AnimationVector> createSpringAnimations(
    visibilityThreshold: V?,
    dampingRatio: Float,
    stiffness: Float,
): Animations {
    return if (visibilityThreshold != null) {
        object : Animations {
            private val anims =
                Array(visibilityThreshold.size) { index ->
                    FloatSpringSpec(dampingRatio, stiffness, visibilityThreshold[index])
                }

            override fun get(index: Int): FloatSpringSpec = anims[index]
        }
    } else {
        object : Animations {
            private val anim = FloatSpringSpec(dampingRatio, stiffness)

            override fun get(index: Int): FloatSpringSpec = anim
        }
    }
}

// TODO: Support different tween on different dimens
public class VectorizedTweenSpec<V : AnimationVector>(
    override val durationMillis: Int = DefaultDurationMillis,
    override val delayMillis: Int = 0,
    public val easing: Easing = FastOutSlowInEasing,
) : VectorizedDurationBasedAnimationSpec<V> {

    private val anim =
        VectorizedFloatAnimationSpec<V>(FloatTweenSpec(durationMillis, delayMillis, easing))

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        return anim.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        return anim.getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
    }
}

public class VectorizedFloatAnimationSpec<V : AnimationVector>
internal constructor(private val anims: Animations) : VectorizedFiniteAnimationSpec<V> {
    private lateinit var valueVector: V
    private lateinit var velocityVector: V
    private lateinit var endVelocityVector: V

    
    public constructor(
        anim: FloatAnimationSpec
    ) : this(
        object : Animations {
            override fun get(index: Int): FloatAnimationSpec {
                return anim
            }
        }
    )

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        if (!::valueVector.isInitialized) {
            valueVector = initialValue.newInstance()
        }
        for (i in 0 until valueVector.size) {
            valueVector[i] =
                anims[i].getValueFromNanos(
                    playTimeNanos,
                    initialValue[i],
                    targetValue[i],
                    initialVelocity[i],
                )
        }
        return valueVector
    }

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: V,
        targetValue: V,
        initialVelocity: V,
    ): V {
        if (!::velocityVector.isInitialized) {
            velocityVector = initialVelocity.newInstance()
        }
        for (i in 0 until velocityVector.size) {
            velocityVector[i] =
                anims[i].getVelocityFromNanos(
                    playTimeNanos,
                    initialValue[i],
                    targetValue[i],
                    initialVelocity[i],
                )
        }
        return velocityVector
    }

    override fun getEndVelocity(initialValue: V, targetValue: V, initialVelocity: V): V {
        if (!::endVelocityVector.isInitialized) {
            endVelocityVector = initialVelocity.newInstance()
        }
        for (i in 0 until endVelocityVector.size) {
            endVelocityVector[i] =
                anims[i].getEndVelocity(initialValue[i], targetValue[i], initialVelocity[i])
        }
        return endVelocityVector
    }

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long {
        var maxDuration = 0L
        for (i in 0 until initialValue.size) {
            maxDuration =
                maxOf(
                    maxDuration,
                    anims[i].getDurationNanos(initialValue[i], targetValue[i], initialVelocity[i]),
                )
        }
        return maxDuration
    }
}

