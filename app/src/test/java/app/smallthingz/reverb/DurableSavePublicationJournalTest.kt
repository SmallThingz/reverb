package app.smallthingz.reverb

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DurableSavePublicationJournalTest {
    private val firstJob = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
    private val secondJob = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
    private val digest = CopyDigest(1234L, ByteArray(32) { 0x42 })

    @Test
    fun publishedOrCompleteRecoveryRetiresOriginalUriMarkerByExactJobOwnership() {
        val staging = marker(RecordingStorageType.DOCUMENT,
            "content://example.documents/tree/root/document/staging", firstJob)
        val entries = setOf(encodeVerifiedExportStagingRecord(staging))
        // A URI-changing publication has a different output URI/revision. Its durable job UUID
        // transfers the old staging authority only after the coordinator rechecks the final bytes.
        val remaining = verifiedExportStagingEntriesAfterJobCompletion(entries, firstJob,
            RecordingStorageType.DOCUMENT, digest)
        assertNotNull(remaining)
        assertTrue(remaining!!.isEmpty())
        assertTrue(verifiedExportStagingEntriesAfterJobCompletion(remaining, firstJob,
            RecordingStorageType.DOCUMENT, digest)!!.isEmpty())
    }

    @Test
    fun staleCompletionCannotRetireAnotherJobAtReusedPathWithEqualBytes() {
        val path = "/managed/.reverb-staging.pending"
        val owned = encodeVerifiedExportStagingRecord(marker(RecordingStorageType.FILE, path, firstJob))
        val newer = encodeVerifiedExportStagingRecord(marker(RecordingStorageType.FILE, path, secondJob))
        val legacy = encodeVerifiedExportStagingRecord(marker(RecordingStorageType.FILE, path, null))
        val entries = setOf(owned, newer, legacy, "unrelated-corrupt-record")
        val remaining = verifiedExportStagingEntriesAfterJobCompletion(entries, firstJob, RecordingStorageType.FILE, digest)
        assertEquals(setOf(newer, legacy, "unrelated-corrupt-record"), remaining)
    }

    @Test
    fun conflictingSameJobAuthorityAndRecoverableMalformedJobRecordFailClosed() {
        val raw = encodeVerifiedExportStagingRecord(marker(RecordingStorageType.FILE, "/managed/staging", firstJob))
        assertNull(verifiedExportStagingEntriesAfterJobCompletion(setOf(raw), firstJob, RecordingStorageType.DOCUMENT, digest))
        assertNull(verifiedExportStagingEntriesAfterJobCompletion(setOf(raw), firstJob, RecordingStorageType.FILE,
            CopyDigest(digest.byteCount + 1, digest.sha256)))
        assertNull(verifiedExportStagingEntriesAfterJobCompletion(setOf(raw), firstJob, RecordingStorageType.FILE,
            CopyDigest(digest.byteCount, ByteArray(32) { 0x43 })))
        assertNull(verifiedExportStagingEntriesAfterJobCompletion(setOf(raw + "|torn"), firstJob,
            RecordingStorageType.FILE, digest))
    }

    @Test
    fun ownedStagingCannotBePublishedByGenericRecovery() {
        val record = marker(RecordingStorageType.FILE, "/managed/staging", firstJob)
        val raw = encodeVerifiedExportStagingRecord(record)
        assertEquals(firstJob, decodeVerifiedExportStagingRecord(raw)!!.jobId)
        val fingerprint = StableOutputFingerprint(digest, record.fileKey, null)
        assertNull(verifiedExportStagingRecoveryForFingerprint(setOf(raw), record.storageType, record.id, fingerprint))
    }

    private fun marker(storage: RecordingStorageType, id: String, job: String?) = VerifiedExportStagingRecord(
        storage, id, digest.byteCount, "42".repeat(32),
        fileKey = if (storage == RecordingStorageType.FILE) "stat:1:2:3:4:0" else null,
        providerIdentity = if (storage == RecordingStorageType.DOCUMENT) "provider-before-publication" else null,
        finalDisplayName = "saved.wav", jobId = job,
    )
}
