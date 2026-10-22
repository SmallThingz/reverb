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

package app.smallthingz.nativeui.unit

import app.smallthingz.nativeui.geometry.Size
import app.smallthingz.nativeui.util.fastRoundToInt
import app.smallthingz.nativeui.util.packInts
import app.smallthingz.nativeui.util.unpackInt1
import app.smallthingz.nativeui.util.unpackInt2

/** Constructs an [IntSize] from width and height [Int] values. */
inline fun IntSize(width: Int, height: Int): IntSize = IntSize(packInts(width, height))

/**
 * A two-dimensional size class used for measuring in [Int] pixels.
 *
 * To create an [IntSize], call the top-level function that accepts a width/height pair of
 * dimensions:
 * ```
 * val size = IntSize(width, height)
 * ```
 *
 * The primary constructor of [IntSize] is intended to be used with the [packedValue] property to
 * allow storing sizes in arrays or collections of primitives without boxing.
 *
 * @param packedValue [Long] value encoding the [width] and [height] components of the [IntSize].
 *   Encoded values can be obtained by using the [packedValue] property of existing [IntSize]
 *   instances.
 */
@kotlin.jvm.JvmInline
value class IntSize @PublishedApi internal constructor(val packedValue: Long) {
    /** The horizontal aspect of the size in [Int] pixels. */
    inline val width: Int
        get() = unpackInt1(packedValue)

    /** The vertical aspect of the size in [Int] pixels. */
    inline val height: Int
        get() = unpackInt2(packedValue)

    inline operator fun component1(): Int = width

    inline operator fun component2(): Int = height

    /** Returns an IntSize scaled by multiplying [width] and [height] by [other] */
    operator fun times(other: Int): IntSize =
        IntSize(packInts(unpackInt1(packedValue) * other, unpackInt2(packedValue) * other))

    /** Returns an IntSize scaled by dividing [width] and [height] by [other] */
    operator fun div(other: Int): IntSize =
        IntSize(packInts(unpackInt1(packedValue) / other, unpackInt2(packedValue) / other))

    override fun toString(): String = "$width x $height"

    companion object {
        /** IntSize with a zero (0) width and height. */
        val Zero = IntSize(0L)
    }
}

/** Returns an [IntSize] with [size]'s [IntSize.width] and [IntSize.height] multiplied by [this]. */
inline operator fun Int.times(size: IntSize) = size * this

/** Convert a [IntSize] to a [IntRect]. */
fun IntSize.toIntRect(): IntRect {
    return IntRect(IntOffset.Zero, this)
}

/**
 * Returns the [IntOffset] of the center of the rect from the point of [0, 0] with this [IntSize].
 */
val IntSize.center: IntOffset
    get() =
        IntOffset(
            // Divide X by 2 by moving it to the low bits, then place it back in the high bits
            (packedValue shr 33 shl 32) or
                // Move Y to the high bits so we can preserve the sign when dividing by 2, then
                // move Y back to the low bits and mask out the top 32 bits for X
                ((packedValue shl 32 shr 33) and 0xffffffffL)
        )

// temporary while PxSize is transitioned to Size
fun IntSize.toSize() = Size(width.toFloat(), height.toFloat())

/**
 * Convert a [Size] to an [IntSize]. This rounds the width and height values down to the nearest
 * integer.
 */
fun Size.toIntSize(): IntSize = IntSize(packInts(this.width.toInt(), this.height.toInt()))

/**
 * Convert a [Size] to an [IntSize]. This rounds [Size.width] and [Size.height] to the nearest
 * integer.
 */
fun Size.roundToIntSize(): IntSize =
    IntSize(packInts(this.width.fastRoundToInt(), this.height.fastRoundToInt()))
