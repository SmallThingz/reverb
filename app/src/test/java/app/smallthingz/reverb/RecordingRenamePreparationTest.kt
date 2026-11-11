package app.smallthingz.reverb

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RecordingRenamePreparationTest {
    private fun failure(block: () -> Unit): Throwable {
        try { block() } catch (error: Throwable) { return error }
        error("Expected failure")
    }

    private fun route(error: Throwable): String {
        var result = ""
        routeRecordingRenameFailure(error, { result = "inline" }, { result = "reconcile" })
        return result
    }

    @Test fun preCloseNameRejectionKeepsEditorAndOriginalCause() {
        val native = noReplaceMoveFailure(NO_REPLACE_MOVE_NAME_TOO_LONG, "old.wav", "requested.wav") {
            error("A proven pre-close rejection does not need an ambiguous errno description")
        }
        val delivered = failure { attemptRecordingRenameMove { throw native } }
        assertFalse(fileRenameMoveFailureIsUncertain(delivered))
        assertEquals("inline", route(delivered))
        assertSame(native, delivered.cause)
        assertTrue(native is NoReplaceMoveNameTooLong)
    }

    @Test fun ordinaryNameErrnoAfterPotentialMoveStillRequiresReconciliation() {
        // A successful syscall followed by a fault-injected close ENAMETOOLONG must stay uncertain.
        val native = noReplaceMoveFailure(36, "old.wav", "requested.wav") { "errno $it" }
        val delivered = failure { attemptRecordingRenameMove { throw native } }
        assertFalse(native is NoReplaceMoveNameTooLong)
        assertTrue(fileRenameMoveFailureIsUncertain(delivered))
        assertSame(native, delivered)
        assertEquals("reconcile", route(delivered))
    }

    @Test fun genericFailureCollisionAndUnsupportedKeepTheirExistingOwners() {
        val errors = listOf(IOException("close failed"), FileAlreadyExistsException("target"),
            NoReplaceMoveUnsupported("mount does not support atomic no-replace"))
        for (error in errors) {
            assertSame(error, failure { attemptRecordingRenameMove { throw error } })
        }
        val rolledBackNameError = NoReplaceMoveNameTooLong("renamed.wav", "original.wav")
        assertEquals("reconcile", route(rolledBackNameError))
    }

    @Test fun permissionSenderFactoryFailureRemainsInlineAndPreservesCause() {
        for (cause in listOf(SecurityException("provider denied request"),
            IllegalArgumentException("provider cannot create request"),
            IllegalStateException("legacy permission sender missing"), IOException("request unavailable"))) {
            var calls = 0
            val delivered = failure { prepareRecordingRenameConsentSender { calls++; throw cause } }
            assertEquals(1, calls)
            assertEquals("inline", route(delivered))
            assertSame(cause, delivered.cause)
            assertEquals("reconcile", route(cause))
        }
    }

    @Test fun permissionSenderCancellationRemainsCancellation() {
        val cancelled = CancellationException("permission owner ended")
        assertSame(cancelled, failure { prepareRecordingRenameConsentSender { throw cancelled } })
    }

    @Test fun successfulMoveAndPermissionPreparationReturnExactOwnerOnce() {
        val owner = Any()
        var moves = 0
        var requests = 0
        assertSame(owner, attemptRecordingRenameMove { moves++; owner })
        assertSame(owner, prepareRecordingRenameConsentSender { requests++; owner })
        assertEquals(1, moves)
        assertEquals(1, requests)
    }
}
