package app.smallthingz.reverb

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePathStateTest {
    @Test
    fun observeStoragePath_doesNotFollowSymbolicLinks() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "nofollow-").toFile()
        try {
            val target = File(root, "target.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val link = File(root, "link.wav")
            Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath())

            val targetObservation = observeStoragePath(target)
            assertEquals(StoragePathState.PRESENT, targetObservation.state)
            assertTrue(targetObservation.isRegularFile)

            val linkObservation = observeStoragePath(link)
            assertEquals(StoragePathState.PRESENT, linkObservation.state)
            assertFalse(linkObservation.isRegularFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileIdentityAndVerifiedReads_rejectSymbolicLinksBeforeAuthority() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "identity-link-").toFile()
        try {
            val target = File(root, "target.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val targetIdentity = resolveFileIdentity(target)
            assertTrue(targetIdentity.isNotBlank())
            val link = File(root, "recording.wav")
            Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath())

            assertEquals("", resolveFileIdentity(link))
            assertEquals(RecordingAssetState.MISSING, fileRecordingAssetState(link))
            assertEquals(null, readStableFileOutputFingerprint(link))
            val recording = RecordingEntity(
                id = link.absolutePath,
                displayName = link.name,
                mimeType = "audio/wav",
                startedAtMillis = 0L,
                durationMillis = 1L,
                sizeBytes = target.length(),
                codecSummary = "PCM",
                storageType = RecordingStorageType.FILE,
                directoryId = root.absolutePath,
                fileIdentity = targetIdentity,
            )
            assertEquals(null, openVerifiedFileInputStream(recording))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun observeStoragePath_danglingSymbolicLinkRemainsPresentAndNonRegular() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "dangling-").toFile()
        try {
            val missingTarget = File(root, "missing.wav")
            val link = File(root, "dangling.wav")
            Files.createSymbolicLink(link.toPath(), missingTarget.toPath().toAbsolutePath())

            val observation = observeStoragePath(link)
            assertEquals(StoragePathState.PRESENT, observation.state)
            assertFalse(observation.isRegularFile)
            assertEquals(StoragePathState.PRESENT, storagePathState(link))
        } finally {
            root.deleteRecursively()
        }
    }
}
