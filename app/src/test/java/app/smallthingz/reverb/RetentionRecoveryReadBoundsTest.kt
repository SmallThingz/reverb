package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetentionRecoveryReadBoundsTest {
    private fun encoded(): ByteArray = encodeRetentionRecoveryConfiguration(
        RetentionConfiguration(
            mode = RetentionMode.SIZE,
            oneShotSeconds = 11L,
            oneShotSizeBytes = 22L,
            loopingSeconds = 33L,
            loopingSizeBytes = 44L,
        ),
    )

    @Test
    fun boundedRead_acceptsExactRecoverySizeAndPreservesShortInput() {
        val encoded = encoded()
        assertArrayEquals(encoded, readBoundedRetentionRecoveryBytes(ByteArrayInputStream(encoded)))

        val short = encoded.copyOf(encoded.size - 1)
        assertArrayEquals(short, readBoundedRetentionRecoveryBytes(ByteArrayInputStream(short)))
    }

    @Test
    fun boundedRead_rejectsOversizedRecoveryWithoutReadingPastSentinelByte() {
        val encoded = encoded()
        val source = CountingRepeatedByteInputStream()
        assertNull(readBoundedRetentionRecoveryBytes(source))
        assertEquals(encoded.size + 1, source.bytesRead)
    }

    @Test
    fun boundedRead_forcesProgressAfterZeroLengthRead() {
        val encoded = encoded()
        val source = ZeroOnceInputStream(encoded)
        assertArrayEquals(encoded, readBoundedRetentionRecoveryBytes(source))
    }

    private class CountingRepeatedByteInputStream : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int {
            bytesRead++
            return 0x5a
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            repeat(length) { buffer[offset + it] = 0x5a.toByte() }
            bytesRead += length
            return length
        }
    }

    private class ZeroOnceInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        private var zeroPending = true
        private var index = 0

        override fun read(): Int {
            if (index >= bytes.size) return -1
            return bytes[index++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (zeroPending) {
                zeroPending = false
                return 0
            }
            if (index >= bytes.size) return -1
            val count = minOf(length, bytes.size - index)
            bytes.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }
    }
}
