package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Models Android30's same-identity CANCEL_CURRENT factory; this is not an Android runtime test. */
class RecordingDeletionBatchConsentTest {
    private data class Target(val uri: String, val proof: String, val owned: Boolean = false)
    private data class Batch(val token: String, val targets: List<Target>)
    private class CancelCurrentFactory {
        var generation = 0
        val calls = mutableListOf<List<String>>()
        inner class Sender(val uris: List<String>, private val version: Int) {
            private var sent = false
            fun launch(): List<String> {
                check(version == generation) { "SendIntentException: canceled by later request" }
                check(!sent) { "SendIntentException: one-shot sender already used" }
                sent = true
                return uris
            }
        }
        fun create(targets: List<Target>): Sender {
            val uris = targets.map { it.uri }
            calls += uris
            return Sender(uris, ++generation)
        }
    }
    private fun queue() = RecordingDeletionConsentState<Batch> { it.token }
    private fun targets(count: Int) = List(count) { Target("content://media/$it", "original-$it") }

    @Test fun adapterReproducesEarlierEagerPerRowCancellation() {
        val factory = CancelCurrentFactory()
        val original = targets(2)
        val first = factory.create(listOf(original[0]))
        val second = factory.create(listOf(original[1]))
        assertTrue(runCatching { first.launch() }.isFailure)
        assertEquals(listOf(original[1].uri), second.launch())
    }

    @Test fun productionChunkAndLazyFactoryGiveTwoAndThreeTargetsOneUsableSender() = runBlocking {
        for (count in listOf(2, 3)) {
            val factory = CancelCurrentFactory()
            val state = queue()
            val batch = Batch("batch-$count", targets(count))
            state.offer(batch)
            assertEquals(0, factory.calls.size)
            assertSame(batch, state.claim())
            val chunk = prepareRecordingConsentChunk(batch.targets, 0, RECORDING_CONSENT_URI_LIMIT) { _, target -> target }
            val sender = requireNotNull(state.createRequest(batch.token) { factory.create(chunk.targets.map { it.value }) })
            assertTrue(state.publishPrompt(batch.token, "prompt-$count"))
            assertEquals("prompt-$count", state.claimPrompt())
            assertEquals(batch.targets.map { it.uri }, sender.launch())
            assertEquals(1, factory.calls.size)
            chunk.targets.forEach { assertSame(batch.targets[it.index], it.value) }
        }
    }

    @Test fun futureBatchCannotReplaceSenderOrAcquireOwnerUntilAsynchronousApplyCompletes() = runBlocking {
        val factory = CancelCurrentFactory()
        val state = queue()
        val first = Batch("first", targets(2))
        val second = Batch("second", listOf(Target("content://media/later", "later-proof")))
        state.offer(first)
        state.claim()
        val sender = requireNotNull(state.createRequest(first.token) { factory.create(first.targets) })
        state.publishPrompt(first.token, "first-prompt")
        state.offer(second)
        assertNull(state.createRequest(second.token) { factory.create(second.targets) })
        assertEquals("first-prompt", state.claimPrompt())
        assertEquals(first.targets.map { it.uri }, sender.launch())
        assertSame(first, state.finish("first-prompt"))
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val apply = async {
            applyEntered.complete(Unit)
            releaseApply.await()
            state.complete(first.token)
        }
        applyEntered.await()
        assertNull(state.claim())
        assertNull(state.createRequest(second.token) { factory.create(second.targets) })
        assertNull(state.finish("first-prompt"))
        assertEquals(1, factory.calls.size)
        releaseApply.complete(Unit)
        assertSame(first, apply.await())
        assertSame(second, state.claim())
        assertTrue(state.createRequest(second.token) { factory.create(second.targets) } != null)
        assertEquals(2, factory.calls.size)
    }

    @Test fun mixedOwnershipAndRejectedIdentityPromptOnlyOriginalEligibleSubset() = runBlocking {
        val all = listOf(Target("owned", "a", true), Target("foreign-one", "b"),
            Target("changed", "c"), Target("foreign-two", "d"))
        val physicallyDeleted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val chunk = prepareRecordingConsentChunk(all, 0, RECORDING_CONSENT_URI_LIMIT) { _, target ->
            when {
                target.owned -> { physicallyDeleted += target.uri; null }
                target.uri == "changed" -> { failures += target.uri; null }
                else -> target
            }
        }
        assertEquals(listOf("owned"), physicallyDeleted)
        assertEquals(listOf("changed"), failures)
        assertEquals(listOf(1, 3), chunk.targets.map { it.index })
        assertEquals(listOf("foreign-one", "foreign-two"), chunk.targets.map { it.value.uri })
        assertSame(all[1], chunk.targets[0].value)
        assertSame(all[3], chunk.targets[1].value)
        assertEquals(all.size, chunk.nextIndex)
    }

