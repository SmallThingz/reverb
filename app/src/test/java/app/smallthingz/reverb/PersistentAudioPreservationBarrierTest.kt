package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import org.junit.Assert.*
import org.junit.Test

class PersistentAudioPreservationBarrierTest {
    @Test fun movedRecoveryArtifactRetainsOwnershipUntilBothDirectoryBarriersSucceed() = withRoot { root ->
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        PersistentAudioChunkStore(root).use { store ->
            configure(store)
            assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
            store.sealActiveChunk()
        }
        val chunks = File(root, BUFFER_CHUNKS_FOLDER_NAME)
        val healthy = File(chunks, "0")
        val healthyBytes = healthy.readBytes()
        val healthyKey = key(healthy)
        val source = File(chunks, "mystery.partial")
        val artifactBytes = byteArrayOf(91, 92, 93, 94, 95)
        source.writeBytes(artifactBytes)
        val artifactKey = key(source)
        val failure = IOException("injected preserved directory barrier failure")
        var movedTarget: File? = null
        var failBarrier = true
        val successfulBarriers = mutableListOf<String>()
        val store = PersistentAudioChunkStore(root,
            preservationMove = { from, to ->
                assertEquals(source, from)
                Files.move(from.toPath(), to.toPath())
                movedTarget = to
            },
            directorySync = { directory ->
                if (movedTarget != null && directory.name == "preserved" && failBarrier) throw failure
                forceRecordingDirectoryDurable(directory)
                if (movedTarget != null && !failBarrier) successfulBarriers.add(directory.name)
            },
        )
        store.use {
            assertSame(failure, assertThrows(IOException::class.java) { configure(store) })
            val target = requireNotNull(movedTarget)
            fun assertRetained() {
                assertFalse(source.exists())
                assertTrue(target.isFile)
                assertEquals(artifactKey, key(target))
                assertArrayEquals(artifactBytes, target.readBytes())
                assertEquals(healthyKey, key(healthy))
                assertArrayEquals(healthyBytes, healthy.readBytes())
            }
            assertRetained()
            assertSame(failure, assertThrows(IOException::class.java) { configure(store) })
            assertRetained()
            failBarrier = false
            configure(store)
            assertTrue(successfulBarriers.size >= 2)
            assertEquals(listOf("preserved", chunks.name), successfulBarriers.take(2))
            assertRetained()
            assertArrayEquals(pcm, read(store))
        }
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened)
            assertArrayEquals(pcm, read(reopened))
        }
        val target = requireNotNull(movedTarget)
        assertEquals(artifactKey, key(target))
        assertArrayEquals(artifactBytes, target.readBytes())
    }

    private fun key(file: File): Any = requireNotNull(
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey(),
    ) { "Filesystem does not provide object identity: $file" }

    private fun configure(store: PersistentAudioChunkStore) =
        store.configure(RetentionMode.SIZE, 1024L, 8_000, 1, PcmSampleFormat.PCM_16)

    private fun read(store: PersistentAudioChunkStore): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use(::readStoreTestPcm16Mono8Khz)

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/critical-preservation-barrier")
        Files.createDirectories(parent.toPath())
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        block(root)
        check(root.deleteRecursively()) { "Could not remove completed fixture: $root" }
    }
}
