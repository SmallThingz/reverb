package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DocumentFixtureCatalogObservationTest {
    private fun row(id: String) = RecordingEntity(id, id, "audio/wav", 1, 1, 4, "test",
        RecordingStorageType.DOCUMENT, "tree", "identity", createdAtMillis = 1, lastSeenAtMillis = 1)
    private fun proof(byte: Byte = 1, identity: String = "provider") =
        StableOutputFingerprint(CopyDigest(4, ByteArray(32) { byte }), null, identity)

    @Test fun mixedAvailabilityRetainsEveryRowInStableOrderWithoutInventingProof() {
        val a = row("a"); val b = row("b")
        val observed = observeDocumentFixtureCatalog(listOf(b, a)) { if (it.id == "a") null else proof() }
        assertEquals(listOf(a.toString(), b.toString()), observed.map { it.row })
        assertNull(observed[0].verifiedBytes); assertNull(observed[0].verifiedSha256)
        assertNull(observed[0].verifiedIdentity); assertEquals(4L, observed[1].verifiedBytes)
        assertEquals(observed, observeDocumentFixtureCatalog(listOf(a, b)) { if (it.id == "a") null else proof() })
    }

    @Test fun unavailableRowsStillDetectMetadataAdditionAndRemoval() {
        val a = row("a"); val before = observeDocumentFixtureCatalog(listOf(a)) { null }
        assertNotEquals(before, observeDocumentFixtureCatalog(listOf(a.copy(displayName = "changed"))) { null })
        assertNotEquals(before, observeDocumentFixtureCatalog(listOf(a, row("b"))) { null })
        assertNotEquals(before, observeDocumentFixtureCatalog(emptyList()) { null })
    }

    @Test fun lostRecoveredOrChangedPayloadProofNeverMatchesPriorObservation() {
        val rows = listOf(row("a")); val unverified = observeDocumentFixtureCatalog(rows) { null }
        val verified = observeDocumentFixtureCatalog(rows) { proof() }
        assertNotEquals(verified, unverified); assertNotEquals(unverified, verified)
        assertNotEquals(verified, observeDocumentFixtureCatalog(rows) { proof(2) })
        assertNotEquals(verified, observeDocumentFixtureCatalog(rows) { proof(identity = "replacement") })
        assertNotEquals(verified, observeDocumentFixtureCatalog(rows.map { it.copy(fileIdentity = "changed") }) { proof() })
    }

    @Test fun exclusionOnlyRemovesExactOwnedNewIdAndObservationErrorsPropagate() {
        val visited = mutableListOf<String>()
        val rows = listOf(row("old"), row("new"), row("new-other"))
        val result = observeDocumentFixtureCatalog(rows, "new") { visited += it.id; null }
        assertEquals(listOf("new-other", "old"), visited)
        assertEquals(2, result.size)
        val failure = IOException("provider observation failed")
        try { observeDocumentFixtureCatalog(rows) { throw failure }; fail("failure swallowed") }
        catch (actual: IOException) { assertSame(failure, actual) }
    }
}
