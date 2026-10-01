package app.smallthingz.reverb

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedProviderDigestCacheTest {
    private val storage = RecordingStorageType.MEDIASTORE
    private val id = "content://media/external/audio/media/${UUID.randomUUID()}"
    private val identity = "verified-provider-${UUID.randomUUID()}"
    private val revision = ProviderPayloadRevision(1L, 2L, 4096L, 1234L, 5678L)
    private val digest = CopyDigest(4096L, ByteArray(32) { it.toByte() })
    private fun fingerprint(value: CopyDigest = digest) =
        StableOutputFingerprint(value, fileKey = null, providerIdentity = identity)

    @Test fun cachedProofRequiresExactProviderAndNativeRevision() {
        VerifiedProviderDigestCache.remember(storage, id, fingerprint(), revision)
        assertNotNull(VerifiedProviderDigestCache.read(storage, id, identity, revision))
        assertNull(VerifiedProviderDigestCache.read(RecordingStorageType.DOCUMENT, id, identity, revision))
        assertNull(VerifiedProviderDigestCache.read(storage, id + "other", identity, revision))
        assertNull(VerifiedProviderDigestCache.read(storage, id, identity + "other", revision))
        for (changed in listOf(
            revision.copy(device = 9L), revision.copy(inode = 9L), revision.copy(sizeBytes = 4098L),
            revision.copy(changedSeconds = 1235L), revision.copy(changedNanos = 5679L),
        )) assertNull(VerifiedProviderDigestCache.read(storage, id, identity, changed))
        assertNull(VerifiedProviderDigestCache.read(storage, id, identity, null))
    }

    @Test fun missingNativeProofCannotPopulateCache() {
        VerifiedProviderDigestCache.remember(storage, id, fingerprint(), null)
        assertNull(VerifiedProviderDigestCache.read(storage, id, identity, revision))
        VerifiedProviderDigestCache.remember(storage, id, fingerprint(digest.copy(byteCount = 4095L)), revision)
        assertNull(VerifiedProviderDigestCache.read(storage, id, identity, revision))
        VerifiedProviderDigestCache.remember(storage, id, fingerprint(digest.copy(sha256 = ByteArray(31))), revision)
        assertNull(VerifiedProviderDigestCache.read(storage, id, identity, revision))
    }

    @Test fun cachedBytesCannotBeMutatedByCaller() {
        val expected = digest.sha256.clone()
        VerifiedProviderDigestCache.remember(storage, id, fingerprint(), revision)
        digest.sha256.fill(99)
        val first = requireNotNull(VerifiedProviderDigestCache.read(storage, id, identity, revision))
        assertArrayEquals(expected, first.sha256)
        first.sha256.fill(17)
        assertArrayEquals(expected, requireNotNull(VerifiedProviderDigestCache.read(storage, id, identity, revision)).sha256)
    }

    @Test fun fileOrIdentitylessProofCannotPopulateProviderCache() {
        VerifiedProviderDigestCache.remember(RecordingStorageType.FILE, id, fingerprint(), revision)
        assertNull(VerifiedProviderDigestCache.read(RecordingStorageType.FILE, id, identity, revision))
        VerifiedProviderDigestCache.remember(storage, id, fingerprint().copy(providerIdentity = ""), revision)
        assertNull(VerifiedProviderDigestCache.read(storage, id, "", revision))
    }

    @Test fun cacheIsBoundedAndEvictionOnlyRequiresReverification() {
        val ids = List(129) { id + it }
        ids.forEach { VerifiedProviderDigestCache.remember(storage, it, fingerprint(), revision) }
        assertNull(VerifiedProviderDigestCache.read(storage, ids.first(), identity, revision))
        assertNotNull(VerifiedProviderDigestCache.read(storage, ids.last(), identity, revision))
    }
}
