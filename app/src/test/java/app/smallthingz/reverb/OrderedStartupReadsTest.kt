package app.smallthingz.reverb

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class OrderedStartupReadsTest {
    @Test fun parallelReadsKeepMutationOrderedOnOwnerAndDrainEachBatch() {
        val owner = Thread.currentThread()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val readThreads = Collections.synchronizedSet(mutableSetOf<Thread>())
        val observed = ArrayList<Int>()
        forEachOrderedStartupRead(Array(1027) { it }, 4, batchSize = 64, read = { value ->
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            try {
                readThreads += Thread.currentThread()
                value * 7
            } finally { active.decrementAndGet() }
        }) {
            assertSame(owner, Thread.currentThread())
            assertEquals(0, active.get())
            observed += it
        }
        assertEquals(List(1027) { it * 7 }, observed)
        assertTrue(readThreads.size > 1 && readThreads.size <= 4)
        assertTrue(peak.get() in 1..4)
    }

    @Test fun failedBatchNeverMutatesAndEveryReaderFinishesBeforeFailure() {
        val original = IOException("read failure")
        val finished = AtomicInteger()
        val begun = CountDownLatch(4)
        var consumed = 0
        val error = assertThrows(IOException::class.java) {
            forEachOrderedStartupRead(Array(8) { it }, 4, batchSize = 4, read = { value ->
                begun.countDown()
                check(begun.await(10, TimeUnit.SECONDS))
                try {
                    if (value == 1) throw original
                    value
                } finally { finished.incrementAndGet() }
            }) { consumed++ }
        }
        assertSame(original, error)
        assertEquals(4, finished.get())
        assertEquals(0, consumed)
    }

    @Test fun interruptedOwnerWaitsForReadersAndPreservesInterrupt() {
        val blockedReader = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val interrupted = AtomicReference<Boolean>()
        val reads = AtomicInteger()
        val owner = Thread {
            try {
                forEachOrderedStartupRead(Array(4) { it }, 2, batchSize = 4, read = { value ->
                    if (value == 1) {
                        blockedReader.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    reads.incrementAndGet()
                    value
                }) { fail("Interrupted read batch must not mutate") }
            } catch (failure: Throwable) { error.set(failure) }
            finally {
                interrupted.set(Thread.currentThread().isInterrupted)
                completed.countDown()
            }
        }
        owner.start()
        try {
            assertTrue(blockedReader.await(10, TimeUnit.SECONDS))
            owner.interrupt()
            assertFalse(completed.await(30, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        owner.join()
        assertTrue(error.get() is InterruptedException)
        assertEquals(true, interrupted.get())
        assertEquals(4, reads.get())
    }

    @Test fun consumerFailureDoesNotStartTheNextBatch() {
        val reads = AtomicInteger()
        val original = IOException("recovery failure")
        val error = assertThrows(IOException::class.java) {
            forEachOrderedStartupRead(Array(32) { it }, 4, batchSize = 8,
                read = { reads.incrementAndGet(); it }) { throw original }
        }
        assertSame(original, error)
        assertEquals(8, reads.get())
    }

    @Test fun serialAndNullableReadsPreserveExactResults() {
        val owner = Thread.currentThread()
        val observed = ArrayList<Int?>()
        forEachOrderedStartupRead(Array(9) { it }, 1, read = {
            assertSame(owner, Thread.currentThread())
            if (it % 2 == 0) null else it
        }, consume = observed::add)
        assertEquals(List(9) { if (it % 2 == 0) null else it }, observed)
    }
}
