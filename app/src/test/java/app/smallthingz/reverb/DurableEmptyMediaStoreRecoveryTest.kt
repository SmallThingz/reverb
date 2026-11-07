package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class DurableEmptyMediaStoreRecoveryTest {
    private val id = "content://media/external/audio/media/10200"
    private fun identity(generation: Long, size: Long = 0L, uri: String = id) =
        mediaStoreProviderIdentityFromMetadata(uri, true, size, true, 100L, true, generation)

    private fun target() = RecordingOutputTarget(
        id, "selected.wav", "audio/wav", RecordingStorageType.MEDIASTORE,
        MEDIA_STORE_DIRECTORY_ID, 1L, staging = true,
        stagingDisplayName = "reverb-partial-owned.wav", stagingIdentity = identity(45134L),
    )

    @Test
    fun changedRevisionCannotBorrowTheOrdinaryWritersSameUriAdmission() {
        val target = target()
        val changed = identity(45135L)
        assertTrue(sameProviderObjectAcrossMutation(target.stagingIdentity, changed))
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(target, changed))
        assertTrue(emptyMediaStoreRecoveryIdentityMatches(target, identity(45134L)))
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(target, identity(45134L, size = 8L)))
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(target, identity(45134L, uri = id + "1")))
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(target, ""))
    }

    @Test
    fun recoveryBridgeRequiresOwnedMediaStoreStagingRatherThanGenericProviderIdentity() {
        val target = target()
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(target.copy(staging = false), target.stagingIdentity))
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(target.copy(stagingIdentity = ""), target.stagingIdentity))
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(
            target.copy(storageType = RecordingStorageType.DOCUMENT), target.stagingIdentity))
        assertFalse(emptyMediaStoreRecoveryIdentityMatches(
            target.copy(storageType = RecordingStorageType.FILE), target.stagingIdentity))
    }

    @Test
    fun postCloseFingerprintMustReadTheAdmittedEmptyInodeEvenWhenRevisionChanges() {
        val admitted = ProviderPayloadRevision(7L, 91L, 0L, 100L, 1L)
        assertTrue(providerPayloadObjectMatches(admitted, admitted.copy(changedSeconds = 101L, changedNanos = 2L)))
        assertFalse(providerPayloadObjectMatches(admitted, admitted.copy(inode = 92L)))
        assertFalse(providerPayloadObjectMatches(admitted, admitted.copy(device = 8L)))
        assertFalse(providerPayloadObjectMatches(admitted, admitted.copy(sizeBytes = 1L)))
        assertFalse(providerPayloadObjectMatches(admitted, null))
    }
}
