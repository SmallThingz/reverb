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

package app.smallthingz.nativeui.foundation

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MutatePriority {
    
    Default,

    
    UserInput,

    
    PreventUserInput,
}

internal class MutationInterruptedException :
    CancellationException("Mutation interrupted") {
    override fun fillInStackTrace(): Throwable = this
}

class MutatorMutex {
    private class Mutator(val priority: MutatePriority, val job: Job) {
        fun canInterrupt(other: Mutator) = priority >= other.priority

        fun cancel() = job.cancel(MutationInterruptedException())
    }

    private val currentMutator = AtomicReference<Mutator?>(null)
    private val mutex = Mutex()

    private fun tryMutateOrCancel(mutator: Mutator) {
        while (true) {
            val oldMutator = currentMutator.get()
            if (oldMutator == null || mutator.canInterrupt(oldMutator)) {
                if (currentMutator.compareAndSet(oldMutator, mutator)) {
                    oldMutator?.cancel()
                    break
                }
            } else throw CancellationException("Current mutation had a higher priority")
        }
    }

    
    suspend fun <R> mutate(
        priority: MutatePriority = MutatePriority.Default,
        block: suspend () -> R,
    ) = coroutineScope {
        val mutator = Mutator(priority, coroutineContext[Job]!!)

        tryMutateOrCancel(mutator)

        mutex.withLock {
            try {
                block()
            } finally {
                currentMutator.compareAndSet(mutator, null)
            }
        }
    }

    
    suspend fun <T, R> mutateWith(
        receiver: T,
        priority: MutatePriority = MutatePriority.Default,
        block: suspend T.() -> R,
    ) = coroutineScope {
        val mutator = Mutator(priority, coroutineContext[Job]!!)

        tryMutateOrCancel(mutator)

        mutex.withLock {
            try {
                receiver.block()
            } finally {
                currentMutator.compareAndSet(mutator, null)
            }
        }
    }

    
    inline fun tryMutate(block: () -> Unit): Boolean {
        val didLock = tryLock()
        if (didLock) {
            try {
                block()
            } finally {
                unlock()
            }
        }
        return didLock
    }

    @PublishedApi internal fun tryLock(): Boolean = mutex.tryLock()

    @PublishedApi
    internal fun unlock() {
        mutex.unlock()
    }
}
