package app.smallthingz.nativeui.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MutatorMutexCancellationTest {
    private enum class Entry { Mutate, MutateWith }

    private suspend fun <T> MutatorMutex.enter(
        entry: Entry,
        priority: MutatePriority,
        block: suspend () -> T,
    ): T = when (entry) {
        Entry.Mutate -> mutate(priority, block)
        Entry.MutateWith -> mutateWith(block, priority) { invoke() }
    }

    @Test fun cancelledWaiterDoesNotKeepItsPriorityAfterHolderExits() = runBlocking {
        withTimeout(5_000) {
            for (entry in Entry.entries) coroutineScope {
                val mutex = MutatorMutex()
                val cleanupStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                    mutex.mutate {
                        try { awaitCancellation() }
                        finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                releaseHolder.await()
                            }
                        }
                    }
                }
                var waiter: Job? = null
                try {
                    waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                        mutex.enter(entry, MutatePriority.PreventUserInput) {
                            fail("Cancelled waiter must never acquire the held lock")
                        }
                    }
                    cleanupStarted.await()
                    waiter.cancelAndJoin()
                    releaseHolder.complete(Unit)
                    holder.join()

                    var entered = false
                    mutex.mutate(MutatePriority.Default) { entered = true }
                    assertTrue("A dead $entry waiter must not reject later work", entered)
                } finally {
                    releaseHolder.complete(Unit)
                    waiter?.cancel()
                    holder.cancel()
                    withContext(NonCancellable) { waiter?.join(); holder.join() }
                }
            }
        }
    }

    @Test fun cancelledOldWaiterCannotClearTheNewerOwner() = runBlocking {
        withTimeout(5_000) {
            for (entry in Entry.entries) coroutineScope {
                val mutex = MutatorMutex()
                val releaseHolder = CompletableDeferred<Unit>()
                val newestEntered = CompletableDeferred<Unit>()
                val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                    mutex.mutate {
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) { releaseHolder.await() } }
                    }
                }
                var oldWaiter: Job? = null
                var newest: Job? = null
                var lower: Job? = null
                try {
                    oldWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
                        mutex.enter(entry, MutatePriority.PreventUserInput) {
                            fail("Superseded waiter must not enter")
                        }
                    }
                    newest = launch(start = CoroutineStart.UNDISPATCHED) {
                        mutex.enter(entry, MutatePriority.PreventUserInput) {
                            newestEntered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                    oldWaiter.join()
                    assertTrue(oldWaiter.isCancelled)
                    releaseHolder.complete(Unit)
                    holder.join()
                    newestEntered.await()

                    lower = async(start = CoroutineStart.UNDISPATCHED) {
                        mutex.mutate(MutatePriority.Default) {
                            fail("A live higher-priority owner must still reject lower work")
                        }
                    }
                    assertTrue("Old cleanup must preserve the newer owner", lower.isCancelled)
                    assertFalse(newest.isCancelled)
                    newest.cancelAndJoin()
                    assertEquals(17, mutex.mutate { 17 })
                } finally {
                    releaseHolder.complete(Unit)
                    lower?.cancel()
                    newest?.cancel()
                    oldWaiter?.cancel()
                    holder.cancel()
                    withContext(NonCancellable) {
                        lower?.join(); newest?.join(); oldWaiter?.join(); holder.join()
                    }
                }
            }
        }
    }

    @Test fun failedBlockRetiresItsOwnerAndSuccessfulResultIsPreserved() = runBlocking {
        for (entry in Entry.entries) {
            val mutex = MutatorMutex()
            val expected = IllegalStateException("fixture failure")
            try {
                mutex.enter(entry, MutatePriority.PreventUserInput) { throw expected }
                fail("Expected the block's exception")
            } catch (actual: IllegalStateException) {
                assertTrue(actual === expected)
            }
            assertEquals(23, mutex.enter(entry, MutatePriority.Default) { 23 })
        }
    }

    @Test fun rejectedLowerPriorityDoesNotRetireTheActiveOwner() = runBlocking {
        withTimeout(5_000) {
            for (entry in Entry.entries) coroutineScope {
                val mutex = MutatorMutex()
                val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                    mutex.mutate(MutatePriority.PreventUserInput) { awaitCancellation() }
                }
                try {
                    repeat(2) {
                        var rejected = false
                        try {
                            mutex.enter(entry, MutatePriority.Default) { fail("Unexpected entry") }
                        } catch (_: CancellationException) {
                            rejected = true
                        }
                        assertTrue(rejected)
                        assertFalse(holder.isCancelled)
                    }
                    holder.cancelAndJoin()
                    assertEquals(31, mutex.enter(entry, MutatePriority.Default) { 31 })
                } finally {
                    holder.cancel()
                    withContext(NonCancellable) { holder.join() }
                }
            }
        }
    }
}
