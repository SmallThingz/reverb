package app.smallthingz.reverb

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class IdleConfigurationCheckpointTest {
    @Test fun idleSettingsDoNotRewriteAnUnchangedChunkIndex() = fixture { root ->
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
            val before = indexes(root)
            assertEquals(1, before.size) // Recovery checkpoint, not a second settings-only write.
            repeat(3) {
                store.configure(RetentionMode.SIZE, 0L, 16000, 2)
                store.configure(RetentionMode.TIME, 60L, 8000, 1)
            }
            assertEquals(before, indexes(root))
        }
    }

    @Test fun realFormatChangeStillCheckpointsSealedAudioAndSurvivesRestart() = fixture { root ->
        val pcm = ByteArray(512) { (it * 17).toByte() }
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
            store.append(pcm, 0, pcm.size)
            val before = indexes(root)
            store.configure(RetentionMode.SIZE, 65536L, 16000, 2)
            assertNotEquals(before, indexes(root))
            assertEquals(512L, store.countFilledBytes())
        }
        PersistentAudioChunkStore(root).use { recovered ->
            recovered.configure(RetentionMode.SIZE, 65536L, 16000, 2)
            val bytes = java.io.ByteArrayOutputStream()
            requireNotNull(recovered.acquireRange(0.0, recovered.durationSeconds())).use { lease ->
                lease.readNormalized(8000, 1, PcmSampleFormat.PCM_16) { array, offset, count ->
                    bytes.write(array, offset, count); count
                }
            }
            assertArrayEquals(pcm, bytes.toByteArray())
        }
    }

    private fun indexes(root: File): Map<String, List<Byte>> =
        listOf("index.a", "index.b").map { File(root, it) }.filter(File::isFile)
            .associate { it.name to it.readBytes().toList() }

    private fun fixture(test: (File) -> Unit) {
        val parent = File("build/tmp/idle-configuration-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
