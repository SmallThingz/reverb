package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class DurableSaveJournalEncodingTest {
    @Test
    fun versionTwoEncodingMatchesRetainedPriorEncoderBytesExactly() = withJournal { journal, directory ->
        for (name in listOf("queued-v2.bin", "unicode-v2.bin", "legacy-pinning-v2.bin")) {
            val expected = golden(name)
            val record = journal.decode(expected, directory)
            assertEquals(JOB_ID, record.id)
            assertArrayEquals(name, expected, journal.encode(record))
            assertEquals(3, record.source.objects.size)
            assertEquals(1, record.source.range.segments.size)
            assertEquals(1, record.source.range.leadingContext.size)
            assertEquals(1, record.source.range.trailingContext.size)
        }
        assertEquals("Selected \u0000 \u03a9 \ud83d\ude00.flac",
            journal.decode(golden("unicode-v2.bin"), directory).request.name)
    }

    @Test
    fun retainedVersionOneDecodesToTheSameLocalPinsAndReencodesAsPriorVersionTwo() = withJournal { journal, directory ->
        val legacy = journal.decode(golden("legacy-pinning-v1.bin"), directory)
        val versionTwo = journal.decode(golden("legacy-pinning-v2.bin"), directory)
        assertEquals(versionTwo, legacy)
        assertEquals(DurableSaveStage.PINNING, legacy.stage)
        assertTrue(legacy.source.objects.all { it.sourcePath.isEmpty() &&
            it.sourceDirectoryIdentity.isEmpty() && it.storeDirectoryIdentity.isEmpty() })
        assertEquals(File(directory, "pcm-1"), legacy.source.range.segments.single().record.file)
        assertArrayEquals(golden("legacy-pinning-v2.bin"), journal.encode(legacy))
    }

    @Test
    fun largestBodyIncludesExactlyEightChecksumBytesAndRejectsAnyAdditionalPayload() {
        val payload = ByteArray(DurableSaveJournal.MAX_MANIFEST_BYTES - 8) { (it * 13).toByte() }
        val buffer = DurableSaveManifestBuffer()
        buffer.write(payload)
        assertThrows(IOException::class.java) { buffer.write(0) }
        assertThrows(IOException::class.java) { buffer.write(byteArrayOf(0), 0, 1) }
        val wire = buffer.finishWithChecksum()
        assertEquals(DurableSaveJournal.MAX_MANIFEST_BYTES, wire.size)
        for (index in payload.indices) {
            if (payload[index] != wire[index]) fail("Payload changed at byte $index")
        }
        val checksum = DataInputStream(ByteArrayInputStream(wire, payload.size, 8)).readLong()
        assertEquals(CRC32().apply { update(payload) }.value, checksum)
        assertEquals(payload.size, buffer.size())
    }

    @Test
    fun oversizedBulkWriteIsRejectedBeforeGrowthAndFinishingDoesNotExposeMutableBacking() {
        val buffer = DurableSaveManifestBuffer()
        buffer.write(byteArrayOf(1, 2, 3, 4))
        val before = buffer.finishWithChecksum()
        assertThrows(IOException::class.java) {
            buffer.write(ByteArray(DurableSaveJournal.MAX_MANIFEST_BYTES - 8), 0,
                DurableSaveJournal.MAX_MANIFEST_BYTES - 8)
        }
        assertEquals(4, buffer.size())
        assertArrayEquals(before, buffer.finishWithChecksum())
        before[0] = 99
        assertEquals(1.toByte(), buffer.finishWithChecksum()[0])
    }

    @Test
    fun fullGeometryAndFieldValidationStillPrecedeManifestCreation() = withJournal { journal, directory ->
        val record = journal.decode(golden("queued-v2.bin"), directory)
        val source = record.source
        val invalid = record.copy(source = source.copy(range = source.range.copy(segments =
            listOf(source.range.segments.single().copy(frameCount = Long.MAX_VALUE)))))
        assertThrows(IOException::class.java) { journal.write(invalid) }
        assertTrue(requireNotNull(directory.listFiles()).isEmpty())
        assertThrows(IOException::class.java) {
            journal.encode(record.copy(request = record.request.copy(name = "x".repeat(8193))))
        }
        assertThrows(IOException::class.java) {
            journal.encode(record.copy(source = source.copy(objects = emptyList())))
        }
        assertTrue(requireNotNull(directory.listFiles()).isEmpty())
    }

    @Test
    fun validationOnlyAcceptsCurrentAndLegacyGoldenBytesWithoutChangingEncoding() = withJournal { journal, directory ->
        for (name in listOf("queued-v2.bin", "unicode-v2.bin", "legacy-pinning-v2.bin", "legacy-pinning-v1.bin")) {
            val bytes = golden(name)
            val original = bytes.copyOf()
            val encoded = journal.encode(journal.decode(bytes, directory))
            journal.validateEncoded(bytes, directory)
            assertArrayEquals(original, bytes)
            assertArrayEquals(encoded, journal.encode(journal.decode(bytes, directory)))
        }
    }

    @Test
    fun validationOnlyRejectsTheSameMalformedPayloadsIncludingChecksummedTrailingData() = withJournal { journal, directory ->
        val good = golden("queued-v2.bin")
        val body = good.copyOf(good.size - 8) + byteArrayOf(42)
        val crc = CRC32().apply { update(body) }.value
        val trailing = body + ByteArray(8) { (crc ushr ((7 - it) * 8)).toByte() }
        val corrupt = good.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        for (bytes in listOf(corrupt, good.copyOf(15), good.copyOf(good.size - 1), trailing)) {
            val decoded = assertThrows(InvalidDurableSaveManifest::class.java) { journal.decode(bytes, directory) }
            val validated = assertThrows(InvalidDurableSaveManifest::class.java) { journal.validateEncoded(bytes, directory) }
            assertEquals(decoded.message, validated.message)
            assertEquals(decoded.cause?.javaClass, validated.cause?.javaClass)
        }
    }

    @Test
    fun validationOnlyStillRequiresFreshLegacyDirectoryAuthorityAfterSuccessfulValidation() = withJournal { journal, directory ->
        val bytes = golden("legacy-pinning-v1.bin")
        journal.validateEncoded(bytes, directory)
        assertTrue(directory.delete())
        val validated = assertThrows(InvalidDurableSaveManifest::class.java) { journal.validateEncoded(bytes, directory) }
        val decoded = assertThrows(InvalidDurableSaveManifest::class.java) { journal.decode(bytes, directory) }
        assertEquals("Save directory unavailable", validated.message)
        assertEquals(decoded.message, validated.message)
    }

    private fun golden(name: String): ByteArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/durable-save-journal/$name")) {
            "Missing retained prior-encoder fixture: $name"
        }.use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(requireNotNull(HASHES[name]), sha)
        return bytes
    }

    private fun withJournal(action: (DurableSaveJournal, File) -> Unit) {
        val root = Files.createTempDirectory("journal-encoding-golden").toFile().absoluteFile
        try {
            val journal = DurableSaveJournal(root)
            val directory = journal.directory(JOB_ID)
            assertTrue(directory.mkdir())
            action(journal, directory)
        } finally { root.deleteRecursively() }
    }

    private companion object {
        const val JOB_ID = "01234567-89ab-cdef-0123-456789abcdef"
        val HASHES = mapOf(
            "queued-v2.bin" to "2a32c38090ae073db0b94d2a2592a9435e49c643825dc0f54d151dc94ddb26f4",
            "unicode-v2.bin" to "9528a457a9d96c3ce32115fdda801c596b688623bb1ecdcad4a288a7b5324319",
            "legacy-pinning-v2.bin" to "037cb1d0846f866d031e7871e430c8eb59c3cb1fd32696864bd58ea77008ecb1",
            "legacy-pinning-v1.bin" to "e7e05ecb8769289086fb1c0f1bf54461dc43ab053f1e1cd05e315ad93879e989",
        )
    }
}
