package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingMoveCommitPolicyTest {
    @Test
    fun moveCommit_distinguishesTargetReplacementFromSourceCleanupFailure() {
        assertEquals(
            VerifiedMoveCommitResult.TARGET_CHANGED,
            verifiedMoveCommitResult(targetIdentityCurrent = false, sourceCleanupComplete = false),
        )
        assertEquals(
            VerifiedMoveCommitResult.SOURCE_CLEANUP_FAILED,
            verifiedMoveCommitResult(targetIdentityCurrent = true, sourceCleanupComplete = false),
        )
        assertEquals(
            VerifiedMoveCommitResult.MOVED,
            verifiedMoveCommitResult(targetIdentityCurrent = true, sourceCleanupComplete = true),
        )
    }
}
