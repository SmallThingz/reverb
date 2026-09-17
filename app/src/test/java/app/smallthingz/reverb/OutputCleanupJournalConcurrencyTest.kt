package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCleanupJournalConcurrencyTest {
    @Test
    fun staleReplayRemoval_cannotRemoveNewerRecordForSameId() {
        val old = encodePendingOutputCleanupRecord(
            PendingOutputCleanupRecord(
                storageType = RecordingStorageType.FILE,
                id = "/recordings/clip.wav",
                byteCount = 10L,
                sha256Hex = "11".repeat(32),
                fileKey = "old-key",
            ),
        )
        val newer = encodePendingOutputCleanupRecord(
            PendingOutputCleanupRecord(
                storageType = RecordingStorageType.FILE,
                id = "/recordings/clip.wav",
                byteCount = 20L,
                sha256Hex = "22".repeat(32),
                fileKey = "new-key",
            ),
        )

        assertEquals(
            setOf(newer),
            pendingOutputCleanupEntriesAfterExactRemoval(setOf(newer), old),
        )
        assertEquals(
            setOf(newer),
            pendingOutputCleanupEntriesAfterExactRemoval(setOf(old, newer), old),
        )
        assertTrue(pendingOutputCleanupEntriesAfterExactRemoval(setOf(old), old).isEmpty())
    }
    @Test
    fun staleVerifiedStagingRemoval_cannotRevokeNewerMarkerForSameId() {
        val id = "/recordings/.reverb-export-same.wav"
        fun fingerprint(byteCount: Long, byte: Int, fileKey: String) = StableOutputFingerprint(
            digest = CopyDigest(byteCount, ByteArray(32) { byte.toByte() }),
            fileKey = fileKey,
            providerIdentity = null,
        )
        fun raw(fingerprint: StableOutputFingerprint) = encodeVerifiedExportStagingRecord(
            VerifiedExportStagingRecord(
                storageType = RecordingStorageType.FILE,
                id = id,
                byteCount = fingerprint.digest.byteCount,
                sha256Hex = fingerprint.digest.sha256.toHexString(),
                fileKey = fingerprint.fileKey,
                providerIdentity = null,
            ),
        )

        val oldFingerprint = fingerprint(10L, 0x11, "old-key")
        val newerFingerprint = fingerprint(20L, 0x22, "new-key")
        val old = raw(oldFingerprint)
        val newer = raw(newerFingerprint)

        assertEquals(
            setOf(newer),
            verifiedExportStagingEntriesAfterFingerprintRemoval(
                setOf(old, newer), RecordingStorageType.FILE, id, oldFingerprint,
            ),
        )
        assertEquals(
            setOf(newer),
            verifiedExportStagingEntriesAfterFingerprintRemoval(
                setOf(newer), RecordingStorageType.FILE, id, oldFingerprint,
            ),
        )
        assertTrue(
            verifiedExportStagingEntriesAfterFingerprintRemoval(
                setOf(old), RecordingStorageType.FILE, id, oldFingerprint,
            ).isEmpty(),
        )
    }

}
