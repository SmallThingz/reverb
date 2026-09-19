package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        val staging = VerifiedExportStagingRecord(
            storageType = RecordingStorageType.MEDIASTORE,
            id = "relative/media",
            byteCount = 4L,
            sha256Hex = digestHex,
            fileKey = null,
            providerIdentity = "provider:3:bad:4:1",
        )
        assertNull(decodeVerifiedExportStagingRecord(encodeVerifiedExportStagingRecord(staging)))
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
