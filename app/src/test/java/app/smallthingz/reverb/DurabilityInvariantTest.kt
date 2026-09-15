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
    fun catalogCorruption_firstPaintResetsButDoesNotRunProviderRebuild() = runBlocking {
        val events = mutableListOf<String>()
        val result = recoverCatalogAfterCorruption(
            mode = CatalogCorruptionRecoveryMode.FIRST_PAINT,
            reset = { events += "reset" },
            rebuild = {
                events += "rebuild"
                listOf("unexpected")
            },
            emptyValue = emptyList(),
        )

        assertEquals(emptyList<String>(), result)
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
            storageType = RecordingStorageType.FILE.name,
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
            emptyValue = emptyList(),
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
                    emptyValue = emptyList<String>(),
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
        assertThrows(IllegalArgumentException::class.java) { recordingDatabaseMigrationSteps(4, 5) }
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
        assertTrue(isStagingOutputFromSession(exportStaging, "session-a"))
        assertFalse(isStagingOutputFromSession(exportStaging, "session-b"))
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

            assertThrows(IOException::class.java) {
                publishStagedFile(staged, "clip.wav", expected)
            }

            assertFalse(File(directory, "clip.wav").exists())
            assertTrue(staged.isFile)
            assertArrayEquals(replacementBytes, staged.readBytes())
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
        val firstIntent = requireNotNull(pendingOutputCleanupFileIntent(record))
        val secondIntent = requireNotNull(pendingOutputCleanupFileIntent(record))
        assertEquals(record.id, firstIntent.id)
        assertEquals(record.fileKey, firstIntent.fileIdentity)
        assertEquals(firstIntent.claimToken, secondIntent.claimToken)
        assertTrue(requireNotNull(deletionClaimFile(firstIntent)).name.startsWith(".reverb-delete-"))
    }

    @Test
    fun verifiedExportStaging_requiresExactDigestAndStableObjectIdentity() {
        val digest = CopyDigest(4L, ByteArray(32) { 0x01 })
        val fileRecord = VerifiedExportStagingRecord(
            storageType = RecordingStorageType.FILE,
            id = "recordings/staged.wav",
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
                "provider:MEDIASTORE:item:4:8",
            ),
        )
        assertFalse(
            sameProviderObjectAcrossMutation(
                "provider:MEDIASTORE:item:4:7",
                "provider:MEDIASTORE:other:4:8",
            ),
        )
    }

    @Test
    fun pendingOutputCleanup_expectedDigestMustMatchCopiedBytes() {
        val digest = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val same = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val changed = CopyDigest(4L, byteArrayOf(1, 2, 3, 5))
        val record = PendingOutputCleanupRecord(
            storageType = RecordingStorageType.FILE,
            id = "recordings/copied.wav",
            byteCount = digest.byteCount,
            sha256Hex = digest.sha256.toHexString(),
            fileKey = "stat:1:2:100:5:77",
        )

        assertTrue(copyDigestMatches(digest, same))
        assertFalse(copyDigestMatches(digest, changed))
        assertTrue(pendingOutputCleanupRecordMatchesDigest(record, digest))
        assertFalse(pendingOutputCleanupRecordMatchesDigest(record, CopyDigest(5L, digest.sha256)))
    }

    @Test
    fun completedCopy_sourceChangePreservesVerifiedBytesInsteadOfAuthorizingCleanup() {
        val copied = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val same = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val changed = CopyDigest(4L, byteArrayOf(1, 2, 3, 5))

        assertFalse(completedCopySourceChanged(copied, same))
        assertFalse(completedCopySourceChanged(copied, null))
        assertTrue(completedCopySourceChanged(copied, changed))
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
    fun safPublication_requiresRenameSupportToStayHiddenUntilVerified() {
        assertTrue(canSafelyPublishDocumentOutput(supportsRename = true))
        assertFalse(canSafelyPublishDocumentOutput(supportsRename = false))
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
