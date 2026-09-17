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

    @Test
    fun sameIdSnapshot_keepsTornSuppressionEntryInObservedRemovalSet() {
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "/recordings/torn.wav",
            byteCount = 10L,
            sha256Hex = "11".repeat(32),
            fileKey = "stat:1:10:1:1:1",
        )
        val valid = encodePendingOutputCleanupRecord(record)
        val torn = valid.substringBeforeLast('|')
        assertEquals(null, decodePendingOutputCleanupRecord(torn))
        assertEquals(record.id, pendingOutputCleanupSuppressedId(torn))
        assertEquals(
            setOf(valid, torn),
            pendingOutputCleanupRawEntriesForId(setOf(valid, torn, "unrelated"), record.id),
        )
    }

    @Test
    fun exactSnapshotRemoval_preservesEntriesPublishedAfterSnapshot() {
        val observedA = "observed-a"
        val observedB = "observed-b"
        val newer = "newer"
        assertEquals(
            setOf(newer),
            pendingOutputCleanupEntriesAfterExactRemovals(
                entries = setOf(observedA, observedB, newer),
                expectedRaws = setOf(observedA, observedB),
            ),
        )
        assertEquals(
            setOf(newer),
            pendingOutputCleanupEntriesAfterExactRemovals(
                entries = setOf(newer),
                expectedRaws = setOf(observedA, observedB),
            ),
        )
    }

    @Test
    fun staleCleanupProducer_cannotOverwriteNewerSameIdAuthority() {
        val id = "/recordings/reused.wav"
        fun record(byteCount: Long, byte: Int, key: String) = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = id,
            byteCount = byteCount,
            sha256Hex = "%02x".format(byte).repeat(32),
            fileKey = key,
        )
        fun fingerprint(record: PendingOutputCleanupRecord, byte: Int) = StableOutputFingerprint(
            digest = CopyDigest(record.byteCount, ByteArray(32) { byte.toByte() }),
            fileKey = record.fileKey,
            providerIdentity = null,
        )

        val oldRecord = record(10L, 0x11, "stat:1:10:1:1:1")
        val newRecord = record(20L, 0x22, "stat:1:20:1:1:1")
        val old = encodePendingOutputCleanupRecord(oldRecord)
        val newer = encodePendingOutputCleanupRecord(newRecord)
        val combined = pendingOutputCleanupEntriesAfterAppend(setOf(newer), old)

        assertEquals(setOf(old, newer), combined)
        assertEquals(
            newer,
            pendingOutputCleanupRawMatchingFingerprint(
                combined, id, RecordingStorageType.FILE, fingerprint(newRecord, 0x22),
            ),
        )
        assertEquals(
            old,
            pendingOutputCleanupRawMatchingFingerprint(
                combined, id, RecordingStorageType.FILE, fingerprint(oldRecord, 0x11),
            ),
        )
    }

    @Test
    fun staleVerifiedStagingProducer_cannotOverwriteNewerSameIdMarker() {
        val id = "/recordings/.reverb-export-reused.wav"
        fun fingerprint(byteCount: Long, byte: Int, key: String) = StableOutputFingerprint(
            digest = CopyDigest(byteCount, ByteArray(32) { byte.toByte() }),
            fileKey = key,
            providerIdentity = null,
        )
        fun raw(fingerprint: StableOutputFingerprint) = encodeVerifiedExportStagingRecord(
            VerifiedExportStagingRecord(
                storageType = RecordingStorageType.FILE, id = id,
                byteCount = fingerprint.digest.byteCount,
                sha256Hex = fingerprint.digest.sha256.toHexString(),
                fileKey = fingerprint.fileKey, providerIdentity = null,
            ),
        )
        val oldFingerprint = fingerprint(10L, 0x11, "old-key")
        val newFingerprint = fingerprint(20L, 0x22, "new-key")
        val old = raw(oldFingerprint)
        val newer = raw(newFingerprint)
        val combined = verifiedExportStagingEntriesAfterAppend(setOf(newer), old)

        assertEquals(setOf(old, newer), combined)
        assertTrue(verifiedExportStagingHasFingerprint(combined, RecordingStorageType.FILE, id, oldFingerprint))
        assertTrue(verifiedExportStagingHasFingerprint(combined, RecordingStorageType.FILE, id, newFingerprint))
    }

}
