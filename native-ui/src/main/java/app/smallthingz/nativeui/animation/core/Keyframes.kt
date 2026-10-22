/*
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Derived from animation-core 1.11.2's linear keyframe path. Arc/spline families
 * are deliberately absent: native controls use scalar linear/eased keyframes.
 */
package app.smallthingz.nativeui.animation.core

class KeyframesSpec<T>(val config: KeyframesSpecConfig<T>) : DurationBasedAnimationSpec<T> {
    class KeyframeEntity<T>(val value: T, var easing: Easing = LinearEasing)
    class KeyframesSpecConfig<T> {
        var durationMillis: Int = AnimationConstants.DefaultDurationMillis
        var delayMillis: Int = 0
        internal val keyframes = LinkedHashMap<Int, KeyframeEntity<T>>()
        infix fun T.at(timeStamp: Int): KeyframeEntity<T> =
            KeyframeEntity(this).also { keyframes[timeStamp] = it }
        infix fun KeyframeEntity<T>.using(easing: Easing): KeyframeEntity<T> =
            apply { this.easing = easing }
    }
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedDurationBasedAnimationSpec<V> =
        VectorizedKeyframesSpec(config.keyframes.mapValues { (_, e) ->
            converter.convertToVector(e.value) to e.easing
        }, config.durationMillis, config.delayMillis)
}

class VectorizedKeyframesSpec<V : AnimationVector>(
    private val keyframes: Map<Int, Pair<V, Easing>>,
    override val durationMillis: Int,
    override val delayMillis: Int = 0,
) : VectorizedDurationBasedAnimationSpec<V> {
    private val timestamps = (keyframes.keys + 0 + durationMillis).distinct().sorted().toIntArray()
    private var valueVector: V? = null
    private var velocityVector: V? = null

    private fun init(initialValue: V, initialVelocity: V) {
        if (valueVector == null) {
            valueVector = initialValue.newInstance()
            velocityVector = initialVelocity.newInstance()
        }
    }

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
        val clampedPlayTime = clampPlayTime(playTimeNanos / MillisToNanos).toInt()
        keyframes[clampedPlayTime]?.let { return it.first }
        if (clampedPlayTime >= durationMillis) return targetValue
        if (clampedPlayTime <= 0) return initialValue
        init(initialValue, initialVelocity)
        val result = valueVector!!
        val search = timestamps.binarySearch(clampedPlayTime)
        val index = if (search < -1) -(search + 2) else search
        val timeMin = timestamps[index]
        val timeMax = timestamps[index + 1]
        val easing = keyframes[timeMin]?.second ?: LinearEasing
        val rawFraction = (clampedPlayTime - timeMin).toFloat() / (timeMax - timeMin)
        val fraction = easing.transform(rawFraction)
        val start = keyframes[timeMin]?.first ?: initialValue
        val end = keyframes[timeMax]?.first ?: targetValue
        for (i in 0 until result.size) result[i] = lerp(start[i], end[i], fraction)
        return result
    }

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
        val clampedPlayTime = clampPlayTime(playTimeNanos / MillisToNanos)
        if (clampedPlayTime < 0L) return initialVelocity
        init(initialValue, initialVelocity)
        // Keep the released method's vector aliasing and subtraction order.
        val start = getValueFromMillis(clampedPlayTime - 1, initialValue, targetValue, initialVelocity)
        val end = getValueFromMillis(clampedPlayTime, initialValue, targetValue, initialVelocity)
        return velocityVector!!.also { result ->
            for (i in 0 until start.size) result[i] = (start[i] - end[i]) * 1000f
        }
    }
}

fun <T> tween(durationMillis: Int = AnimationConstants.DefaultDurationMillis, delayMillis: Int = 0,
              easing: Easing = FastOutSlowInEasing): TweenSpec<T> = TweenSpec(durationMillis, delayMillis, easing)
fun <T> spring(dampingRatio: Float = Spring.DampingRatioNoBouncy, stiffness: Float = Spring.StiffnessMedium,
               visibilityThreshold: T? = null): SpringSpec<T> = SpringSpec(dampingRatio, stiffness, visibilityThreshold)
fun <T> snap(delayMillis: Int = 0): SnapSpec<T> = SnapSpec(delayMillis)
fun <T> keyframes(init: KeyframesSpec.KeyframesSpecConfig<T>.() -> Unit): KeyframesSpec<T> =
    KeyframesSpec(KeyframesSpec.KeyframesSpecConfig<T>().apply(init))
fun <T> infiniteRepeatable(animation: DurationBasedAnimationSpec<T>, repeatMode: RepeatMode = RepeatMode.Restart,
                           initialStartOffset: StartOffset = StartOffset(0)): InfiniteRepeatableSpec<T> =
    InfiniteRepeatableSpec(animation, repeatMode, initialStartOffset)
