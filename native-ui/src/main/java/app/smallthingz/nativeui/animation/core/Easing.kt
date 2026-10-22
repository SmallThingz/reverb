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

import app.smallthingz.nativeui.util.fastCoerceIn
import kotlin.math.max

public fun interface Easing {
    public fun transform(fraction: Float): Float
}

public val FastOutSlowInEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

public val LinearOutSlowInEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

public val FastOutLinearInEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

public val LinearEasing: Easing = Easing { fraction -> fraction }

// This is equal to 1f.ulp or 1f.nextUp() - 1f, but neither ulp nor nextUp() are part of all KMP
// targets, only JVM and native
private const val OneUlpAt1 = 1.1920929e-7f

public class CubicBezierEasing(
    private val a: Float,
    private val b: Float,
    private val c: Float,
    private val d: Float,
) : Easing {
    private val min: Float
    private val max: Float

    init {
        requirePrecondition(!a.isNaN() && !b.isNaN() && !c.isNaN() && !d.isNaN()) {
            "Parameters to CubicBezierEasing cannot be NaN. Actual parameters are: $a, $b, $c, $d."
        }
        val roots = FloatArray(5)
        val extrema = computeCubicVerticalBounds(0.0f, b, d, 1.0f, roots, 0)
        min = extrema.first
        max = extrema.second
    }

    
    override fun transform(fraction: Float): Float {
        return if (fraction > 0f && fraction < 1f) {
            // We translate the coordinates by the fraction when calling findFirstCubicRoot,
            // but we need to make sure the translation can be done at 1.0f so we take at
            // least 1 ulp at 1.0f
            val f = max(fraction, OneUlpAt1)
            val t = findFirstCubicRoot(0.0f - f, a - f, c - f, 1.0f - f)

            // No root, the cubic curve has no solution
            if (t.isNaN()) {
                throwNoSolution(fraction)
            }

            // Don't clamp the values since the curve might be used to over- or under-shoot
            // The test above that checks if fraction is in ]0..1[ will ensure we start and
            // end at 0 and 1 respectively
            evaluateCubic(b, d, t).fastCoerceIn(min, max)
        } else {
            fraction
        }
    }

    private fun throwNoSolution(fraction: Float) {
        throw IllegalArgumentException(
            "The cubic curve with parameters ($a, $b, $c, $d) has no solution at $fraction"
        )
    }

    override fun equals(other: Any?): Boolean {
        return other is CubicBezierEasing &&
            a == other.a &&
            b == other.b &&
            c == other.c &&
            d == other.d
    }

    override fun hashCode(): Int {
        return ((a.hashCode() * 31 + b.hashCode()) * 31 + c.hashCode()) * 31 + d.hashCode()
    }

    override fun toString(): String = "CubicBezierEasing(a=$a, b=$b, c=$c, d=$d)"
}
