package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTargetValidationTest {
    private val digestHex = "ab".repeat(32)
    private val statIdentity = "stat:1:2:3:4:5"

    @Test
    fun malformedFileCleanupTargetLosesAuthorityButKeepsSuppressionId() {
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "relative/unsafe.wav",
            byteCount = 4L,
            sha256Hex = digestHex,
            fileKey = statIdentity,
        )
        val raw = encodePendingOutputCleanupRecord(record)
        assertNull(decodePendingOutputCleanupRecord(raw))
        assertEquals(record.id, pendingOutputCleanupSuppressedId(raw))
    }

    @Test
    fun malformedProviderCleanupAndStagingTargetsLoseAuthority() {
        val cleanup = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.DOCUMENT,
            id = "file:///unsafe.wav",
            byteCount = 4L,
            sha256Hex = digestHex,
            fileKey = null,
            providerIdentity = "provider:2:bad:4:1",
        )
        assertNull(decodePendingOutputCleanupRecord(encodePendingOutputCleanupRecord(cleanup)))
        val canonicalDocument = cleanup.copy(
            id = "content://docs/document/7",
            providerIdentity = "provider:2:canonical:4:1",
        )
        assertNull(decodePendingOutputCleanupRecord(encodePendingOutputCleanupRecord(canonicalDocument)))
        val scopedDocument = cleanup.copy(
            id = "content://docs/tree/root/document/7",
            providerIdentity = "provider:2:scoped:4:1",
        )
        assertEquals(
            scopedDocument,
            decodePendingOutputCleanupRecord(encodePendingOutputCleanupRecord(scopedDocument)),
        )

        val staging = VerifiedExportStagingRecord(
            storageType = RecordingStorageType.MEDIASTORE,
            id = "relative/media",
            byteCount = 4L,
            sha256Hex = digestHex,
            fileKey = null,
            providerIdentity = "provider:3:bad:4:1",
        )
        assertNull(decodeVerifiedExportStagingRecord(encodeVerifiedExportStagingRecord(staging)))

        val wrongMediaAuthority = staging.copy(id = "content://other.provider/external/audio/media/7")
        assertNull(decodeVerifiedExportStagingRecord(encodeVerifiedExportStagingRecord(wrongMediaAuthority)))
        val wrongMediaCollection = staging.copy(id = "content://media/external/images/media/7")
        assertNull(decodeVerifiedExportStagingRecord(encodeVerifiedExportStagingRecord(wrongMediaCollection)))
    }

    @Test
    fun malformedFileDeletionTargetLosesAuthorityButKeepsSuppressionId() {
        val intent = PendingDeletionIntent(
            id = "relative/delete.wav",
            byteCount = 4L,
            sha256Hex = digestHex,
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000901",
            fileIdentity = statIdentity,
        )
        val raw = encodePendingDeletionIntent(intent)
        assertNull(decodePendingDeletionIntent(raw))
        assertEquals(intent.id, pendingDeletionSuppressedId(raw))
    }

    @Test
    fun absoluteFileJournalTargetsOutsideManagedRootsRemainSuppressionOnly() {
        val managed = setOf("/recordings")
        val cleanup = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "/other/unsafe.wav",
            byteCount = 4L,
            sha256Hex = digestHex,
            fileKey = statIdentity,
        )
        assertFalse(pendingOutputCleanupTargetHasManagedFileAuthority(cleanup, managed))
        assertTrue(
            pendingOutputCleanupTargetHasManagedFileAuthority(
                cleanup.copy(id = "/recordings/safe.wav"),
                managed,
            ),
        )
        assertFalse(
            pendingOutputCleanupTargetHasManagedFileAuthority(
                cleanup.copy(id = "/recordings/nested/not-direct.wav"),
                managed,
            ),
        )

        val deletion = PendingDeletionIntent(
            id = "/other/unsafe.wav",
            byteCount = 4L,
            sha256Hex = digestHex,
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000903",
            fileIdentity = statIdentity,
        )
        assertFalse(pendingDeletionTargetsHaveManagedFileAuthority(deletion, managed))
        assertTrue(
            pendingDeletionTargetsHaveManagedFileAuthority(
                deletion.copy(id = "/recordings/safe.wav"),
                managed,
            ),
        )
        assertFalse(
            pendingDeletionTargetsHaveManagedFileAuthority(
                deletion.copy(
                    id = "/recordings/source.wav",
                    moveTargetStorageType = RecordingStorageType.FILE,
                    moveTargetId = "/other/target.wav",
                    moveTargetIdentity = statIdentity,
                ),
                managed,
            ),
        )
    }

    @Test
    fun malformedMoveTargetInvalidatesWholeDeletionAuthority() {
        val intent = PendingDeletionIntent(
            id = "/recordings/source.wav",
            byteCount = 4L,
            sha256Hex = digestHex,
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000902",
            fileIdentity = statIdentity,
            moveTargetStorageType = RecordingStorageType.FILE,
            moveTargetId = "relative/target.wav",
            moveTargetIdentity = "stat:7:8:9:10:11",
        )
        val raw = encodePendingDeletionIntent(intent)
        assertNull(decodePendingDeletionIntent(raw))
        assertEquals(intent.id, pendingDeletionSuppressedId(raw))
    }
}
