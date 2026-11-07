package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeletionConsentTransactionTest {
    private val original = PendingDeletionIntent("content://media/external/audio/media/42", 4,
        "01".repeat(32), false, RecordingStorageType.MEDIASTORE)

    @Test fun approvedUnchangedProviderRetainsOriginalProofObject() {
        var reads = 0
        val accepted = revalidateConsentedDeletionIntent(original.id, original) {
            observeRecordingDeletionProof(original) {
                reads++
                CopyDigest(4, ByteArray(32) { 1 })
            } == RecordingDeletionProof.MATCH
        }
        assertSame(original, accepted)
        assertEquals(1, reads)
    }

    @Test fun stableChangedBytesOrSizeReportIdentityChangeWithOneRead() {
        for (digest in listOf(CopyDigest(4, ByteArray(32) { 2 }), CopyDigest(5, ByteArray(32) { 1 }))) {
            var reads = 0
            var proof = RecordingDeletionProof.UNAVAILABLE
            val accepted = revalidateConsentedDeletionIntent(original.id, original) {
                proof = observeRecordingDeletionProof(original) { reads++; digest }
                proof == RecordingDeletionProof.MATCH
            }
            assertNull(accepted)
            assertEquals(1, reads)
            assertEquals(RecordingDeletionProof.MISMATCH, proof)
            assertEquals(RecordingDeletionFailureReason.IDENTITY_CHANGED, recordingDeletionProofFailureReason(proof))
        }
    }

    @Test fun unavailableDigestDoesNotClaimContentChanged() {
        var reads = 0
        val proof = observeRecordingDeletionProof(original) { reads++; null }
        assertEquals(1, reads)
        assertEquals(RecordingDeletionProof.UNAVAILABLE, proof)
        assertEquals(RecordingDeletionFailureReason.VERIFICATION_FAILED, recordingDeletionProofFailureReason(proof))
    }

    @Test fun readFailureRetainsExactCauseWithoutClaimingContentChanged() {
        var reads = 0
        val failure = java.io.IOException("synthetic read failure")
        var observed: Throwable? = null
        val proof = observeRecordingDeletionProof(original, { observed = it }) { reads++; throw failure }
        assertEquals(1, reads)
        assertSame(failure, observed)
        assertEquals(RecordingDeletionProof.UNAVAILABLE, proof)
        assertEquals(RecordingDeletionFailureReason.VERIFICATION_FAILED, recordingDeletionProofFailureReason(proof))
    }

    private fun phoneIdentity(generation: Long) = mediaStoreProviderIdentityFromMetadata(
        "content://media/external/audio/media/10316", true, 1644, true,
        if (generation == 45897L) 1793552798 else 1793552852, true, generation)

    @Test fun phoneGenerationChangeAfterRejectedReadIsDiagnosedWithoutAnotherHash() {
        var reads = 0
        var probes = 0
        val proof = observeRecordingDeletionProof(original) { reads++; null }
        val diagnosed = diagnoseUnavailableDeletionProof(proof, phoneIdentity(45897)) { probes++; phoneIdentity(45908) }
        assertEquals(1, reads)
        assertEquals(1, probes)
        assertEquals(RecordingDeletionProof.IDENTITY_UNVERIFIED, diagnosed)
        assertEquals(RecordingDeletionFailureReason.IDENTITY_UNVERIFIED, recordingDeletionProofFailureReason(diagnosed))
        assertNull(revalidateConsentedDeletionIntent(original.id, original) { diagnosed == RecordingDeletionProof.MATCH })
    }

    @Test fun mediaStoreRevisionBasisFallbackDoesNotClaimChangedAudio() {
        val fallback = mediaStoreProviderIdentityFromMetadata(
            "content://media/external/audio/media/10316", true, 1644, true, 1793552798, false, 0)
        val proof = diagnoseUnavailableDeletionProof(RecordingDeletionProof.UNAVAILABLE, phoneIdentity(45897)) { fallback }
        assertEquals(RecordingDeletionProof.IDENTITY_UNVERIFIED, proof)
        assertEquals(RecordingDeletionFailureReason.IDENTITY_UNVERIFIED, recordingDeletionProofFailureReason(proof))
    }

    @Test fun documentMetadataToNativeFallbackDoesNotClaimChangedAudio() {
        val id = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsample.wav"
        val metadata = documentProviderIdentityFromMetadata(id, true, 1644, true, 1793552798000)
        val native = DocumentNativeIdentity(id, 1644, 1, 42, 1793552798, 0).encode()
        assertTrue(parseDocumentNativeIdentity(native) != null)
        val proof = diagnoseUnavailableDeletionProof(RecordingDeletionProof.UNAVAILABLE, metadata) { native }
        assertEquals(RecordingDeletionProof.IDENTITY_UNVERIFIED, proof)
        assertEquals(RecordingDeletionFailureReason.IDENTITY_UNVERIFIED, recordingDeletionProofFailureReason(proof))
    }

    @Test fun sameUnknownOrMalformedIdentityCannotAuthorizeOrClaimChange() {
        for (current in listOf(phoneIdentity(45897), "", "malformed", "provider:1:!:1644:45908")) {
            var probes = 0
            val diagnosed = diagnoseUnavailableDeletionProof(RecordingDeletionProof.UNAVAILABLE, phoneIdentity(45897)) {
                probes++; current
            }
            assertEquals(1, probes)
            assertEquals(RecordingDeletionProof.UNAVAILABLE, diagnosed)
        }
        assertEquals(RecordingDeletionProof.UNAVAILABLE,
            diagnoseUnavailableDeletionProof(RecordingDeletionProof.UNAVAILABLE, "malformed") { phoneIdentity(45908) })
    }

    @Test fun diagnosticFailurePreservesOriginalReadCause() {
        val originalFailure = java.io.IOException("original read failure")
        var observed: Throwable? = null
        var reads = 0
        var probes = 0
        val proof = observeRecordingDeletionProof(original, { observed = it }) { reads++; throw originalFailure }
        val diagnosed = diagnoseUnavailableDeletionProof(proof, phoneIdentity(45897)) {
            probes++; throw SecurityException("diagnostic unavailable")
        }
        assertEquals(1, reads)
        assertEquals(1, probes)
        assertSame(originalFailure, observed)
        assertEquals(RecordingDeletionProof.UNAVAILABLE, diagnosed)
    }

    @Test fun establishedDigestResultDoesNotRunDiagnosticProbe() {
        for (proof in listOf(RecordingDeletionProof.MATCH, RecordingDeletionProof.MISMATCH)) {
            assertEquals(proof, diagnoseUnavailableDeletionProof(proof, phoneIdentity(45897)) {
                error("no diagnostic probe for established result")
            })
        }
    }

    @Test fun sameUriReplacementBytesNeverBecomeFreshDeletionAuthority() {
        // A provider may reuse its URI and even its coarse metadata revision during the prompt.
        assertNull(revalidateConsentedDeletionIntent(original.id, original) {
            pendingDeletionMatchesDigest(original, 4, "02".repeat(32))
        })
        assertNull(revalidateConsentedDeletionIntent(original.id, original) {
            pendingDeletionMatchesDigest(original, 5, original.sha256Hex)
        })
    }

    @Test fun staleOrAlreadyCommittedProofDoesNotEvenReadProvider() {
        assertNull(revalidateConsentedDeletionIntent("content://media/other", original) {
            error("different target must not be queried")
        })
        assertNull(revalidateConsentedDeletionIntent(original.id, original.copy(assetDeleted = true)) {
            error("committed proof must not be reused")
        })
    }

    @Test fun replacementProviderIdentityCannotPassSelectedTargetCheckEvenWithSameBytes() {
        val selected = RecordingEntity(id = original.id, displayName = "original.wav", mimeType = "audio/wav",
            startedAtMillis = 1, durationMillis = 1, sizeBytes = 4, codecSummary = "PCM",
            storageType = RecordingStorageType.MEDIASTORE, directoryId = "Music/Reverb",
            fileIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode}:selected:4:1")
        assertTrue(sameRecordingActionTarget(selected, selected))
        assertFalse(sameRecordingActionTarget(selected,
            selected.copy(fileIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode}:selected:4:2")))
    }
}
