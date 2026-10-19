package app.smallthingz.reverb

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPublicationCollisionTest {
    @Test
    fun danglingFinalNamePublishesAlternateWithoutChangingSourceOrLink() {
        val parent = File("build/tmp/recording-publication-collision").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "dangling-").toFile()
        try {
            val bytes = byteArrayOf(9, 8, 7, 6, 5)
            val original = File(directory, "source.wav").apply { writeBytes(bytes) }
            val staged = File(directory, stagingOutputName("clip.wav", "collision-test")).apply {
                writeBytes(original.readBytes())
            }
            val collision = File(directory, "clip.wav")
            val linkTarget = File("missing-target.wav").toPath()
            Files.createSymbolicLink(collision.toPath(), linkTarget)
            assertFalse(collision.exists())
            assertTrue(Files.isSymbolicLink(collision.toPath()))
            var moveAttempts = 0

            val published = publishStagedFile(staged, "clip.wav", moveFile = { source, destination ->
                // Fail the old implementation without leaving a spinning test worker behind.
                check(++moveAttempts <= 2) { "Publication repeated the occupied dangling-link name" }
                Files.move(source.toPath(), destination.toPath())
            }).file

            assertEquals(1, moveAttempts)
            assertEquals("clip (2).wav", published.name)
            assertArrayEquals(bytes, published.readBytes())
            assertArrayEquals(bytes, original.readBytes())
            assertFalse(staged.exists())
            assertFalse(File(directory, linkTarget.toString()).exists())
            assertTrue(Files.isSymbolicLink(collision.toPath()))
            assertEquals(linkTarget, Files.readSymbolicLink(collision.toPath()))
        } finally {
            directory.deleteRecursively()
        }
    }
}
