package app.smallthingz.reverb

import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class PcmVerifiedSegmentBoundaryTest {
    private fun withDirectory(block: (File) -> Unit) {
        val parent = File("build/tmp/pcm-verified-boundaries").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun zeroPayloadStillOpensSeeksAndValidatesChecksum() = withDirectory { root ->
        val file = File(root, "empty.pcm").apply { writeBytes(ByteArray(0)) }
        assertArrayEquals(ByteArray(0), PcmNormalizer.readVerifiedSegment(file, 0, 0, 0, 0, 0))
        assertThrows(FileNotFoundException::class.java) {
            PcmNormalizer.readVerifiedSegment(File(root, "missing.pcm"), 0, 0, 0, 0, 0)
        }
        assertThrows(IOException::class.java) {
            PcmNormalizer.readVerifiedSegment(file, -1, 0, 0, 0, 0)
        }
        val failure = assertThrows(IOException::class.java) {
            PcmNormalizer.readVerifiedSegment(file, 0, 0, 1, 0, 0)
        }
        assertEquals("PCM chunk checksum mismatch: empty.pcm", failure.message)
    }

    @Test fun negativePayloadCannotUnderflowRangeAdmission() = withDirectory { root ->
        val missing = File(root, "must-not-open.pcm")
        for (payload in listOf(-1L, Long.MIN_VALUE, Long.MIN_VALUE + 1)) {
            for (count in listOf(0L, 1L, 2L)) {
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    PcmNormalizer.readVerifiedSegment(missing, 0, payload, 0, 0, count)
                }
                assertEquals("Negative PCM payload size", failure.message)
            }
        }
        assertFalse(missing.exists())
    }

    @Test fun scratchBoundariesPreserveBytesAndDetectTruncationAcrossTheWholePayload() = withDirectory { root ->
        for (size in listOf(1, 2048, 65535, 65536, 65537)) {
            val prefix = ByteArray(7) { 99 }
            val payload = ByteArray(size) { (it * 73 + it / 19).toByte() }
            val checksum = CRC32().apply { update(payload) }.value.toInt()
            val file = File(root, "payload-$size.pcm").apply { writeBytes(prefix + payload) }
            assertArrayEquals(payload, PcmNormalizer.readVerifiedSegment(file, 7, size.toLong(), checksum, 0, size.toLong()))
            assertArrayEquals(payload.copyOfRange(size - 1, size),
                PcmNormalizer.readVerifiedSegment(file, 7, size.toLong(), checksum, size - 1L, 1))
            assertArrayEquals(ByteArray(0), PcmNormalizer.readVerifiedSegment(file, 7, size.toLong(), checksum, size.toLong(), 0))
            file.writeBytes(prefix + payload.copyOf(size - 1))
            // Even an empty selected range must verify every byte in the recorded payload.
            assertThrows(EOFException::class.java) {
                PcmNormalizer.readVerifiedSegment(file, 7, size.toLong(), checksum, 0, 0)
            }
        }
    }
}
