package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
                RecordingDatabaseMigrationStep.ADD_STORAGE_TYPE_CODE,
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
        assertTrue(sql.any { RecordingDatabase.COLUMN_STORAGE_TYPE_CODE in it })
        val storageMigration = recordingDatabaseMigrationSql(RecordingDatabaseMigrationStep.ADD_STORAGE_TYPE_CODE)
            .joinToString("\n")
        assertTrue("WHEN 'FILE' THEN ${RecordingStorageType.FILE.storageCode.toInt()}" in storageMigration)
        assertTrue("WHEN 'DOCUMENT' THEN ${RecordingStorageType.DOCUMENT.storageCode.toInt()}" in storageMigration)
        assertTrue("WHEN 'MEDIASTORE' THEN ${RecordingStorageType.MEDIASTORE.storageCode.toInt()}" in storageMigration)
    }

    @Test
    fun corruptDatabaseRecovery_preservesMainAndSidecarsWithoutMutatingSources() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "db-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val wal = File(database.path + "-wal").apply { writeBytes(byteArrayOf(5, 6, 7)) }
            val shm = File(database.path + "-shm").apply { writeBytes(byteArrayOf(8, 9)) }
            val journal = File(database.path + "-journal").apply { writeBytes(byteArrayOf(10, 11, 12)) }
            val recoveryRoot = File(root, "recovery")

            val first = requireNotNull(preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot"))
            assertArrayEquals(database.readBytes(), File(first, database.name).readBytes())
            assertArrayEquals(wal.readBytes(), File(first, wal.name).readBytes())
            assertArrayEquals(shm.readBytes(), File(first, shm.name).readBytes())
            assertArrayEquals(journal.readBytes(), File(first, journal.name).readBytes())
            assertTrue(database.isFile)
            assertTrue(wal.isFile)
            assertTrue(shm.isFile)
            assertTrue(journal.isFile)

            val second = requireNotNull(preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot"))
            assertTrue(second.name != first.name)
            assertArrayEquals(database.readBytes(), File(second, database.name).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_rejectsSourceMutationDuringCopy() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "source-mutation-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val recoveryRoot = File(root, "recovery")
            val preserved = preserveCorruptRecordingDatabase(
                database,
                recoveryRoot,
                "snapshot",
                InjectedRecordingDatabaseRecoveryIo(
                    copyOverride = { source, target ->
                        target.writeBytes(source.readBytes())
                        source.writeBytes(byteArrayOf(9, 9, 9, 9))
                    },
                ),
            )

            assertEquals(null, preserved)
            assertFalse(File(recoveryRoot, "snapshot").exists())
            assertTrue(File(recoveryRoot, "snapshot.partial").isDirectory)
            assertArrayEquals(byteArrayOf(9, 9, 9, 9), database.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_rejectsNewSidecarDuringCopy() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "sidecar-race-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val wal = File(database.path + "-wal")
            val recoveryRoot = File(root, "recovery")
            val preserved = preserveCorruptRecordingDatabase(
                database,
                recoveryRoot,
                "snapshot",
                InjectedRecordingDatabaseRecoveryIo(
                    copyOverride = { source, target ->
                        target.writeBytes(source.readBytes())
                        wal.writeBytes(byteArrayOf(5, 6, 7))
                    },
                ),
            )

            assertEquals(null, preserved)
            assertFalse(File(recoveryRoot, "snapshot").exists())
            assertTrue(wal.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_rejectsMismatchedCopy() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "copy-mismatch-").toFile()
        try {
            val original = byteArrayOf(1, 2, 3, 4)
            val database = File(root, "recordings.db").apply { writeBytes(original) }
            val recoveryRoot = File(root, "recovery")
            val preserved = preserveCorruptRecordingDatabase(
                database,
                recoveryRoot,
                "snapshot",
                InjectedRecordingDatabaseRecoveryIo(
                    copyOverride = { source, target ->
                        target.writeBytes(source.readBytes() + byteArrayOf(99))
                    },
                ),
            )

            assertEquals(null, preserved)
            assertFalse(File(recoveryRoot, "snapshot").exists())
            assertArrayEquals(original, database.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_neverInventsSnapshotWithoutSourceFiles() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "empty-").toFile()
        try {
            val database = File(root, "recordings.db")
            val recoveryRoot = File(root, "recovery")
            assertEquals(null, preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot"))
            assertFalse(recoveryRoot.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_sidecarAvailabilityIsNeverTreatedAsAbsence() {
        val database = File("build/tmp/database-recovery-tests/observed/recordings.db")
        val unavailableWal: (File) -> StoragePathObservation = { candidate ->
            when {
                candidate == database -> StoragePathObservation(
                    StoragePathState.PRESENT,
                    isRegularFile = true,
                )
                candidate.path.endsWith("-wal") -> StoragePathObservation(StoragePathState.UNAVAILABLE)
                else -> StoragePathObservation(StoragePathState.MISSING)
            }
        }

        assertEquals(null, recordingDatabaseFilesForPreservation(database, unavailableWal))
        assertFalse(recordingDatabaseSidecarsConfirmedMissing(database, unavailableWal))
        assertTrue(
            recordingDatabaseSidecarsConfirmedMissing(database) {
                StoragePathObservation(StoragePathState.MISSING)
            },
        )
        assertEquals(
            null,
            recordingDatabaseFilesForPreservation(database) { candidate ->
                if (candidate == database) {
                    StoragePathObservation(StoragePathState.PRESENT, isRegularFile = false)
                } else {
                    StoragePathObservation(StoragePathState.MISSING)
                }
            },
        )
    }

    @Test
    fun corruptDatabaseRecovery_identitySnapshotRejectsSameByteReplacement() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "identity-replacement-").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val database = File(root, "recordings.db").apply { writeBytes(bytes) }
            val expected = requireNotNull(recordingDatabaseSourceIdentitySnapshot(database))

            assertTrue(database.delete())
            database.writeBytes(bytes)

            assertFalse(recordingDatabaseSourceIdentitySnapshotStillCurrent(database, expected))
            assertArrayEquals(bytes, database.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_identitySnapshotRejectsNewSidecar() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "identity-sidecar-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val expected = requireNotNull(recordingDatabaseSourceIdentitySnapshot(database))
            val wal = File(database.path + "-wal").apply { writeBytes(byteArrayOf(5, 6, 7)) }

            assertFalse(recordingDatabaseSourceIdentitySnapshotStillCurrent(database, expected))
            assertTrue(wal.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_identitySnapshotAcceptsUnchangedBackingSet() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "identity-stable-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            File(database.path + "-wal").writeBytes(byteArrayOf(5, 6, 7))
            val expected = requireNotNull(recordingDatabaseSourceIdentitySnapshot(database))

            assertTrue(recordingDatabaseSourceIdentitySnapshotStillCurrent(database, expected))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_atomicPublishFailureNeverCreatesFinalSnapshot() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "publish-failure-").toFile()
        try {
            val original = byteArrayOf(1, 2, 3, 4)
            val database = File(root, "recordings.db").apply { writeBytes(original) }
            val recoveryRoot = File(root, "recovery")
            val preserved = preserveCorruptRecordingDatabase(
                database,
                recoveryRoot,
                "snapshot",
                InjectedRecordingDatabaseRecoveryIo(
                    atomicMoveError = AtomicMoveNotSupportedException(
                        "snapshot.partial",
                        "snapshot",
                        "injected",
                    ),
                ),
            )

            assertEquals(null, preserved)
            assertFalse(File(recoveryRoot, "snapshot").exists())
            val partial = File(recoveryRoot, "snapshot.partial")
            assertTrue(partial.isDirectory)
            assertArrayEquals(original, File(partial, database.name).readBytes())
            assertArrayEquals(original, database.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_existingPartialIsNeverPromotedOrOverwritten() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "partial-collision-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val recoveryRoot = File(root, "recovery").apply { mkdirs() }
            val stalePartial = File(recoveryRoot, "snapshot.partial").apply { mkdir() }
            val marker = File(stalePartial, "marker").apply { writeText("stale") }

            val preserved = requireNotNull(
                preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot"),
            )

            assertEquals("snapshot-1", preserved.name)
            assertTrue(stalePartial.isDirectory)
            assertEquals("stale", marker.readText())
            assertArrayEquals(database.readBytes(), File(preserved, database.name).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_rejectsSidecarDisappearanceAfterCopy() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "sidecar-disappear-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val wal = File(database.path + "-wal").apply { writeBytes(byteArrayOf(5, 6, 7)) }
            val recoveryRoot = File(root, "recovery")
            val io = InjectedRecordingDatabaseRecoveryIo(
                afterCopy = { source, _ ->
                    if (source == wal) assertTrue(wal.delete())
                },
            )

            assertEquals(null, preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot", io))
            assertTrue(database.isFile)
            assertFalse(wal.exists())
            assertFalse(File(recoveryRoot, "snapshot").exists())
            assertTrue(File(recoveryRoot, "snapshot.partial").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_failsClosedWhenPrePublicationDirectorySyncFails() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "sync-failure-").toFile()
        try {
            val original = byteArrayOf(1, 2, 3, 4)
            val database = File(root, "recordings.db").apply { writeBytes(original) }
            val recoveryRoot = File(root, "recovery")
            val io = InjectedRecordingDatabaseRecoveryIo(failForceAt = 3)

            assertEquals(null, preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot", io))
            assertArrayEquals(original, database.readBytes())
            assertTrue(File(recoveryRoot, "snapshot.partial").isDirectory)
            assertFalse(File(recoveryRoot, "snapshot").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_postPublicationDirectorySyncFailureKeepsSourceAndReturnsFailure() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "post-publish-sync-").toFile()
        try {
            val original = byteArrayOf(1, 2, 3, 4)
            val database = File(root, "recordings.db").apply { writeBytes(original) }
            val recoveryRoot = File(root, "recovery")
            val io = InjectedRecordingDatabaseRecoveryIo(failForceAt = 4)

            assertEquals(null, preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot", io))
            assertArrayEquals(original, database.readBytes())
            val final = File(recoveryRoot, "snapshot")
            assertTrue(final.isDirectory)
            assertFalse(File(recoveryRoot, "snapshot.partial").exists())
            assertArrayEquals(original, File(final, database.name).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDatabaseRecovery_retryAfterFailedPublicationPreservesBothAttempts() {
        val parent = File("build/tmp/database-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "retry-").toFile()
        try {
            val database = File(root, "recordings.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val recoveryRoot = File(root, "recovery")
            val firstIo = InjectedRecordingDatabaseRecoveryIo(
                atomicMoveError = AtomicMoveNotSupportedException("snapshot.partial", "snapshot", "injected"),
            )
            assertEquals(null, preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot", firstIo))

            val retry = requireNotNull(preserveCorruptRecordingDatabase(database, recoveryRoot, "snapshot"))
            assertEquals("snapshot-1", retry.name)
            assertTrue(File(recoveryRoot, "snapshot.partial").isDirectory)
            assertArrayEquals(database.readBytes(), File(retry, database.name).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun catalogCorruption_firstPaintResetsAndSignalsRetryWithoutProviderRebuild() {
        val events = mutableListOf<String>()
        assertThrows(CatalogFirstPaintUnavailableException::class.java) {
            runBlocking {
                recoverCatalogAfterCorruption(
                    mode = CatalogCorruptionRecoveryMode.FIRST_PAINT,
                    reset = { events += "reset" },
                    rebuild = {
                        events += "rebuild"
                        listOf("unexpected")
                    },
                )
            }
        }
        assertEquals(listOf("reset"), events)
    }

    @Test
    fun catalogCorruption_refreshResetsThenRebuildsFromAuthoritativeStorage() = runBlocking {
        val events = mutableListOf<String>()
        val recovered = RecordingEntity(
            id = "recovered",
            displayName = "recovered.wav",
            mimeType = "audio/wav",
            startedAtMillis = 10L,
            durationMillis = 20L,
            sizeBytes = 30L,
            codecSummary = "WAV",
            storageType = RecordingStorageType.FILE,
            directoryId = "storage",
            createdAtMillis = 10L,
        )
        val result = recoverCatalogAfterCorruption(
            mode = CatalogCorruptionRecoveryMode.REFRESH,
            reset = { events += "reset" },
            rebuild = {
                events += "rebuild"
                listOf(recovered)
            },
        )

        assertEquals(listOf(recovered), result)
        assertEquals(listOf("reset", "rebuild"), events)
    }

    @Test
    fun catalogCorruption_refreshRetriesOnlyOnceAndPropagatesRepeatedFailure() {
        val events = mutableListOf<String>()
        val error = assertThrows(IOException::class.java) {
            runBlocking {
                recoverCatalogAfterCorruption(
                    mode = CatalogCorruptionRecoveryMode.REFRESH,
                    reset = { events += "reset" },
                    rebuild = {
                        events += "rebuild"
                        throw IOException("still unavailable")
                    },
                )
            }
        }

        assertEquals("still unavailable", error.message)
        assertEquals(listOf("reset", "rebuild"), events)
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
        assertEquals(
            listOf(RecordingDatabaseMigrationStep.ADD_STORAGE_TYPE_CODE),
            recordingDatabaseMigrationSteps(4, 5),
        )
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(5, 6) }
    }

    @Test
    fun retentionRecovery_roundTripsExactIndependentValues_andRejectsCorruption() {
        val configuration = RetentionConfiguration(
            mode = RetentionMode.TIME,
            oneShotSeconds = 12_345L,
            oneShotSizeBytes = 987_654_322L,
            loopingSeconds = 54_321L,
            loopingSizeBytes = 1_987_654_320L,
        )
        val encoded = encodeRetentionRecoveryConfiguration(configuration)
        assertEquals(configuration, decodeRetentionRecoveryConfiguration(encoded))

        val corrupted = encoded.copyOf().also { bytes -> bytes[19] = (bytes[19].toInt() xor 0x40).toByte() }
        assertEquals(null, decodeRetentionRecoveryConfiguration(corrupted))
        assertEquals(null, decodeRetentionRecoveryConfiguration(encoded.copyOf(encoded.size - 1)))
        assertEquals(null, decodeRetentionRecoveryConfiguration(encoded + byteArrayOf(0)))
    }

    @Test
    fun legacyRetentionBootstrapRequiresPositivelyMissingRecoveryJournal() {
        assertTrue(legacyRetentionPreferencesAllowed(RetentionRecoveryReadState.MISSING))
        assertFalse(legacyRetentionPreferencesAllowed(RetentionRecoveryReadState.VALID))
        assertFalse(legacyRetentionPreferencesAllowed(RetentionRecoveryReadState.INVALID))
    }

    @Test
    fun retentionPreferences_preservePreciseIndependentTimeAndSizeValues() {
        val size = retentionConfigurationFromPreferences(
            RetentionPreferenceValues(
                modePresent = true,
                modeCode = RetentionMode.SIZE.storageCode.toInt(),
                oneShotSeconds = 3_601L,
                oneShotSizeBytes = 777_777_778L,
                loopingSeconds = 7_203L,
                loopingSizeBytes = 1_888_888_890L,
            ),
        )
        assertEquals(
            RetentionConfiguration(
                RetentionMode.SIZE,
                3_601L,
                777_777_778L,
                7_203L,
                1_888_888_890L,
            ),
            size,
        )

        val time = retentionConfigurationFromPreferences(
            RetentionPreferenceValues(
                modePresent = true,
                modeCode = RetentionMode.TIME.storageCode.toInt(),
                oneShotSeconds = 4_567L,
                oneShotSizeBytes = 123_456_790L,
                loopingSeconds = 8_901L,
                loopingSizeBytes = 2_345_678_902L,
            ),
        )
        assertEquals(RetentionMode.TIME, time?.mode)
        assertEquals(4_567L, time?.oneShotSeconds)
        assertEquals(123_456_790L, time?.oneShotSizeBytes)
        assertEquals(8_901L, time?.loopingSeconds)
        assertEquals(2_345_678_902L, time?.loopingSizeBytes)
    }

    @Test
    fun retentionPreferenceDigest_rejectsEveryValidLookingFieldMutation() {
        val expected = RetentionConfiguration(
            RetentionMode.SIZE,
            oneShotSeconds = 3_601L,
            oneShotSizeBytes = 700_000_002L,
            loopingSeconds = 7_203L,
            loopingSizeBytes = 4_402_970_624L,
        )
        val digest = retentionConfigurationDigest(expected)
        val valid = RetentionPreferenceValues(
            modePresent = true,
            modeCode = expected.mode.storageCode.toInt(),
            oneShotSeconds = expected.oneShotSeconds,
            oneShotSizeBytes = expected.oneShotSizeBytes,
            loopingSeconds = expected.loopingSeconds,
            loopingSizeBytes = expected.loopingSizeBytes,
            digestPresent = true,
            digest = digest,
        )
        assertEquals(
            expected,
            retentionConfigurationFromPreferences(valid, allowLegacyWithoutDigest = false),
        )
        assertTrue(retentionPreferenceDigestMatches(valid, expected))

        val mutations = listOf(
            valid.copy(modeCode = RetentionMode.TIME.storageCode.toInt()),
            valid.copy(oneShotSeconds = expected.oneShotSeconds + 1L),
            valid.copy(oneShotSizeBytes = expected.oneShotSizeBytes - 2L),
            valid.copy(loopingSeconds = expected.loopingSeconds + 1L),
            valid.copy(loopingSizeBytes = expected.loopingSizeBytes / 10L),
        )
        mutations.forEach { mutated ->
            assertEquals(
                null,
                retentionConfigurationFromPreferences(mutated, allowLegacyWithoutDigest = false),
            )
        }

        val legacy = valid.copy(digestPresent = false, digest = null)
        assertEquals(null, retentionConfigurationFromPreferences(legacy, allowLegacyWithoutDigest = false))
        assertEquals(expected, retentionConfigurationFromPreferences(legacy, allowLegacyWithoutDigest = true))
    }

    @Test
    fun retentionPreferences_inferOnlyUnambiguousLegacyMode_andRejectPartialOrCorruptState() {
        val recovery = RetentionConfiguration(
            RetentionMode.SIZE,
            oneShotSeconds = 6_001L,
            oneShotSizeBytes = 1L,
            loopingSeconds = 12_003L,
            loopingSizeBytes = 1L,
        )
        val inferred = retentionConfigurationFromPreferences(
            RetentionPreferenceValues(
                modePresent = false,
                modeCode = null,
                oneShotSeconds = null,
                oneShotSizeBytes = 700_000_002L,
                loopingSeconds = null,
                loopingSizeBytes = 1_900_000_004L,
            ),
            recoveryFallback = recovery,
        )
        assertEquals(RetentionMode.SIZE, inferred?.mode)
        assertEquals(6_001L, inferred?.oneShotSeconds)
        assertEquals(12_003L, inferred?.loopingSeconds)
        assertEquals(700_000_002L, inferred?.oneShotSizeBytes)
        assertEquals(1_900_000_004L, inferred?.loopingSizeBytes)

        assertEquals(
            null,
            retentionConfigurationFromPreferences(
                RetentionPreferenceValues(false, null, 1L, 2L, 3L, 4L),
                recoveryFallback = recovery,
            ),
        )
        assertEquals(
            null,
            retentionConfigurationFromPreferences(
                RetentionPreferenceValues(true, 99, null, 2L, null, 4L),
                recoveryFallback = recovery,
            ),
        )
        assertEquals(
            null,
            retentionConfigurationFromPreferences(
                RetentionPreferenceValues(true, RetentionMode.SIZE.storageCode.toInt(), null, -2L, null, 4L),
                recoveryFallback = recovery,
            ),
        )
        assertEquals(
            null,
            retentionConfigurationFromPreferences(
                RetentionPreferenceValues(true, RetentionMode.TIME.storageCode.toInt(), 1L, 2L, null, 4L),
                recoveryFallback = recovery,
            ),
        )
    }

    @Test
    fun retentionReadPolicy_treatsRecoveryFirstCrashAsUncommittedWithoutHistory() {
        val primary = RetentionConfiguration(RetentionMode.SIZE, 11L, 22L, 33L, 44L)
        val recovery = RetentionConfiguration(RetentionMode.TIME, 55L, 66L, 77L, 88L)

        assertEquals(primary, preferredRetentionConfigurationForRead(primary, recovery, historyExists = false))
        assertEquals(recovery, preferredRetentionConfigurationForRead(primary, recovery, historyExists = true))
        assertEquals(primary, preferredRetentionConfigurationForRead(primary, primary, historyExists = true))
        assertEquals(recovery, preferredRetentionConfigurationForRead(null, recovery, historyExists = true))
    }

    @Test
    fun retentionResolution_neverAppliesConflictingDurableStateOverExistingHistory() {
        val primary = RetentionConfiguration(RetentionMode.SIZE, 11L, 22L, 33L, 44L)
        val recovery = RetentionConfiguration(RetentionMode.TIME, 55L, 66L, 77L, 88L)

        assertEquals(
            ResolvedRetentionConfiguration(primary, RetentionConfigurationSource.PREFERENCES),
            resolveRetentionConfiguration(primary, primary, historyExists = true),
        )
        assertEquals(null, resolveRetentionConfiguration(primary, recovery, historyExists = true))
        assertEquals(
            ResolvedRetentionConfiguration(primary, RetentionConfigurationSource.PREFERENCES),
            resolveRetentionConfiguration(primary, recovery, historyExists = false),
        )
        assertEquals(
            ResolvedRetentionConfiguration(recovery, RetentionConfigurationSource.RECOVERY),
            resolveRetentionConfiguration(null, recovery, historyExists = true),
        )
        assertEquals(null, resolveRetentionConfiguration(null, null, historyExists = true))
        assertEquals(
            ResolvedRetentionConfiguration(
                defaultRetentionConfiguration(),
                RetentionConfigurationSource.DEFAULTS,
            ),
            resolveRetentionConfiguration(null, null, historyExists = false),
        )
    }

    @Test
    fun settingsPersistenceAttempt_returnsFailureButPreservesCancellation() {
        assertTrue(runBlocking { runDurableUiBooleanAttempt { true } })
        assertFalse(runBlocking { runDurableUiBooleanAttempt { throw IOException("storage unavailable") } })
        assertThrows(CancellationException::class.java) {
            runBlocking { runDurableUiBooleanAttempt { throw CancellationException("cancelled") } }
        }
    }

    @Test
    fun settingsHydrationRetry_usesBoundedExponentialBackoff() {
        assertEquals(1_000L, nextDurableUiRetryDelayMillis(500L))
        assertEquals(30_000L, nextDurableUiRetryDelayMillis(20_000L))
        assertEquals(30_000L, nextDurableUiRetryDelayMillis(30_000L))
    }

    @Test
    fun retentionTransaction_writesRecoveryBeforePreferencesAndRollsBackFailedPhases() {
        fun run(writeRecoverySucceeds: Boolean, commitPreferencesSucceeds: Boolean): Pair<Boolean, List<String>> {
            val events = mutableListOf<String>()
            val result = persistRetentionTransaction(
                writeNewRecovery = {
                    events += "recovery:new"
                    writeRecoverySucceeds
                },
                commitNewPreferences = {
                    events += "preferences:new"
                    commitPreferencesSucceeds
                },
                restoreRecovery = {
                    events += "recovery:old"
                    true
                },
                restorePreferences = {
                    events += "preferences:old"
                    true
                },
            )
            return result to events
        }

        assertEquals(true to listOf("recovery:new", "preferences:new"), run(true, true))
        assertEquals(false to listOf("recovery:new", "recovery:old"), run(false, true))
        assertEquals(
            false to listOf("recovery:new", "preferences:new", "recovery:old", "preferences:old"),
            run(true, false),
        )
    }

    @Test
    fun loopingBoundaryMath_dropsOnlyTheMinimumWholeFrames() {
        assertEquals(2L, loopingDropChunkBytesForSize(1L, 100L, 2))
        assertEquals(4L, loopingDropChunkBytesForSize(3L, 100L, 2))
        assertEquals(100L, loopingDropChunkBytesForSize(500L, 100L, 2))
        assertEquals(100L, loopingDropChunkBytesForSize(Long.MAX_VALUE, 100L, 2))
        assertEquals(Long.MAX_VALUE - 1L, loopingDropChunkBytesForSize(Long.MAX_VALUE, Long.MAX_VALUE, 2))
        assertEquals(100L, loopingDropChunkBytesForSize(Long.MAX_VALUE, 101L, 2))

        val rate = 48_000
        assertEquals(2L, loopingDropChunkBytesForTime(1.0 / rate, 100L, rate, 2))
        assertEquals(4L, loopingDropChunkBytesForTime(1.1 / rate, 100L, rate, 2))
        assertEquals(200L, loopingDropChunkBytesForTime(1.0, 100L, rate, 2))
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
    fun recordingNameValidation_usesStorageIllegalCharacterRules() {
        assertFalse(hasIllegalRecordingNameCharacters("clip name"))
        for (illegal in charArrayOf('\\', '/', '*', '?', '"', '<', '>', '|')) {
            assertTrue(hasIllegalRecordingNameCharacters("clip${illegal}name"))
        }
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
        assertTrue(isStagingOutputName(exportStaging))
        assertFalse(isSupportedRecordingName(exportStaging))
        assertFalse(shouldRecoverStagingOutput(exportMetadata, currentSessionId = "session-a"))
        assertTrue(shouldRecoverStagingOutput(exportMetadata, currentSessionId = "session-b"))

        val trackedStaging = stagingOutputName(
            finalDisplayName = "tracked.wav",
            token = "token-v2",
            sessionId = "session-old",
            kind = StagingOutputKind.EXPORT_TRACKED,
        )
        val trackedMetadata = parseStagingOutputMetadata(trackedStaging)
        assertEquals(StagingOutputKind.EXPORT_TRACKED, trackedMetadata?.kind)
        assertFalse(
            shouldRecoverStagingOutput(
                trackedMetadata,
                currentSessionId = "session-new",
                verifiedTrackedExport = false,
            ),
        )
        assertTrue(
            shouldRecoverStagingOutput(
                trackedMetadata,
                currentSessionId = "session-new",
                verifiedTrackedExport = true,
            ),
        )
        assertFalse(
            shouldRecoverStagingOutput(
                trackedMetadata,
                currentSessionId = "session-old",
                verifiedTrackedExport = true,
            ),
        )

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
        // New durable enum writes use compact stable byte codes, but every deployed legacy
        // staging kind must remain parseable for crash recovery.
        assertTrue(copyStaging.startsWith("reverb-partial-3__"))
        assertEquals(
            StagingOutputMetadata(StagingOutputKind.COPY, "legacy-session", "legacy.wav"),
            parseStagingOutputMetadata(
                "reverb-partial-copy__legacy-session__token__bGVnYWN5Lndhdg.wav",
            ),
        )
    }

    @Test
    fun stagingWavRecovery_requiresExactCompleteContainerBytes() {
        val payload = ByteArray(8_820) { index -> ((index * 17 + 3) and 0xff).toByte() }
        val header = buildWavHeaderBytes(44_100, 1, PcmSampleFormat.PCM_16, payload.size.toLong())
        val complete = header + payload

        val observation = requireNotNull(readRecoverableStagingWav(ByteArrayInputStream(complete)))
        assertEquals(100L, observation.durationMillis)
        assertTrue(copyDigestMatches(sha256(ByteArrayInputStream(complete)), observation.digest))
        assertEquals(100L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(complete)))
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(complete.copyOf(complete.size - 1))))
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(complete + byteArrayOf(0))))

        val placeholder = buildWavHeaderBytes(44_100, 1, PcmSampleFormat.PCM_16, 0L) + payload
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(placeholder)))

        val unsupportedFormat = complete.clone().also { it[20] = 2 }
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(unsupportedFormat)))
        val inconsistentByteRate = complete.clone().also { it[28] = 0 }
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(inconsistentByteRate)))
        val inconsistentBlockAlign = complete.clone().also { it[32] = 1 }
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(inconsistentBlockAlign)))

        val partialFramePayload = payload + byteArrayOf(1)
        val partialFrame = buildWavHeaderBytes(
            44_100,
            1,
            PcmSampleFormat.PCM_16,
            partialFramePayload.size.toLong(),
        ) + partialFramePayload + byteArrayOf(0)
        assertEquals(0L, readRecoverableStagingWavDurationMillis(ByteArrayInputStream(partialFrame)))
    }

    @Test
    fun wavOutputVerification_returnsWholeDigestAndRejectsChangedOrExtraBytes() {
        val payload = ByteArray(8_820) { index -> ((index * 31 + 5) and 0xff).toByte() }
        val header = buildWavHeaderBytes(44_100, 1, PcmSampleFormat.PCM_16, payload.size.toLong())
        val complete = header + payload
        val payloadDigest = sha256(ByteArrayInputStream(payload))
        val expectedFull = sha256(ByteArrayInputStream(complete))

        val observed = verifyWavOutputStreamAndDigest(
            input = ByteArrayInputStream(complete),
            expectedFileBytes = complete.size.toLong(),
            expectedPrefix = header,
            payloadOffsetBytes = header.size.toLong(),
            payloadBytes = payload.size.toLong(),
            expectedPayloadSha256 = payloadDigest.sha256,
            bufferSize = 257,
        )
        assertEquals(expectedFull.byteCount, observed.byteCount)
        assertArrayEquals(expectedFull.sha256, observed.sha256)

        val changedHeader = complete.copyOf().also { bytes ->
            bytes[8] = (bytes[8].toInt() xor 0x01).toByte()
        }
        assertThrows(IOException::class.java) {
            verifyWavOutputStreamAndDigest(
                ByteArrayInputStream(changedHeader), complete.size.toLong(), header,
                header.size.toLong(), payload.size.toLong(), payloadDigest.sha256,
            )
        }

        val changedPayload = complete.copyOf().also { bytes ->
            val index = header.size + 17
            bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        }
        assertThrows(IOException::class.java) {
            verifyWavOutputStreamAndDigest(
                ByteArrayInputStream(changedPayload), complete.size.toLong(), header,
                header.size.toLong(), payload.size.toLong(), payloadDigest.sha256,
            )
        }
        assertThrows(IOException::class.java) {
            verifyWavOutputStreamAndDigest(
                ByteArrayInputStream(complete.copyOf(complete.size - 1)), complete.size.toLong(), header,
                header.size.toLong(), payload.size.toLong(), payloadDigest.sha256,
            )
        }
        assertThrows(IOException::class.java) {
            verifyWavOutputStreamAndDigest(
                ByteArrayInputStream(complete + byteArrayOf(0)), complete.size.toLong(), header,
                header.size.toLong(), payload.size.toLong(), payloadDigest.sha256,
            )
        }
    }

    @Test
    fun verifiedStagingFingerprint_staysBoundToTheObjectCreatedBeforeWrite() {
        val digest = CopyDigest(4L, ByteArray(32) { 0x22 })
        val fileTarget = RecordingOutputTarget(
            id = "/tmp/reverb-partial.wav",
            displayName = "recording.wav",
            mimeType = "audio/wav",
            storageType = RecordingStorageType.FILE,
            directoryId = "/tmp",
            startedAtMillis = 1L,
            staging = true,
            stagingIdentity = "stat:1:2:100:5:77",
        )
        assertTrue(
            stagingFingerprintMatchesCreatedObject(
                fileTarget,
                StableOutputFingerprint(digest, "stat:1:2:101:6:77", null),
            ),
        )
        assertFalse(
            stagingFingerprintMatchesCreatedObject(
                fileTarget,
                StableOutputFingerprint(digest, "stat:1:3:101:6:78", null),
            ),
        )

        val providerBefore = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/17",
            0L,
            10L,
        )
        val providerAfterWrite = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/17",
            4L,
            11L,
        )
        val providerReplacement = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/18",
            4L,
            11L,
        )
        val providerTarget = fileTarget.copy(
            id = "content://media/external/audio/media/17",
            storageType = RecordingStorageType.MEDIASTORE,
            directoryId = "mediastore",
            stagingIdentity = providerBefore,
        )
        assertTrue(
            stagingFingerprintMatchesCreatedObject(
                providerTarget,
                StableOutputFingerprint(digest, null, providerAfterWrite),
            ),
        )
        assertFalse(
            stagingFingerprintMatchesCreatedObject(
                providerTarget,
                StableOutputFingerprint(digest, null, providerReplacement),
            ),
        )
        // Some SAF providers do not expose a usable revision while an empty child exists. The
        // strict high-entropy name/tree/zero-size creation proof remains the pre-write authority
        // in that compatibility case; once an identity exists it must stay bound as above.
        assertTrue(
            stagingFingerprintMatchesCreatedObject(
                providerTarget.copy(stagingIdentity = ""),
                StableOutputFingerprint(digest, null, providerAfterWrite),
            ),
        )
        assertFalse(stagingFingerprintMatchesCreatedObject(fileTarget.copy(staging = false), StableOutputFingerprint(digest, fileTarget.stagingIdentity, null)))
    }

    @Test
    fun stagingFileWrite_requiresOriginalEmptyObjectAtDescriptorHandoff() {
        val expected = "stat:1:2:100:5:77"
        val descriptor = "statfd:1:2:100:5"
        assertTrue(stagingFileDescriptorMatchesCreation(expected, expected, descriptor, 0L))
        assertFalse(
            stagingFileDescriptorMatchesCreation(
                expected,
                "stat:1:3:100:5:78",
                descriptor,
                0L,
            ),
        )
        assertFalse(
            stagingFileDescriptorMatchesCreation(
                expected,
                expected,
                "statfd:1:3:100:5",
                0L,
            ),
        )
        assertFalse(stagingFileDescriptorMatchesCreation(expected, expected, descriptor, 1L))
        assertFalse(stagingFileDescriptorMatchesCreation("", expected, descriptor, 0L))
    }

    @Test
    fun newProviderOutput_requiresExactEmptyStagingObjectBeforeWrite() {
        val document = NewlyCreatedOutputObservation(
            displayName = "reverb-partial-token.wav",
            sizeBytes = 0L,
            sizeKnown = true,
            isFile = true,
            pending = null,
            directoryMatches = true,
        )
        assertTrue(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-token.wav", requirePending = false, document,
            ),
        )
        assertFalse(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-other.wav", requirePending = false, document,
            ),
        )
        assertFalse(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-token.wav", requirePending = false, document.copy(sizeKnown = false),
            ),
        )
        assertFalse(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-token.wav", requirePending = false, document.copy(sizeBytes = 1L),
            ),
        )
        assertFalse(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-token.wav", requirePending = false, document.copy(isFile = false),
            ),
        )
        assertFalse(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-token.wav", requirePending = false, document.copy(directoryMatches = false),
            ),
        )

        val mediaStore = document.copy(pending = true)
        assertTrue(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-token.wav", requirePending = true, mediaStore,
            ),
        )
        assertFalse(
            newlyCreatedOutputMayBeWritten(
                "reverb-partial-token.wav", requirePending = true, mediaStore.copy(pending = false),
            ),
        )
        assertFalse(newlyCreatedOutputMayBeWritten("reverb-partial-token.wav", true, null))
    }

    @Test
    fun ambiguousDocumentPublish_requiresExactNameContentAndRenameContinuity() {
        val digest = CopyDigest(5L, ByteArray(32) { 0x41 })
        val expected = StableOutputFingerprint(
            digest = digest,
            fileKey = null,
            providerIdentity = "provider:${RecordingStorageType.DOCUMENT.storageCode.toInt()}:source:5:7",
        )
        val sameUriPublished = expected.copy(
            providerIdentity = "provider:${RecordingStorageType.DOCUMENT.storageCode.toInt()}:source:5:8",
        )
        val sameUri = DocumentPublicationObservation(
            displayName = "clip.wav",
            sourceUriUnchanged = true,
            oldSourceState = RecordingAssetState.PRESENT,
            fingerprint = sameUriPublished,
        )
        assertTrue(documentPublicationMatchesExpected("clip.wav", expected, sameUri))
        assertFalse(documentPublicationMatchesExpected("other.wav", expected, sameUri))
        assertFalse(
            documentPublicationMatchesExpected(
                "clip.wav",
                expected,
                sameUri.copy(
                    fingerprint = sameUriPublished.copy(
                        providerIdentity = "provider:${RecordingStorageType.DOCUMENT.storageCode.toInt()}:other:5:8",
                    ),
                ),
            ),
        )

        val changedUri = sameUri.copy(
            sourceUriUnchanged = false,
            oldSourceState = RecordingAssetState.MISSING,
            fingerprint = sameUriPublished.copy(
                providerIdentity = "provider:${RecordingStorageType.DOCUMENT.storageCode.toInt()}:renamed:5:8",
            ),
        )
        assertTrue(documentPublicationMatchesExpected("clip.wav", expected, changedUri))
        assertFalse(
            documentPublicationMatchesExpected(
                "clip.wav", expected, changedUri.copy(oldSourceState = RecordingAssetState.PRESENT),
            ),
        )
        assertFalse(
            documentPublicationMatchesExpected(
                "clip.wav", expected, changedUri.copy(oldSourceState = RecordingAssetState.UNAVAILABLE),
            ),
        )
        assertFalse(
            documentPublicationMatchesExpected(
                "clip.wav",
                expected,
                changedUri.copy(
                    fingerprint = changedUri.fingerprint.copy(
                        digest = CopyDigest(5L, ByteArray(32) { 0x42 }),
                    ),
                ),
            ),
        )
        assertFalse(documentPublicationMatchesExpected("clip.wav", expected, null))
    }

    @Test
    fun ambiguousMediaStorePublish_isAcceptedOnlyWhenExactFinalRowIsProven() {
        val digest = CopyDigest(4L, ByteArray(32) { 0x31 })
        val expected = StableOutputFingerprint(
            digest = digest,
            fileKey = null,
            providerIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:item:4:7",
        )
        val published = expected.copy(
            providerIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:item:4:8",
        )
        val exact = MediaStorePublicationObservation(
            displayName = "clip.wav",
            pending = false,
            fingerprint = published,
        )

        assertTrue(mediaStorePublicationMatchesExpected("clip.wav", expected, exact))
        assertFalse(mediaStorePublicationMatchesExpected("clip.wav", expected, exact.copy(pending = true)))
        assertFalse(mediaStorePublicationMatchesExpected("clip.wav", expected, exact.copy(displayName = "other.wav")))
        assertFalse(
            mediaStorePublicationMatchesExpected(
                "clip.wav",
                expected,
                exact.copy(
                    fingerprint = published.copy(
                        digest = CopyDigest(4L, ByteArray(32) { 0x32 }),
                    ),
                ),
            ),
        )
        assertFalse(mediaStorePublicationMatchesExpected("clip.wav", expected, null))
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
    fun stagedFilePublish_moveThatCommitsBeforeFailure_isSuppressedAndReturnedToStaging() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "publish-ambiguous-").toFile()
        try {
            val stagedBytes = byteArrayOf(3, 1, 4, 1, 5, 9)
            val staged = File(directory, stagingOutputName("clip.wav", "ambiguous-token")).apply {
                writeBytes(stagedBytes)
            }
            val expected = StableOutputFingerprint(
                digest = sha256(ByteArrayInputStream(stagedBytes)),
                fileKey = resolveFileIdentity(staged).takeIf { it.isNotBlank() } ?: "stat:1:2:3:4:5",
                providerIdentity = null,
            )
            var suppressed: PendingOutputCleanupRecord? = null
            val transportFailure = IOException("move transport failed after commit")

            val observed = assertThrows(IOException::class.java) {
                publishStagedFile(
                    source = staged,
                    finalDisplayName = "clip.wav",
                    expectedFingerprint = expected,
                    onUnexpectedPublishedFile = { published, digest ->
                        suppressionOnlyFileOutputRecord(published.absolutePath, digest)
                            ?.also { suppressed = it } != null
                    },
                    moveFile = { source, destination ->
                        Files.move(source.toPath(), destination.toPath())
                        throw transportFailure
                    },
                    readFingerprint = { published ->
                        if (!published.isFile) null else StableOutputFingerprint(
                            digest = sha256(ByteArrayInputStream(published.readBytes())),
                            fileKey = expected.fileKey,
                            providerIdentity = null,
                        )
                    },
                )
            }

            assertTrue(observed === transportFailure)
            val protected = requireNotNull(suppressed)
            assertEquals(File(directory, "clip.wav").absolutePath, protected.id)
            assertEquals(stagedBytes.size.toLong(), protected.byteCount)
            assertEquals(null, protected.fileKey)
            assertTrue(staged.isFile)
            assertArrayEquals(stagedBytes, staged.readBytes())
            assertFalse(File(directory, "clip.wav").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun stagedFilePublish_rejectsObjectThatReplacedVerifiedStagingPath() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "publish-race-").toFile()
        try {
            val verifiedBytes = byteArrayOf(1, 3, 5, 7, 9)
            val replacementBytes = byteArrayOf(2, 4, 6, 8)
            val staged = File(directory, stagingOutputName("clip.wav", "verified-token")).apply {
                writeBytes(verifiedBytes)
            }
            val expected = StableOutputFingerprint(
                digest = staged.inputStream().use(::sha256),
                fileKey = resolveFileIdentity(staged).takeIf { it.isNotBlank() } ?: "stat:1:2:3:4:5",
                providerIdentity = null,
            )
            val replacement = File(directory, "replacement.tmp").apply { writeBytes(replacementBytes) }
            Files.move(
                replacement.toPath(),
                staged.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )

            var cleanupRecord: PendingOutputCleanupRecord? = null
            assertThrows(IOException::class.java) {
                publishStagedFile(
                    source = staged,
                    finalDisplayName = "clip.wav",
                    expectedFingerprint = expected,
                    onUnexpectedPublishedFile = { published, digest ->
                        suppressionOnlyFileOutputRecord(published.absolutePath, digest)
                            ?.also { cleanupRecord = it } != null
                    },
                )
            }

            val protected = requireNotNull(cleanupRecord)
            assertEquals(RecordingStorageType.FILE, protected.storageType)
            assertEquals(File(directory, "clip.wav").absolutePath, protected.id)
            assertEquals(replacementBytes.size.toLong(), protected.byteCount)
            assertEquals(sha256(ByteArrayInputStream(replacementBytes)).sha256.toHexString(), protected.sha256Hex)
            assertEquals(null, protected.fileKey)
            assertEquals(
                PendingOutputCleanupMatch.UNPROVEN,
                classifyPendingOutputCleanup(
                    protected,
                    protected.byteCount,
                    protected.sha256Hex,
                    fileKey = "stat:different-object",
                ),
            )
            assertFalse(File(directory, "clip.wav").exists())
            assertTrue(staged.isFile)
            assertArrayEquals(replacementBytes, staged.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unexpectedFileRename_suppressesVisibleTargetBeforeRollback() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "rename-race-").toFile()
        try {
            val replacementBytes = byteArrayOf(7, 6, 5, 4, 3)
            val source = File(directory, "original.wav")
            val moved = File(directory, "renamed.wav").apply { writeBytes(replacementBytes) }
            var callbackSawVisibleTarget = false
            var protectedDigest: CopyDigest? = null

            val protected = preserveUnexpectedRenameTarget(
                source = source,
                moved = moved,
                originalDisplayName = "original.wav",
                onUnexpectedMovedFile = { visible, digest ->
                    callbackSawVisibleTarget = visible == moved && moved.isFile && !source.exists()
                    protectedDigest = digest
                    true
                },
            )

            assertTrue(protected)
            assertTrue(callbackSawVisibleTarget)
            assertEquals(replacementBytes.size.toLong(), requireNotNull(protectedDigest).byteCount)
            assertArrayEquals(replacementBytes, source.readBytes())
            assertFalse(moved.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun mediaStoreNameQuery_requiresAuthoritativeCursor() {
        assertTrue(mediaStoreNameQueryOccupied(cursorAvailable = true, hasMatchingRow = true))
        assertFalse(mediaStoreNameQueryOccupied(cursorAvailable = true, hasMatchingRow = false))
        assertThrows(IOException::class.java) {
            mediaStoreNameQueryOccupied(cursorAvailable = false, hasMatchingRow = false)
        }
    }

    @Test
    fun freshMoveCopyCollisionPreservesExistingTargets() {
        val occupied = setOf("clip.wav", "clip (2).wav")
        assertEquals(
            "clip (3).wav",
            findAvailableDisplayName("clip.wav") { candidate -> candidate in occupied },
        )
        assertEquals(
            "clip",
            findAvailableDisplayName("clip") { false },
        )
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
    fun trimRequiresStableSelectedSourceIdentity() {
        val base = RecordingEntity(
            id = "content://docs/clip",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 1L,
            durationMillis = 2_000L,
            sizeBytes = 4_000L,
            codecSummary = "PCM",
            storageType = RecordingStorageType.DOCUMENT,
            directoryId = "content://docs/tree",
        )
        assertFalse(recordingHasStableTrimIdentity(base))
        assertTrue(recordingHasStableTrimIdentity(
            base.copy(
                fileIdentity = providerRecordingIdentity(
                    RecordingStorageType.DOCUMENT, base.id, base.sizeBytes, 9L,
                ),
            ),
        ))
        assertFalse(recordingHasStableTrimIdentity(
            base.copy(storageType = RecordingStorageType.FILE, id = "/recordings/clip.wav"),
        ))
        assertTrue(recordingHasStableTrimIdentity(
            base.copy(
                storageType = RecordingStorageType.FILE,
                id = "/recordings/clip.wav",
                fileIdentity = "stat:1:2:3:4:5",
            ),
        ))
    }

    @Test
    fun trimPublicationValidatesSourceBeforeRecoveryGrantAndPublish() {
        val events = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            publishVerifiedTrimAfterSourceValidation(
                sourceStillCurrent = { events += "source"; false },
                persistRecoveryMarker = { events += "marker"; true },
                targetId = "target",
                publish = { events += "publish" },
            )
        }
        assertEquals(listOf("source"), events)

        events.clear()
        assertEquals(
            "published",
            publishVerifiedTrimAfterSourceValidation(
                sourceStillCurrent = { events += "source"; true },
                persistRecoveryMarker = { events += "marker"; true },
                targetId = "target",
                publish = { events += "publish"; "published" },
            ),
        )
        assertEquals(listOf("source", "marker", "publish"), events)
    }

    @Test
    fun providerOutputCleanupRequiresPositivePostDeleteAbsence() {
        assertTrue(providerOutputCleanupCompleted(OutputCleanupAssetState.MISSING))
        assertFalse(providerOutputCleanupCompleted(OutputCleanupAssetState.PRESENT))
        assertFalse(providerOutputCleanupCompleted(OutputCleanupAssetState.UNAVAILABLE))
    }

    @Test
    fun providerCleanup_observesPostconditionEvenWhenDeleteTransportThrows() {
        val events = mutableListOf<String>()
        val completed = providerDeleteAttemptCompleted(
            delete = {
                events += "delete"
                throw IOException("provider failed after commit")
            },
            observeState = {
                events += "observe"
                OutputCleanupAssetState.MISSING
            },
            onDeleteFailure = { events += "failure" },
        )

        assertTrue(completed)
        assertEquals(listOf("delete", "failure", "observe"), events)
        assertFalse(
            providerDeleteAttemptCompleted(
                delete = { throw IOException("provider failed") },
                observeState = { OutputCleanupAssetState.PRESENT },
            ),
        )
        assertFalse(
            providerDeleteAttemptCompleted(
                delete = { throw IOException("provider failed") },
                observeState = { OutputCleanupAssetState.UNAVAILABLE },
            ),
        )
    }

    @Test
    fun publishedOutputIdentityWinsOverLaterPathOrUriObservation() {
        val publishedProviderIdentity = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/clip", 4_000L, 9L,
        )
        val replacementProviderIdentity = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/clip", 4_000L, 10L,
        )
        val providerTarget = RecordingOutputTarget(
            id = "content://docs/clip",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            storageType = RecordingStorageType.DOCUMENT,
            directoryId = "content://docs/tree",
            startedAtMillis = 1L,
            staging = false,
            publishedIdentity = publishedProviderIdentity,
        )
        assertEquals(
            publishedProviderIdentity,
            recordingIdentityForPublishedTarget(providerTarget) { replacementProviderIdentity },
        )
        var resolverCalls = 0
        assertEquals(
            publishedProviderIdentity,
            recordingIdentityForPublishedTarget(providerTarget) {
                resolverCalls++
                replacementProviderIdentity
            },
        )
        assertEquals(0, resolverCalls)
        assertEquals(
            replacementProviderIdentity,
            recordingIdentityForPublishedTarget(providerTarget.copy(publishedIdentity = "")) {
                replacementProviderIdentity
            },
        )

        val publishedFileIdentity = "stat:1:2:3:4:5"
        val replacementFileIdentity = "stat:1:3:6:7:8"
        val fileTarget = providerTarget.copy(
            id = "/recordings/clip.wav",
            storageType = RecordingStorageType.FILE,
            directoryId = "/recordings",
            publishedIdentity = publishedFileIdentity,
        )
        assertEquals(
            publishedFileIdentity,
            recordingIdentityForPublishedTarget(fileTarget) { replacementFileIdentity },
        )
    }

    @Test
    fun mediaStoreRelativePath_requiresExactManagedDirectory() {
        val managed = "Music/Reverb/"
        assertTrue(mediaStoreRelativePathIsManaged(managed, managed))
        assertFalse(mediaStoreRelativePathIsManaged("Music/Reverb", managed))
        assertFalse(mediaStoreRelativePathIsManaged("Music/Other/", managed))
        assertFalse(mediaStoreRelativePathIsManaged(null, managed))
    }

    @Test
    fun mediaStoreProviderIdentity_requiresKnownSizeAndRealRevision() {
        val id = "content://media/external/audio/media/9"
        val generationIdentity = providerRecordingIdentity(RecordingStorageType.MEDIASTORE, id, 7_000L, 42L)
        val modifiedIdentity = providerRecordingIdentity(RecordingStorageType.MEDIASTORE, id, 7_000L, 123_000L)

        assertEquals(
            generationIdentity,
            mediaStoreProviderIdentityFromMetadata(id, true, 7_000L, false, 0L, true, 42L),
        )
        assertEquals(
            modifiedIdentity,
            mediaStoreProviderIdentityFromMetadata(id, true, 7_000L, true, 123L, false, 0L),
        )
        assertEquals(
            "",
            mediaStoreProviderIdentityFromMetadata(id, false, 7_000L, true, 123L, true, 42L),
        )
        assertEquals(
            "",
            mediaStoreProviderIdentityFromMetadata(id, true, 7_000L, false, 0L, false, 0L),
        )
    }

    @Test
    fun documentProviderIdentity_requiresOneCompleteMetadataObservation() {
        val id = "content://provider/tree/root/document/clip"
        val expected = providerRecordingIdentity(RecordingStorageType.DOCUMENT, id, 4_096L, 123L)

        assertEquals(
            expected,
            documentProviderIdentityFromMetadata(
                id = id,
                sizeKnown = true,
                sizeBytes = 4_096L,
                modifiedKnown = true,
                modifiedMillis = 123L,
            ),
        )
        assertEquals(
            "",
            documentProviderIdentityFromMetadata(id, false, 4_096L, true, 123L),
        )
        assertEquals(
            "",
            documentProviderIdentityFromMetadata(id, true, 4_096L, false, 123L),
        )
        assertEquals(
            "",
            documentProviderIdentityFromMetadata(id, true, 4_096L, true, 0L),
        )
    }

    @Test
    fun providerReadHandoff_requiresSelectedIdentityBeforeAndAfterDescriptorOpen() {
        val expected = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/7",
            4_000L,
            10L,
        )
        val changedRevision = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/7",
            4_000L,
            11L,
        )
        val replacement = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/8",
            4_000L,
            10L,
        )

        assertTrue(providerReadHandoffMatchesExpected(expected, expected, expected))
        assertFalse(providerReadHandoffMatchesExpected(expected, changedRevision, expected))
        assertFalse(providerReadHandoffMatchesExpected(expected, expected, changedRevision))
        assertFalse(providerReadHandoffMatchesExpected(expected, expected, replacement))
        assertFalse(providerReadHandoffMatchesExpected("", expected, expected))
    }

    @Test
    fun documentRename_requiresSameObjectOrVerifiedUriHandoff() {
        val before = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val same = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val changed = CopyDigest(4L, byteArrayOf(9, 8, 7, 6))
        val oldIdentity = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/old", 4L, 10L,
        )
        val sameUriIdentity = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/old", 4L, 11L,
        )
        val newUriIdentity = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/new", 4L, 12L,
        )

        assertTrue(
            documentRenameTransitionIsSafe(
                true, RecordingAssetState.PRESENT, oldIdentity, sameUriIdentity, before, same,
            ),
        )
        assertFalse(
            documentRenameTransitionIsSafe(
                true, RecordingAssetState.PRESENT, oldIdentity, sameUriIdentity, before, null,
            ),
        )
        assertFalse(
            documentRenameTransitionIsSafe(
                true, RecordingAssetState.PRESENT, oldIdentity, sameUriIdentity, before, changed,
            ),
        )
        assertFalse(
            documentRenameTransitionIsSafe(
                true, RecordingAssetState.PRESENT, oldIdentity, newUriIdentity, before, same,
            ),
        )
        assertTrue(
            documentRenameTransitionIsSafe(
                false, RecordingAssetState.MISSING, oldIdentity, newUriIdentity, before, same,
            ),
        )
        assertFalse(
            documentRenameTransitionIsSafe(
                false, RecordingAssetState.PRESENT, oldIdentity, newUriIdentity, before, same,
            ),
        )
        assertFalse(
            documentRenameTransitionIsSafe(
                false, RecordingAssetState.UNAVAILABLE, oldIdentity, newUriIdentity, before, same,
            ),
        )
        assertFalse(
            documentRenameTransitionIsSafe(
                false, RecordingAssetState.MISSING, oldIdentity, newUriIdentity, before, changed,
            ),
        )
        assertFalse(
            documentRenameTransitionIsSafe(
                false, RecordingAssetState.MISSING, oldIdentity, "", before, same,
            ),
        )
        assertFalse(rejectedDocumentRenameShouldSuppressReturnedUri(sourceUriUnchanged = true))
        assertTrue(rejectedDocumentRenameShouldSuppressReturnedUri(sourceUriUnchanged = false))
    }

    @Test
    fun providerDeleteRequiresPositivePostDeleteAbsence() {
        assertTrue(providerDeletionCompleted(RecordingAssetState.MISSING))
        assertFalse(providerDeletionCompleted(RecordingAssetState.PRESENT))
        assertFalse(providerDeletionCompleted(RecordingAssetState.UNAVAILABLE))
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
    fun providerFingerprintRead_requiresIdentityAcrossDescriptorOpenAndHash() {
        val before = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/11",
            8_192L,
            20L,
        )
        val changed = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/external/audio/media/11",
            8_192L,
            21L,
        )

        assertTrue(providerReadRemainsStable(before, before, before))
        assertFalse(providerReadRemainsStable(before, changed, before))
        assertFalse(providerReadRemainsStable(before, before, changed))
        assertFalse(providerReadRemainsStable("", before, before))
    }

    @Test
    fun pendingOutputCleanup_roundTripsAndRejectsReplacementIdentity() {
        val hash = "ab".repeat(32)
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "/recordings/path-with-delimiters.wav",
            byteCount = 1234L,
            sha256Hex = hash,
            fileKey = "stat:1:2:100:5:77",
        )
        val encoded = encodePendingOutputCleanupRecord(record)
        assertTrue(encoded.startsWith("v3|1|"))
        assertEquals(record, decodePendingOutputCleanupRecord(encoded))
        assertTrue(pendingOutputCleanupMatches(record, 1234L, hash, "stat:1:2:100:5:77"))
        assertFalse(pendingOutputCleanupMatches(record, 1235L, hash, "stat:1:2:100:5:77"))
        assertFalse(pendingOutputCleanupMatches(record, 1234L, "cd".repeat(32), "stat:1:2:100:5:77"))
        assertFalse(pendingOutputCleanupMatches(record, 1234L, hash, "stat:1:3:100:5:78"))
        assertEquals(null, decodePendingOutputCleanupRecord("v1|FILE|broken|12|short|"))
        val encodedId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(record.id.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val malformedDigest = "v3|1|$encodedId|1234|short||"
        val truncated = "v3|1|$encodedId|1234"
        assertEquals(null, decodePendingOutputCleanupRecord(malformedDigest))
        assertEquals(record.id, pendingOutputCleanupSuppressedId(malformedDigest))
        assertEquals(record.id, pendingOutputCleanupSuppressedId(truncated))
        assertEquals(null, pendingOutputCleanupSuppressedId("v99|1|$encodedId|1234"))
        val claimTokens = outputCleanupClaimTokens(record)
        assertEquals(2, claimTokens.size)
        assertFalse(claimTokens[0] == claimTokens[1])
        val firstIntent = requireNotNull(pendingOutputCleanupFileIntent(record))
        val secondIntent = requireNotNull(pendingOutputCleanupFileIntent(record))
        assertEquals(record.id, firstIntent.id)
        assertEquals(record.fileKey, firstIntent.fileIdentity)
        assertEquals(claimTokens.first(), firstIntent.claimToken)
        assertEquals(firstIntent.claimToken, secondIntent.claimToken)
        val legacyIntent = requireNotNull(pendingOutputCleanupFileIntent(record, claimTokens.last()))
        assertEquals(claimTokens.last(), legacyIntent.claimToken)
        assertTrue(requireNotNull(deletionClaimFile(firstIntent)).name.startsWith(".reverb-delete-"))
        assertTrue(requireNotNull(deletionClaimFile(legacyIntent)).name.startsWith(".reverb-delete-"))
        assertFalse(deletionClaimFile(firstIntent) == deletionClaimFile(legacyIntent))
    }

    @Test
    fun suppressionOnlyFileOutput_rechecksReplacementWithoutDeletionAuthority() {
        val digest = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val record = requireNotNull(
            suppressionOnlyFileOutputRecord("/recordings/unsafe-final.wav", digest),
        )

        assertEquals(RecordingStorageType.FILE, record.storageType)
        assertEquals(null, record.fileKey)
        assertEquals(null, pendingOutputCleanupFileIntent(record))
        assertFalse(pendingFileOutputCleanupRequiresClaimReplay(record))
        assertEquals(
            PendingOutputCleanupMatch.UNPROVEN,
            classifyPendingOutputCleanup(
                record, digest.byteCount, digest.sha256.toHexString(), "stat:current-object",
            ),
        )
        assertEquals(
            PendingOutputCleanupMatch.REPLACED,
            classifyPendingOutputCleanup(
                record, digest.byteCount, "aa".repeat(32), "stat:replacement-object",
            ),
        )
    }

    @Test
    fun suppressionOnlyProviderOutput_neverGainsDeletionAuthority() {
        val digest = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val record = requireNotNull(
            suppressionOnlyProviderOutputRecord(
                RecordingStorageType.DOCUMENT, "content://docs/unsafe-final", digest,
            ),
        )
        val currentIdentity = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/unsafe-final", 4L, 9L,
        )

        assertEquals(null, record.providerIdentity)
        assertEquals(null, record.fileKey)
        assertEquals(
            PendingOutputCleanupMatch.UNPROVEN,
            classifyPendingOutputCleanup(
                record, digest.byteCount, digest.sha256.toHexString(), null, currentIdentity,
            ),
        )
        assertEquals(
            PendingOutputCleanupMatch.REPLACED,
            classifyPendingOutputCleanup(
                record, digest.byteCount, "aa".repeat(32), null, currentIdentity,
            ),
        )
        assertEquals(
            null,
            suppressionOnlyProviderOutputRecord(RecordingStorageType.FILE, "/recordings/clip.wav", digest),
        )
    }

    @Test
    fun verifiedExportStaging_requiresExactDigestAndStableObjectIdentity() {
        val digest = CopyDigest(4L, ByteArray(32) { 0x01 })
        val fileRecord = VerifiedExportStagingRecord(
            storageType = RecordingStorageType.FILE,
            id = "/recordings/staged.wav",
            byteCount = digest.byteCount,
            sha256Hex = digest.sha256.toHexString(),
            fileKey = "stat:1:2:100:5:77",
            providerIdentity = null,
        )
        val fileFingerprint = StableOutputFingerprint(
            digest = digest,
            fileKey = fileRecord.fileKey,
            providerIdentity = null,
        )
        val encoded = encodeVerifiedExportStagingRecord(fileRecord)
        assertTrue(encoded.startsWith("v2|1|"))
        assertEquals(fileRecord, decodeVerifiedExportStagingRecord(encoded))

        val encodeField: (String) -> String = { value ->
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }
        val legacyV1 = buildString {
            append("v1|FILE|")
            append(encodeField(fileRecord.id)).append('|')
            append(fileRecord.byteCount).append('|')
            append(fileRecord.sha256Hex).append('|')
            append(encodeField(fileRecord.fileKey.orEmpty())).append('|')
            append(encodeField(fileRecord.providerIdentity.orEmpty()))
        }
        assertEquals(fileRecord, decodeVerifiedExportStagingRecord(legacyV1))
        assertTrue(verifiedExportStagingRecordMatches(fileRecord, fileFingerprint))
        assertFalse(
            verifiedExportStagingRecordMatches(
                fileRecord,
                fileFingerprint.copy(fileKey = "stat:1:3:100:5:78"),
            ),
        )
        assertFalse(
            verifiedExportStagingRecordMatches(
                fileRecord,
                fileFingerprint.copy(digest = CopyDigest(4L, ByteArray(32) { 0x02 })),
            ),
        )

        val providerIdentity = "provider:MEDIASTORE:item:4:7"
        val providerRecord = fileRecord.copy(
            storageType = RecordingStorageType.MEDIASTORE,
            id = "content://media/external/audio/media/7",
            fileKey = null,
            providerIdentity = providerIdentity,
        )
        val providerFingerprint = StableOutputFingerprint(digest, null, providerIdentity)
        val providerPublished = providerFingerprint.copy(
            providerIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:item:4:8",
        )
        assertTrue(verifiedProviderPublicationMatches(providerFingerprint, providerPublished))
        assertFalse(
            verifiedProviderPublicationMatches(
                providerFingerprint,
                providerPublished.copy(providerIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:other:4:8"),
            ),
        )
        assertFalse(
            verifiedProviderPublicationMatches(
                providerFingerprint,
                providerPublished.copy(digest = CopyDigest(4L, ByteArray(32) { 0x02 })),
            ),
        )
        val reboundProvider = outputCleanupFingerprintForTarget(
            RecordingOutputTarget(
                id = providerRecord.id,
                displayName = "clip.wav",
                mimeType = "audio/wav",
                storageType = RecordingStorageType.MEDIASTORE,
                directoryId = MEDIA_STORE_DIRECTORY_ID,
                startedAtMillis = 1L,
                staging = false,
                publishedIdentity = requireNotNull(providerPublished.providerIdentity),
            ),
            providerFingerprint,
        )
        assertEquals(providerPublished.providerIdentity, reboundProvider.providerIdentity)
        assertEquals(null, reboundProvider.fileKey)
        assertTrue(verifiedExportStagingRecordMatches(providerRecord, providerFingerprint))
        assertFalse(
            verifiedExportStagingRecordMatches(
                providerRecord,
                providerFingerprint.copy(providerIdentity = "provider:MEDIASTORE:item:4:8"),
            ),
        )
        assertFalse(verifiedExportStagingRecordMatches(providerRecord.copy(providerIdentity = null), providerFingerprint))
        assertTrue(stableOutputFingerprintMatches(RecordingStorageType.FILE, fileFingerprint, fileFingerprint))
        assertFalse(
            stableOutputFingerprintMatches(
                RecordingStorageType.FILE,
                fileFingerprint,
                fileFingerprint.copy(fileKey = "stat:1:3:100:5:78"),
            ),
        )
        assertTrue(
            verifiedFilePublishMatches(
                fileFingerprint,
                fileFingerprint.copy(fileKey = "stat:1:2:101:6:77"),
            ),
        )
        assertFalse(
            verifiedFilePublishMatches(
                fileFingerprint,
                fileFingerprint.copy(fileKey = "stat:1:3:101:6:77"),
            ),
        )
        assertFalse(
            verifiedFilePublishMatches(
                fileFingerprint,
                fileFingerprint.copy(digest = CopyDigest(4L, ByteArray(32) { 0x03 })),
            ),
        )
        assertTrue(
            sameProviderObjectAcrossMutation(
                "provider:MEDIASTORE:item:4:7",
                "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:item:4:8",
            ),
        )
        assertFalse(
            sameProviderObjectAcrossMutation(
                "provider:MEDIASTORE:item:4:7",
                "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:other:4:8",
            ),
        )
    }

    @Test
    fun pendingOutputCleanup_requiresTheOriginallyVerifiedObject() {
        val digest = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val same = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val changed = CopyDigest(4L, byteArrayOf(1, 2, 3, 5))
        val fileKey = "stat:1:2:100:5:77"
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "recordings/copied.wav",
            byteCount = digest.byteCount,
            sha256Hex = digest.sha256.toHexString(),
            fileKey = fileKey,
        )
        val verified = StableOutputFingerprint(digest, fileKey, null)

        assertTrue(copyDigestMatches(digest, same))
        assertFalse(copyDigestMatches(digest, changed))
        assertTrue(pendingOutputCleanupRecordMatchesFingerprint(record, RecordingStorageType.FILE, verified))
        assertFalse(
            pendingOutputCleanupRecordMatchesFingerprint(
                record,
                RecordingStorageType.FILE,
                verified.copy(fileKey = "stat:1:3:100:5:77"),
            ),
        )
        assertFalse(
            pendingOutputCleanupRecordMatchesFingerprint(
                record.copy(fileKey = null),
                RecordingStorageType.FILE,
                verified,
            ),
        )
    }

    @Test
    fun completedCopy_sourceChangeOrUnavailabilityPreservesVerifiedBytes() {
        val copied = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val same = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val changed = CopyDigest(4L, byteArrayOf(1, 2, 3, 5))

        assertFalse(completedCopySourcePreservationRequired(copied, same))
        assertTrue(completedCopySourcePreservationRequired(copied, null))
        assertTrue(completedCopySourcePreservationRequired(copied, changed))
    }

    @Test
    fun pendingProviderOutputCleanup_requiresStableProviderIdentity_andLegacyV1FailsClosed() {
        val id = "content://media/external/audio/media/42"
        val hash = "ef".repeat(32)
        val identity = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            id,
            sizeBytes = 4321L,
            revisionToken = 77L,
        )
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.MEDIASTORE,
            id = id,
            byteCount = 4321L,
            sha256Hex = hash,
            fileKey = null,
            providerIdentity = identity,
        )

        val encoded = encodePendingOutputCleanupRecord(record)
        assertTrue(encoded.startsWith("v3|3|"))
        assertEquals(record, decodePendingOutputCleanupRecord(encoded))
        val legacyV2Identity = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(identity.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val legacyV2Id = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val legacyV2 = "v2|MEDIASTORE|$legacyV2Id|4321|$hash||$legacyV2Identity"
        assertEquals(record, decodePendingOutputCleanupRecord(legacyV2))
        assertTrue(pendingOutputCleanupMatches(record, 4321L, hash, null, identity))
        assertEquals(
            PendingOutputCleanupMatch.EXACT,
            classifyPendingOutputCleanup(record, 4321L, hash, null, identity),
        )
        assertEquals(
            PendingOutputCleanupMatch.REPLACED,
            classifyPendingOutputCleanup(record, 4321L, hash, null, "provider:MEDIASTORE:replacement"),
        )
        assertEquals(
            PendingOutputCleanupMatch.UNPROVEN,
            classifyPendingOutputCleanup(record, 4321L, hash, null, null),
        )

        val encodedId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val legacy = requireNotNull(
            decodePendingOutputCleanupRecord("v1|MEDIASTORE|$encodedId|4321|$hash|"),
        )
        assertEquals(null, legacy.providerIdentity)
        assertFalse(pendingOutputCleanupMatches(legacy, 4321L, hash, null, identity))
        assertEquals(
            PendingOutputCleanupMatch.UNPROVEN,
            classifyPendingOutputCleanup(legacy, 4321L, hash, null, identity),
        )
        assertEquals(
            PendingOutputCleanupMatch.REPLACED,
            classifyPendingOutputCleanup(legacy, 4321L, "aa".repeat(32), null, identity),
        )
    }

    @Test
    fun pendingFileOutputCleanup_legacyIdentityRemainsSuppressionOnly() {
        val legacy = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "/recordings/legacy-nio.wav",
            byteCount = 10L,
            sha256Hex = "ab".repeat(32),
            fileKey = "nio:bGVnYWN5LWZpbGUta2V5:1234",
        )
        val descriptorBound = legacy.copy(fileKey = "stat:1:2:3:4:5")

        assertTrue(pendingFileOutputCleanupRequiresClaimReplay(legacy))
        assertFalse(pendingFileOutputCleanupHasDeleteAuthority(legacy))
        assertTrue(pendingFileOutputCleanupRequiresClaimReplay(descriptorBound))
        assertTrue(pendingFileOutputCleanupHasDeleteAuthority(descriptorBound))
    }

    @Test
    fun pendingFileOutputCleanup_withoutObjectIdentityFailsClosed() {
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "recordings/legacy.wav",
            byteCount = 10L,
            sha256Hex = "aa".repeat(32),
            fileKey = null,
        )
        assertFalse(pendingOutputCleanupMatches(record, 10L, record.sha256Hex, null))
        assertEquals(null, pendingOutputCleanupFileIntent(record))
        assertFalse(pendingFileOutputCleanupRequiresClaimReplay(record))
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
    fun pendingDeletionV3_roundTripsByteCodedFileClaim_andReadsLegacyV2() {
        val intent = PendingDeletionIntent(
            id = "/storage/emulated/0/Music/Reverb/clip.wav",
            byteCount = 9_999L,
            sha256Hex = "cd".repeat(32),
            assetDeleted = false,
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000123",
            fileIdentity = "stat:1:42:100:7:55",
        )
        val encoded = encodePendingDeletionIntent(intent)
        val encodedParts = encoded.split('|')
        assertEquals("v3", encodedParts[0])
        assertEquals(RecordingStorageType.FILE.storageCode.toString(), encodedParts[5])
        assertEquals(intent, decodePendingDeletionIntent(encoded))

        val encodedId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(intent.id.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val encodedIdentity = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(requireNotNull(intent.fileIdentity).toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val legacyV2 = "v2|$encodedId|${intent.byteCount}|${intent.sha256Hex}|0|FILE|${intent.claimToken}|$encodedIdentity"
        assertEquals(intent, decodePendingDeletionIntent(legacyV2))
        assertTrue(requireNotNull(deletionClaimFile(intent)).name.startsWith(".reverb-delete-"))
        val malformedToken = encoded.split('|').toMutableList().also { it[6] = "not-a-uuid" }.joinToString("|")
        assertEquals(null, decodePendingDeletionIntent(malformedToken))
    }

    @Test
    fun malformedDeletionJournalRetainsSuppressionWithoutDeleteAuthority() {
        val id = "/storage/emulated/0/Music/Reverb/clip.wav"
        val encodedId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val malformed = "v3|$encodedId|4|${"cd".repeat(32)}|0|1|not-a-uuid|broken"

        assertEquals(null, decodePendingDeletionIntent(malformed))
        assertEquals(id, pendingDeletionSuppressedId(malformed))
        assertEquals("content://legacy/id", pendingDeletionSuppressedId("content://legacy/id"))
        assertEquals(null, pendingDeletionSuppressedId("v99|$encodedId|broken"))
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
                storageType = RecordingStorageType.FILE,
                claimToken = "00000000-0000-0000-0000-000000000126",
                fileIdentity = identity,
            )

            val result = deleteClaimedFile(intent)
            if (deletionClaimIdentityHasPinnedDescriptorAuthority(identity)) {
                assertEquals(FileDeletionClaimResult.DELETED, result)
                assertFalse(source.exists())
            } else {
                assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, result)
                assertTrue(source.isFile)
                assertArrayEquals(bytes, source.readBytes())
            }
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
                storageType = RecordingStorageType.FILE,
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
    fun claimedFileDeletion_legacyNonDescriptorIdentityIsPreservedNotDeleted() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "delete-legacy-claim-").toFile()
        try {
            val bytes = ByteArray(4_096) { index -> ((index * 19 + 7) and 0xff).toByte() }
            val source = File(directory, "clip.wav").apply { writeBytes(bytes) }
            val digest = sha256(ByteArrayInputStream(bytes))
            val intent = PendingDeletionIntent(
                id = source.absolutePath,
                byteCount = digest.byteCount,
                sha256Hex = digest.sha256.toHexString(),
                assetDeleted = false,
                storageType = RecordingStorageType.FILE,
                claimToken = "00000000-0000-0000-0000-000000000127",
                fileIdentity = "nio:bGVnYWN5LWZpbGUta2V5:1234",
            )
            val claim = requireNotNull(deletionClaimFile(intent))
            Files.move(source.toPath(), claim.toPath())

            assertFalse(deletionClaimIdentityHasPinnedDescriptorAuthority(requireNotNull(intent.fileIdentity)))
            assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, replayClaimedFileDeletion(intent, claim))
            assertTrue(source.isFile)
            assertArrayEquals(bytes, source.readBytes())
            assertFalse(claim.exists())
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
                storageType = RecordingStorageType.FILE,
                claimToken = "00000000-0000-0000-0000-000000000125",
                fileIdentity = originalIdentity,
            )
            val claim = requireNotNull(deletionClaimFile(intent))
            Files.move(source.toPath(), claim.toPath())
            source.writeBytes(replacementBytes)
            assertFalse(fileIdentityMatches(originalIdentity, resolveFileIdentity(source)))
            assertTrue(fileIdentityMatches(originalIdentity, resolveFileIdentity(claim)))

            val result = replayClaimedFileDeletion(intent, claim)
            if (deletionClaimIdentityHasPinnedDescriptorAuthority(originalIdentity)) {
                assertEquals(FileDeletionClaimResult.DELETED, result)
                assertFalse(claim.exists())
            } else {
                assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, result)
                assertFalse(claim.exists())
            }
            assertArrayEquals(replacementBytes, source.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun claimedFileDeletion_rechecksVerifiedClaimIdentityAtDeleteBoundary() {
        val parent = File("build/tmp/durability-invariants").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "delete-preboundary-").toFile()
        try {
            val originalBytes = ByteArray(4_096) { index -> ((index * 17 + 11) and 0xff).toByte() }
            val source = File(directory, "clip.wav").apply { writeBytes(originalBytes) }
            val originalIdentity = resolveFileIdentity(source)
            val digest = sha256(ByteArrayInputStream(originalBytes))
            val descriptorBoundIdentity = "stat:11:22:33:44:55"
            val intent = PendingDeletionIntent(
                id = source.absolutePath,
                byteCount = digest.byteCount,
                sha256Hex = digest.sha256.toHexString(),
                assetDeleted = false,
                storageType = RecordingStorageType.FILE,
                claimToken = "00000000-0000-0000-0000-000000000126",
                fileIdentity = descriptorBoundIdentity,
            )
            val claim = requireNotNull(deletionClaimFile(intent))
            Files.move(source.toPath(), claim.toPath())
            val replacementIdentity = "stat:99:98:97:96:95"

            val result = replayClaimedFileDeletion(
                intent = intent,
                claim = claim,
                readClaimFingerprint = { _, _ ->
                    StableOutputFingerprint(
                        digest = digest,
                        fileKey = descriptorBoundIdentity,
                        providerIdentity = null,
                    )
                },
                identityBeforeDelete = { replacementIdentity },
            )

            assertEquals(FileDeletionClaimResult.MISMATCH_PRESERVED, result)
            assertTrue(source.isFile)
            assertArrayEquals(originalBytes, source.readBytes())
            assertFalse(claim.exists())
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
        val filePlanned = planned.copy(
            id = "/storage/emulated/0/Music/Reverb/clip.wav",
            storageType = RecordingStorageType.FILE,
            claimToken = "00000000-0000-0000-0000-000000000126",
            fileIdentity = "stat:1:42:100:7:55",
        )

        assertEquals(
            PendingDeletionReplayAction.ABANDON_INTENT,
            pendingDeletionReplayAction(planned, RecordingAssetState.PRESENT),
        )
        assertEquals(
            PendingDeletionReplayAction.ABANDON_INTENT,
            pendingDeletionReplayAction(filePlanned, RecordingAssetState.PRESENT),
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
    fun claimedFileReplay_retiresCatalogOnlyAfterPhysicalDeletion() {
        assertTrue(claimedFileReplayRetiresCatalog(FileDeletionClaimResult.DELETED))
        assertFalse(claimedFileReplayRetiresCatalog(FileDeletionClaimResult.MISMATCH_PRESERVED))
        assertFalse(claimedFileReplayRetiresCatalog(FileDeletionClaimResult.RETRY))
    }

    @Test
    fun claimedFileReplay_waitsUnlessClaimPresenceIsProven() {
        assertEquals(
            ClaimedFileReplayAction.NO_CLAIM,
            claimedFileReplayAction(StoragePathObservation(StoragePathState.MISSING)),
        )
        assertEquals(
            ClaimedFileReplayAction.WAIT,
            claimedFileReplayAction(StoragePathObservation(StoragePathState.UNAVAILABLE)),
        )
        assertEquals(
            ClaimedFileReplayAction.WAIT,
            claimedFileReplayAction(
                StoragePathObservation(StoragePathState.PRESENT, isRegularFile = false),
            ),
        )
        assertEquals(
            ClaimedFileReplayAction.REPLAY,
            claimedFileReplayAction(
                StoragePathObservation(StoragePathState.PRESENT, isRegularFile = true),
            ),
        )
    }

    @Test
    fun outputCleanupFailure_failsClosedInsteadOfEscaping() {
        assertTrue(runOutputCleanupFailClosed { true })
        assertFalse(runOutputCleanupFailClosed { throw IllegalStateException("journal unreadable") })
    }

    @Test
    fun verifiedOutput_neverPublishesWithoutDurableRecoveryMarker() {
        requireVerifiedOutputRecoveryMarker(true, "target")
        assertThrows(IOException::class.java) {
            requireVerifiedOutputRecoveryMarker(false, "target")
        }
    }

    @Test
    fun exportCancellationDistinguishesUserCancelFromSystemFailure() {
        assertEquals(ExportCancellationTerminal.CANCELLED, exportCancellationTerminal(reportFailure = false))
        assertEquals(ExportCancellationTerminal.FAILED, exportCancellationTerminal(reportFailure = true))
    }

    @Test
    fun exportCancellationStopsAtVerifiedPublicationBoundary() {
        assertTrue(exportCancellationAllowed(publicationStarted = false, committed = false))
        assertFalse(exportCancellationAllowed(publicationStarted = true, committed = false))
        assertFalse(exportCancellationAllowed(publicationStarted = false, committed = true))
        assertFalse(exportCancellationAllowed(publicationStarted = true, committed = true))
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
    fun selectedAssetState_hidesProvenReplacementButPreservesIdentityUncertainty() {
        val providerStored = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/clip", 4_000L, 9L,
        )
        val providerSame = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/clip", 4_000L, 9L,
        )
        val providerReplacement = providerRecordingIdentity(
            RecordingStorageType.DOCUMENT, "content://docs/clip", 4_000L, 10L,
        )

        assertEquals(
            RecordingAssetState.PRESENT,
            selectedRecordingAssetState(
                RecordingAssetState.PRESENT, RecordingStorageType.DOCUMENT, providerStored, providerSame,
            ),
        )
        assertEquals(
            RecordingAssetState.MISSING,
            selectedRecordingAssetState(
                RecordingAssetState.PRESENT, RecordingStorageType.DOCUMENT, providerStored, providerReplacement,
            ),
        )
        assertEquals(
            RecordingAssetState.UNAVAILABLE,
            selectedRecordingAssetState(
                RecordingAssetState.PRESENT, RecordingStorageType.DOCUMENT, providerStored, "",
            ),
        )
        assertEquals(
            RecordingAssetState.PRESENT,
            selectedRecordingAssetState(
                RecordingAssetState.PRESENT, RecordingStorageType.DOCUMENT, "", providerReplacement,
            ),
        )
        assertEquals(
            RecordingAssetState.UNAVAILABLE,
            selectedRecordingAssetState(
                RecordingAssetState.UNAVAILABLE, RecordingStorageType.DOCUMENT, providerStored, providerReplacement,
            ),
        )
        assertEquals(
            RecordingAssetState.MISSING,
            selectedRecordingAssetState(
                RecordingAssetState.MISSING, RecordingStorageType.FILE, "stat:1:2:3:4:5", "stat:1:3:4:5:6",
            ),
        )
        assertEquals(
            RecordingAssetState.MISSING,
            selectedRecordingAssetState(
                RecordingAssetState.PRESENT, RecordingStorageType.FILE, "stat:1:2:3:4:5", "stat:1:3:4:5:6",
            ),
        )
    }

    @Test
    fun libraryPresentation_hidesMissingAndPendingRowsWithoutForgettingThem() {
        val base = RecordingEntity(
            id = "present", displayName = "present.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 1L, sizeBytes = 1L, codecSummary = "WAV",
            storageType = RecordingStorageType.FILE, directoryId = "dir", createdAtMillis = 1L,
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
    fun pendingDeletion_isNeverEligibleForMoveOrRecoveryTarget() {
        val pending = setOf("delete-me")
        assertFalse(isRecordingEligibleForMove("delete-me", pending))
        assertTrue(isRecordingEligibleForMove("keep-me", pending))
    }

}

private class InjectedRecordingDatabaseRecoveryIo(
    private val copyOverride: ((File, File) -> Unit)? = null,
    private val afterCopy: ((File, File) -> Unit)? = null,
    private val failForceAt: Int? = null,
    private val atomicMoveError: IOException? = null,
) : RecordingDatabaseRecoveryIo {
    private var forceCalls = 0

    override fun sha256(file: File): ByteArray = DefaultRecordingDatabaseRecoveryIo.sha256(file)

    override fun copyAndSync(source: File, target: File) {
        val copy = copyOverride
        if (copy != null) copy(source, target)
        else DefaultRecordingDatabaseRecoveryIo.copyAndSync(source, target)
        afterCopy?.invoke(source, target)
    }

    override fun forceDirectory(directory: File) {
        forceCalls++
        if (forceCalls == failForceAt) throw IOException("Injected directory sync failure")
        DefaultRecordingDatabaseRecoveryIo.forceDirectory(directory)
    }

    override fun atomicMove(source: File, target: File) {
        atomicMoveError?.let { throw it }
        DefaultRecordingDatabaseRecoveryIo.atomicMove(source, target)
    }
}
