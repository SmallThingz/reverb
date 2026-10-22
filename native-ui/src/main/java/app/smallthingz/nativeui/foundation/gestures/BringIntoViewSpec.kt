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

package app.smallthingz.nativeui.foundation.gestures

import app.smallthingz.nativeui.animation.core.AnimationSpec
import app.smallthingz.nativeui.animation.core.spring
import kotlin.math.abs


interface BringIntoViewSpec {

    
    @Deprecated("Animation spec customization is no longer supported.")
    @get:Deprecated("Animation spec customization is no longer supported.")
    val scrollAnimationSpec: AnimationSpec<Float>
        get() = DefaultScrollAnimationSpec

    
    fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        defaultCalculateScrollDistance(offset, size, containerSize)

    companion object {

        
        internal val DefaultScrollAnimationSpec: AnimationSpec<Float> = spring()

        internal val DefaultBringIntoViewSpec = object : BringIntoViewSpec {}

        internal fun defaultCalculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float {
            val trailingEdge = offset + size
            @Suppress("UnnecessaryVariable") val leadingEdge = offset
            return when {

                // If the item is already visible, no need to scroll.
                leadingEdge >= 0 && trailingEdge <= containerSize -> 0f

                // If the item is visible but larger than the parent, we don't scroll.
                leadingEdge < 0 && trailingEdge > containerSize -> 0f

                // Find the minimum scroll needed to make one of the edges coincide with the
                // parent's
                // edge.
                abs(leadingEdge) < abs(trailingEdge - containerSize) -> leadingEdge
                else -> trailingEdge - containerSize
            }
        }
    }
}
