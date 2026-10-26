package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStagingNameTest {
    private val session = "11111111-1111-1111-1111-111111111111"
    private val token = "22222222-2222-2222-2222-222222222222"

    private fun encoded(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun legacyName(value: String): String = "reverb-partial-export__old-session__token__${encoded(value)}.wav"

    private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun inDirectory(block: (File) -> Unit) {
        val parent = File("build/tmp/recording-staging-names").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "names-").toFile()
        try { block(directory) } finally { check(directory.deleteRecursively()) }
    }

    @Test
    fun malformedRecoveredBasenamesNeverReachPublicationMoveAndPreserveStaging() = inDirectory { directory ->
        val bytes = byteArrayOf(1, 3, 5, 7)
        val staged = File(directory, "preserved.partial").apply { writeBytes(bytes) }
        for (name in listOf("folder/clip.wav", "folder\\clip.wav", "clip\u0000.wav", "", " ", ".", "..")) {
            assertNull(resolveStagingOutputRecovery(legacyName(name)) { null }?.metadata)
            var recoveryReads = 0
            assertNull(resolveStagingOutputRecovery(legacyName(name)) { recoveryReads++; null })
            assertEquals(0, recoveryReads)
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
            assertEquals(listOf(staged.name), directory.listFiles().orEmpty().map { it.name })
        }
    }

    @Test
    fun legacyUnicodeNamesRemainExactAndMalformedUtf8IsRejected() {
        val name = "été 東京 🎙 session.wav"
        assertEquals(StagingOutputMetadata(StagingOutputKind.EXPORT, "old-session", name),
            resolveStagingOutputRecovery(legacyName(name)) { null }?.metadata)
        assertTrue(shouldRecoverStagingOutput(resolveStagingOutputRecovery(legacyName(name)) { null }?.metadata, "new-session"))
        val badUtf8 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(byteArrayOf(0xc3.toByte(), 0x28) + ".wav".toByteArray())
        assertNull(resolveStagingOutputRecovery("reverb-partial-export__old__token__$badUtf8.wav") { null }?.metadata)
        assertFalse(isDirectChildRecordingName("\uD800.wav"))
    }

    @Test
    fun asciiAndUnicodeByteBoundaryNamesCreateAndPublishOnTheActualFilesystem() = inDirectory { directory ->
        val names = listOf("a".repeat(113) + ".wav", "a".repeat(114) + ".wav",
            "é".repeat(56) + "a.wav", "é".repeat(57) + ".wav")
        assertEquals(listOf(117, 118, 117, 118), names.map(::utf8Size))
        names.forEachIndexed { index, name ->
            val generated = stagingOutputName(name, token, session, StagingOutputKind.EXPORT_TRACKED)
            assertTrue(utf8Size(generated) <= 255)
            if (utf8Size(name) == 117) {
                assertEquals(254, utf8Size(generated))
                assertEquals(name, resolveStagingOutputRecovery(generated) { null }?.metadata?.finalDisplayName)
            } else {
                assertNull(resolveStagingOutputRecovery(generated) { null }?.metadata)
                assertEquals(name, resolveStagingOutputRecovery(generated) {
                    VerifiedExportStagingRecovery(fingerprint(record(RecordingStorageType.FILE, name)), name)
                }?.metadata?.finalDisplayName)
            }
            val child = File(directory, "case-$index").apply { mkdir() }
            val created = createLocalStagingFile(child, name, StagingOutputKind.EXPORT_TRACKED, io = realCreationIo())
            assertTrue(utf8Size(created.file.name) <= 255)
            val bytes = byteArrayOf(index.toByte(), 7, 9)
            created.file.writeBytes(bytes)
            val published = publishStagedFile(created.file, name).file
            assertEquals(name, published.name)
            assertArrayEquals(bytes, published.readBytes())
        }
    }

    @Test
    fun renamedLongSourceSupportsGeneratedTrimRecompressionAndMoveNamesWithoutChangingSource() = inDirectory { directory ->
        val sourceName = "🎙".repeat(62) + "a.wav" // 253 UTF-8 bytes, with surrogate pairs intact.
        assertEquals(253, utf8Size(sourceName))
        val pcm = ByteArray(320) { (it * 7).toByte() }
        val bytes = buildWavHeaderBytes(16_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
        val source = File(directory, "short.wav").apply { writeBytes(bytes) }
        val renamed = File(directory, sourceName)
        Files.move(source.toPath(), renamed.toPath())
        // Exercise the name/creation/publication path used by these operations; codec and
        // retained-range content are covered separately by the trim/recompression fixtures.
        val trim = trimmedRecordingBaseName(renamed.name) + ".wav"
        val recompressed = generatedRecordingDisplayName(renamed.name.substringBeforeLast('.'),
            " recompressed", ".wav", reserveCollisionSuffix = true)
        assertTrue(trim.endsWith(" trim.wav") && recompressed.endsWith(" recompressed.wav"))
        assertTrue(utf8Size(trim) <= 242 && utf8Size(recompressed) <= 242)
        for ((index, name) in listOf(trim, recompressed, renamed.name).withIndex()) {
            val targetDirectory = File(directory, "output-$index").apply { mkdir() }
            val blockerBytes = byteArrayOf(99, 98)
            val blocker = File(targetDirectory, name).apply { writeBytes(blockerBytes) }
            val created = createLocalStagingFile(targetDirectory, name, StagingOutputKind.COPY, io = realCreationIo())
            created.file.writeBytes(renamed.readBytes())
            val published = publishStagedFile(created.file, name).file
            assertTrue(utf8Size(published.name) <= 255)
            assertTrue(published.name.endsWith(" (2).wav"))
            assertEquals(published.name, published.name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            if (name == trim) assertTrue(published.name.endsWith(" trim (2).wav"))
            if (name == recompressed) assertTrue(published.name.endsWith(" recompressed (2).wav"))
            assertArrayEquals(bytes, published.readBytes())
            assertArrayEquals(bytes, renamed.readBytes())
            assertArrayEquals(blockerBytes, blocker.readBytes())
        }
    }

    @Test
    fun finalCollisionAt255BytesPreservesExtensionAndExplicitFreeName() = inDirectory { directory ->
        val name = "a".repeat(251) + ".wav"
        assertEquals(255, utf8Size(name))
        assertEquals(name, findAvailableDisplayName(name) { false })
        val blocker = File(directory, name).apply { writeBytes(byteArrayOf(1)) }
        val created = createLocalStagingFile(directory, name, StagingOutputKind.COPY, io = realCreationIo())
        created.file.writeBytes(byteArrayOf(2, 3))
        val published = publishStagedFile(created.file, name).file
        assertEquals(255, utf8Size(published.name))
        assertEquals("a".repeat(247) + " (2).wav", published.name)
        assertArrayEquals(byteArrayOf(1), blocker.readBytes())
        assertArrayEquals(byteArrayOf(2, 3), published.readBytes())
    }

    @Test
    fun compactStagingWithoutVerificationStaysHiddenAndUntouched() = inDirectory { directory ->
        val name = "é".repeat(100) + ".wav"
        val stagedName = stagingOutputName(name, token, session, StagingOutputKind.EXPORT_TRACKED)
        val staged = File(directory, stagedName).apply { writeBytes(byteArrayOf(3, 1, 4)) }
        assertNull(resolveStagingOutputRecovery(stagedName) { null })
        assertFalse(shouldRecoverStagingOutput(resolveStagingOutputRecovery(stagedName) { null }?.metadata, "other-session"))
        assertArrayEquals(byteArrayOf(3, 1, 4), staged.readBytes())
        val copyName = stagingOutputName(name, token, session, StagingOutputKind.COPY)
        assertNull(resolveStagingOutputRecovery(copyName) { error("COPY must not obtain recovery authority") })
    }

    @Test
    fun v3NamesRequireMatchingContentIdentityAndHashAcrossEveryStorageKind() {
        val name = "é".repeat(100) + ".wav"
        val stagedName = stagingOutputName(name, token, session, StagingOutputKind.EXPORT_TRACKED)
        for (type in RecordingStorageType.entries) {
            val record = record(type, name)
            val fingerprint = fingerprint(record)
            val raw = encodeVerifiedExportStagingRecord(record)
            assertTrue(raw.startsWith("v3|"))
            assertEquals(record, decodeVerifiedExportStagingRecord(raw))
            fun resolve(entries: Set<String>, observed: StableOutputFingerprint = fingerprint) =
                resolveStagingOutputRecovery(stagedName) {
                    verifiedExportStagingRecoveryForFingerprint(entries, type, record.id, observed)
                }
            val recovered = requireNotNull(resolve(setOf(raw)))
            assertEquals(name, recovered.metadata.finalDisplayName)
            assertTrue(shouldRecoverStagingOutput(recovered.metadata, "new-session", recovered.fingerprint != null))
            assertNull(resolve(setOf(raw), fingerprint.copy(digest = CopyDigest(4, ByteArray(32) { 8 }))))
            assertNull(resolve(setOf(raw), fingerprint.copy(
                fileKey = fingerprint.fileKey?.replace(":2:", ":9:"),
                providerIdentity = fingerprint.providerIdentity?.replace(":old:", ":new:"))))
            assertNull(resolve(setOf(encodeVerifiedExportStagingRecord(record.copy(finalDisplayName = "different.wav")))))
            assertNull(resolve(setOf(encodeVerifiedExportStagingRecord(record.copy(finalDisplayName = null)))))
            assertNull(decodeVerifiedExportStagingRecord(encodeVerifiedExportStagingRecord(record.copy(finalDisplayName = "folder/clip.wav"))))
            assertNull(resolve(setOf(raw, encodeVerifiedExportStagingRecord(record.copy(finalDisplayName = "conflict.wav")))))
        }
    }

    @Test
    fun journalVersionsAndFingerprintBoundRetirementRemainCompatibleAndIdempotent() {
        for (type in RecordingStorageType.entries) {
            val record = record(type, "東京 recording.wav")
            val legacy = record.copy(finalDisplayName = null)
            val v2 = encodeVerifiedExportStagingRecord(legacy)
            assertTrue(v2.startsWith("v2|"))
            assertEquals(legacy, decodeVerifiedExportStagingRecord(v2))
            val legacyType = when (type) {
                RecordingStorageType.FILE -> "FILE"
                RecordingStorageType.DOCUMENT -> "DOCUMENT"
                RecordingStorageType.MEDIASTORE -> "MEDIASTORE"
            }
            val v1 = "v1|$legacyType|" + v2.split('|').drop(2).joinToString("|")
            assertEquals(legacy, decodeVerifiedExportStagingRecord(v1))
            val current = encodeVerifiedExportStagingRecord(record)
            val replacement = encodeVerifiedExportStagingRecord(record.copy(sha256Hex = "08".repeat(32)))
            val entries = setOf(v1, v2, current, replacement)
            val remaining = verifiedExportStagingEntriesAfterFingerprintRemoval(entries, type, record.id, fingerprint(record))
            assertEquals(setOf(replacement), remaining)
            assertEquals(remaining, verifiedExportStagingEntriesAfterFingerprintRemoval(remaining, type, record.id, fingerprint(record)))
            assertTrue(verifiedExportStagingRecoveryForFingerprint(remaining, type, record.id,
                fingerprint(record).copy(digest = CopyDigest(4, ByteArray(32) { 8 }))) != null)
        }
    }

    private fun record(type: RecordingStorageType, name: String): VerifiedExportStagingRecord {
        val id = when (type) {
            RecordingStorageType.FILE -> File("build/tmp/staging-record.wav").absolutePath
            RecordingStorageType.DOCUMENT -> "content://docs/tree/root/document/7"
            RecordingStorageType.MEDIASTORE -> "content://media/external/audio/media/7"
        }
        return VerifiedExportStagingRecord(type, id, 4, "07".repeat(32),
            if (type == RecordingStorageType.FILE) "stat:1:2:3:4:5" else null,
            if (type == RecordingStorageType.FILE) null else "provider:${type.storageCode.toInt()}:old:4:1", name)
    }

    private fun fingerprint(record: VerifiedExportStagingRecord) = StableOutputFingerprint(
        CopyDigest(4, ByteArray(32) { 7 }), record.fileKey, record.providerIdentity)

    private fun realCreationIo() = object : LocalStagingCreationIo {
        override fun openExclusive(file: File): LocalStagingCreationHandle {
            // Use the actual filesystem component limit; only Android descriptor identity
            // observation is replaced so this test can run on the JVM.
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
}
