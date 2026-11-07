package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeletionConsentStateTest {
    private data class Request(val token: String, val uri: String, val fingerprint: String)
    private fun queue() = RecordingDeletionConsentState<Request> { it.token }
    private fun launch(queue: RecordingDeletionConsentState<Request>, request: Request) {
        assertEquals(request, queue.claim())
        assertEquals(Unit, queue.createRequest(request.token) { Unit })
        assertTrue(queue.publishPrompt(request.token, request.token))
        assertEquals(request.token, queue.claimPrompt())
    }

    @Test fun restoredHostTokenModelKeepsExactInFlightProofWithoutSecondLaunch() {
        val queue = queue()
        val original = Request("old", "content://media/1", "original bytes")
        queue.offer(original)
        launch(queue, original)
        // A newly resumed host must bind the saved token; it cannot launch the request again.
        assertTrue(queue.contains("old"))
        assertNull(queue.claim())
        assertNull(queue.claimPrompt())
        assertEquals(original, queue.finish("old"))
        assertFalse(queue.contains("old"))
        assertNull(queue.finish("old"))
        assertNull(queue.claim())
        assertEquals(original, queue.complete("old"))
    }

    @Test fun lateResultCannotConsumeNewRequestEvenWhenUriIsReused() {
        val queue = queue()
        val old = Request("old", "content://media/1", "original bytes")
        val replacement = Request("new", old.uri, "replacement bytes")
        queue.offer(old)
        launch(queue, old)
        assertEquals(old, queue.finish("old"))
        queue.complete("old")
        queue.offer(replacement)
        launch(queue, replacement)
        assertNull(queue.finish("old"))
        assertTrue(queue.contains("new"))
        assertEquals(replacement, queue.finish("new"))
    }

    @Test fun terminalBatchReleasesOnlyItsOwnQueuedGroup() {
        val queue = queue()
        val first = Request("first", "content://media/1", "a")
        val second = Request("second", "content://media/2", "b")
        queue.offer(first)
        queue.offer(second)
        launch(queue, first)
        assertEquals(first, queue.finish("first"))
        assertNull(queue.finish("first"))
        assertNull(queue.claim())
        queue.complete("first")
        launch(queue, second)
        assertEquals(second, queue.finish("second"))
        queue.complete("second")
        assertNull(queue.claim())
    }

    @Test fun processDeathDropsRequestsAndRejectsPreviouslySavedResult() {
        val deadProcess = queue()
        val request = Request("dead", "content://media/1", "a")
        deadProcess.offer(request)
        launch(deadProcess, request)
        val newProcess = queue()
        assertFalse(newProcess.contains("dead"))
        assertNull(newProcess.finish("dead"))
        assertNull(newProcess.claim())
    }

    @Test fun queuedRequestCannotBeCompletedBeforeAHostClaimsIt() {
        val queue = queue()
        val request = Request("queued", "content://media/1", "a")
        queue.offer(request)
        assertNull(queue.finish("queued"))
        assertEquals(request, queue.claim())
        assertNull(queue.finish("queued"))
    }

    @Test fun duplicatePreviousChunkResultCannotConsumeNextChunk() {
        val queue = queue()
        val request = Request("batch", "many", "original")
        queue.offer(request)
        queue.claim()
        queue.createRequest(request.token) { Unit }
        queue.publishPrompt(request.token, "chunk-one")
        queue.claimPrompt()
        assertEquals(request, queue.finish("chunk-one"))
        assertTrue(queue.continueBatch(request.token))
        queue.createRequest(request.token) { Unit }
        queue.publishPrompt(request.token, "chunk-two")
        queue.claimPrompt()
        assertNull(queue.finish("chunk-one"))
        assertTrue(queue.contains("chunk-two"))
        assertEquals(request, queue.finish("chunk-two"))
    }
}