    @Test fun changedBytesAfterBatchApprovalCannotReceiveReplacementProof() {
        val original = PendingDeletionIntent("content://media/one", 4, "01".repeat(32), false)
        val sibling = original.copy(id = "content://media/two")
        val approved = listOf(original, sibling)
        val deleted = approved.filter { proof ->
            revalidateConsentedDeletionIntent(proof.id, proof) {
                pendingDeletionMatchesDigest(proof, 4, if (proof === original) "02".repeat(32) else "01".repeat(32))
            } != null
        }
        assertEquals(listOf(sibling), deleted)
        assertSame(sibling, deleted.single())
        assertEquals("01".repeat(32), original.sha256Hex)
    }

    @Test fun platformLimitSplitsOnlyAtTwoThousandConsentItemsWithFreshPromptToken() = runBlocking {
        val state = queue()
        val factory = CancelCurrentFactory()
        val batch = Batch("large", targets(2001))
        state.offer(batch)
        state.claim()
        val first = prepareRecordingConsentChunk(batch.targets, 0, RECORDING_CONSENT_URI_LIMIT) { _, target -> target }
        state.createRequest(batch.token) { factory.create(first.targets.map { it.value }) }
        state.publishPrompt(batch.token, "chunk-1")
        state.claimPrompt()
        assertEquals(2000, factory.calls.single().size)
        assertNull(state.createRequest(batch.token) { factory.create(batch.targets.drop(2000)) })
        state.finish("chunk-1")
        state.continueBatch(batch.token)
        val next = prepareRecordingConsentChunk(batch.targets, first.nextIndex, RECORDING_CONSENT_URI_LIMIT) { _, target -> target }
        state.createRequest(batch.token) { factory.create(next.targets.map { it.value }) }
        state.publishPrompt(batch.token, "chunk-2")
        state.claimPrompt()
        assertNull(state.finish("chunk-1"))
        assertSame(batch, state.finish("chunk-2"))
        assertEquals(listOf(2000, 1), factory.calls.map { it.size })
    }

    @Test fun legacySingleItemLimitDoesNotPrepareLaterRecoverableActions() = runBlocking {
        val all = targets(3)
        val visited = mutableListOf<String>()
        val first = prepareRecordingConsentChunk(all, 0, 1) { _, target -> visited += target.uri; target }
        assertEquals(listOf(all[0].uri), visited)
        assertEquals(listOf(0), first.targets.map { it.index })
        assertEquals(1, first.nextIndex)
    }

    @Test fun deniedLogicalBatchCannotGenerateLaterChunkAndDuplicateResultIsNoop() = runBlocking {
        val state = queue()
        val factory = CancelCurrentFactory()
        val batch = Batch("denied", targets(2001))
        state.offer(batch)
        state.claim()
        val first = prepareRecordingConsentChunk(batch.targets, 0, RECORDING_CONSENT_URI_LIMIT) { _, target -> target }
        state.createRequest(batch.token) { factory.create(first.targets.map { it.value }) }
        state.publishPrompt(batch.token, "denied-prompt")
        state.claimPrompt()
        assertSame(batch, state.finish("denied-prompt"))
        assertSame(batch, state.complete(batch.token))
        assertNull(state.finish("denied-prompt"))
        assertNull(state.createRequest(batch.token) { factory.create(batch.targets.drop(first.nextIndex)) })
        assertNull(state.claim())
        assertEquals(1, factory.calls.size)
        assertEquals(2001, batch.targets.size)
    }

    @Test fun failedFactoryKeepsBatchOwnerUntilFailureSettlement() {
        val state = queue()
        val first = Batch("first", targets(2))
        val second = Batch("second", targets(1))
        state.offer(first); state.offer(second); state.claim()
        val error = IOException("factory unavailable")
        val thrown = runCatching { state.createRequest<Unit>(first.token) { throw error } }.exceptionOrNull()
        assertSame(error, thrown)
        assertNull(state.claim())
        assertNull(state.createRequest<Unit>(second.token) { throw AssertionError("must not create future sender") })
        assertTrue(state.continueBatch(first.token))
        assertSame(first, state.complete(first.token))
        assertSame(second, state.claim())
    }
}
