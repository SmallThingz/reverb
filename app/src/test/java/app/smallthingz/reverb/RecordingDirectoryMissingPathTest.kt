package app.smallthingz.reverb

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDirectoryMissingPathTest {
    @Test
    fun listedRecordingDisappearingAtStreamOpenIsSkipped() = withDirectory { root, file ->
        val boundary = BoundaryFile(file, beforeStream = { Files.delete(file.toPath()) })
        assertEquals(emptyList<RecordingEntity>(), scan(root, boundary))
        assertTrue(boundary.streamBoundaryReached)
        assertFalse(file.exists())
    }

    @Test
    fun listedRecordingReplacedByDirectoryRemainsScopeFailure() = withDirectory { root, file ->
        val boundary = BoundaryFile(file, beforeStream = {
            Files.delete(file.toPath())
            Files.createDirectory(file.toPath())
        })
        val error = assertThrows(IOException::class.java) { scan(root, boundary) }
        assertTrue(boundary.streamBoundaryReached)
        assertTrue(error.cause is FileNotFoundException)
        assertTrue(error.message!!.startsWith("Unable to validate discovered recording"))
        assertTrue(file.isDirectory)
    }

    @Test
    fun unavailableAfterStreamOpenFailureRemainsScopeFailure() = withDirectory { root, file ->
        val boundary = BoundaryFile(file, beforeStream = { Files.delete(file.toPath()) }, denyAfterStream = true)
        val error = assertThrows(IOException::class.java) { scan(root, boundary) }
        assertTrue(boundary.streamBoundaryReached)
        assertTrue(error.cause is FileNotFoundException)
    }

    private fun scan(root: File, entry: File): Any? {
        val directory = object : File(root.path) {
            override fun listFiles(): Array<File> = arrayOf(entry)
        }
        return try {
            Class.forName("app.smallthingz.reverb.RecordingFilesKt")
                .getDeclaredMethod("listFileDirectoryRecordings", Context::class.java, File::class.java,
                    Map::class.java, Set::class.java)
                .apply { isAccessible = true }
                .invoke(null, ContextWrapper(null), directory, emptyMap<String, RecordingEntity>(), emptySet<String>())
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private inline fun withDirectory(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("recording-missing-path-").toFile()
        try {
            // These tests fail at stream open, before format decoding or Android context use.
            val file = File(root, "recording.wav").apply { writeBytes(ByteArray(128)) }
            block(root, file)
        } finally {
            root.deleteRecursively()
        }
    }
}
