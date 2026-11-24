package app.smallthingz.reverb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

/** Real production pipeline/channel/accumulator; only owner dispatch and source reads are gated. */
class ProgressiveWaveformPipelineTest {
    @Test fun twoPassSuccessConflatesWhileConsumerIsPausedAndKeepsFinalSamples() {
        val dispatcher = OwnerDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val passes = RangeWaveformPass.entries
        val entered = passes.map { CountDownLatch(1) }
        val release = passes.map { CountDownLatch(1) }
        val finished = passes.map { CountDownLatch(1) }
        val reads = passes.map { AtomicInteger() }
        val closes = passes.map { AtomicInteger() }
        val started = mutableListOf<RangeWaveformPass>()
        val delivered = mutableListOf<Pair<RangeWaveformPass, ProgressiveWaveformSnapshot>>()
        val job = scope.launch {
            buildProgressiveWaveform(
                readPass = { pass, emit ->
                    val index = pass.ordinal
                    reads[index].incrementAndGet()
                    entered[index].countDown()
                    try {
                        await(release[index])
                        repeat(pass.bucketCount) { bucket ->
                            check(emit(bucket, magnitude(pass, bucket)))
                        }
                    } finally {
                        closes[index].incrementAndGet()
                        finished[index].countDown()
                    }
                },
                onPassStarted = started::add,
                onSnapshot = { pass, snapshot -> delivered += pass to snapshot },
            )
        }
        try {
            for (pass in passes) {
                dispatcher.runUntil { started.contains(pass) }
                await(entered[pass.ordinal])
                val before = delivered.size
                // No owner dispatch while the actual IO producer fills the channel.
                release[pass.ordinal].countDown()
                await(finished[pass.ordinal])
                assertEquals(before, delivered.size)
                dispatcher.runUntil { delivered.any { it.first == pass && it.second.builtCount == pass.bucketCount } }
                val updates = delivered.filter { it.first == pass }.map { it.second }
                // One receive was already waiting, and one latest snapshot can be retained.
                assertEquals("Conflated delivery must not replay the producer's full backlog", 2, updates.size)
                assertTrue(updates.first().builtCount in 1 until pass.bucketCount)
                assertEquals(pass.bucketCount, updates.last().builtCount)
                assertEquals(pass.bucketCount, updates.last().values.size)
                assertArrayEquals(FloatArray(pass.bucketCount) { magnitude(pass, it) }, updates.last().values, 0f)
                assertEquals("Published early snapshot must not alias later accumulator writes", 0f,
                    updates.first().values.last(), 0f)
                assertNotSame(updates.first().values, updates.last().values)
            }
            dispatcher.runUntil { job.isCompleted }
            assertFalse(job.isCancelled)
            assertEquals(passes.toList(), started)
            assertEquals(listOf(1, 1), reads.map { it.get() })
            assertEquals(listOf(1, 1), closes.map { it.get() })
            assertEquals(listOf(RangeWaveformPass.COARSE, RangeWaveformPass.COARSE,
                RangeWaveformPass.DETAIL, RangeWaveformPass.DETAIL), delivered.map { it.first })
        } finally {
            scope.cancel()
            release.forEach { it.countDown() }
            dispatcher.runUntil { job.isCompleted }
        }
    }

    @Test fun cancellationDiscardsQueuedSnapshotsClosesReaderAndNeverStartsDetail() {
        val dispatcher = OwnerDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CountDownLatch(1)
        val begin = CountDownLatch(1)
        val halfway = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val rejected = AtomicInteger()
        val started = mutableListOf<RangeWaveformPass>()
        var publications = 0
        val job = scope.launch {
            buildProgressiveWaveform(
                readPass = { pass, emit ->
                    try {
                        entered.countDown()
                        await(begin)
                        repeat(pass.bucketCount / 2) { check(emit(it, .4f)) }
                        halfway.countDown()
                        await(release)
                        for (index in pass.bucketCount / 2 until pass.bucketCount) {
                            if (!emit(index, .6f)) {
                                rejected.incrementAndGet()
                                break
                            }
                        }
                    } finally { closed.incrementAndGet() }
                },
                onPassStarted = started::add,
                onSnapshot = { _, _ -> publications++ },
            )
        }
        try {
            dispatcher.runUntil { started.isNotEmpty() }
            await(entered)
            begin.countDown()
            await(halfway)
            assertEquals(0, publications)
            job.cancel()
            // Deliver cancellation while the reader is still held, closing the real channel.
            dispatcher.drainQueued()
            assertTrue(job.isCancelled)
            assertFalse("Parent must still own the in-flight reader", job.isCompleted)
            release.countDown()
            dispatcher.runUntil { job.isCompleted }
            dispatcher.drainQueued()
            assertEquals(listOf(RangeWaveformPass.COARSE), started)
            assertEquals(1, rejected.get())
            assertEquals(1, closed.get())
            assertEquals(0, publications)
        } finally {
            scope.cancel()
            begin.countDown()
            release.countDown()
            dispatcher.runUntil { job.isCompleted }
        }
    }

    @Test fun cancellationDuringInterPassDelayNeverStartsDetail() {
        val dispatcher = OwnerDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = mutableListOf<RangeWaveformPass>()
        val reads = AtomicInteger()
        var coarseComplete = false
        val job = scope.launch {
            buildProgressiveWaveform(
                readPass = { pass, emit ->
                    reads.incrementAndGet()
                    repeat(pass.bucketCount) { check(emit(it, .5f)) }
                },
                onPassStarted = started::add,
                onSnapshot = { pass, update ->
                    if (pass == RangeWaveformPass.COARSE && update.builtCount == pass.bucketCount) {
                        coarseComplete = true
                    }
                },
            )
        }
        try {
            dispatcher.runUntil { coarseComplete }
            // The owner has yielded into the real production inter-pass delay. Even if its
            // timer enqueues while this test is descheduled, cancellation wins before dispatch.
            job.cancel()
            dispatcher.runUntil { job.isCompleted }
            assertTrue(job.isCancelled)
            assertEquals(listOf(RangeWaveformPass.COARSE), started)
            assertEquals(1, reads.get())
        } finally {
            scope.cancel()
            dispatcher.runUntil { job.isCompleted }
        }
    }

    private fun magnitude(pass: RangeWaveformPass, index: Int): Float =
        if (pass == RangeWaveformPass.COARSE) .2f + (index % 3) * .1f else .6f + (index % 3) * .1f

    private fun await(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Controlled waveform reader did not reach its boundary" }
    }

    private class OwnerDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drainQueued() {
            while (true) (tasks.poll() ?: return).run()
        }
        fun runUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!done()) {
                check(System.nanoTime() < deadline) { "Waveform owner did not reach the expected boundary" }
                tasks.poll(5, TimeUnit.MILLISECONDS)?.run()
            }
        }
    }
}
