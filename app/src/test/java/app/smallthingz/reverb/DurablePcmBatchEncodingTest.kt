package app.smallthingz.reverb

import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DurablePcmBatchEncodingTest {
    private val job = "00000000-0000-0000-0000-000000000001"
    private val proof = DurablePcmPins.ObjectProof("pcm-0", "stat:1:2:3:4:0", 128, 4096, -123456,
        "/tmp/room-\u00e9-\u4e2d/chunks/1", "stat:1:20:3:4:0", "stat:1:30:3:4:0")

    @Test fun fixedVersionOneWireBytesAndUnicodeRemainCompatible() {
        val encoded = DurablePcmBatchReferences.encode(job, listOf(proof))
        // Independent version-one big-endian/UTF/CRC fixture, not an encoder round-trip alone.
        assertEquals(162, encoded.size)
        assertEquals("4b1ea9fbf307a0c9eb73694501546ed244d0dde315aa6f76cc12d7de59d8e654", MessageDigest.getInstance("SHA-256").digest(encoded)
            .joinToString("") { "%02x".format(it) })
        assertEquals(mapOf(proof.sourcePath to proof), DurablePcmBatchReferences.decode(encoded, job))
        val corrupted = encoded.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { DurablePcmBatchReferences.decode(corrupted, job) }
    }

    @Test fun metadataLargerThanEightMiBStillFailsBeforePublication() {
        val parent = "/tmp/" + "s".repeat(4096) + "/chunks/"
        val proofs = List(2048) { index -> proof.copy(name = "pcm-$index", sourcePath = parent + (index + 1)) }
        assertThrows(IOException::class.java) { DurablePcmBatchReferences.encode(job, proofs) }
    }
}
