package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class PcmUpsamplingHaloTest {
    private val sourceRate = 12_000
    private val targetRate = 24_000
    private val source = pcm(0f, .75f, 0f, .75f)
    private val wholeExpected = pcm(0f, .375f, .75f, .375f, 0f, .375f, .75f, .75f)
    private val prefixExpected = pcm(0f, .375f, .75f, .375f)

    private fun pcm(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
        }.array()

    private fun withRoot(block: (File) -> Unit) {
        val parent = File(".tmp/pcm-upsampling-halo").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun normalize(file: File, checksum: Int, first: Int, frames: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val count = PcmNormalizer.normalizeSegment(file, 0, source.size.toLong(), checksum,
            first * 4L, frames.toLong(), sourceRate, 1, PcmSampleFormat.PCM_FLOAT,
            frames * 2L, 1, PcmSampleFormat.PCM_FLOAT, 8) { bytes, offset, size ->
            output.write(bytes, offset, size); size
        }
        assertEquals(frames * 8L, count)
        return output.toByteArray()
    }

    private fun read(lease: PersistentAudioChunkStore.RangeLease, budget: Long = Long.MAX_VALUE): ByteArray {
        val output = ByteArrayOutputStream()
        val result = lease.readNormalized(targetRate, 1, PcmSampleFormat.PCM_FLOAT, budget, 8) { bytes, offset, size ->
            assertTrue(size in 1..8)
            output.write(bytes, offset, size); size
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        return output.toByteArray()
    }

    @Test fun segmentUpsamplingUsesAvailableTrailingSampleAndClampsOnlyAtPayloadEnd() = withRoot { root ->
        val file = File(root, "source.pcm").apply { writeBytes(source) }
        val checksum = CRC32().apply { update(source) }.value.toInt()
        val whole = normalize(file, checksum, 0, 4)
        assertArrayEquals(wholeExpected, whole)
        val prefix = normalize(file, checksum, 0, 2)
        assertArrayEquals(prefixExpected, prefix)
        assertArrayEquals(whole.copyOfRange(0, 16), prefix)
        assertArrayEquals(pcm(.75f, .375f, 0f, .375f), normalize(file, checksum, 1, 2))
        assertArrayEquals(pcm(.75f, .75f), normalize(file, checksum, 3, 1))
    }

    @Test fun leasedUpsamplingPreservesPartialBoundaryAndNewestFrameBudget() = withRoot { root ->
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 4096, sourceRate, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(source.size, store.append(source, 0, source.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { whole ->
                assertArrayEquals(wholeExpected, read(whole))
                requireNotNull(whole.acquireSubRange(0.0, 2.0 / sourceRate)).use { prefix ->
                    assertArrayEquals(prefixExpected, read(prefix))
                    for (budget in 0L..4L) {
                        val bytes = read(prefix, budget)
                        assertEquals(budget * 4L, bytes.size.toLong())
                        assertArrayEquals(prefixExpected.copyOfRange(16 - budget.toInt() * 4, 16), bytes)
                    }
                }
                requireNotNull(whole.acquireSubRange(3.0 / sourceRate, whole.durationSeconds)).use { tail ->
                    assertArrayEquals(pcm(.75f, .75f), read(tail))
                }
            }
        }
    }

    @Test fun segmentHaloCannotBypassWholePayloadChecksum() = withRoot { root ->
        val checksum = CRC32().apply { update(source) }.value.toInt()
        val file = File(root, "source.pcm").apply {
            writeBytes(source.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        }
        var delivered = 0
        assertThrows(IOException::class.java) {
            PcmNormalizer.normalizeSegment(file, 0, source.size.toLong(), checksum, 0, 2,
                sourceRate, 1, PcmSampleFormat.PCM_FLOAT, 4, 1, PcmSampleFormat.PCM_FLOAT, 8) { _, _, size ->
                delivered += size; size
            }
        }
        assertEquals(0, delivered)
    }

    @Test fun leasedHaloVerifiesPayloadBeforeDeliveringNewestFrame() = withRoot { root ->
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 4096, sourceRate, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(source.size, store.append(source, 0, source.size))
            requireNotNull(store.acquireRange(0.0, 2.0 / sourceRate)).use { prefix ->
                val file = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
                val corrupt = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                file.writeBytes(corrupt)
                var delivered = 0
                assertThrows(IOException::class.java) {
                    prefix.readNormalized(targetRate, 1, PcmSampleFormat.PCM_FLOAT, 1, 8) { _, _, size ->
                        delivered += size; size
                    }
                }
                assertEquals(0, delivered)
            }
        }
    }

    @Test fun selectedPayloadEndDoesNotReadAcrossASeparateCaptureRun() = withRoot { root ->
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 4096, sourceRate, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(8, store.append(source, 0, 8))
            store.sealActiveChunk()
            assertEquals(8, store.append(source, 8, 8))
            requireNotNull(store.acquireRange(0.0, 2.0 / sourceRate)).use { firstRun ->
                val unrelated = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "1")
                val corrupt = unrelated.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                unrelated.writeBytes(corrupt)
                assertArrayEquals(pcm(0f, .375f, .75f, .75f), read(firstRun))
            }
        }
    }
}
