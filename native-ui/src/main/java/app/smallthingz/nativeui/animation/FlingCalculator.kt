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

package app.smallthingz.nativeui.animation

import app.smallthingz.nativeui.unit.Density
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign

private const val GravityEarth = 9.80665f
private const val InchesPerMeter = 39.37f

private val DecelerationRate = (ln(0.78) / ln(0.9)).toFloat()

private fun computeDeceleration(friction: Float, density: Float): Float =
    GravityEarth * InchesPerMeter * density * 160f * friction

internal class FlingCalculator(private val friction: Float, val density: Density) {

    
    private val magicPhysicalCoefficient: Float = computeDeceleration(density)

    
    private fun computeDeceleration(density: Density) = computeDeceleration(0.84f, density.density)

    private fun getSplineDeceleration(velocity: Float): Double =
        AndroidFlingSpline.deceleration(velocity, friction * magicPhysicalCoefficient)

    
    fun flingDuration(velocity: Float): Long {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = DecelerationRate - 1.0
        return (1000.0 * exp(l / decelMinusOne)).toLong()
    }

    
    fun flingDistance(velocity: Float): Float {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = DecelerationRate - 1.0
        return (friction * magicPhysicalCoefficient * exp(DecelerationRate / decelMinusOne * l))
            .toFloat()
    }

    
    fun flingInfo(velocity: Float): FlingInfo {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = DecelerationRate - 1.0
        return FlingInfo(
            initialVelocity = velocity,
            distance =
                (friction * magicPhysicalCoefficient * exp(DecelerationRate / decelMinusOne * l))
                    .toFloat(),
            duration = (1000.0 * exp(l / decelMinusOne)).toLong(),
        )
    }

    
    data class FlingInfo(val initialVelocity: Float, val distance: Float, val duration: Long) {
        fun position(time: Long): Float {
            val splinePos = if (duration > 0) time / duration.toFloat() else 1f
            return distance *
                sign(initialVelocity) *
                AndroidFlingSpline.flingPosition(splinePos).distanceCoefficient
        }

        fun velocity(time: Long): Float {
            val splinePos = if (duration > 0) time / duration.toFloat() else 1f
            return AndroidFlingSpline.flingPosition(splinePos).velocityCoefficient *
                sign(initialVelocity) *
                distance / duration * 1000.0f
        }
    }
}
