package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCompatibilityTest {
    private val id = "content://documents/tree/root/document/audio.wav"
    private val native = DocumentNativeIdentity(id, 4096L, 11L, 13L, 1234L, 5678L)

    @Test
    fun externalTreeSpellingIsNormalizedWithoutRelaxingJournalIdentity() {
        val canonical = "content://documents/tree/root%3Afolder"
        for (input in listOf(canonical, "content://documents/tree/root%3afolder", "content://documents/tree/%72oot:folder")) {
            assertEquals(canonical, canonicalDocumentTreeId(input))
        }
        assertFalse(documentTreeIdIsValid("content://documents/tree/root%3afolder"))
        assertTrue(documentTreeIdIsValid(canonical))
        assertEquals("content://documents/tree/a%2Bb", canonicalDocumentTreeId("content://documents/tree/a+b"))
        assertEquals("content://documents/tree/a%252Fb", canonicalDocumentTreeId("content://documents/tree/a%252Fb"))
        assertEquals("content://documents/tree/%EF%BF%BD", canonicalDocumentTreeId("content://documents/tree/%ef%bf%bd"))
    }

    @Test
    fun normalizationCannotIntroduceAnotherAuthorityOrDocumentScope() {
        for (invalid in listOf(
            "file:///tree/root", "content://doc%75ments/tree/root", "content://documents/tree/root?other=1",
            "content://documents/tree/root#other", "content://documents/tree/root/document/item",
            "content://documents/tree/root/", "content://documents/tree/", "content://documents/tree/%00",
            "content://documents/tree/%ff", "content://documents/tree/%",
        )) assertNull(invalid, canonicalDocumentTreeId(invalid))
    }

    @Test
    fun nativeIdentityRoundTripsAndOnlyExactRevisionMatchesReads() {
        val encoded = native.encode()
        assertEquals(native, parseDocumentNativeIdentity(encoded))
        assertTrue(providerRecordingIdentityMatches(encoded, encoded))
        for (changed in listOf(
            native.copy(sizeBytes = 4098L), native.copy(device = 12L), native.copy(inode = 14L),
            native.copy(changedSeconds = 1235L), native.copy(changedNanos = 5679L), native.copy(id = id + "2"),
        )) assertFalse(providerRecordingIdentityMatches(encoded, changed.encode()))
        assertFalse(providerRecordingIdentityMatches(encoded, "provider:2:encoded:4096:1234"))
    }

    @Test
    fun ownWritesMayChangeRevisionButCannotChangeObject() {
        assertTrue(sameProviderObjectAcrossMutation(native.encode(), native.copy(sizeBytes = 8192L, changedSeconds = 1240L).encode()))
        assertFalse(sameProviderObjectAcrossMutation(native.encode(), native.copy(inode = 14L).encode()))
        assertFalse(sameProviderObjectAcrossMutation(native.encode(), native.copy(id = id + "2").encode()))
    }

    @Test
    fun renamedNativeIdentityRemainsWithinItsOriginalTree() {
        assertTrue(documentProviderIdentitiesShareTree(native.encode(), native.copy(id = id + "2").encode()))
        assertFalse(documentProviderIdentitiesShareTree(native.encode(), native.copy(id = id.replace("/tree/root/", "/tree/other/")).encode()))
        assertFalse(documentProviderIdentitiesShareTree(native.encode(), native.copy(id = id.replace("//documents/", "//other/")).encode()))
    }

    @Test
    fun malformedNativeIdentityNeverGainsReadOrMutationAuthority() {
        for (invalid in listOf(
            "documentfd:", native.encode() + ":trailing", native.copy(sizeBytes = -1L).encode(),
            native.copy(inode = 0L).encode(), native.copy(changedSeconds = -1L).encode(),
            native.copy(changedNanos = 1_000_000_000L).encode(), native.copy(id = "content://documents/document/7").encode(),
        )) {
            assertNull(parseDocumentNativeIdentity(invalid))
            assertFalse(providerRecordingIdentityMatches(invalid, invalid))
            assertFalse(sameProviderObjectAcrossMutation(invalid, invalid))
        }
    }

    @Test
    fun verifiedNativeStagingJournalRoundTripsWithoutInventingAuthority() {
        val record = VerifiedExportStagingRecord(
            storageType = RecordingStorageType.DOCUMENT, id = id, byteCount = native.sizeBytes,
            sha256Hex = "ab".repeat(32), fileKey = null, providerIdentity = native.encode(),
        )
        val decoded = decodeVerifiedExportStagingRecord(encodeVerifiedExportStagingRecord(record))
        assertNotNull(decoded)
        assertEquals(record, decoded)
        assertFalse(verifiedExportStagingRecordMatches(record, StableOutputFingerprint(
            CopyDigest(native.sizeBytes, ByteArray(32)), fileKey = null, providerIdentity = native.encode(),
        )))
    }
}
