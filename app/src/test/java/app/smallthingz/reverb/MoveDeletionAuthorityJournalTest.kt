package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveDeletionAuthorityJournalTest {
    private fun fileMoveIntent(source: File, token: String): PendingDeletionIntent {
        val bytes = source.readBytes()
        val digest = sha256(ByteArrayInputStream(bytes))
        return PendingDeletionIntent(
            id = source.absolutePath,
            byteCount = digest.byteCount,
            sha256Hex = digest.sha256.toHexString(),
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = token,
            fileIdentity = resolveFileIdentity(source),
            moveTargetStorageType = RecordingStorageType.FILE,
            moveTargetId = File(source.parentFile, "moved.wav").absolutePath,
            moveTargetIdentity = "stat:target:identity",
        )
    }

    @Test
    fun moveDeletionV4_roundTripsSourceAndTargetAuthority() {
        val intent = PendingDeletionIntent(
            id = "/storage/source.wav",
            byteCount = 1234L,
            sha256Hex = "ab".repeat(32),
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000201",
            fileIdentity = "stat:source:identity",
            moveTargetStorageType = RecordingStorageType.DOCUMENT,
            moveTargetId = "content://docs/tree/root/document/moved",
            moveTargetIdentity = "provider:2:target:1234:9",
        )
        val encoded = encodePendingDeletionIntent(intent)
        assertTrue(encoded.startsWith("v4|"))
        assertEquals(intent, decodePendingDeletionIntent(encoded))
        assertEquals(intent.id, pendingDeletionSuppressedId(encoded))
    }

    @Test
    fun providerSourceMoveV4_roundTripsWithoutFileClaimAuthority() {
        val intent = PendingDeletionIntent(
            id = "content://media/external/audio/media/17",
            byteCount = 4321L,
            sha256Hex = "cd".repeat(32),
            assetDeleted = false,
            storageType = RecordingStorageType.MEDIASTORE,
            moveTargetStorageType = RecordingStorageType.FILE,
            moveTargetId = "/storage/moved.wav",
            moveTargetIdentity = "stat:target:identity",
        )
        val decoded = decodePendingDeletionIntent(encodePendingDeletionIntent(intent))
        assertEquals(intent, decoded)
        assertEquals(null, deletionClaimFile(requireNotNull(decoded)))
    }

    @Test
    fun claimedMoveSource_isRestoredWhenTargetAuthorityIsLost() {
        withClaimedMoveSource("00000000-0000-0000-0000-000000000202") { source, claim, bytes, intent ->
            val result = replayWithDescriptorBoundAuthority(intent, claim, bytes) { false }
            assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, result)
            assertTrue(source.isFile)
            assertArrayEquals(bytes, source.readBytes())
            assertFalse(claim.exists())
        }
    }

    @Test
    fun claimedMoveSource_isDeletedOnlyWhileTargetAuthorityRemainsCurrent() {
        withClaimedMoveSource("00000000-0000-0000-0000-000000000203") { source, claim, bytes, intent ->
            val result = replayWithDescriptorBoundAuthority(intent, claim, bytes) { true }
            assertEquals(FileDeletionClaimResult.DELETED, result)
            assertFalse(source.exists())
            assertFalse(claim.exists())
        }
    }

    @Test
    fun claimedMoveSource_withoutTargetValidatorFailsSafe() {
        withClaimedMoveSource("00000000-0000-0000-0000-000000000204") { source, claim, bytes, intent ->
            val result = replayWithDescriptorBoundAuthority(intent, claim, bytes, null)
            assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, result)
            assertTrue(source.isFile)
            assertArrayEquals(bytes, source.readBytes())
            assertFalse(claim.exists())
        }
    }

    @Test
    fun claimedMoveSource_targetValidationFailureFailsSafe() {
        withClaimedMoveSource("00000000-0000-0000-0000-000000000205") { source, claim, bytes, intent ->
            val result = replayWithDescriptorBoundAuthority(intent, claim, bytes) { error("provider unavailable") }
            assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, result)
            assertTrue(source.isFile)
            assertArrayEquals(bytes, source.readBytes())
            assertFalse(claim.exists())
        }
    }

    @Test
    fun partialMoveAuthorityCannotSerializeAsDeletionJournal() {
        val partial = PendingDeletionIntent(
            id = "/storage/source.wav",
            byteCount = 12L,
            sha256Hex = "ef".repeat(32),
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000206",
            fileIdentity = "stat:source:identity",
            moveTargetStorageType = RecordingStorageType.FILE,
        )
        assertEquals("", encodePendingDeletionIntent(partial))
    }

    private fun replayWithDescriptorBoundAuthority(
        intent: PendingDeletionIntent,
        claim: File,
        bytes: ByteArray,
        moveTargetStillCurrent: (() -> Boolean)?,
    ): FileDeletionClaimResult {
        val descriptorBoundIdentity = "stat:101:202:303:404:505"
        val digest = sha256(ByteArrayInputStream(bytes))
        return replayClaimedFileDeletion(
            intent = intent.copy(fileIdentity = descriptorBoundIdentity),
            claim = claim,
            readClaimFingerprint = { _, _ ->
                StableOutputFingerprint(
                    digest = digest,
                    fileKey = descriptorBoundIdentity,
                    providerIdentity = null,
                )
            },
            identityBeforeDelete = { descriptorBoundIdentity },
            moveTargetStillCurrent = moveTargetStillCurrent,
        )
    }

    private inline fun withClaimedMoveSource(
        token: String,
        block: (source: File, claim: File, bytes: ByteArray, intent: PendingDeletionIntent) -> Unit,
    ) {
        val parent = File("build/tmp/move-deletion-authority").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "claim-").toFile()
        try {
            val bytes = ByteArray(8192) { index -> ((index * 37 + 11) and 0xff).toByte() }
            val source = File(directory, "source.wav").apply { writeBytes(bytes) }
            val intent = fileMoveIntent(source, token)
            assertTrue(requireNotNull(intent.fileIdentity).isNotBlank())
            val claim = requireNotNull(deletionClaimFile(intent))
            Files.move(source.toPath(), claim.toPath())
            assertTrue(claim.isFile)
            block(source, claim, bytes, intent)
        } finally {
            directory.deleteRecursively()
        }
    }
}
