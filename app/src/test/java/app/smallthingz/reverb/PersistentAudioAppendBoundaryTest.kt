package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PersistentAudioAppendBoundaryTest {
    @Test fun uninspectablePartialAppendBlocksPublicationUntilRecovery() = withRoot { root ->
        val prefix = byteArrayOf(1, 2, 3, 4)
        val attempted = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)
        val store = PersistentAudioChunkStore(root)
        store.use {
            configure(store)
            assertEquals(prefix.size, store.append(prefix, 0, prefix.size))
            store.checkpoint()
            val inspectionFailure = IOException("injected position failure")
            val injected = installOneFailure(store, 4, inspectionFailure)
            val writeFailure = assertThrows(IOException::class.java) {
                store.append(attempted, 0, attempted.size)
            }
            assertSame(injected, writeFailure)
            val boundaryFailure = writeFailure.suppressed.single()
            assertSame(inspectionFailure, boundaryFailure.cause)
            val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
            val rawAfterFailure = chunk.readBytes()
            assertArrayEquals(prefix + attempted.copyOf(4), rawAfterFailure.copyOfRange(128, rawAfterFailure.size))
            assertEquals(prefix.size.toLong(), store.countFilledBytes())
            assertSame(boundaryFailure, assertThrows(IOException::class.java) {
                store.append(attempted, 0, attempted.size)
            }.cause)
            assertSame(boundaryFailure, assertThrows(IOException::class.java) { store.syncActivePayloadToDisk() }.cause)
            assertSame(boundaryFailure, assertThrows(IOException::class.java) { store.checkpoint() }.cause)
            assertSame(boundaryFailure, assertThrows(IOException::class.java) { store.sealActiveChunk() }.cause)
            assertArrayEquals(rawAfterFailure, chunk.readBytes())
            assertSame(boundaryFailure, assertThrows(IOException::class.java) { store.close() }.cause)
            assertArrayEquals(rawAfterFailure, chunk.readBytes())
        }
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened)
            assertArrayEquals(prefix + attempted.copyOf(4), read(reopened))
        }
    }

    @Test fun failedAppendAtWholeFrameBoundariesKeepsExactlyWrittenPcmAndCanContinue() {
        for (written in listOf(0, 4)) withRoot { root ->
            val prefix = byteArrayOf(1, 2, 3, 4)
            val attempted = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)
            val suffix = byteArrayOf(21, 22, 23, 24)
            val expected = prefix + attempted.copyOf(written) + suffix
            PersistentAudioChunkStore(root).use { store ->
                configure(store)
                assertEquals(prefix.size, store.append(prefix, 0, prefix.size))
                val injected = installOneFailure(store, written)
                val failure = assertThrows(IOException::class.java) {
                    store.append(attempted, 0, attempted.size)
                }
                assertSame(injected, failure)
                assertEquals(0, failure.suppressed.size)
                assertEquals((prefix.size + written).toLong(), store.countFilledBytes())
                assertEquals(suffix.size, store.append(suffix, 0, suffix.size))
                assertEquals(expected.size.toLong(), store.countFilledBytes())
                assertArrayEquals(expected, read(store))
                assertTrue(File(root, "preserved").listFiles().orEmpty().none { ".partial-append" in it.name })
            }
            PersistentAudioChunkStore(root).use { reopened ->
                configure(reopened)
                assertEquals(expected.size.toLong(), reopened.countFilledBytes())
                assertArrayEquals(expected, read(reopened))
            }
        }
    }

    @Test fun invalidAppendGeometryDoesNotChangeStoredPcm() = withRoot { root ->
        val prefix = byteArrayOf(31, 32, 33, 34, 35, 36)
        val input = byteArrayOf(41, 42, 43, 44)
        PersistentAudioChunkStore(root).use { store ->
            configure(store)
            assertEquals(prefix.size, store.append(prefix, 0, prefix.size))
            for ((offset, count) in listOf(-1 to 2, 0 to -1, 3 to 2, Int.MAX_VALUE to 2, 0 to 3)) {
                assertThrows(IllegalArgumentException::class.java) { store.append(input, offset, count) }
                assertEquals(prefix.size.toLong(), store.countFilledBytes())
                assertArrayEquals(prefix, read(store))
            }
            assertEquals(0, store.append(input, input.size, 0))
            assertArrayEquals(prefix, read(store))
        }
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened)
            assertArrayEquals(prefix, read(reopened))
        }
    }

    private fun installOneFailure(
        store: PersistentAudioChunkStore,
        written: Int,
        inspectionFailure: IOException? = null,
    ): IOException {
        val recordField = PersistentAudioChunkStore::class.java.getDeclaredField("activeRecord").apply { isAccessible = true }
        val record = requireNotNull(recordField.get(store) as? PersistentAudioChunkStore.ChunkRecord)
        val accessField = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess").apply { isAccessible = true }
        val old = requireNotNull(accessField.get(store) as? RandomAccessFile)
        old.close()
        val injected = IOException("injected append failure")
        val failing = object : RandomAccessFile(record.file, "rw") {
            var first = true
            var failInspection = false
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (first) {
                    first = false
                    super.write(bytes, offset, written)
                    failInspection = inspectionFailure != null
                    throw injected
                }
                super.write(bytes, offset, count)
            }
            override fun getFilePointer(): Long {
                if (failInspection) {
                    failInspection = false
                    throw requireNotNull(inspectionFailure)
                }
                return super.getFilePointer()
            }
        }
        try {
            failing.seek(record.payloadOffsetBytes + record.payloadBytes)
            accessField.set(store, failing)
        } catch (error: Throwable) {
            try { failing.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            throw error
        }
        return injected
    }

    private fun configure(store: PersistentAudioChunkStore) =
        store.configure(RetentionMode.SIZE, 1024L, 8_000, 1, PcmSampleFormat.PCM_16)

    private fun read(store: PersistentAudioChunkStore): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use(::readStoreTestPcm16Mono8Khz)

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/critical-append-boundaries")
        Files.createDirectories(parent.toPath())
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        block(root)
        check(root.deleteRecursively()) { "Could not remove completed fixture: $root" }
    }
}
