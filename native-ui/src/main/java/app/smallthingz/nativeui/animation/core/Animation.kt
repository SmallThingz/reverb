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

import androidx.annotation.RestrictTo

public interface Animation<T, V : AnimationVector> {
    
    @get:Suppress("MethodNameUnits") public val durationNanos: Long

    
    public val typeConverter: TwoWayConverter<T, V>

    
    public val targetValue: T

    
    public val isInfinite: Boolean

    
    public fun getValueFromNanos(playTimeNanos: Long): T

    
    public fun getVelocityVectorFromNanos(playTimeNanos: Long): V

    
    public fun isFinishedFromNanos(playTimeNanos: Long): Boolean {
        return playTimeNanos >= durationNanos
    }
}

internal val Animation<*, *>.durationMillis: Long
    get() = durationNanos / MillisToNanos

internal const val MillisToNanos: Long = 1_000_000L

internal const val SecondsToMillis: Long = 1_000L

public fun <T, V : AnimationVector> Animation<T, V>.getVelocityFromNanos(playTimeNanos: Long): T =
    typeConverter.convertFromVector(getVelocityVectorFromNanos(playTimeNanos))

@RestrictTo(RestrictTo.Scope.LIBRARY)
public fun <V : AnimationVector> VectorizedAnimationSpec<V>.createAnimation(
    initialValue: V,
    targetValue: V,
    initialVelocity: V,
): TargetBasedAnimation<V, V> =
    TargetBasedAnimation(
        animationSpec = this,
        initialValue = initialValue,
        targetValue = targetValue,
        initialVelocityVector = initialVelocity,
        typeConverter = TwoWayConverter({ it }, { it }),
    )

public fun <T, V : AnimationVector> TargetBasedAnimation(
    animationSpec: AnimationSpec<T>,
    typeConverter: TwoWayConverter<T, V>,
    initialValue: T,
    targetValue: T,
    initialVelocity: T,
): TargetBasedAnimation<T, V> =
    TargetBasedAnimation(
        animationSpec,
        typeConverter,
        initialValue,
        targetValue,
        typeConverter.convertToVector(initialVelocity),
    )

public class TargetBasedAnimation<T, V : AnimationVector>
internal constructor(
    internal val animationSpec: VectorizedAnimationSpec<V>,
    override val typeConverter: TwoWayConverter<T, V>,
    initialValue: T,
    targetValue: T,
    initialVelocityVector: V? = null,
) : Animation<T, V> {
    internal var mutableTargetValue: T = targetValue
        set(value) {
            if (field != value) {
                field = value
                targetValueVector = typeConverter.convertToVector(value)
                _endVelocity = null
                _durationNanos = -1L
            }
        }

    internal var mutableInitialValue: T = initialValue
        set(value) {
            if (value != field) {
                field = value
                initialValueVector = typeConverter.convertToVector(value)
                _endVelocity = null
                _durationNanos = -1L
            }
        }

    public val initialValue: T
        get() = mutableInitialValue

    override val targetValue: T
        get() = mutableTargetValue

    
    public constructor(
        animationSpec: AnimationSpec<T>,
        typeConverter: TwoWayConverter<T, V>,
        initialValue: T,
        targetValue: T,
        initialVelocityVector: V? = null,
    ) : this(
        animationSpec.vectorize(typeConverter),
        typeConverter,
        initialValue,
        targetValue,
        initialVelocityVector,
    )

    private var initialValueVector = typeConverter.convertToVector(initialValue)
    private var targetValueVector = typeConverter.convertToVector(targetValue)
    private val initialVelocityVector =
        initialVelocityVector?.copy() ?: typeConverter.convertToVector(initialValue).newInstance()

    override val isInfinite: Boolean
        get() = animationSpec.isInfinite

    override fun getValueFromNanos(playTimeNanos: Long): T {
        return if (!isFinishedFromNanos(playTimeNanos)) {
            animationSpec
                .getValueFromNanos(
                    playTimeNanos,
                    initialValueVector,
                    targetValueVector,
                    initialVelocityVector,
                )
                .let {
                    // TODO: Remove after b/232030217
                    for (i in 0 until it.size) {
                        checkPrecondition(!it.get(i).isNaN()) {
                            "AnimationVector cannot contain a NaN. $it. Animation: $this," +
                                " playTimeNanos: $playTimeNanos"
                        }
                    }
                    typeConverter.convertFromVector(it)
                }
        } else {
            targetValue
        }
    }

    private var _durationNanos: Long = -1L

    @get:Suppress("MethodNameUnits")
    override val durationNanos: Long
        get() {
            if (_durationNanos < 0L) {
                _durationNanos =
                    animationSpec.getDurationNanos(
                        initialValue = initialValueVector,
                        targetValue = targetValueVector,
                        initialVelocity = this.initialVelocityVector,
                    )
            }
            return _durationNanos
        }

    private var _endVelocity: V? = null

    private val endVelocity
        get() =
            _endVelocity
                ?: animationSpec
                    .getEndVelocity(
                        initialValueVector,
                        targetValueVector,
                        this.initialVelocityVector,
                    )
                    .also { _endVelocity = it }

    override fun getVelocityVectorFromNanos(playTimeNanos: Long): V {
        return if (!isFinishedFromNanos(playTimeNanos)) {
            animationSpec.getVelocityFromNanos(
                playTimeNanos,
                initialValueVector,
                targetValueVector,
                initialVelocityVector,
            )
        } else {
            endVelocity
        }
    }

    override fun toString(): String {
        return "TargetBasedAnimation: $initialValue -> $targetValue," +
            "initial velocity: $initialVelocityVector, duration: $durationMillis ms," +
            "animationSpec: $animationSpec"
    }
}

