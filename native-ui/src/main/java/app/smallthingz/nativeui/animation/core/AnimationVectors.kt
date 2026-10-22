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

public sealed class AnimationVector {
    internal abstract fun reset()

    internal abstract fun newVector(): AnimationVector

    internal abstract operator fun get(index: Int): Float

    internal abstract operator fun set(index: Int, value: Float)

    internal abstract val size: Int
}

public fun AnimationVector(v1: Float): AnimationVector1D = AnimationVector1D(v1)

public fun AnimationVector(v1: Float, v2: Float): AnimationVector2D = AnimationVector2D(v1, v2)

public fun AnimationVector(v1: Float, v2: Float, v3: Float): AnimationVector3D =
    AnimationVector3D(v1, v2, v3)

public fun AnimationVector(v1: Float, v2: Float, v3: Float, v4: Float): AnimationVector4D =
    AnimationVector4D(v1, v2, v3, v4)

internal fun <T : AnimationVector> T.newInstance(): T {
    @Suppress("UNCHECKED_CAST")
    return this.newVector() as T
}

internal fun <T : AnimationVector> T.copy(): T {
    val newVector = newInstance()
    for (i in 0 until newVector.size) {
        newVector[i] = this[i]
    }
    return newVector
}

internal fun <T : AnimationVector> T.copyFrom(source: T) {
    for (i in 0 until size) {
        this[i] = source[i]
    }
}

public class AnimationVector1D(initVal: Float) : AnimationVector() {
    
    public var value: Float = initVal
        internal set

    // internal
    override fun reset() {
        value = 0f
    }

    override fun newVector(): AnimationVector1D = AnimationVector1D(0f)

    override fun get(index: Int): Float {
        if (index == 0) {
            return value
        } else {
            return 0f
        }
    }

    override fun set(index: Int, value: Float) {
        if (index == 0) {
            this.value = value
        }
    }

    override val size: Int = 1

    override fun toString(): String {
        return "AnimationVector1D: value = $value"
    }

    override fun equals(other: Any?): Boolean = other is AnimationVector1D && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

public class AnimationVector2D(v1: Float, v2: Float) : AnimationVector() {
    
    public var v1: Float = v1
        internal set

    
    public var v2: Float = v2
        internal set

    // internal
    override fun reset() {
        v1 = 0f
        v2 = 0f
    }

    override fun newVector(): AnimationVector2D = AnimationVector2D(0f, 0f)

    override fun get(index: Int): Float {
        return when (index) {
            0 -> v1
            1 -> v2
            else -> 0f
        }
    }

    override fun set(index: Int, value: Float) {
        when (index) {
            0 -> v1 = value
            1 -> v2 = value
        }
    }

    override val size: Int = 2

    override fun toString(): String {
        return "AnimationVector2D: v1 = $v1, v2 = $v2"
    }

    override fun equals(other: Any?): Boolean =
        other is AnimationVector2D && other.v1 == v1 && other.v2 == v2

    override fun hashCode(): Int = v1.hashCode() * 31 + v2.hashCode()
}

public class AnimationVector3D(v1: Float, v2: Float, v3: Float) : AnimationVector() {
    // Internally mutable, so we don't have to create a number of small objects per anim frame
    
    public var v1: Float = v1
        internal set

    
    public var v2: Float = v2
        internal set

    
    public var v3: Float = v3
        internal set

    // internal
    override fun reset() {
        v1 = 0f
        v2 = 0f
        v3 = 0f
    }

    override fun newVector(): AnimationVector3D = AnimationVector3D(0f, 0f, 0f)

    override fun get(index: Int): Float {
        return when (index) {
            0 -> v1
            1 -> v2
            2 -> v3
            else -> 0f
        }
    }

    override fun set(index: Int, value: Float) {
        when (index) {
            0 -> v1 = value
            1 -> v2 = value
            2 -> v3 = value
        }
    }

    override val size: Int = 3

    override fun toString(): String {
        return "AnimationVector3D: v1 = $v1, v2 = $v2, v3 = $v3"
    }

    override fun equals(other: Any?): Boolean =
        other is AnimationVector3D && other.v1 == v1 && other.v2 == v2 && other.v3 == v3

    override fun hashCode(): Int = (v1.hashCode() * 31 + v2.hashCode()) * 31 + v3.hashCode()
}

public class AnimationVector4D(v1: Float, v2: Float, v3: Float, v4: Float) : AnimationVector() {
    // Internally mutable, so we don't have to create a number of small objects per anim frame
    
    public var v1: Float = v1
        internal set

    
    public var v2: Float = v2
        internal set

    
    public var v3: Float = v3
        internal set

    
    public var v4: Float = v4
        internal set

    override fun reset() {
        v1 = 0f
        v2 = 0f
        v3 = 0f
        v4 = 0f
    }

    override fun newVector(): AnimationVector4D = AnimationVector4D(0f, 0f, 0f, 0f)

    override fun get(index: Int): Float {
        return when (index) {
            0 -> v1
            1 -> v2
            2 -> v3
            3 -> v4
            else -> 0f
        }
    }

    override fun set(index: Int, value: Float) {
        when (index) {
            0 -> v1 = value
            1 -> v2 = value
            2 -> v3 = value
            3 -> v4 = value
        }
    }

    override val size: Int = 4

    override fun toString(): String {
        return "AnimationVector4D: v1 = $v1, v2 = $v2, v3 = $v3, v4 = $v4"
    }

    override fun equals(other: Any?): Boolean =
        other is AnimationVector4D &&
            other.v1 == v1 &&
            other.v2 == v2 &&
            other.v3 == v3 &&
            other.v4 == v4

    override fun hashCode(): Int =
        ((v1.hashCode() * 31 + v2.hashCode()) * 31 + v3.hashCode()) * 31 + v4.hashCode()
}
