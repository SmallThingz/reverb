package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurabilityInvariantTest {
    @Test
    fun databaseVersionOneMigration_preservesRowsWithAdditiveOnlySql() {
        val steps = recordingDatabaseMigrationSteps(1, RecordingDatabase.DATABASE_VERSION)

        assertEquals(
            listOf(
                RecordingDatabaseMigrationStep.ADD_LAST_SEEN,
                RecordingDatabaseMigrationStep.ADD_MISSING_SINCE,
            ),
            steps,
        )
        val sql = steps.flatMap(::recordingDatabaseMigrationSql)
        assertTrue(sql.isNotEmpty())
        sql.forEach { statement ->
            val normalized = statement.uppercase()
            assertFalse("Migration must never drop recording data: $statement", "DROP TABLE" in normalized)
            assertFalse("Migration must never delete recording data: $statement", "DELETE FROM" in normalized)
        }
        assertTrue(sql.any { RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS in it })
        assertTrue(sql.any { RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS in it })
    }

    @Test
    fun databaseMigration_refusesDowngradeAndUnknownVersions() {
        assertEquals(emptyList<RecordingDatabaseMigrationStep>(), recordingDatabaseMigrationSteps(2, 2))
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(2, 3) }
    }

    @Test
    fun copyDigest_copiesEveryByteAcrossBoundarySizes() {
        val source = ByteArray(262_147) { index -> ((index * 37 + 11) and 0xff).toByte() }
        val expectedDigest = sha256(ByteArrayInputStream(source)).sha256

        for (bufferSize in listOf(1, 3, 4_096, 131_072)) {
            val output = ByteArrayOutputStream(source.size)
            val digest = copyWithSha256(ByteArrayInputStream(source), output, bufferSize)

            assertEquals(source.size.toLong(), digest.byteCount)
            assertArrayEquals(source, output.toByteArray())
            assertArrayEquals(expectedDigest, digest.sha256)
        }
    }

    @Test
    fun copyDigest_detectsSameSizeContentCorruption() {
        val source = ByteArray(65_536) { index -> (index xor (index ushr 8)).toByte() }
        val altered = source.copyOf().also { bytes ->
            bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x40).toByte()
        }

        val sourceDigest = sha256(ByteArrayInputStream(source))
        val alteredDigest = sha256(ByteArrayInputStream(altered))

        assertEquals(sourceDigest.byteCount, alteredDigest.byteCount)
        assertFalse(sourceDigest.sha256.contentEquals(alteredDigest.sha256))
    }

    @Test
    fun supportedRecordingName_isCaseInsensitiveAndRejectsArtifacts() {
        assertTrue(isSupportedRecordingName("recording.wav"))
        assertTrue(isSupportedRecordingName("RECORDING.WAV"))
        assertFalse(isSupportedRecordingName("recording.wav.partial"))
        assertFalse(isSupportedRecordingName("recording.tmp"))
        assertFalse(isSupportedRecordingName("recording"))
    }
    @Test
    fun storageStrategy_switchesToUninstallPersistentMediaStoreAtAndroid10() {
        assertFalse(usesMediaStoreDefaultStorage(28))
        assertTrue(requiresLegacyPublicStoragePermission(28))
        assertTrue(usesMediaStoreDefaultStorage(29))
        assertFalse(requiresLegacyPublicStoragePermission(29))
        assertTrue(usesMediaStoreDefaultStorage(37))
        assertFalse(requiresLegacyPublicStoragePermission(37))
    }

    @Test
    fun sha256Range_hashesOnlyRequestedPayloadAndRejectsTruncation() {
        val prefix = ByteArray(44) { 0x55.toByte() }
        val payload = ByteArray(12_345) { index -> ((index * 19 + 7) and 0xff).toByte() }
        val suffix = ByteArray(9) { 0x33.toByte() }
        val all = prefix + payload + suffix

        val ranged = sha256Range(ByteArrayInputStream(all), prefix.size.toLong(), payload.size.toLong(), 257)
        val direct = sha256(ByteArrayInputStream(payload), 113)
        assertEquals(payload.size.toLong(), ranged.byteCount)
        assertArrayEquals(direct.sha256, ranged.sha256)

        assertThrows(IOException::class.java) {
            sha256Range(ByteArrayInputStream(all), prefix.size.toLong(), (payload.size + suffix.size + 1).toLong())
        }
    }

    @Test
    fun exportCleanup_neverDeletesVerifiedDataExceptExplicitPrecommitCancellation() {
        assertTrue(shouldDeleteExportTarget(cancelled = false, verifiedComplete = false, committed = false))
        assertFalse(shouldDeleteExportTarget(cancelled = false, verifiedComplete = true, committed = false))
        assertTrue(shouldDeleteExportTarget(cancelled = true, verifiedComplete = false, committed = false))
        assertTrue(shouldDeleteExportTarget(cancelled = true, verifiedComplete = true, committed = false))
        assertFalse(shouldDeleteExportTarget(cancelled = false, verifiedComplete = true, committed = true))
        assertFalse(shouldDeleteExportTarget(cancelled = true, verifiedComplete = true, committed = true))
    }

    @Test
    fun wavHeaderBuilder_emitsExactPcmAndFloatContainerMetadata() {
        val pcmDataBytes = 192_000L
        val pcm = buildWavHeaderBytes(48_000, 2, PcmSampleFormat.PCM_16, pcmDataBytes)
        assertEquals(44, pcm.size)
        assertArrayEquals("RIFF".toByteArray(), pcm.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), pcm.copyOfRange(8, 12))
        assertArrayEquals("data".toByteArray(), pcm.copyOfRange(36, 40))
        val pcmBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((36L + pcmDataBytes).toInt(), pcmBuffer.getInt(4))
        assertEquals(48_000, pcmBuffer.getInt(24))
        assertEquals(192_000, pcmBuffer.getInt(28))
        assertEquals(4, pcmBuffer.getShort(32).toInt())
        assertEquals(pcmDataBytes.toInt(), pcmBuffer.getInt(40))

        val floatDataBytes = 192_001L
        val floatHeader = buildWavHeaderBytes(48_000, 1, PcmSampleFormat.PCM_FLOAT, floatDataBytes)
        assertEquals(58, floatHeader.size)
        assertArrayEquals("fact".toByteArray(), floatHeader.copyOfRange(38, 42))
        assertArrayEquals("data".toByteArray(), floatHeader.copyOfRange(50, 54))
        val floatBuffer = ByteBuffer.wrap(floatHeader).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((50L + floatDataBytes + 1L).toInt(), floatBuffer.getInt(4))
        assertEquals((floatDataBytes / 4L).toInt(), floatBuffer.getInt(46))
        assertEquals(floatDataBytes.toInt(), floatBuffer.getInt(54))
    }

    @Test
    fun libraryPresentation_hidesMissingAndPendingRowsWithoutForgettingThem() {
        val base = RecordingEntity(
            id = "present", displayName = "present.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 1L, sizeBytes = 1L, codecSummary = "WAV",
            storageType = RecordingStorageType.FILE.name, directoryId = "dir", createdAtMillis = 1L,
        )
        val missing = base.copy(id = "missing", displayName = "missing.wav", missingSinceMillis = 5L)
        val pending = base.copy(id = "pending", displayName = "pending.wav")
        val all = listOf(base, missing, pending)

        assertEquals(listOf(base), visibleCatalogRecordings(all, setOf("pending")))
        assertEquals(3, all.size)
        assertEquals(5L, missing.missingSinceMillis)
    }

    @Test
    fun pendingMediaRecovery_requiresNonEmptyStructurallyReadableAudio() {
        assertFalse(canRecoverPendingMedia(0L, 1_000L))
        assertFalse(canRecoverPendingMedia(44L, 0L))
        assertFalse(canRecoverPendingMedia(0L, 0L))
        assertTrue(canRecoverPendingMedia(45L, 1L))
    }

}
