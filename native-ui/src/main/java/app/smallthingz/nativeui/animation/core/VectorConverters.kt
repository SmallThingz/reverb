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

@file:Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")

package app.smallthingz.nativeui.animation.core

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect
import app.smallthingz.nativeui.geometry.Size
import app.smallthingz.nativeui.unit.Dp
import app.smallthingz.nativeui.unit.IntOffset
import app.smallthingz.nativeui.unit.IntSize
import app.smallthingz.nativeui.unit.dp
import app.smallthingz.nativeui.util.fastCoerceAtLeast
import app.smallthingz.nativeui.util.fastRoundToInt

public interface TwoWayConverter<T, V : AnimationVector> {
    
    public val convertToVector: (T) -> V
    
    public val convertFromVector: (V) -> T
}

public fun <T, V : AnimationVector> TwoWayConverter(
    convertToVector: (T) -> V,
    convertFromVector: (V) -> T,
): TwoWayConverter<T, V> = TwoWayConverterImpl(convertToVector, convertFromVector)

private class TwoWayConverterImpl<T, V : AnimationVector>(
    override val convertToVector: (T) -> V,
    override val convertFromVector: (V) -> T,
) : TwoWayConverter<T, V>

internal inline fun lerp(start: Float, stop: Float, fraction: Float) =
    (start * (1 - fraction) + stop * fraction)

public val Float.Companion.VectorConverter: TwoWayConverter<Float, AnimationVector1D>
    get() = FloatToVector

public val Int.Companion.VectorConverter: TwoWayConverter<Int, AnimationVector1D>
    get() = IntToVector

private val FloatToVector: TwoWayConverter<Float, AnimationVector1D> =
    TwoWayConverter({ AnimationVector1D(it) }, { it.value })

private val IntToVector: TwoWayConverter<Int, AnimationVector1D> =
    TwoWayConverter({ AnimationVector1D(it.toFloat()) }, { it.value.toInt() })

public val Rect.Companion.VectorConverter: TwoWayConverter<Rect, AnimationVector4D>
    get() = RectToVector

public val Dp.Companion.VectorConverter: TwoWayConverter<Dp, AnimationVector1D>
    get() = DpToVector

public val Size.Companion.VectorConverter: TwoWayConverter<Size, AnimationVector2D>
    get() = SizeToVector

public val Offset.Companion.VectorConverter: TwoWayConverter<Offset, AnimationVector2D>
    get() = OffsetToVector

public val IntOffset.Companion.VectorConverter: TwoWayConverter<IntOffset, AnimationVector2D>
    get() = IntOffsetToVector

public val IntSize.Companion.VectorConverter: TwoWayConverter<IntSize, AnimationVector2D>
    get() = IntSizeToVector

private val DpToVector: TwoWayConverter<Dp, AnimationVector1D> =
    TwoWayConverter(
        convertToVector = { AnimationVector1D(it.value) },
        convertFromVector = { Dp(it.value) },
    )

private val SizeToVector: TwoWayConverter<Size, AnimationVector2D> =
    TwoWayConverter(
        convertToVector = { AnimationVector2D(it.width, it.height) },
        convertFromVector = { Size(it.v1, it.v2) },
    )

private val OffsetToVector: TwoWayConverter<Offset, AnimationVector2D> =
    TwoWayConverter(
        convertToVector = { AnimationVector2D(it.x, it.y) },
        convertFromVector = { Offset(it.v1, it.v2) },
    )

private val IntOffsetToVector: TwoWayConverter<IntOffset, AnimationVector2D> =
    TwoWayConverter(
        convertToVector = { AnimationVector2D(it.x.toFloat(), it.y.toFloat()) },
        convertFromVector = { IntOffset(it.v1.fastRoundToInt(), it.v2.fastRoundToInt()) },
    )

private val IntSizeToVector: TwoWayConverter<IntSize, AnimationVector2D> =
    TwoWayConverter(
        { AnimationVector2D(it.width.toFloat(), it.height.toFloat()) },
        {
            IntSize(
                width = it.v1.fastRoundToInt().fastCoerceAtLeast(0),
                height = it.v2.fastRoundToInt().fastCoerceAtLeast(0),
            )
        },
    )

private val RectToVector: TwoWayConverter<Rect, AnimationVector4D> =
    TwoWayConverter(
        convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
        convertFromVector = { Rect(it.v1, it.v2, it.v3, it.v4) },
    )
