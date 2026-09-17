package app.smallthingz.reverb

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TornDeletionClaimRecoveryTest {
    @Test
    fun tornFileJournal_recoversOnlySourceDirectoryAuthority() {
        val id = "/storage/emulated/0/Music/Reverb/clip.wav"
        val encodedId = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.toByteArray(StandardCharsets.UTF_8))
        val sha = "ab".repeat(32)
        val fileCode = RecordingStorageType.FILE.storageCode.toInt()
        val malformed = "v3|$encodedId|4|$sha|0|$fileCode|not-a-uuid|broken"

        assertNull(decodePendingDeletionIntent(malformed))
        assertEquals(id, tornPendingDeletionFileSourceId(malformed))

        val providerCode = RecordingStorageType.MEDIASTORE.storageCode.toInt()
        val providerMalformed = "v3|$encodedId|4|$sha|0|$providerCode|not-a-uuid|broken"
        assertNull(tornPendingDeletionFileSourceId(providerMalformed))

        val relativeId = "Music/Reverb/clip.wav"
        val encodedRelativeId = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(relativeId.toByteArray(StandardCharsets.UTF_8))
        val relativeMalformed =
            "v3|$encodedRelativeId|4|$sha|0|$fileCode|not-a-uuid|broken"
        assertNull(tornPendingDeletionFileSourceId(relativeMalformed))

        val v4Torn =
            "v4|$encodedId|4|$sha|0|$fileCode|00000000-0000-0000-0000-000000000300|broken"
        assertNull(decodePendingDeletionIntent(v4Torn))
        assertEquals(id, tornPendingDeletionFileSourceId(v4Torn))

        val valid = PendingDeletionIntent(
            id = id,
            byteCount = 4L,
            sha256Hex = sha,
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000301",
            fileIdentity = "stat:1:2:3:4:5",
        )
        assertNull(tornPendingDeletionFileSourceId(encodePendingDeletionIntent(valid)))
    }

    @Test
    fun orphanedClaim_isPublishedVisibly_withoutTouchingOwnedClaim() {
        val parent = File("build/tmp/torn-delete-claim").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "recover-").toFile()
        try {
            val source = File(directory, "clip.wav")
            val orphanBytes = ByteArray(4_096) { index -> ((index * 17 + 3) and 0xff).toByte() }
            val orphan = File(
                directory,
                ".reverb-delete-00000000-0000-0000-0000-000000000302.pending",
            ).apply { writeBytes(orphanBytes) }
            val owned = File(
                directory,
                ".reverb-delete-00000000-0000-0000-0000-000000000303.pending",
            ).apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
            val lookalike = File(
                directory,
                ".reverb-delete-not-a-uuid.pending",
            ).apply { writeBytes(byteArrayOf(5, 4, 3, 2)) }

            assertTrue(
                preserveOrphanedDeletionClaims(source.absolutePath) { claim ->
                    claim.absolutePath == owned.absolutePath
                },
            )
            assertFalse(orphan.exists())
            assertTrue(owned.isFile)
            assertTrue(lookalike.isFile)
            assertFalse(source.exists())

            val recovered = requireNotNull(directory.listFiles()).filter { file ->
                file.name.startsWith("recovered-delete-journal-") && file.name.endsWith(".wav")
            }
            assertEquals(1, recovered.size)
            assertArrayEquals(orphanBytes, recovered.single().readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nonRegularOrphanClaim_keepsSuppressionPending() {
        val parent = File("build/tmp/torn-delete-claim").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "non-regular-").toFile()
        try {
            val source = File(directory, "clip.wav")
            File(
                directory,
                ".reverb-delete-00000000-0000-0000-0000-000000000304.pending",
            ).mkdirs()

            assertFalse(preserveOrphanedDeletionClaims(source.absolutePath) { false })
        } finally {
            directory.deleteRecursively()
        }
    }
}
