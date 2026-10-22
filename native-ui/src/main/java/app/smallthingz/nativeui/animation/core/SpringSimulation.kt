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

@file:Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")

package app.smallthingz.nativeui.animation.core

import app.smallthingz.nativeui.util.packFloats
import app.smallthingz.nativeui.util.unpackFloat1
import app.smallthingz.nativeui.util.unpackFloat2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

@kotlin.jvm.JvmInline
internal value class Motion(val packedValue: Long) {
    inline val value: Float
        get() = unpackFloat1(packedValue)

    inline val velocity: Float
        get() = unpackFloat2(packedValue)
}

internal inline fun Motion(value: Float, velocity: Float) = Motion(packFloats(value, velocity))

internal class SpringSimulation(var finalPosition: Float) {
    // Natural frequency
    private var naturalFreq = sqrt(Spring.StiffnessVeryLow.toDouble())

    
    var stiffness: Float
        set(value) {
            if (stiffness <= 0) {
                throwIllegalArgumentException("Spring stiffness constant must be positive.")
            }
            naturalFreq = sqrt(value.toDouble())
        }
        get() {
            return (naturalFreq * naturalFreq).toFloat()
        }

    
    var dampingRatio: Float = Spring.DampingRatioNoBouncy
        set(value) {
            if (value < 0) {
                throwIllegalArgumentException("Damping ratio must be non-negative")
            }
            field = value
        }

    
    fun getAcceleration(lastDisplacement: Float, lastVelocity: Float): Float {
        val adjustedDisplacement = lastDisplacement - finalPosition

        val k = naturalFreq * naturalFreq
        val c = 2.0 * naturalFreq * dampingRatio

        return (-k * adjustedDisplacement - c * lastVelocity).toFloat()
    }

    
    internal fun updateValues(
        lastDisplacement: Float,
        lastVelocity: Float,
        timeElapsed: Long,
    ): Motion {
        val adjustedDisplacement = lastDisplacement - finalPosition
        val deltaT = timeElapsed / 1000.0 // unit: seconds
        val dampingRatioSquared = dampingRatio * dampingRatio.toDouble()
        val r = -dampingRatio * naturalFreq

        val displacement: Double
        val currentVelocity: Double

        if (dampingRatio > 1) {
            // Over damping
            val s = naturalFreq * sqrt(dampingRatioSquared - 1)
            val gammaPlus = r + s
            val gammaMinus = r - s

            // Overdamped
            val coeffB =
                (gammaMinus * adjustedDisplacement - lastVelocity) / (gammaMinus - gammaPlus)
            val coeffA = adjustedDisplacement - coeffB
            displacement = (coeffA * exp(gammaMinus * deltaT) + coeffB * exp(gammaPlus * deltaT))
            currentVelocity =
                (coeffA * gammaMinus * exp(gammaMinus * deltaT) +
                    coeffB * gammaPlus * exp(gammaPlus * deltaT))
        } else if (dampingRatio == 1.0f) {
            // Critically damped
            val coeffA = adjustedDisplacement
            val coeffB = lastVelocity + naturalFreq * adjustedDisplacement
            val nFdT = -naturalFreq * deltaT
            displacement = (coeffA + coeffB * deltaT) * exp(nFdT)
            currentVelocity =
                (((coeffA + coeffB * deltaT) * exp(nFdT) * (-naturalFreq)) + coeffB * exp(nFdT))
        } else {
            val dampedFreq = naturalFreq * sqrt(1 - dampingRatioSquared)
            // Underdamped
            val cosCoeff = adjustedDisplacement
            val sinCoeff = ((1 / dampedFreq) * (((-r * adjustedDisplacement) + lastVelocity)))
            val dFdT = dampedFreq * deltaT
            displacement = (exp(r * deltaT) * ((cosCoeff * cos(dFdT) + sinCoeff * sin(dFdT))))
            currentVelocity =
                (displacement * r +
                    (exp(r * deltaT) *
                        ((-dampedFreq * cosCoeff * sin(dFdT) + dampedFreq * sinCoeff * cos(dFdT)))))
        }

        val newValue = (displacement + finalPosition).toFloat()
        val newVelocity = currentVelocity.toFloat()

        return Motion(newValue, newVelocity)
    }
}
