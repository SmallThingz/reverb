package app.smallthingz.reverb

import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DigestHexEncodingTest {
    @Test
    fun everyByteUsesTwoLowercaseAsciiDigits() {
        val expected =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
            "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" +
            "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
            "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
            "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
            "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
        assertEquals(expected, ByteArray(256) { it.toByte() }.toHexString())
        assertEquals("", byteArrayOf().toHexString())
    }

    @Test
    fun sha256VectorsRetainTheirPersistedRepresentation() {
        val digest = MessageDigest.getInstance("SHA-256")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest.digest(byteArrayOf()).toHexString(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest.digest("abc".toByteArray(Charsets.US_ASCII)).toHexString(),
        )
    }

    @Test
    fun randomInputsMatchLegacyEncodingWithoutChangingSourceBytes() {
        val random = Random(0x484558L)
        for (size in listOf(1, 2, 3, 16, 20, 32, 64, 65, 128, 256, 1024)) {
            repeat(4) {
                val bytes = ByteArray(size).also(random::nextBytes)
                val before = bytes.copyOf()
                val expected = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
                assertEquals("size=$size", expected, bytes.toHexString())
                assertArrayEquals(before, bytes)
            }
        }
    }
}
