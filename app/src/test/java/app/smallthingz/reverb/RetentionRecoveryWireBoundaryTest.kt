package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RetentionRecoveryWireBoundaryTest {
    // Fixed published v1 bytes, independently assembled with Python struct/zlib.
    private val published = "4e52545201000000010000003930000000000000b268de3a0000000031d4000000000000b0327976000000005d0a1d61"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val expected = RetentionConfiguration(RetentionMode.TIME, 12345L, 987654322L, 54321L, 1987654320L)

    @Test fun publishedBytesDecodeAndEncoderPreservesPublishedLayout() {
        assertEquals(expected, decodeRetentionRecoveryConfiguration(published))
        assertArrayEquals(published, encodeRetentionRecoveryConfiguration(expected))
    }

    @Test fun everyTruncationAndEachSingleBitCorruptionLosesAuthority() {
        for (length in 0 until published.size) {
            assertNull("truncation $length", decodeRetentionRecoveryConfiguration(published.copyOf(length)))
        }
        for (offset in published.indices) for (bit in 0..7) {
            val corrupt = published.copyOf()
            corrupt[offset] = (corrupt[offset].toInt() xor (1 shl bit)).toByte()
            assertNull("offset $offset bit $bit", decodeRetentionRecoveryConfiguration(corrupt))
        }
        assertNull(decodeRetentionRecoveryConfiguration(published + byteArrayOf(0)))
    }

    @Test fun validChecksumCannotAuthorizeUnknownMagicVersionOrMode() {
        for ((offset, value) in listOf(0 to 0, 4 to 0, 4 to 2, 8 to -1, 8 to 2, 8 to Int.MAX_VALUE)) {
            val bytes = changed { putInt(offset, value) }
            assertNull("offset $offset value $value", decodeRetentionRecoveryConfiguration(bytes))
        }
    }

    @Test fun validChecksumCannotAuthorizeAnyNegativeGeometryField() {
        for (offset in listOf(12, 20, 28, 36)) for (value in listOf(-1L, Long.MIN_VALUE)) {
            assertNull("offset $offset value $value", decodeRetentionRecoveryConfiguration(changed { putLong(offset, value) }))
        }
    }

    @Test fun zeroAndMaximumGeometryArePreservedWithoutArithmeticOrUnitConversion() {
        for (value in listOf(0L, Long.MAX_VALUE)) {
            val bytes = changed {
                putInt(8, 0)
                for (offset in listOf(12, 20, 28, 36)) putLong(offset, value)
            }
            val config = RetentionConfiguration(RetentionMode.SIZE, value, value, value, value)
            assertEquals(config, decodeRetentionRecoveryConfiguration(bytes))
            assertArrayEquals(bytes, encodeRetentionRecoveryConfiguration(config))
        }
    }

    @Test fun encoderRejectsEachNegativeGeometryBeforePublishingBytes() {
        val invalid = listOf(expected.copy(oneShotSeconds = -1), expected.copy(oneShotSizeBytes = -1),
            expected.copy(loopingSeconds = -1), expected.copy(loopingSizeBytes = -1))
        for (config in invalid) assertThrows(IllegalArgumentException::class.java) {
            encodeRetentionRecoveryConfiguration(config)
        }
    }

    private fun changed(edit: ByteBuffer.() -> Unit): ByteArray = published.copyOf().also { bytes ->
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.edit()
        val checksum = CRC32().apply { update(bytes, 0, 44) }.value.toInt()
        buffer.putInt(44, checksum)
    }
}
