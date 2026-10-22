package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import org.junit.Assert.*
import org.junit.Test

class FlacReadBuffersTest {
    @Test fun codecSlicesReuseCapacityButPublishOnlyTheirCurrentLength() {
        var scratch: ByteArray? = null
        val actual = ByteArrayOutputStream()
        val expected = ByteArrayOutputStream()
        var allocations = 0
        for ((batch, count) in listOf(6, 2, 9, 3).withIndex()) {
            val pcm = ByteArray(count) { (batch * 16 + it).toByte() }
            val padded = byteArrayOf(0x55) + pcm + byteArrayOf(0x66)
            val source = ByteBuffer.wrap(padded).apply { position(1); limit(1 + count) }
            val before = scratch
            scratch = consumeFlacPcmBuffer(source, scratch) { bytes, offset, length ->
                assertEquals(0, offset)
                assertEquals(count, length)
                actual.write(bytes, offset, length)
            }
            if (before == null || before.size < count) {
                assertNotSame(before, scratch)
                allocations++
            } else assertSame(before, scratch)
            assertEquals(source.limit(), source.position())
            assertArrayEquals(byteArrayOf(0x55) + pcm + byteArrayOf(0x66), padded)
            expected.write(pcm)
        }
        assertEquals(2, allocations)
        assertArrayEquals(expected.toByteArray(), actual.toByteArray())
    }

    @Test fun callbackFailureStaysPrimaryAfterExactlyTheSelectedSliceIsConsumed() {
        val failure = IOException("consumer failed")
        val source = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 88)).apply { position(1); limit(4) }
        val scratch = ByteArray(8) { 0x7f }
        val actual = assertThrows(IOException::class.java) {
            consumeFlacPcmBuffer(source, scratch) { bytes, offset, count ->
                assertSame(scratch, bytes)
                assertEquals(0, offset)
                assertEquals(3, count)
                throw failure
            }
        }
        assertSame(failure, actual)
        assertEquals(4, source.position())
        assertArrayEquals(byteArrayOf(1, 2, 3, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f), scratch)
    }

    @Test fun channelReadUsesOneSizeObservationAndPreservesDestinationSlice() {
        val channel = CountingChannel(byteArrayOf(10, 11, 12, 13))
        val target = ByteArray(8) { 99 }
        assertEquals(2, readFlacChannelAt(channel, 2, target, 3, 4))
        assertArrayEquals(byteArrayOf(99, 99, 99, 12, 13, 99, 99, 99), target)
        assertEquals(1, channel.sizeCalls)
        assertEquals(1, channel.readCalls)
        assertEquals(-1, readFlacChannelAt(channel, 4, target, 0, 1))
        assertEquals(2, channel.sizeCalls)
        assertEquals(1, channel.readCalls)
        assertTrue(channel.isOpen)
    }

    @Test fun emptyAndInvalidReadsDoNotTouchTheDescriptor() {
        val channel = CountingChannel(byteArrayOf(1, 2, 3))
        val target = ByteArray(8)
        assertEquals(0, readFlacChannelAt(channel, Long.MAX_VALUE, target, target.size, 0))
        for ((position, offset, size) in listOf(
            Triple(-1L, 0, 1), Triple(0L, -1, 1), Triple(0L, 0, -1),
            Triple(0L, 7, 2), Triple(0L, Int.MAX_VALUE, Int.MAX_VALUE),
        )) assertThrows(IOException::class.java) { readFlacChannelAt(channel, position, target, offset, size) }
        assertEquals(0, channel.sizeCalls)
        assertEquals(0, channel.readCalls)
    }

    @Test fun sizeIsFreshForEachCallAndFailureDoesNotCloseTheOwnedChannel() {
        val channel = CountingChannel(byteArrayOf(1, 2, 3, 4))
        val target = ByteArray(4)
        assertEquals(2, readFlacChannelAt(channel, 2, target, 0, 4))
        channel.reportedSize = 2
        assertEquals(-1, readFlacChannelAt(channel, 2, target, 0, 4))
        val failure = IOException("size failed")
        channel.sizeFailure = failure
        assertSame(failure, assertThrows(IOException::class.java) { readFlacChannelAt(channel, 0, target, 0, 4) })
        assertEquals(3, channel.sizeCalls)
        assertEquals(1, channel.readCalls)
        assertTrue(channel.isOpen)
    }

    private class CountingChannel(private val bytes: ByteArray) : FileChannel() {
        var reportedSize = bytes.size.toLong()
        var sizeCalls = 0
        var readCalls = 0
        var sizeFailure: IOException? = null
        override fun size(): Long {
            sizeCalls++
            sizeFailure?.let { throw it }
            return reportedSize
        }
        override fun read(dst: ByteBuffer, position: Long): Int {
            readCalls++
            if (position >= bytes.size) return -1
            val count = minOf(dst.remaining(), bytes.size - position.toInt())
            dst.put(bytes, position.toInt(), count)
            return count
        }
        override fun implCloseChannel() = Unit
        override fun read(dst: ByteBuffer): Int = unsupported()
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = unsupported()
        override fun write(src: ByteBuffer): Int = unsupported()
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = unsupported()
        override fun write(src: ByteBuffer, position: Long): Int = unsupported()
        override fun position(): Long = unsupported()
        override fun position(newPosition: Long): FileChannel = unsupported()
        override fun truncate(size: Long): FileChannel = unsupported()
        override fun force(metaData: Boolean): Unit = unsupported()
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = unsupported()
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = unsupported()
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = unsupported()
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = unsupported()
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }
}
