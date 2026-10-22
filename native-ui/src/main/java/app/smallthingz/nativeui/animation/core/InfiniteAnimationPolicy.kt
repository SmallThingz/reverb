/* Copyright 2021 The Android Open Source Project. Licensed under Apache-2.0. */
package app.smallthingz.nativeui.animation.core

import app.smallthingz.nativeui.runtime.withFrameNanos
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

interface InfiniteAnimationPolicy : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key
    suspend fun <R> onInfiniteOperation(block: suspend () -> R): R
    companion object Key : CoroutineContext.Key<InfiniteAnimationPolicy>
}

suspend fun <R> withInfiniteAnimationFrameNanos(onFrame: (Long) -> R): R =
    when (val policy = coroutineContext[InfiniteAnimationPolicy]) {
        null -> withFrameNanos(onFrame)
        else -> policy.onInfiniteOperation { withFrameNanos(onFrame) }
    }

suspend inline fun <R> withInfiniteAnimationFrameMillis(crossinline onFrame: (Long) -> R): R =
    withInfiniteAnimationFrameNanos { onFrame(it / 1_000_000L) }
