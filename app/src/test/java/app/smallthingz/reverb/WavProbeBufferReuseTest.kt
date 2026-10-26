package app.smallthingz.reverb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.*
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

class WavProbeBufferReuseTest {
    @Test fun actualWavSamplerMatchesScalarReadsAndReusesOneWrapperPerPass() {
        for (format in PcmSampleFormat.values()) for (channels in 1..2) for (frames in listOf(3, 113)) {
            compare(format, channels, frames, RangeWaveformPass.COARSE)
        }
        compare(PcmSampleFormat.PCM_16, 2, 113, RangeWaveformPass.DETAIL)
    }

    private fun compare(format: PcmSampleFormat, channels: Int, frames: Int, pass: RangeWaveformPass) {
        val offset = 17L
        val payload = frames * channels * format.bytesPerSample
        val bytes = ByteArray(offset.toInt() + payload) { (it * 73 + 19).toByte() }
        val layout = WavPcmLayout(48000, channels, format, offset, payload.toLong())
        val old = ProbeChannel(bytes)
        val new = ProbeChannel(bytes)
        val oldBuckets = ArrayList<Pair<Int, Int>>()
        val newBuckets = ArrayList<Pair<Int, Int>>()
        val expected = samplePcmWaveformEnvelopeProgressive(layout, pass, { first, count, scratch ->
            baselineReadAt(old, offset + first * layout.frameBytes, scratch, count * layout.frameBytes)
        }) { bucket, value -> oldBuckets.add(bucket to value.toRawBits()); bucket < 11 }
        val actual = sampleWavWaveformEnvelopeProgressive(new, layout, pass) { bucket, value ->
            newBuckets.add(bucket to value.toRawBits()); bucket < 11
        }
        assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
        assertEquals(oldBuckets, newBuckets)
        assertEquals(old.reads, new.reads)
        assertTrue(old.buffers.size > 1)
        assertEquals(1, new.buffers.size)
        assertTrue(new.isOpen)
    }

    @Test fun repeatedGenericReadsResetPositionAndLimitAndLeaveSuffixUntouched() {
        val channel = ProbeChannel(ByteArray(31) { it.toByte() })
        val target = ByteArray(12) { 99 }
        requireReadAt(channel, 4, target, 11)
        assertArrayEquals(ByteArray(12) { if (it < 11) (it + 4).toByte() else 99 }, target)
        requireReadAt(channel, 1, target, 2)
        assertEquals(1, target[0].toInt()); assertEquals(2, target[1].toInt())
        assertEquals(6, target[2].toInt()); assertEquals(99, target[11].toInt())
        val calls = channel.reads.size
        requireReadAt(channel, Long.MAX_VALUE, target, 0)
        assertEquals(calls, channel.reads.size)
        assertThrows(IllegalArgumentException::class.java) { requireReadAt(channel, 0, target, -1) }
        assertThrows(IllegalArgumentException::class.java) { requireReadAt(channel, 0, target, 13) }
    }

    @Test fun shortReadEofZeroAndOriginalFailureAreNotHidden() {
        for (zero in listOf(false, true)) {
            val channel = ProbeChannel(byteArrayOf(11, 22, 33)).apply { zeroAtEnd = zero }
            val target = ByteArray(5) { 99 }
            val failure = assertThrows(IOException::class.java) { requireReadAt(channel, 0, target, 5) }
            assertEquals("Unexpected end of WAV file", failure.message)
            assertArrayEquals(byteArrayOf(11, 22, 33, 99, 99), target)
            assertTrue(channel.isOpen)
        }
        val failure = IOException("provider read failure")
        val channel = ProbeChannel(ByteArray(8)).apply { readFailure = failure }
        assertSame(failure, assertThrows(IOException::class.java) { requireReadAt(channel, 0, ByteArray(8), 8) })
    }

    // Exact 7d894ce requireReadAt scalar implementation, renamed only.
    private fun baselineReadAt(channel: FileChannel, position: Long, bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size)
        var readTotal = 0
        while (readTotal < count) {
            val buffer = ByteBuffer.wrap(bytes, readTotal, count - readTotal)
            val read = channel.read(buffer, position + readTotal)
            if (read <= 0) throw IOException("Unexpected end of WAV file")
            readTotal += read
        }
    }

    private class ProbeChannel(private val bytes: ByteArray) : FileChannel() {
        val buffers = Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())
        val reads = ArrayList<Triple<Long, Int, Int>>()
        var zeroAtEnd = false
        var readFailure: IOException? = null
        override fun read(dst: ByteBuffer, position: Long): Int {
            buffers.add(dst)
            reads.add(Triple(position, dst.position(), dst.limit()))
            readFailure?.let { throw it }
            if (position >= bytes.size) return if (zeroAtEnd) 0 else -1
            val count = minOf(7, dst.remaining(), bytes.size - position.toInt())
            dst.put(bytes, position.toInt(), count)
            return count
        }
        override fun size(): Long = bytes.size.toLong()
        override fun implCloseChannel() = Unit
        override fun read(dst: ByteBuffer): Int = unsupported()
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = unsupported()
        override fun write(src: ByteBuffer): Int = unsupported()
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = unsupported()
        override fun write(src: ByteBuffer, position: Long): Int = unsupported()
        override fun position(): Long = unsupported()
        override fun position(newPosition: Long): FileChannel = unsupported()
        override fun truncate(size: Long): FileChannel = unsupported()
        override fun force(metaData: Boolean) = Unit
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = unsupported()
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = unsupported()
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = unsupported()
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = unsupported()
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }
}
