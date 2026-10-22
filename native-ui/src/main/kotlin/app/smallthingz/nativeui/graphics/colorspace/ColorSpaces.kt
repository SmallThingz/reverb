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

@file:Suppress("NOTHING_TO_INLINE")

package app.smallthingz.nativeui.graphics.colorspace

import androidx.annotation.Size
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Native renderer palette spaces. No display profiles or framework color registry. */
object ColorSpaces {
    internal val SrgbPrimaries = floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f)
    internal val Ntsc1953Primaries = floatArrayOf(0.67f, 0.33f, 0.21f, 0.71f, 0.14f, 0.08f)
    internal val SrgbTransferParameters = TransferParameters(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)
    private val NoneTransferParameters = TransferParameters(2.2, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)
    val Srgb = Rgb("sRGB IEC61966-2.1", SrgbPrimaries, Illuminant.D65, SrgbTransferParameters, id = 0)
    internal val Unspecified = Rgb("None", SrgbPrimaries, Illuminant.D65, NoneTransferParameters, id = 16)
    val Oklab: ColorSpace = Oklab("Oklab", id = 19)
    internal fun getColorSpace(id: Int): ColorSpace = when (id) {
        0 -> Srgb
        16 -> Unspecified
        19 -> Oklab
        else -> throw IllegalArgumentException("Unsupported native color space: $id")
    }

    internal fun transferHlgOetf(params: TransferParameters, x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        var absX = x * sign

        // Unpack the transfer params matching skia's packing & invert R, G, and a
        val R = 1.0 / params.a
        val G = 1.0 / params.b
        val a = 1.0 / params.c
        val b = params.d
        val c = params.e
        val K = params.f + 1.0

        absX /= K
        val result =
            if (absX <= 1) {
                R * absX.pow(G)
            } else {
                a * ln(absX - b) + c
            }
        return sign * result
    }

    internal fun transferHlgEotf(params: TransferParameters, x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val absX = x * sign

        // Unpack the transfer params matching skia's packing
        val R = params.a
        val G = params.b
        val a = params.c
        val b = params.d
        val c = params.e
        val K = params.f + 1.0

        val result =
            if (absX * R <= 1) {
                (absX * R).pow(G)
            } else {
                exp((absX - c) * a) + b
            }
        return K * sign * result
    }

    internal fun transferSt2048Oetf(params: TransferParameters, x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val absX = x * sign

        val a = -params.a
        val b = params.d
        val c = 1.0 / params.f
        val d = params.b
        val e = -params.e
        val f = 1.0 / params.c

        val tmp = max(a + b * absX.pow(c), 0.0)
        return sign * (tmp / (d + e * absX.pow(c))).pow(f)
    }

    internal fun transferSt2048Eotf(pq: TransferParameters, x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val absX = x * sign

        val tmp = (pq.a + pq.b * absX.pow(pq.c)).coerceAtLeast(0.0)
        return sign * (tmp / (pq.d + pq.e * absX.pow(pq.c))).pow(pq.f)
    }
}
