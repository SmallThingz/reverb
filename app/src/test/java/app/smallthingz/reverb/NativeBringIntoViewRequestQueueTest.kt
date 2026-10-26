package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.NativeBringIntoViewRequestQueue
import app.smallthingz.reverb.nativeui.NativeContentInViewOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.geometry.Rect

class NativeBringIntoViewRequestQueueTest {
    @Test fun partiallyOverlappingParentCancelsItsNestedSuffix() = runBlocking {
        val queue = NativeBringIntoViewRequestQueue()
        val a = rect(0f, 100f)
        val b = rect(40f, 60f)
        val c = rect(30f, 120f)
        val first = enqueue(queue, { a })
        val second = enqueue(queue, { b })
        val replacement = enqueue(queue, { c })

        assertTrue(first.continuation.isCancelled)
        assertTrue(second.continuation.isCancelled)
        assertFalse(replacement.continuation.isCancelled)
        assertEquals(listOf(c), queue.bounds())
        queue.resumeAndRemoveAll()
        first.job.join(); second.job.join(); replacement.job.join()
        assertTrue(queue.isEmpty())
    }

    @Test fun conflictKeepsTheContainingAncestorAndCancelsFromSmallest() = runBlocking {
        val queue = NativeBringIntoViewRequestQueue()
        val outer = rect(0f, 200f)
        val a = rect(20f, 100f)
        val b = rect(40f, 60f)
        val c = rect(30f, 120f)
        val cancellationSnapshots = ArrayList<Pair<String, List<Rect?>>>()
        val ancestor = enqueue(queue, { outer })
        val first = enqueue(queue, { a }) {
            cancellationSnapshots.add("a" to queue.bounds())
        }
        val second = enqueue(queue, { b }) {
            cancellationSnapshots.add("b" to queue.bounds())
        }
        val replacement = enqueue(queue, { c })

        // Unconfined completion observes the queue after each synchronous removal handler.
        assertEquals(listOf("b", "a"), cancellationSnapshots.map { it.first })
        assertEquals(listOf(a, outer), cancellationSnapshots[0].second)
        assertEquals(listOf(outer), cancellationSnapshots[1].second)
        assertEquals(listOf(c, outer), queue.bounds())
        assertFalse(ancestor.continuation.isCancelled)
        assertTrue(first.continuation.isCancelled)
        assertTrue(second.continuation.isCancelled)
        queue.resumeAndRemoveAll()
        ancestor.job.join(); first.job.join(); second.job.join(); replacement.job.join()
    }

    @Test fun fullyNestedRequestsKeepTheirOrderAndCompleteNormally() = runBlocking {
        val queue = NativeBringIntoViewRequestQueue()
        val middle = rect(20f, 100f)
        val small = rect(40f, 60f)
        val outer = rect(0f, 200f)
        val first = enqueue(queue, { middle })
        val second = enqueue(queue, { small })
        val third = enqueue(queue, { outer })
        assertEquals(listOf(small, middle, outer), queue.bounds())
        queue.resumeAndRemoveAll()
        for (probe in listOf(first, second, third)) {
            probe.job.join()
            assertFalse(probe.continuation.isCancelled)
            assertTrue(probe.continuation.isCompleted)
        }
    }

    @Test fun externallyCancelledRequestRemovesOnlyItself() = runBlocking {
        val queue = NativeBringIntoViewRequestQueue()
        val outer = rect(0f, 200f)
        val first = enqueue(queue, { outer })
        val second = enqueue(queue, { rect(40f, 60f) })
        second.job.cancel()
        second.job.join()
        assertEquals(listOf(outer), queue.bounds())
        assertFalse(first.continuation.isCancelled)
        queue.cancelAndRemoveAll(null)
        first.job.join()
        assertTrue(first.continuation.isCancelled)
        assertTrue(queue.isEmpty())
    }

    @Test fun disappearedNestedBoundsDoNotLeaveAnUncancelledSuffix() = runBlocking {
        val queue = NativeBringIntoViewRequestQueue()
        val first = enqueue(queue, { rect(0f, 100f) })
        var nestedBounds: Rect? = rect(40f, 60f)
        val second = enqueue(queue, { nestedBounds })
        nestedBounds = null
        val replacementBounds = rect(30f, 120f)
        val replacement = enqueue(queue, { replacementBounds })
        assertTrue(first.continuation.isCancelled)
        assertTrue(second.continuation.isCancelled)
        assertEquals(listOf(replacementBounds), queue.bounds())
        queue.resumeAndRemoveAll()
        first.job.join(); second.job.join(); replacement.job.join()
    }

    @Test fun initiallyAbsentBoundsCompleteWithoutEnteringTheQueue() = runBlocking {
        val queue = NativeBringIntoViewRequestQueue()
        val probe = enqueue(queue, { null })
        probe.job.join()
        assertFalse(probe.accepted)
        assertTrue(probe.continuation.isCompleted)
        assertFalse(probe.continuation.isCancelled)
        assertTrue(queue.isEmpty())
    }

    private data class Probe(
        val continuation: CancellableContinuation<Unit>,
        val job: Job,
        val accepted: Boolean,
    )

    private fun CoroutineScope.enqueue(
        queue: NativeBringIntoViewRequestQueue,
        bounds: () -> Rect?,
        onCompletion: () -> Unit = {},
    ): Probe {
        lateinit var continuation: CancellableContinuation<Unit>
        var accepted = false
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                suspendCancellableCoroutine<Unit> {
                    continuation = it
                    accepted = queue.enqueue(NativeContentInViewOwner.Request(bounds, it))
                }
            } finally {
                onCompletion()
            }
        }
        return Probe(continuation, job, accepted)
    }

    private fun NativeBringIntoViewRequestQueue.bounds(): List<Rect?> =
        ArrayList<Rect?>().also { result -> forEachFromSmallest { result.add(it) } }

    private fun rect(start: Float, end: Float) = Rect(start, start, end, end)
}