public class DecayAnimation<T, V : AnimationVector> 
constructor(
    private val animationSpec: VectorizedDecayAnimationSpec<V>,
    override val typeConverter: TwoWayConverter<T, V>,
    public val initialValue: T,
    initialVelocityVector: V,
) : Animation<T, V> {
    private val initialValueVector: V = typeConverter.convertToVector(initialValue)
    public val initialVelocityVector: V = initialVelocityVector.copy()
    private val endVelocity: V

    override val targetValue: T =
        typeConverter.convertFromVector(
            animationSpec.getTargetValue(initialValueVector, initialVelocityVector)
        )
    @get:Suppress("MethodNameUnits") override val durationNanos: Long

    // DecayAnimation finishes by design
    override val isInfinite: Boolean = false

    
    public constructor(
        animationSpec: DecayAnimationSpec<T>,
        typeConverter: TwoWayConverter<T, V>,
        initialValue: T,
        initialVelocityVector: V,
    ) : this(
        animationSpec.vectorize(typeConverter),
        typeConverter,
        initialValue,
        initialVelocityVector,
    )

    
    public constructor(
        animationSpec: DecayAnimationSpec<T>,
        typeConverter: TwoWayConverter<T, V>,
        initialValue: T,
        initialVelocity: T,
    ) : this(
        animationSpec.vectorize(typeConverter),
        typeConverter,
        initialValue,
        typeConverter.convertToVector(initialVelocity),
    )

    init {
        durationNanos = animationSpec.getDurationNanos(initialValueVector, initialVelocityVector)
        endVelocity =
            animationSpec
                .getVelocityFromNanos(durationNanos, initialValueVector, initialVelocityVector)
                .copy()
        for (i in 0 until endVelocity.size) {
            endVelocity[i] =
                endVelocity[i].coerceIn(
                    -animationSpec.absVelocityThreshold,
                    animationSpec.absVelocityThreshold,
                )
        }
    }

    override fun getValueFromNanos(playTimeNanos: Long): T {
        if (!isFinishedFromNanos(playTimeNanos)) {
            return typeConverter.convertFromVector(
                animationSpec.getValueFromNanos(
                    playTimeNanos,
                    initialValueVector,
                    initialVelocityVector,
                )
            )
        } else {
            return targetValue
        }
    }

    override fun getVelocityVectorFromNanos(playTimeNanos: Long): V {
        if (!isFinishedFromNanos(playTimeNanos)) {
            return animationSpec.getVelocityFromNanos(
                playTimeNanos,
                initialValueVector,
                initialVelocityVector,
            )
        } else {
            return endVelocity
        }
    }
}

public fun DecayAnimation(
    animationSpec: FloatDecayAnimationSpec,
    initialValue: Float,
    initialVelocity: Float = 0f,
): DecayAnimation<Float, AnimationVector1D> =
    DecayAnimation(
        animationSpec.generateDecayAnimationSpec(),
        Float.VectorConverter,
        initialValue,
        AnimationVector(initialVelocity),
    )
