package app.smallthingz.reverb

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RecordingRenameConsentTest {
    private fun routed(failure: Throwable): Pair<String, Throwable> {
        var delivered: Pair<String, Throwable>? = null
        routeRecordingRenameFailure(failure,
            onDefinite = { check(delivered == null); delivered = "inline" to it },
            onReconcile = { check(delivered == null); delivered = "reconcile" to it })
        return checkNotNull(delivered)
    }

    @Test fun postMoveUnknownAndStaleTargetFailuresConservativelyReconcile() {
        for (failure in listOf(
            IllegalStateException("Recording changed on disk during rename"),
            IllegalStateException("Recording content changed during rename"),
            IOException("Recording changed before approved rename"),
            IOException("Recording disappeared while requesting rename permission"),
            SecurityException("FILE access failed after move"),
            IOException("Document provider read failed"),
            RecordingRenameStateUncertainException("provider outcome unknown"),
        )) {
            val result = routed(failure)
            assertEquals("reconcile", result.first)
            assertSame(failure, result.second)
        }
    }

    @Test fun catalogFailureStillReconcilesAfterSuccessfulRollback() {
        val primary = IOException("catalog commit failed")
        assertEquals("original URI", attemptRenameRecoveryPreservingPrimaryFailure(
            primaryFailure = primary, fallback = null) { "original URI" })
        val result = routed(primary)
        assertEquals("reconcile", result.first)
        assertSame(primary, result.second)
    }

    @Test fun failedRollbackAndCleanupPreservePrimaryAndReconciliation() {
        val primary = IOException("catalog commit failed")
        val rollback = SecurityException("rollback denied")
        val cleanup = IOException("stale catalog cleanup failed")
        attemptRenameRecoveryPreservingPrimaryFailure(primary, Unit) { throw rollback }
        attemptRenameRecoveryPreservingPrimaryFailure(primary, Unit) { throw cleanup }
        val result = routed(primary)
        assertEquals("reconcile", result.first)
        assertSame(primary, result.second)
        assertEquals(listOf(rollback, cleanup), result.second.suppressed.toList())
    }

    @Test fun onlyExplicitUnchangedOrPermissionTerminationMayRemainInline() {
        for (cause in listOf(SecurityException("permission denied"),
            SecurityException("permission request cancelled"), IOException("permission launcher unavailable"),
            IOException("provider reported zero updates"))) {
            val failure = RecordingRenameDefiniteFailure("rename not completed", cause)
            val result = routed(failure)
            assertEquals("inline", result.first)
            assertSame(failure, result.second)
            assertSame(cause, result.second.cause)
            // Merely carrying the same error as a cause must not classify an unknown outer failure as definite.
            assertEquals("reconcile", routed(IOException("catalog operation failed", failure)).first)
        }
    }

    private fun observation(name: String = "original.wav", revision: Long = 10L,
        uri: String = "content://media/external_primary/audio/media/10748",
        bytes: ByteArray = byteArrayOf(1, 2, 3, 4), key: String? = null) =
        MediaStoreRenameObservation(name, StableOutputFingerprint(
            CopyDigest(4L, bytes), key,
            providerRecordingIdentity(RecordingStorageType.MEDIASTORE, uri, 4L, revision)))

    @Test fun consentRequiresExactRevisionNotOnlySameProviderObject() {
        val before = observation()
        assertTrue(renameConsentObservationMatches(before, observation()))
        assertFalse(renameConsentObservationMatches(before, observation(revision = 11L)))
        assertFalse(renameConsentObservationMatches(before, observation(uri = "content://media/external_primary/audio/media/10749")))
    }

    @Test fun consentRejectsChangedNameBytesNativeIdentityAndMissingProof() {
        val before = observation(key = "original native object")
        assertFalse(renameConsentObservationMatches(before, observation(name = "external-rename.wav", key = "original native object")))
        assertFalse(renameConsentObservationMatches(before, observation(bytes = byteArrayOf(4, 3, 2, 1), key = "original native object")))
        assertFalse(renameConsentObservationMatches(before, observation(key = "replacement native object")))
        assertFalse(renameConsentObservationMatches(before, observation()))
        assertFalse(renameConsentObservationMatches(before, null))
        val unknown = before.copy(fingerprint = before.fingerprint.copy(providerIdentity = ""))
        assertFalse(renameConsentObservationMatches(unknown, unknown))
    }

    @Test fun permanentHostCloseRejectsAlreadyStartedLateEnqueue() {
        val host = RecordingRenameConsentHost(); val owner = Any()
        assertFalse(host.accepts(host.generation))
        host.attach(owner); val submitted = host.generation
        assertTrue(host.accepts(submitted))
        assertTrue(host.detach(owner))
        assertFalse(host.accepts(submitted))
        host.attach(Any())
        assertFalse(host.accepts(submitted))
        assertTrue(host.accepts(host.generation))
    }

    @Test fun retainedRotationLineageIsIdempotentAndFinalCloseInvalidatesIt() {
        val host = RecordingRenameConsentHost(); val lineage = Any()
        host.attach(lineage); val submitted = host.generation
        // ViewModelStore retains this same owner through the old Activity's destruction.
        assertTrue(host.accepts(submitted))
        host.attach(lineage)
        assertTrue(host.accepts(submitted))
        assertTrue(host.detach(lineage))
        assertFalse(host.accepts(submitted))
        val ended = host.generation
        assertFalse(host.detach(lineage))
        assertEquals(ended, host.generation)
    }

    @Test fun closingNewerActivityKeepsOlderActivityPermissionLifetime() {
        val host = RecordingRenameConsentHost(); val a = Any(); val b = Any()
        host.attach(a); host.attach(b); val submitted = host.generation
        assertFalse(host.detach(b))
        assertTrue(host.accepts(submitted))
        assertTrue(host.detach(a))
        assertFalse(host.accepts(submitted))
    }

    @Test fun closingOlderActivityCannotRetireNewerOrLaterGeneration() {
        val host = RecordingRenameConsentHost(); val a = Any(); val b = Any()
        host.attach(a); host.attach(b); val submitted = host.generation
        assertFalse(host.detach(a))
        assertTrue(host.accepts(submitted))
        assertFalse(host.detach(a))
        assertTrue(host.accepts(submitted))
        assertTrue(host.detach(b))
        host.attach(Any())
        assertFalse(host.accepts(submitted))
        assertTrue(host.accepts(host.generation))
    }

    @Test fun oneActivityRotationGapSurvivesOtherActivityFinalClose() {
        val host = RecordingRenameConsentHost(); val rotating = Any(); val closing = Any()
        host.attach(rotating); host.attach(closing); val submitted = host.generation
        // Rotation retains its ViewModel owner even before the replacement Activity exists.
        assertFalse(host.detach(closing))
        assertTrue(host.accepts(submitted))
        host.attach(rotating)
        assertTrue(host.accepts(submitted))
        assertTrue(host.detach(rotating))
        assertFalse(host.accepts(submitted))
    }

    @Test fun actualViewModelStoresRetainLineageUntilLastStoreIsCleared() {
        val a = ViewModelStore(); val b = ViewModelStore()
        fun owner(store: ViewModelStore) = ViewModelProvider(store,
            ViewModelProvider.NewInstanceFactory())[RecordingRenameConsentHostViewModel::class.java]
        try {
            val retained = owner(a)
            owner(b)
            val generation = RecordingDeletionConsent.renameLifetime()
            b.clear()
            assertEquals(generation, RecordingDeletionConsent.renameLifetime())
            // Activity recreation receives A's retained store, not a new or phantom owner.
            assertSame(retained, owner(a))
            a.clear()
            assertEquals(generation + 1, RecordingDeletionConsent.renameLifetime())
            b.clear()
            assertEquals(generation + 1, RecordingDeletionConsent.renameLifetime())
        } finally { a.clear(); b.clear() }
    }

    @Test fun cancelledWaiterKeepsReservationUntilApprovedRetrySettles() = runBlocking {
        val result = CompletableDeferred<String>(); val events = mutableListOf<String>()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            try { events += awaitCommittedRenameConsent(result) }
            finally { events += "reservation released" }
        }
        caller.cancel(); yield()
        assertTrue(events.isEmpty())
        result.complete("retry settled")
        caller.join()
        assertEquals(listOf("retry settled", "reservation released"), events)
    }

    @Test fun returnedDocumentObservationFailuresSuppressOnceAndPreserveCause() {
        for (stage in listOf("identity", "metadata", "stream read", "stream close")) {
            val failure = IOException(stage); var attempts = 0
            val terminal = runCatching {
                verifyDocumentRenamePostcondition("source", false,
                    suppressReturned = { attempts++; true }) { throw failure }
            }.exceptionOrNull() as RecordingRenameStateUncertainException
            assertSame(failure, terminal.cause)
            assertEquals(1, attempts)
            assertEquals("reconcile", routed(terminal).first)
        }
    }

    @Test fun rejectedDocumentPostconditionSuppressesOnceWithoutReplacingPrimary() {
        val primary = RecordingRenameStateUncertainException("rejected transition")
        val suppressionFailure = IOException("journal unavailable"); var attempts = 0
        val terminal = runCatching {
            verifyDocumentRenamePostcondition("source", false,
                suppressReturned = { attempts++; throw suppressionFailure }) { throw primary }
        }.exceptionOrNull()
        assertSame(primary, terminal)
        assertEquals(listOf(suppressionFailure), primary.suppressed.toList())
        assertEquals(1, attempts)
    }

    @Test fun failedDocumentSuppressionIsAttachedAndSameUriNeverHidden() {
        val failure = IOException("close failed")
        val terminal = runCatching {
            verifyDocumentRenamePostcondition("source", false,
                suppressReturned = { false }) { throw failure }
        }.exceptionOrNull() as RecordingRenameStateUncertainException
        assertSame(failure, terminal.cause)
        assertEquals(1, terminal.suppressed.size)
        var attempts = 0
        val sameUri = runCatching {
            verifyDocumentRenamePostcondition("source", true,
                suppressReturned = { attempts++; true }) { throw failure }
        }.exceptionOrNull() as RecordingRenameStateUncertainException
        assertSame(failure, sameUri.cause)
        assertEquals(0, attempts)
        assertEquals("accepted", verifyDocumentRenamePostcondition("source", false,
            suppressReturned = { attempts++; true }) { "accepted" })
        assertEquals(0, attempts)
    }

    @Test fun deniedWaiterKeepsOriginalCauseAndReleasesOnce() = runBlocking {
        val result = CompletableDeferred<String>(); val cause = SecurityException("denied")
        var observed: Throwable? = null; var released = 0
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCommittedRenameConsent(result) }
            catch (failure: Throwable) { observed = failure }
            finally { released++ }
        }
        result.completeExceptionally(cause); caller.join()
        // Coroutine stacktrace recovery may copy the exception and chain the original.
        val failure = requireNotNull(observed)
        assertEquals(cause.javaClass, failure.javaClass)
        assertEquals(cause.message, failure.message)
        assertTrue(generateSequence(failure) { it.cause }.any { it === cause })
        assertEquals(1, released)
        assertFalse(result.complete("late granted result"))
    }

    @Test fun mixedRequestsKeepFactorySerializedThroughApprovedRetry() {
        val queue = RecordingDeletionConsentState<String> { it }
        queue.offer("delete"); queue.offer("rename")
        assertEquals("delete", queue.claim())
        var factories = 0
        queue.createRequest("delete") { factories++; Unit }
        assertNull(queue.createRequest("rename") { factories++; Unit })
        assertTrue(queue.publishPrompt("delete", "delete-prompt"))
        assertEquals("delete-prompt", queue.claimPrompt())
        assertEquals("delete", queue.finish("delete-prompt"))
        assertNull(queue.claim())
        assertNull(queue.createRequest("rename") { factories++; Unit })
        assertEquals(1, factories)
        queue.complete("delete")
        assertEquals("rename", queue.claim())
        queue.createRequest("rename") { factories++; Unit }
        assertEquals(2, factories)
        assertNull(queue.finish("delete-prompt"))
    }

    @Test fun permanentCloseRemovesQueuedRenameWithoutReleasingActiveFactory() {
        val queue = RecordingDeletionConsentState<String> { it }
        queue.offer("active-delete"); queue.offer("queued-rename")
        assertEquals("active-delete", queue.claim())
        assertEquals("queued-rename", queue.removeQueued("queued-rename"))
        assertNull(queue.removeQueued("active-delete"))
        queue.createRequest("active-delete") { Unit }
        assertNull(queue.removeQueued("active-delete"))
        assertTrue(queue.publishPrompt("active-delete", "prompt"))
        assertEquals("prompt", queue.claimPrompt())
        assertEquals("active-delete", queue.finish("prompt"))
        assertNull(queue.removeQueued("active-delete"))
        queue.complete("active-delete")
        assertNull(queue.claim())
    }
}
