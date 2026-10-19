package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class VerifiedFlacMetadataCacheTest {
    @Test fun semanticProofRequiresAllDigestBytesAndExactLength() {
        val digest = CopyDigest(12345L, ByteArray(32) { (it + 71).toByte() })
        val info = FlacInfo(48000, 2, 16, 48000L, ByteArray(16) { 9 }, 90, 5)
        VerifiedFlacMetadataCache.remember(digest, info)
        assertEquals(1000L, VerifiedFlacMetadataCache.get(digest)?.durationMillis)
        assertNull(VerifiedFlacMetadataCache.get(digest.copy(byteCount = 12346L)))
        val changed = digest.sha256.clone().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertNull(VerifiedFlacMetadataCache.get(digest.copy(sha256 = changed)))
        assertNull(VerifiedFlacMetadataCache.get(CopyDigest(12345L, ByteArray(16))))
    }

    @Test fun retainedAndReturnedPcmDigestCannotBeMutatedByCallers() {
        val digest = CopyDigest(67890L, ByteArray(32) { (it + 13).toByte() })
        val info = FlacInfo(48000, 1, 16, 96000L, ByteArray(16) { 42 }, 90, 5)
        VerifiedFlacMetadataCache.remember(digest, info)
        info.md5[0] = 0
        val first = requireNotNull(VerifiedFlacMetadataCache.get(digest))
        assertEquals(42.toByte(), first.md5[0])
        first.md5[0] = 1
        assertEquals(42.toByte(), requireNotNull(VerifiedFlacMetadataCache.get(digest)).md5[0])
    }
}
