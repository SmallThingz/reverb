package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRenameUncertaintyTest {
    @Test
    fun providerRename_onlyBecomesUncertainAfterAcceptedMutationLosesProof() {
        assertFalse(providerRenameStateIsUncertain(mutationAccepted = false, contentContinuityVerified = false))
        assertFalse(providerRenameStateIsUncertain(mutationAccepted = true, contentContinuityVerified = true))
        assertTrue(providerRenameStateIsUncertain(mutationAccepted = true, contentContinuityVerified = false))
        assertTrue(
            providerRenameStateIsUncertain(
                mutationAccepted = true,
                contentContinuityVerified = true,
                displayNameVerified = false,
            ),
        )
    }

    @Test
    fun uncertainRenameSignal_isAnIoFailureForRepositoryUiPropagation() {
        val error: Throwable = RecordingRenameStateUncertainException("rename state uncertain")
        assertTrue(error is java.io.IOException)
    }
    @Test
    fun fileMove_ioFailureIsUncertain_butCollisionIsNot() {
        assertTrue(fileRenameMoveFailureIsUncertain(java.io.IOException("move failed")))
        assertFalse(fileRenameMoveFailureIsUncertain(java.nio.file.FileAlreadyExistsException("target")))
        assertFalse(fileRenameMoveFailureIsUncertain(SecurityException("denied")))
    }


    @Test
    fun mediaStoreRenameRecovery_requiresRequestedNameSameObjectAndExactBytes() {
        val beforeDigest = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val beforeIdentity = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE, "content://media/item", 4L, 10L,
        )
        val sameObject = StableOutputFingerprint(
            digest = beforeDigest,
            fileKey = null,
            providerIdentity = providerRecordingIdentity(
                RecordingStorageType.MEDIASTORE, "content://media/item", 4L, 11L,
            ),
        )
        val renamed = MediaStoreRenameObservation("renamed.wav", sameObject)

        assertTrue(
            mediaStoreRenameObservationMatchesExpected(
                "renamed.wav", beforeIdentity, beforeDigest, renamed,
            ),
        )
        assertFalse(
            mediaStoreRenameObservationMatchesExpected(
                "other.wav", beforeIdentity, beforeDigest, renamed,
            ),
        )
        assertFalse(
            mediaStoreRenameObservationMatchesExpected(
                "renamed.wav",
                beforeIdentity,
                beforeDigest,
                renamed.copy(
                    fingerprint = sameObject.copy(
                        providerIdentity = providerRecordingIdentity(
                            RecordingStorageType.MEDIASTORE, "content://media/other", 4L, 11L,
                        ),
                    ),
                ),
            ),
        )
        assertFalse(
            mediaStoreRenameObservationMatchesExpected(
                "renamed.wav",
                beforeIdentity,
                beforeDigest,
                renamed.copy(
                    fingerprint = sameObject.copy(digest = CopyDigest(4L, byteArrayOf(4, 3, 2, 1))),
                ),
            ),
        )
        assertFalse(
            mediaStoreRenameObservationMatchesExpected(
                "renamed.wav", beforeIdentity, beforeDigest, null,
            ),
        )
    }
}
