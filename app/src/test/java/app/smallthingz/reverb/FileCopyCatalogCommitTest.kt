package app.smallthingz.reverb

import android.content.ContextWrapper
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class FileCopyCatalogCommitTest {
    private fun context() = RecordingScanTestContext { emptySet() }

    @Test
    fun reservationRevisionChangeRejectsCatalogMutation() = runBlocking {
        val context = context()
        val snapshot = FileCopyPublications.snapshot(context)
        var committed = false
        try {
            FileCopyPublications.catalogCommit(context, snapshot.copy(revision = snapshot.revision - 1)) {
                committed = true
            }
            fail("stale observation must not commit")
        } catch (_: IOException) { }
        assertFalse(committed)
    }

    @Test
    fun cancelledSuspendingCommitReleasesMetadataMutexAndProvidersDoNotWaitForIt() = runBlocking {
        withTimeout(5_000) {
            val context = context()
            val snapshot = FileCopyPublications.snapshot(context)
            val entered = CompletableDeferred<Unit>()
            val owner = launch(Dispatchers.Default) {
                FileCopyPublications.catalogCommit(context, snapshot) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            try {
                // Null means a provider operation: even its Context must remain untouched.
                val result = FileCopyPublications.catalogCommit(ContextWrapper(null), null) { 17 }
                assertEquals(17, result)
                val waiting = async(Dispatchers.Default) { FileCopyPublications.snapshot(context) }
                owner.cancelAndJoin()
                assertEquals(snapshot, waiting.await())
            } finally {
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun losingTargetDuringIntermediateCleanupCannotReportAMovedCopy() {
        var targetPresent = true
        var rechecked = false
        val result = finishCopiedMoveIntermediate(
            cleanup = { targetPresent = false; false },
            targetStillCurrent = { rechecked = true; targetPresent },
        )
        assertTrue(rechecked)
        assertEquals(VerifiedMoveCommitResult.TARGET_CHANGED, result)
        assertEquals(VerifiedMoveCommitResult.SOURCE_CLEANUP_FAILED,
            finishCopiedMoveIntermediate(cleanup = { false }, targetStillCurrent = { true }))
    }

    @Test
    fun registrationCancellationSurvivesCopyOwnershipAndFailedIdentityRecheck() {
        val cancellation = CancellationException("registration owner cancelled")
        for (current in listOf(false, true)) {
            for (pending in listOf(false, true)) {
                val mapped = catalogRegistrationFailureAfterIdentityRecheck(cancellation,
                    stableIdentityAvailable = current, currentIdentityMatches = current,
                    currentDisplayNameMatches = current, copyCleanupPending = pending)
                assertSame(cancellation, mapped)
                assertFalse(catalogRegistrationFailureAllowsVerifiedSaveSuccess(mapped))
            }
        }
    }

    @Test
    fun cleanupPendingCannotBecomeDurableSaveCompleteThroughMetadataFailureAllowance() {
        val pending = FileCopyCleanupPendingException("Target cataloged; staging retained")
        val reported = catalogRegistrationFailureAfterIdentityRecheck(pending,
            stableIdentityAvailable = true, currentIdentityMatches = true)
        assertSame(pending, reported)
        assertFalse(catalogRegistrationFailureAllowsVerifiedSaveSuccess(reported))
        assertTrue(catalogRegistrationFailureAllowsVerifiedSaveSuccess(IOException("catalog unavailable")))
        val storageFailure = IOException("catalog marker failed")
        val retained = catalogRegistrationFailureAfterIdentityRecheck(storageFailure,
            stableIdentityAvailable = true, currentIdentityMatches = true, copyCleanupPending = true)
        assertTrue(retained is FileCopyCleanupPendingException)
        assertSame(storageFailure, retained.cause)
        assertFalse(catalogRegistrationFailureAllowsVerifiedSaveSuccess(retained))
    }

    @Test
    fun sourceIsHiddenOnlyAlongsideItsExactVerifiedCopiedTarget() {
        val source = RecordingEntity("/owned/source.wav", "source.wav", "audio/wav", 0, 1, 4,
            "PCM", RecordingStorageType.FILE, "/owned", fileIdentity = "source-object")
        val target = source.copy(id = "/owned/target.wav", displayName = "target.wav", fileIdentity = "target-object")
        val receipt = FileCopyPublication("token", FileCopyPurpose.RENAME, source.id, target.displayName,
            target.id, source.fileIdentity, 4, "0".repeat(64), FileCopyPhase.VERIFIED,
            "created-object", target.fileIdentity)
        assertEquals(setOf(source.id), fileCopySourceIdsToHide(listOf(receipt), listOf(source, target)))
        assertTrue(fileCopySourceIdsToHide(listOf(receipt), listOf(source)).isEmpty())
        assertTrue(fileCopySourceIdsToHide(listOf(receipt), listOf(source.copy(fileIdentity = "replacement"), target)).isEmpty())
        assertTrue(fileCopySourceIdsToHide(listOf(receipt.copy(phase = FileCopyPhase.CREATED)), listOf(source, target)).isEmpty())
    }
}
