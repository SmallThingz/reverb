package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class FlacOutputBufferTest {
    @Test
    fun boundedHeapDirectReadOnlyAndSlicedBuffersWriteAndHashExactlyTheirRange() {
        val source = ByteArray(173) { (it * 71 + 3).toByte() }
        val seed = byteArrayOf(9, 8, 7)
        for (direct in listOf(false, true)) for (readOnly in listOf(false, true)) {
            for (sliced in listOf(false, true)) {
                val storage = if (direct) ByteBuffer.allocateDirect(source.size) else ByteBuffer.allocate(source.size)
                storage.put(source).clear()
                val input = if (sliced) {
                    storage.position(5).limit(source.size - 4)
                    storage.slice()
                } else storage
                input.position(7).limit(input.capacity() - 9)
                val bounded = if (readOnly) input.asReadOnlyBuffer() else input
                val beforePosition = bounded.position()
                val beforeLimit = bounded.limit()
                val expected = source.copyOfRange(
                    (if (sliced) 5 else 0) + beforePosition,
                    (if (sliced) 5 else 0) + beforeLimit,
                )
                val written = ByteArrayOutputStream()
                val digest = MessageDigest.getInstance("SHA-256").apply { update(seed) }
                val output = output { bytes ->
                    assertSame("The bounded codec buffer is written directly", bounded, bytes)
                    assertEquals(direct, bytes.isDirect)
                    assertEquals(readOnly, bytes.isReadOnly)
                    val part = ByteArray(minOf(11, bytes.remaining()))
                    bytes.get(part)
                    written.write(part)
                    part.size
                }

                assertEquals(expected.size, writeFlacOutputBuffer(output, bounded, digest))
                assertArrayEquals(expected, written.toByteArray())
                assertArrayEquals(sha256(seed + expected), digest.digest())
                assertEquals(beforeLimit, bounded.position())
                assertEquals(beforeLimit, bounded.limit())
                val retained = ByteArray(source.size)
                storage.duplicate().apply { clear() }.get(retained)
                assertArrayEquals("Writing and hashing must not modify codec bytes", source, retained)
            }
        }
    }

    @Test
    fun multipleBlocksAppendToTheDigestOnlyAfterCompleteWrites() {
        val blocks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7))
        val digest = MessageDigest.getInstance("SHA-256")
        val written = ByteArrayOutputStream()
        val output = output { bytes -> written.write(bytes.get().toInt()); 1 }
        var total = 0

        for (block in blocks) total += writeFlacOutputBuffer(output, ByteBuffer.wrap(block), digest)

        val expected = blocks[0] + blocks[1]
        assertEquals(expected.size, total)
        assertArrayEquals(expected, written.toByteArray())
        assertArrayEquals(sha256(expected), digest.digest())
    }

    @Test
    fun stalledAndThrownPartialWritesDoNotAdvanceTheDigest() {
        val source = ByteArray(19) { it.toByte() }
        val seed = byteArrayOf(5, 2, 9)
        for (failureKind in listOf("zero", "negative", "throw")) {
            val failure = IOException("write failed")
            val input = ByteBuffer.wrap(source)
            val digest = MessageDigest.getInstance("SHA-256").apply { update(seed) }
            val written = ByteArrayOutputStream()
            var writes = 0
            val output = output { bytes ->
                writes++
                if (writes == 1) {
                    val part = ByteArray(4)
                    bytes.get(part)
                    written.write(part)
                    4
                } else when (failureKind) {
                    "zero" -> 0
                    "negative" -> -1
                    else -> throw failure
                }
            }

            val caught = assertThrows(IOException::class.java) { writeFlacOutputBuffer(output, input, digest) }

            if (failureKind == "throw") assertSame(failure, caught)
            else assertEquals("FLAC output stalled", caught.message)
            assertEquals(2, writes)
            assertEquals(4, input.position())
            assertArrayEquals(source.copyOf(4), written.toByteArray())
            assertArrayEquals(sha256(seed), digest.digest())
        }
    }

    @Test
    fun interruptionBeforeOrBetweenWritesPreservesTheFlagAndDigest() {
        val source = ByteArray(19) { it.toByte() }
        val seed = byteArrayOf(7, 3)
        for (interruptBeforeWrite in listOf(false, true)) {
            val digest = MessageDigest.getInstance("SHA-256").apply { update(seed) }
            val input = ByteBuffer.wrap(source)
            var writes = 0
            val output = output { bytes ->
                writes++
                bytes.position(bytes.position() + 4)
                Thread.currentThread().interrupt()
                4
            }
            try {
                if (interruptBeforeWrite) Thread.currentThread().interrupt()

                assertThrows(InterruptedIOException::class.java) { writeFlacOutputBuffer(output, input, digest) }

                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(if (interruptBeforeWrite) 0 else 1, writes)
                assertEquals(if (interruptBeforeWrite) 0 else 4, input.position())
                assertArrayEquals(sha256(seed), digest.digest())
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun configurationAndEmptyBuffersKeepTheirExistingWriteBehavior() {
        val header = byteArrayOf(102, 76, 97, 67)
        val written = ByteArrayOutputStream()
        val output = output { bytes -> written.write(bytes.get().toInt()); 1 }
        assertEquals(header.size, writeFlacOutputBuffer(output, ByteBuffer.wrap(header)))
        assertArrayEquals(header, written.toByteArray())

        val digest = MessageDigest.getInstance("SHA-256").apply { update(header) }
        val empty = ByteBuffer.allocateDirect(8).apply { position(3); limit(3) }.asReadOnlyBuffer()
        assertEquals(0, writeFlacOutputBuffer(this.output { fail("Empty buffers must not write"); 0 }, empty, digest))
        assertEquals(3, empty.position())
        assertArrayEquals(sha256(header), digest.digest())
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun output(writeBytes: (ByteBuffer) -> Int): WavSeekableOutput = object : WavSeekableOutput {
        override fun write(buffer: ByteBuffer): Int = writeBytes(buffer)
        override fun position(position: Long) = fail("Position remains owned by the writer")
        override fun truncate(size: Long) = fail("Truncation remains owned by finalization")
        override fun force(metadata: Boolean) = fail("Sync remains owned by finalization")
        override fun close() = fail("Output release remains owned by the writer")
    }
}
