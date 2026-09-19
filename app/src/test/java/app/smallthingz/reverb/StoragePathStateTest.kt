package app.smallthingz.reverb

import java.io.File
import java.io.FileInputStream
import java.io.IOException
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
    fun recoverableStagingRead_rejectsSymbolicLinkBeforeReadingTarget() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "staging-link-").toFile()
        try {
            val payload = ByteArray(160)
            val target = File(root, "target.wav").apply {
                writeBytes(buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, payload.size.toLong()) + payload)
            }
            val link = File(root, ".reverb-export-test.pending")
            Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath())

            assertEquals(null, readRecoverableStagingFile(link))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverableStagingRead_acceptsStableRegularFile() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "staging-regular-").toFile()
        try {
            val payload = ByteArray(160)
            val file = File(root, ".reverb-export-test.pending").apply {
                writeBytes(buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, payload.size.toLong()) + payload)
            }
            val pathIdentity = resolveFileIdentity(file)
            val descriptorIdentity = FileInputStream(file).use { resolveFileDescriptorIdentity(it.fd) }
            val observation = readRecoverableStagingFile(file)
            if (!fileDescriptorIdentityMatches(pathIdentity, descriptorIdentity)) {
                assertEquals(null, observation)
            } else {
                val verified = requireNotNull(observation)
                assertEquals(10L, verified.durationMillis)
                assertEquals(file.length(), verified.digest.byteCount)
            }
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
    @Test
    fun managedDirectoryCreation_rejectsSymlinkedRootAndCreatesDirectDirectory() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "managed-dir-").toFile()
        try {
            val target = File(root, "target").apply { mkdir() }
            val link = File(root, "recordings")
            Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath())
            assertEquals(StoragePathState.UNAVAILABLE, storageDirectoryState(link))
            assertEquals("", resolveDirectoryIdentity(link))
            org.junit.Assert.assertThrows(IOException::class.java) {
                ensureDirectoryEntryNoFollow(link)
            }
            assertTrue(link.delete())

            assertTrue(ensureDirectoryEntryNoFollow(link))
            assertEquals(StoragePathState.PRESENT, storageDirectoryState(link))
            assertTrue(resolveDirectoryIdentity(link).isNotBlank())
            assertTrue(link.isDirectory)
            assertFalse(ensureDirectoryEntryNoFollow(link))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun managedDirectoryCreation_rejectsSymlinkedParent() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "managed-parent-").toFile()
        try {
            val targetParent = File(root, "target-parent").apply { mkdir() }
            val linkedParent = File(root, "linked-parent")
            Files.createSymbolicLink(linkedParent.toPath(), targetParent.toPath().toAbsolutePath())
            assertEquals(StoragePathState.UNAVAILABLE, storageDirectoryState(linkedParent))
            org.junit.Assert.assertThrows(IOException::class.java) {
                ensureDirectoryEntryNoFollow(File(linkedParent, "recordings"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicFileRegularBackingState_rejectsSymlinkedBackingEntries() {
        val parent = File("build/tmp/storage-path-state").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "atomic-nofollow-").toFile()
        try {
            val base = File(root, "state.bin")
            assertEquals(StoragePathState.MISSING, atomicFileRegularBackingState(base))

            val backup = File(base.path + ".bak").apply { writeBytes(byteArrayOf(1)) }
            assertEquals(StoragePathState.PRESENT, atomicFileRegularBackingState(base))
            assertTrue(backup.delete())

            base.writeBytes(byteArrayOf(2))
            assertEquals(StoragePathState.PRESENT, atomicFileRegularBackingState(base))
            val target = File(root, "outside.bin").apply { writeBytes(byteArrayOf(9)) }
            val newFile = File(base.path + ".new")
            Files.createSymbolicLink(newFile.toPath(), target.toPath().toAbsolutePath())
            assertEquals(StoragePathState.UNAVAILABLE, atomicFileRegularBackingState(base))
            assertTrue(newFile.delete())

            assertTrue(base.delete())
            Files.createSymbolicLink(base.toPath(), target.toPath().toAbsolutePath())
            assertEquals(StoragePathState.UNAVAILABLE, atomicFileRegularBackingState(base))
        } finally {
            root.deleteRecursively()
        }
    }

}
