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

import app.smallthingz.nativeui.animation.core.AnimationConstants.DefaultDurationMillis
import kotlin.math.abs

public object AnimationConstants {
    
    public const val DefaultDurationMillis: Int = 300

    
    public const val UnspecifiedTime: Long = Long.MIN_VALUE
}

public interface AnimationSpec<T> {
    
    public fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>
    ): VectorizedAnimationSpec<V>
}

public interface FiniteAnimationSpec<T> : AnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>
    ): VectorizedFiniteAnimationSpec<V>
}

public class TweenSpec<T>(
    public val durationMillis: Int = DefaultDurationMillis,
    public val delay: Int = 0,
    public val easing: Easing = FastOutSlowInEasing,
) : DurationBasedAnimationSpec<T> {

    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>
    ): VectorizedTweenSpec<V> = VectorizedTweenSpec<V>(durationMillis, delay, easing)

    override fun equals(other: Any?): Boolean =
        if (other is TweenSpec<*>) {
            other.durationMillis == this.durationMillis &&
                other.delay == this.delay &&
                other.easing == this.easing
        } else {
            false
        }

    override fun hashCode(): Int {
        return (durationMillis * 31 + easing.hashCode()) * 31 + delay
    }
}

public interface DurationBasedAnimationSpec<T> : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>
    ): VectorizedDurationBasedAnimationSpec<V>
}

public class SpringSpec<T>(
    public val dampingRatio: Float = Spring.DampingRatioNoBouncy,
    public val stiffness: Float = Spring.StiffnessMedium,
    public val visibilityThreshold: T? = null,
) : FiniteAnimationSpec<T> {

    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>
    ): VectorizedSpringSpec<V> =
        VectorizedSpringSpec(dampingRatio, stiffness, converter.convert(visibilityThreshold))

    override fun equals(other: Any?): Boolean =
        if (other is SpringSpec<*>) {
            other.dampingRatio == this.dampingRatio &&
                other.stiffness == this.stiffness &&
                other.visibilityThreshold == this.visibilityThreshold
        } else {
            false
        }

    override fun hashCode(): Int =
        (visibilityThreshold.hashCode() * 31 + dampingRatio.hashCode()) * 31 + stiffness.hashCode()
}

private fun <T, V : AnimationVector> TwoWayConverter<T, V>.convert(data: T?): V? {
    if (data == null) {
        return null
    } else {
        return convertToVector(data)
    }
}

@JvmInline
public value class StartOffsetType private constructor(internal val value: Int) {
    public companion object {
        
        public val Delay: StartOffsetType = StartOffsetType(-1)

        
        public val FastForward: StartOffsetType = StartOffsetType(1)
    }
}

@JvmInline
public value class StartOffset private constructor(internal val value: Long) {
    public constructor(
        offsetMillis: Int,
        offsetType: StartOffsetType = StartOffsetType.Delay,
    ) : this((offsetMillis * offsetType.value).toLong())

    public val offsetMillis: Int
        get() = abs(this.value.toInt())

    public val offsetType: StartOffsetType
        get() = when (this.value > 0) {
            true -> StartOffsetType.FastForward
            false -> StartOffsetType.Delay
        }
}

public class InfiniteRepeatableSpec<T>(
    public val animation: DurationBasedAnimationSpec<T>,
    public val repeatMode: RepeatMode = RepeatMode.Restart,
    public val initialStartOffset: StartOffset = StartOffset(0),
) : AnimationSpec<T> {

    @Deprecated(level = DeprecationLevel.HIDDEN, message = "This constructor has been deprecated")
    public constructor(
        animation: DurationBasedAnimationSpec<T>,
        repeatMode: RepeatMode = RepeatMode.Restart,
    ) : this(animation, repeatMode, StartOffset(0))

    init {
        // Checking if the animation has 0-duration. If so, it would result in an infinite
        // loop for infinite animations.
        val isZeroDuration =
            when (animation) {
                is TweenSpec -> animation.durationMillis == 0 && animation.delay == 0
                is SnapSpec -> animation.delay == 0
                is KeyframesSpec ->
                    animation.config.durationMillis == 0 && animation.config.delayMillis == 0
                else -> false
            }
        if (isZeroDuration) {
            throw IllegalArgumentException(
                "Animation to be infinitely repeated cannot have a" + " 0-duration"
            )
        }
    }

    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>
    ): VectorizedAnimationSpec<V> {
        return VectorizedInfiniteRepeatableSpec(
            animation.vectorize(converter),
            repeatMode,
            initialStartOffset,
        )
    }

    override fun equals(other: Any?): Boolean =
        if (other is InfiniteRepeatableSpec<*>) {
            other.animation == this.animation &&
                other.repeatMode == this.repeatMode &&
                other.initialStartOffset == this.initialStartOffset
        } else {
            false
        }

    override fun hashCode(): Int {
        return (animation.hashCode() * 31 + repeatMode.hashCode()) * 31 +
            initialStartOffset.hashCode()
    }
}

public enum class RepeatMode {
    
    Restart,

    
    Reverse,
}

public class SnapSpec<T>(public val delay: Int = 0) : DurationBasedAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<T, V>
    ): VectorizedDurationBasedAnimationSpec<V> = VectorizedSnapSpec(delay)

    override fun equals(other: Any?): Boolean =
        if (other is SnapSpec<*>) {
            other.delay == this.delay
        } else {
            false
        }

    override fun hashCode(): Int {
        return delay
    }
}
