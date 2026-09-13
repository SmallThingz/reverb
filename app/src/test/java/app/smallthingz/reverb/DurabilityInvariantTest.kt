package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.nio.file.Files
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
                RecordingDatabaseMigrationStep.ADD_FILE_IDENTITY,
                RecordingDatabaseMigrationStep.ADD_WAVEFORM_CACHE,
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
        assertTrue(sql.any { RecordingDatabase.COLUMN_FILE_IDENTITY in it })
        assertTrue(sql.any { RecordingDatabase.COLUMN_WAVEFORM_DATA in it })
        assertTrue(sql.any { RecordingDatabase.COLUMN_WAVEFORM_REVISION in it })
    }

    @Test
    fun databaseMigration_refusesDowngradeAndUnknownVersions() {
        assertEquals(emptyList<RecordingDatabaseMigrationStep>(), recordingDatabaseMigrationSteps(2, 2))
        assertEquals(
            listOf(RecordingDatabaseMigrationStep.ADD_FILE_IDENTITY),
            recordingDatabaseMigrationSteps(2, 3),
        )
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(0, 2) }
        assertEquals(
            listOf(RecordingDatabaseMigrationStep.ADD_WAVEFORM_CACHE),
            recordingDatabaseMigrationSteps(3, 4),
        )
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(4, 5) }
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
    fun stagingOutputNames_areNeverImportedAsFinishedRecordings() {
        val staging = stagingOutputName("clip.wav", "test-token")
        assertTrue(isStagingOutputName(staging))
        assertTrue(staging.endsWith(".wav"))
        assertFalse(isSupportedRecordingName(staging))
        assertTrue(isSupportedRecordingName("clip.wav"))
        assertFalse(isStagingOutputName("clip.wav"))
    }

    @Test
    fun stagingOutputMetadata_preservesPurposeSessionAndFinalName() {
        val exportStaging = stagingOutputName(
            finalDisplayName = "clip name.wav",
            token = "token",
            sessionId = "session-a",
            kind = StagingOutputKind.EXPORT,
        )
        val exportMetadata = parseStagingOutputMetadata(exportStaging)
        assertEquals(
            StagingOutputMetadata(StagingOutputKind.EXPORT, "session-a", "clip name.wav"),
            exportMetadata,
        )
        assertTrue(isStagingOutputFromSession(exportStaging, "session-a"))
        assertFalse(isStagingOutputFromSession(exportStaging, "session-b"))
        assertTrue(isStagingOutputName(exportStaging))
        assertFalse(isSupportedRecordingName(exportStaging))
        assertFalse(shouldRecoverStagingOutput(exportMetadata, currentSessionId = "session-a"))
        assertTrue(shouldRecoverStagingOutput(exportMetadata, currentSessionId = "session-b"))

        val copyStaging = stagingOutputName(
            finalDisplayName = "clip.wav",
            token = "token-2",
            sessionId = "session-old",
            kind = StagingOutputKind.COPY,
        )
        val copyMetadata = parseStagingOutputMetadata(copyStaging)
        assertEquals(StagingOutputKind.COPY, copyMetadata?.kind)
        assertFalse(shouldRecoverStagingOutput(copyMetadata, currentSessionId = "session-new"))
        assertFalse(shouldRecoverStagingOutput(null, currentSessionId = "session-new"))
        assertEquals(null, parseStagingOutputMetadata("reverb-partial-malformed.wav"))
    }

    @Test
    fun stagingWavRecovery_requiresExactCompleteContainerBytes() {
        val payload = ByteArray(8_820) { index -> ((index * 17 + 3) and 0xff).toByte() }
        val header = buildWavHeaderBytes(44_100, 1, PcmSampleFormat.PCM_16, payload.size.toLong())
        val complete = header + payload

        assertEquals(100L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(complete)))
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(complete.copyOf(complete.size - 1))))
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(complete + byteArrayOf(0))))

        val placeholder = buildWavHeaderBytes(44_100, 1, PcmSampleFormat.PCM_16, 0L) + payload
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(placeholder)))
    }

    @Test
    fun stagedFilePublish_neverOverwritesAnExistingRecording() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "publish-").toFile()
        try {
            val existingBytes = byteArrayOf(1, 2, 3, 4)
            val stagedBytes = byteArrayOf(9, 8, 7, 6, 5)
            val existing = File(directory, "clip.wav").apply { writeBytes(existingBytes) }
            val staged = File(directory, stagingOutputName("clip.wav", "token")).apply { writeBytes(stagedBytes) }

            val published = publishStagedFile(staged, "clip.wav")

            assertEquals("clip (2).wav", published.name)
            assertArrayEquals(existingBytes, existing.readBytes())
            assertArrayEquals(stagedBytes, published.readBytes())
            assertFalse(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun renameRollback_requiresExactOriginalPhysicalIdentity() {
        assertTrue(renameRollbackRestoredOriginal("old-id", "old-id"))
        assertFalse(renameRollbackRestoredOriginal("old-id", "new-id"))
        assertFalse(renameRollbackRestoredOriginal("old-id", null))
    }

    @Test
    fun moveSourceCleanup_neverDeletesChangedOrUnavailableSource() {
        assertEquals(
            MoveSourceCleanupAction.DELETE_SOURCE,
            moveSourceCleanupAction(RecordingAssetState.PRESENT, sameContentAsVerifiedTarget = true),
        )
        assertEquals(
            MoveSourceCleanupAction.KEEP_SOURCE,
            moveSourceCleanupAction(RecordingAssetState.PRESENT, sameContentAsVerifiedTarget = false),
        )
        assertEquals(
            MoveSourceCleanupAction.KEEP_SOURCE,
            moveSourceCleanupAction(RecordingAssetState.UNAVAILABLE, sameContentAsVerifiedTarget = true),
        )
        assertEquals(
            MoveSourceCleanupAction.COMPLETE,
            moveSourceCleanupAction(RecordingAssetState.MISSING, sameContentAsVerifiedTarget = false),
        )
    }

    @Test
    fun moveResult_reportsCopyAndSourceCleanupFailures() {
        assertFalse(RecordingRepository.MoveResult(moved = 1).hasFailures)
        assertTrue(RecordingRepository.MoveResult(failed = 1).hasFailures)
        assertTrue(RecordingRepository.MoveResult(moved = 1, cleanupFailed = 1).hasFailures)
    }

    @Test
    fun fileIdentity_requiresKnownExactObjectIdentity() {
        val original = "stat:1:2:100:5:77"
        assertTrue(fileIdentityMatches(original, original))
        assertFalse(fileIdentityMatches(original, "stat:1:2:101:6:77"))
        assertFalse(fileIdentityMatches(original, "stat:1:3:100:5:78"))
        assertFalse(fileIdentityMatches("", original))
        assertFalse(fileIdentityMatches(original, ""))

        assertTrue(sameFileObjectAcrossRename(original, "stat:1:2:101:6:77"))
        assertFalse(sameFileObjectAcrossRename(original, "stat:1:2:101:6:78"))
        assertFalse(sameFileObjectAcrossRename(original, "stat:1:3:101:6:77"))
        assertTrue(fileDescriptorIdentityMatches(original, "statfd:1:2:100:5"))
        assertFalse(fileDescriptorIdentityMatches(original, "statfd:1:2:101:5"))
    }

    @Test
    fun fileIdentity_tracksObjectAcrossRenameAndRejectsPathReplacement() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "file-identity-").toFile()
        try {
            val original = File(directory, "original.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val identity = resolveFileIdentity(original)
            assertTrue(identity.isNotBlank())
            val renamed = File(directory, "renamed.wav")
            Files.move(original.toPath(), renamed.toPath())
            assertTrue(fileIdentityMatches(identity, resolveFileIdentity(renamed)))

            val replacement = File(directory, "replacement.tmp").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
            Files.move(
                replacement.toPath(),
                renamed.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            assertFalse(fileIdentityMatches(identity, resolveFileIdentity(renamed)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pendingOutputCleanup_roundTripsAndRejectsReplacementIdentity() {
        val hash = "ab".repeat(32)
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "recordings/path-with-delimiters.wav",
            byteCount = 1234L,
            sha256Hex = hash,
            fileKey = "stat:1:2:100:5:77",
        )
        val encoded = encodePendingOutputCleanupRecord(record)
        assertEquals(record, decodePendingOutputCleanupRecord(encoded))
        assertTrue(pendingOutputCleanupMatches(record, 1234L, hash, "stat:1:2:100:5:77"))
        assertFalse(pendingOutputCleanupMatches(record, 1235L, hash, "stat:1:2:100:5:77"))
        assertFalse(pendingOutputCleanupMatches(record, 1234L, "cd".repeat(32), "stat:1:2:100:5:77"))
        assertFalse(pendingOutputCleanupMatches(record, 1234L, hash, "stat:1:3:100:5:78"))
        assertEquals(null, decodePendingOutputCleanupRecord("v1|FILE|broken|12|short|"))
        val firstIntent = requireNotNull(pendingOutputCleanupFileIntent(record))
        val secondIntent = requireNotNull(pendingOutputCleanupFileIntent(record))
        assertEquals(record.id, firstIntent.id)
        assertEquals(record.fileKey, firstIntent.fileIdentity)
        assertEquals(firstIntent.claimToken, secondIntent.claimToken)
        assertTrue(requireNotNull(deletionClaimFile(firstIntent)).name.startsWith(".reverb-delete-"))
    }

    @Test
    fun pendingDeletionIntent_roundTripsAndTracksPhysicalDeletionPhase() {
        val planned = PendingDeletionIntent(
            id = "content://provider/tree/a|b/%20",
            byteCount = 12_345L,
            sha256Hex = "abababababababababababababababababababababababababababababababab",
            assetDeleted = false,
        )
        val encoded = encodePendingDeletionIntent(planned)
        assertEquals(planned, decodePendingDeletionIntent(encoded))

        val deleted = planned.copy(assetDeleted = true)
        assertEquals(deleted, decodePendingDeletionIntent(encodePendingDeletionIntent(deleted)))
    }

    @Test
    fun pendingDeletionV2_roundTripsFileClaimIdentity() {
        val intent = PendingDeletionIntent(
            id = "/storage/emulated/0/Music/Reverb/clip.wav",
            byteCount = 9_999L,
            sha256Hex = "cd".repeat(32),
            assetDeleted = false,
            storageType = RecordingStorageType.FILE.name,
            claimToken = "00000000-0000-0000-0000-000000000123",
            fileIdentity = "stat:1:42:100:7:55",
        )
        val encoded = encodePendingDeletionIntent(intent)
        assertTrue(encoded.startsWith("v2|"))
        assertEquals(intent, decodePendingDeletionIntent(encoded))
        assertTrue(requireNotNull(deletionClaimFile(intent)).name.startsWith(".reverb-delete-"))
        val malformedToken = encoded.split('|').toMutableList().also { it[6] = "not-a-uuid" }.joinToString("|")
        assertEquals(null, decodePendingDeletionIntent(malformedToken))
    }

    @Test
    fun claimedFileDeletion_deletesExactObservedObject() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "delete-exact-").toFile()
        try {
            val bytes = ByteArray(6_144) { index -> ((index * 31 + 13) and 0xff).toByte() }
            val source = File(directory, "clip.wav").apply { writeBytes(bytes) }
            val identity = resolveFileIdentity(source)
            val digest = sha256(ByteArrayInputStream(bytes))
            val intent = PendingDeletionIntent(
                id = source.absolutePath,
                byteCount = digest.byteCount,
                sha256Hex = digest.sha256.toHexString(),
                assetDeleted = false,
                storageType = RecordingStorageType.FILE.name,
                claimToken = "00000000-0000-0000-0000-000000000126",
                fileIdentity = identity,
            )

            assertEquals(FileDeletionClaimResult.DELETED, deleteClaimedFile(intent))
            assertFalse(source.exists())
            assertFalse(requireNotNull(deletionClaimFile(intent)).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun claimedFileDeletion_neverDeletesAReplacementPath() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "delete-claim-").toFile()
        try {
            val originalBytes = ByteArray(4_096) { index -> ((index * 11 + 5) and 0xff).toByte() }
            val replacementBytes = ByteArray(4_096) { index -> ((index * 17 + 9) and 0xff).toByte() }
            val source = File(directory, "clip.wav").apply { writeBytes(originalBytes) }
            val originalIdentity = resolveFileIdentity(source)
            assertTrue(originalIdentity.isNotBlank())
            val digest = sha256(ByteArrayInputStream(originalBytes))
            val intent = PendingDeletionIntent(
                id = source.absolutePath,
                byteCount = digest.byteCount,
                sha256Hex = digest.sha256.toHexString(),
                assetDeleted = false,
                storageType = RecordingStorageType.FILE.name,
                claimToken = "00000000-0000-0000-0000-000000000124",
                fileIdentity = originalIdentity,
            )

            // Simulate another actor replacing the directory entry after Reverb observed it.
            val replacement = File(directory, "replacement.tmp").apply { writeBytes(replacementBytes) }
            Files.move(
                replacement.toPath(),
                source.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            assertFalse(fileIdentityMatches(originalIdentity, resolveFileIdentity(source)))
            assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, deleteClaimedFile(intent))
            assertArrayEquals(replacementBytes, source.readBytes())
            assertFalse(requireNotNull(deletionClaimFile(intent)).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun claimedFileDeletion_replayDeletesOnlyTheClaimedOriginalAfterPathReuse() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "delete-replay-").toFile()
        try {
            val originalBytes = ByteArray(8_192) { index -> ((index * 23 + 3) and 0xff).toByte() }
            val replacementBytes = ByteArray(8_192) { index -> ((index * 29 + 7) and 0xff).toByte() }
            val source = File(directory, "clip.wav").apply { writeBytes(originalBytes) }
            val originalIdentity = resolveFileIdentity(source)
            assertTrue(originalIdentity.isNotBlank())
            val digest = sha256(ByteArrayInputStream(originalBytes))
            val intent = PendingDeletionIntent(
                id = source.absolutePath,
                byteCount = digest.byteCount,
                sha256Hex = digest.sha256.toHexString(),
                assetDeleted = false,
                storageType = RecordingStorageType.FILE.name,
                claimToken = "00000000-0000-0000-0000-000000000125",
                fileIdentity = originalIdentity,
            )
            val claim = requireNotNull(deletionClaimFile(intent))
            Files.move(source.toPath(), claim.toPath())
            source.writeBytes(replacementBytes)
            assertFalse(fileIdentityMatches(originalIdentity, resolveFileIdentity(source)))
            assertTrue(fileIdentityMatches(originalIdentity, resolveFileIdentity(claim)))

            assertEquals(FileDeletionClaimResult.DELETED, replayClaimedFileDeletion(intent, claim))
            assertFalse(claim.exists())
            assertArrayEquals(replacementBytes, source.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pendingDeletionIntent_rejectsLegacyMalformedAndMismatchedContent() {
        assertEquals(null, decodePendingDeletionIntent("content://legacy/id-only"))
        assertEquals(null, decodePendingDeletionIntent("v1|broken|12|abcd|0"))

        val intent = PendingDeletionIntent(
            id = "id",
            byteCount = 4L,
            sha256Hex = "0101010101010101010101010101010101010101010101010101010101010101",
            assetDeleted = false,
        )
        assertTrue(pendingDeletionMatchesDigest(intent, 4L, "0101010101010101010101010101010101010101010101010101010101010101"))
        assertFalse(pendingDeletionMatchesDigest(intent, 5L, "0101010101010101010101010101010101010101010101010101010101010101"))
        assertFalse(pendingDeletionMatchesDigest(intent, 4L, "0202020202020202020202020202020202020202020202020202020202020202"))
    }

    @Test
    fun pendingDeletionReplay_neverRepeatsPhysicalDeletionAfterProcessLoss() {
        val planned = PendingDeletionIntent(
            id = "id",
            byteCount = 4L,
            sha256Hex = "01".repeat(32),
            assetDeleted = false,
        )
        val deleted = planned.copy(assetDeleted = true)

        assertEquals(
            PendingDeletionReplayAction.ABANDON_INTENT,
            pendingDeletionReplayAction(planned, RecordingAssetState.PRESENT),
        )
        assertEquals(
            PendingDeletionReplayAction.WAIT,
            pendingDeletionReplayAction(planned, RecordingAssetState.UNAVAILABLE),
        )
        assertEquals(
            PendingDeletionReplayAction.CLEAN_CATALOG,
            pendingDeletionReplayAction(planned, RecordingAssetState.MISSING),
        )
        assertEquals(
            PendingDeletionReplayAction.ABANDON_INTENT,
            pendingDeletionReplayAction(deleted, RecordingAssetState.PRESENT),
        )
        assertEquals(
            PendingDeletionReplayAction.WAIT,
            pendingDeletionReplayAction(deleted, RecordingAssetState.UNAVAILABLE),
        )
        assertEquals(
            PendingDeletionReplayAction.CLEAN_CATALOG,
            pendingDeletionReplayAction(deleted, RecordingAssetState.MISSING),
        )
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
        assertFalse(
            shouldDeleteExportTarget(
                cancelled = true,
                verifiedComplete = true,
                committed = false,
                preserveVerifiedOutput = true,
            ),
        )
        assertTrue(
            shouldDeleteExportTarget(
                cancelled = true,
                verifiedComplete = false,
                committed = false,
                preserveVerifiedOutput = true,
            ),
        )
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

    @Test
    fun liveSafPublish_isHiddenUntilVerifiedCopyCompletes() {
        val active = setOf("content://provider/final")
        assertFalse(shouldExposeDocumentOutput("content://provider/final", active))
        assertTrue(shouldExposeDocumentOutput("content://provider/other", active))
    }

    @Test
    fun pendingDeletion_isNeverEligibleForMoveOrRecoveryTarget() {
        val pending = setOf("delete-me")
        assertFalse(isRecordingEligibleForMove("delete-me", pending))
        assertTrue(isRecordingEligibleForMove("keep-me", pending))
    }

}
