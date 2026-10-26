/*
 * Copyright 2024 The Android Open Source Project
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

// App-specific subset of Material3 1.4.0; retained hue/chroma and HCT math is unchanged.
// Unused viewing-frame/UCS paths were removed. See SOURCE.md for original provenance.
package app.smallthingz.reverb.nativeui.color

import app.smallthingz.reverb.nativeui.color.CamUtils.xyzFromInt
import app.smallthingz.reverb.nativeui.color.HctSolver.solveToInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/** CAM16 hue/chroma in the standard sRGB viewing frame, used to resolve dynamic palette tones. */
internal class Cam(
    val hue: Float,
    val chroma: Float,
) {
    companion object {
        /** Maps CAM16 hue/chroma and L* to sRGB using the original default-frame HCT solver. */
        fun getInt(hue: Float, chroma: Float, lstar: Float): Int {
            return solveToInt(hue.toDouble(), chroma.toDouble(), lstar.toDouble())
        }

        /** Creates the palette's hue/chroma model from an ARGB color in the sRGB viewing frame. */
        fun fromInt(argb: Int): Cam {
            val frame = Frame.Default
            // Transform ARGB int to XYZ
            val xyz = xyzFromInt(argb)

            // Transform XYZ to 'cone'/'rgb' responses
            val matrix = CamUtils.XYZ_TO_CAM16RGB
            val rT = (xyz[0] * matrix[0][0]) + (xyz[1] * matrix[0][1]) + (xyz[2] * matrix[0][2])
            val gT = (xyz[0] * matrix[1][0]) + (xyz[1] * matrix[1][1]) + (xyz[2] * matrix[1][2])
            val bT = (xyz[0] * matrix[2][0]) + (xyz[1] * matrix[2][1]) + (xyz[2] * matrix[2][2])

            // Discount illuminant
            val rD = frame.rgbD[0] * rT
            val gD = frame.rgbD[1] * gT
            val bD = frame.rgbD[2] * bT

            // Chromatic adaptation
            val rAF = (frame.fl * abs(rD) / 100f).pow(0.42f)
            val gAF = (frame.fl * abs(gD) / 100f).pow(0.42f)
            val bAF = (frame.fl * abs(bD) / 100f).pow(0.42f)
            val rA = (sign(rD) * 400.0f * rAF / (rAF + 27.13f))
            val gA = (sign(gD) * 400.0f * gAF / (gAF + 27.13f))
            val bA = (sign(bD) * 400.0f * bAF / (bAF + 27.13f))

            // redness-greenness
            val a = (11f * rA + -12f * gA + bA) / 11.0f
            // yellowness-blueness
            val b = (rA + gA - 2f * bA) / 9.0f

            // auxiliary components
            val u = (20.0f * rA + 20.0f * gA + 21.0f * bA) / 20.0f
            val p2 = (40.0f * rA + 20.0f * gA + bA) / 20.0f

            // hue
            val atan2 = atan2(b, a)
            val atanDegrees = atan2 * 180.0f / PI.toFloat()
            val hue =
                if (atanDegrees < 0) atanDegrees + 360.0f
                else if (atanDegrees >= 360) atanDegrees - 360.0f else atanDegrees

            // achromatic response to color
            val ac = p2 * frame.nbb

            // CAM16 lightness, needed to derive chroma
            val j = 100.0f * (ac / frame.aw).pow((frame.c * frame.z))

            // CAM16 chroma
            val huePrime = if ((hue < 20.14)) hue + 360 else hue
            val eHue = 0.25f * (cos(huePrime * PI.toFloat() / 180f + 2f) + 3.8f)
            val p1 = 50000.0f / 13.0f * eHue * frame.nc * frame.ncb
            val t = p1 * sqrt((a * a + b * b)) / (u + 0.305f)
            val alpha = t.pow(0.9f) * (1.64f - 0.29f.pow(frame.n)).pow(0.73f)
            val c = alpha * sqrt(j / 100f)
            return Cam(hue, c)
        }
    }
}
