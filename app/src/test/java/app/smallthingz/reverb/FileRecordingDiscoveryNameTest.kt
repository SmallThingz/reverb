package app.smallthingz.reverb

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRecordingDiscoveryNameTest {
    @Test
    fun acceptedDotPrefixedRenameNamesRemainDiscoverableWithoutCatalogRows() = inDirectory { directory ->
        val cases = listOf(
            Triple("source.wav", ".memo", ".memo.wav"),
            Triple("source.wav", ".wav", ".wav"),
            Triple("source.flac", ".memo", ".memo.flac"),
            Triple("source.flac", ".flac", ".flac"),
            Triple("source.wav", "ordinary", "ordinary.wav"),
        )
        cases.forEachIndexed { index, (originalName, request, expectedName) ->
            val child = File(directory, "rename-$index").apply { mkdir() }
            val bytes = recordingBytes(expectedName)
            val source = File(child, originalName).apply { writeBytes(bytes) }
            val accepted = requireNotNull(recordingRenameDisplayName(originalName, request, RecordingStorageType.FILE))
            assertEquals(expectedName, accepted)
            val renamed = File(child, accepted)
            Files.move(source.toPath(), renamed.toPath())
            // The production scanner applies this name admission before its unchanged
            // descriptor/structure checks. No catalog row participates in this listing.
            assertEquals(listOf(expectedName), child.listFiles()!!.filter {
                isDiscoverableFileRecordingName(it.name)
            }.map { it.name })
            assertArrayEquals(bytes, renamed.readBytes())
            FileInputStream(renamed).use { input ->
                if (expectedName.endsWith(".flac")) {
                    // Header inspection is host-only; native FLAC decode is not asserted here.
                    assertEquals(800L, readFlacInfo(input).frameCount)
                } else {
                    assertEquals(800L, readWavPcmLayout(input.channel).frameCount)
                }
            }
        }
    }

    @Test
    fun localPublicationAndCollisionKeepAcceptedHiddenFinalNamesDiscoverable() = inDirectory { directory ->
        for ((index, requested) in listOf(".memo.wav", ".memo.flac", ".wav", ".flac").withIndex()) {
            val child = File(directory, "publish-$index").apply { mkdir() }
            val accepted = normalizeFileRecordingDisplayName(requested)
            assertEquals(requested, accepted)
            val bytes = recordingBytes(accepted)
            val first = createLocalStagingFile(child, accepted, StagingOutputKind.EXPORT_TRACKED, io = realCreationIo())
            first.file.writeBytes(bytes)
            assertFalse(isDiscoverableFileRecordingName(first.file.name))
            val published = publishStagedFile(first.file, accepted).file
            assertEquals(accepted, published.name)
            val next = createLocalStagingFile(child, accepted, StagingOutputKind.COPY, io = realCreationIo())
            next.file.writeBytes(bytes)
            val collision = publishStagedFile(next.file, accepted).file
            assertTrue(collision.name != published.name)
            if (requested == ".wav" || requested == ".flac") {
                assertEquals("$FALLBACK_DISPLAY_NAME (2)$requested", collision.name)
            }
            assertTrue(isDiscoverableFileRecordingName(published.name))
            assertTrue(isDiscoverableFileRecordingName(collision.name))
            assertEquals(2, child.listFiles()!!.count { isDiscoverableFileRecordingName(it.name) })
            assertArrayEquals(bytes, published.readBytes())
            assertArrayEquals(bytes, collision.readBytes())
        }
    }

    @Test
    fun reservedAndMalformedArtifactsNeverGainFileDiscoveryAdmission() {
        val token = "22222222-2222-2222-2222-222222222222"
        val names = listOf(
            stagingOutputName(".memo.wav", token, "current-session", StagingOutputKind.EXPORT_TRACKED),
            stagingOutputName(".memo.flac", token, "old-session", StagingOutputKind.COPY),
            "reverb-partial-malformed.wav", "REVERB-PARTIAL-malformed.FLAC",
            ".reverb-delete-$token.pending", ".reverb-delete-$token.wav",
            ".reverb-delete-malformed.wav", ".REVERB-DELETE-.flac",
            ".reverb-delete-.pending.wav", ".nomedia", ".notes.txt",
            "folder/clip.wav", "folder\\clip.wav", "clip\u0000.wav",
        )
        for (name in names) assertFalse(name, isDiscoverableFileRecordingName(name))
    }

    @Test
    fun reservedFileFinalRequestsUseExistingNormalizationBeforeRenameOrPublication() = inDirectory { directory ->
        for ((index, requested) in listOf(
            ".reverb-delete-meeting.wav", ".REVERB-DELETE-.flac", ".reverb-delete-.pending.wav",
            "reverb-partial-malformed.wav", "REVERB-PARTIAL-malformed.FLAC",
        ).withIndex()) {
            val extension = requested.substringAfterLast('.')
            val accepted = normalizeFileRecordingDisplayName(requested)
            assertEquals("$FALLBACK_DISPLAY_NAME $requested", accepted)
            assertEquals(accepted, recordingRenameDisplayName("source.$extension", requested, RecordingStorageType.FILE))
            val child = File(directory, "reserved-$index").apply { mkdir() }
            val bytes = recordingBytes(accepted)
            val created = createLocalStagingFile(child, accepted, StagingOutputKind.COPY, io = realCreationIo())
            created.file.writeBytes(bytes)
            val published = publishStagedFile(created.file, accepted).file
            assertEquals(accepted, published.name)
            assertTrue(isDiscoverableFileRecordingName(published.name))
            assertArrayEquals(bytes, published.readBytes())
        }
    }

    @Test
    fun directReservedFilePublicationPreservesStagingBeforeAnyMove() = inDirectory { directory ->
        val bytes = recordingBytes("source.wav")
        val staged = File(directory, "preserved.partial").apply { writeBytes(bytes) }
        for (name in listOf(".reverb-delete-invalid.wav", ".REVERB-DELETE-.flac", "reverb-partial-invalid.wav")) {
            var moves = 0
            var fingerprintReads = 0
            assertThrows(IOException::class.java) {
                publishStagedFile(staged, name,
                    moveFile = { _, _ -> moves++ },
                    readFingerprint = { fingerprintReads++; null })
            }
            assertEquals(0, moves)
            assertEquals(0, fingerprintReads)
            assertArrayEquals(bytes, staged.readBytes())
            assertEquals(listOf(staged.name), directory.listFiles()!!.map { it.name })
        }
    }

    @Test
    fun fileArtifactReservationDoesNotRestrictProviderNames() {
        val name = ".reverb-delete-meeting.wav"
        assertTrue(isSupportedRecordingName(name))
        for (storage in listOf(RecordingStorageType.DOCUMENT, RecordingStorageType.MEDIASTORE)) {
            assertEquals(name, recordingRenameDisplayName("source.wav", name, storage))
        }
        assertEquals("$FALLBACK_DISPLAY_NAME $name", recordingRenameDisplayName("source.wav", name, RecordingStorageType.FILE))
        assertEquals(".wav (2)", findAvailableDisplayName(".wav") { it == ".wav" })
        assertEquals("$FALLBACK_DISPLAY_NAME (2).wav", findAvailableFileDisplayName(".wav") { it == ".wav" })
    }

    private fun recordingBytes(name: String): ByteArray = if (name.endsWith(".flac", ignoreCase = true)) {
        Base64.getMimeDecoder().decode(FLAC_FIXTURE)
    } else {
        val pcm = ByteArray(1_600) { (it * 7).toByte() }
        buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
    }

    private fun inDirectory(block: (File) -> Unit) {
        val parent = File("build/tmp/file-recording-discovery-names").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "names-").toFile()
        try { block(directory) } finally { check(directory.deleteRecursively()) }
    }

    private fun realCreationIo() = object : LocalStagingCreationIo {
        override fun openExclusive(file: File): LocalStagingCreationHandle {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            return object : LocalStagingCreationHandle {
                override fun descriptorIdentity() = "statfd:1:2:3:4"
                override fun close() = channel.close()
            }
        }
        override fun forceDirectory(directory: File) = forceRecordingDirectoryDurable(directory)
        override fun resolveIdentity(file: File): String {
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            return "stat:1:2:3:4:0"
        }
    }

    companion object {
        // Same real libFLAC 1.5.0 fixture as RecordingTrimContainerTest: 800 PCM16 mono frames at 8 kHz.
        private const val FLAC_FIXTURE = """
            ZkxhQwAAACIQABAAAAItAAItAfQA8AAAAyCmcK9nsbhUYEyNXctoCK5hhAAAKCAAAAByZWZlcmVuY2UgbGliRkxBQyAxLjUuMCAyMDI1
            MDIxMQAAAAD/+HQIAAMf9xT8GPw9FD///4f///4gEAgEAgAAAz4AA0EAgEAgEAgEAgEAgEAgEAgEAgEAgAf///4gEAgEAgEAgEAgAAAz4AA0
            EAgEAgEAgEAgEAgEAgEAgAf///4gEAgEAgEAgEAgEAgEAgAAAz4AA0EAgEAgEAgEAgEAgAf///4gEAgEAgEAgEAgEAgEAgEAgEAgAAAz4AA0
            EAgEAgEAgAf///4gEAgEAgEAgEAgEAgEAgEAgEAgEAgEAgAAAz4AA0EAgAf///w////wAAM+AANBAIBAIBAIBAIBAIBAIBAIBAIBAIBAIBAIBAA
            ////xAIBAIAAAM+AANBAIBAIBAIBAIBAIBAIBAIBAIBAIBAA////xAIBAIBAIBAIAAAM+AANBAIBAIBAIBAIBAIBAIBAIBAA////xAIBAIBAIBAIBA
            IBAIAAAM+AANBAIBAIBAIBAIBAIBAA////xAIBAIBAIBAIBAIBAIBAIBAIAAAM+AANBAIBAIBAIBAA////xAIBAIBAIBAIBAIBAIBAIBAIBAIBAIAA
            AM+AANBAIBAA////vAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEAAAAAAn3AAAAAUEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQAf///4gEAgAAAz4AA0
            EAgEAgEAgEAgEAgEAgEAgEAgEAgEAgAf///4gEAgEAgEAgAAAz4AA0EAgEAgEAgEAgEAgEAgEAgEAgAf///yc7Q==
        """
    }
}
