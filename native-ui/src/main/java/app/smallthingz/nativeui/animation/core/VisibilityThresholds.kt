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

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect
import app.smallthingz.nativeui.geometry.Size
import app.smallthingz.nativeui.unit.Dp
import app.smallthingz.nativeui.unit.IntOffset
import app.smallthingz.nativeui.unit.IntSize
import app.smallthingz.nativeui.unit.dp

private const val DpVisibilityThreshold = 0.4f

private const val PxVisibilityThreshold = 1.0f

private val RectVisibilityThreshold =
    Rect(PxVisibilityThreshold, PxVisibilityThreshold, PxVisibilityThreshold, PxVisibilityThreshold)

public val IntOffset.Companion.VisibilityThreshold: IntOffset
    get() = IntOffset(1, 1)

public val Offset.Companion.VisibilityThreshold: Offset
    get() = Offset(PxVisibilityThreshold, PxVisibilityThreshold)

public val Int.Companion.VisibilityThreshold: Int
    get() = 1

public val Dp.Companion.VisibilityThreshold: Dp
    get() = DpVisibilityThreshold.dp

public val Size.Companion.VisibilityThreshold: Size
    get() = Size(PxVisibilityThreshold, PxVisibilityThreshold)

public val IntSize.Companion.VisibilityThreshold: IntSize
    get() = IntSize(1, 1)

public val Rect.Companion.VisibilityThreshold: Rect
    get() = RectVisibilityThreshold

// TODO: Add Dp.DefaultAnimation = spring<Dp>(visibilityThreshold = Dp.VisibilityThreshold)
// The floats coming out of this map are fed to APIs that expect objects (generics), so it's
// better to store them as boxed floats here instead of causing unboxing/boxing every time
// the values are read out and forwarded to other APIs
@Suppress("PrimitiveInCollection")
internal val VisibilityThresholdMap: Map<TwoWayConverter<*, *>, Float> =
    mapOf(
        Int.VectorConverter to 1f,
        IntSize.VectorConverter to 1f,
        IntOffset.VectorConverter to 1f,
        Float.VectorConverter to 0.01f,
        Rect.VectorConverter to PxVisibilityThreshold,
        Size.VectorConverter to PxVisibilityThreshold,
        Offset.VectorConverter to PxVisibilityThreshold,
        Dp.VectorConverter to DpVisibilityThreshold,
    )
