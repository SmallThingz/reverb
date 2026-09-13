package app.smallthingz.reverb

import android.content.pm.ServiceInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingAndHistoryMathTest {
    @Test
    fun libraryDismissEdge_isExactlyThirteenPercentOnEachSide() {
        assertTrue(isLibraryDismissEdge(0f, 100f))
        assertTrue(isLibraryDismissEdge(13f, 100f))
        assertFalse(isLibraryDismissEdge(13.01f, 100f))
        assertFalse(isLibraryDismissEdge(86.99f, 100f))
        assertTrue(isLibraryDismissEdge(87f, 100f))
        assertTrue(isLibraryDismissEdge(100f, 100f))
        assertFalse(isLibraryDismissEdge(0f, 0f))
    }

    @Test
    fun recordingDatabase_v1ToV2MigrationIsExplicitAndNonDestructive() {
        val steps = recordingDatabaseMigrationSteps(1, 2)
        assertEquals(
            listOf(
                RecordingDatabaseMigrationStep.ADD_LAST_SEEN,
                RecordingDatabaseMigrationStep.ADD_MISSING_SINCE,
            ),
            steps,
        )
        val sql = steps.flatMap(::recordingDatabaseMigrationSql)
        assertTrue(sql.any { it.contains(RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS) })
        assertTrue(sql.any { it.contains(RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS) })
        assertFalse(sql.any { it.contains("DROP TABLE", ignoreCase = true) })
        assertTrue(recordingDatabaseMigrationSteps(2, 2).isEmpty())
    }


    @Test
    fun recordingCopyDigest_copiesEveryByteAcrossBoundarySizes() {
        val source = ByteArray(262_147) { index -> ((index * 37 + 11) and 0xff).toByte() }
        val expectedDigest = sha256(ByteArrayInputStream(source)).sha256

        for (bufferSize in listOf(1, 3, 4_096, 131_072)) {
            val output = ByteArrayOutputStream(source.size)
            val digest = copyWithSha256(ByteArrayInputStream(source), output, bufferSize)
            assertEquals(source.size.toLong(), digest.byteCount)
            assertTrue(source.contentEquals(output.toByteArray()))
            assertTrue(expectedDigest.contentEquals(digest.sha256))
        }
    }

    @Test
    fun recordingCopyDigest_makesProgressWhenBulkReadReturnsZero() {
        val expected = byteArrayOf(1, 2, 3, 4, 5)
        val source = object : ByteArrayInputStream(expected) {
            var returnedZero = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!returnedZero) {
                    returnedZero = true
                    return 0
                }
                return super.read(buffer, offset, length)
            }
        }
        val output = ByteArrayOutputStream()
        val digest = copyWithSha256(source, output, 4)
        assertEquals(expected.size.toLong(), digest.byteCount)
        assertTrue(expected.contentEquals(output.toByteArray()))
    }

    @Test
    fun recordingCopyDigest_detectsSameSizeContentCorruption() {
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
    fun cataloguedUnknownStorage_keepsItsDirectoryRecoveryGrant() {
        val unknown = RecordingEntity(
            id = "content://provider/document/audio",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 1L,
            durationMillis = 2L,
            sizeBytes = 3L,
            codecSummary = "PCM",
            storageType = "FUTURE_STORAGE",
            directoryId = "content://provider/tree/recordings",
        )
        assertEquals(
            setOf("content://provider/tree/recordings"),
            recordingDirectoryIdsToRetain(listOf(unknown)),
        )
        assertEquals(null, resolveRecordingStorageType(unknown))
    }

    @Test
    fun missingRecordingMarker_isStableUntilTheAssetReturns() {
        val recording = RecordingEntity(
            id = "id",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 500L,
            durationMillis = 1_000L,
            sizeBytes = 2_000L,
            codecSummary = "PCM 16-bit",
            storageType = RecordingStorageType.FILE.name,
            directoryId = "dir",
            createdAtMillis = 1_000L,
            lastSeenAtMillis = 1_000L,
        )

        val firstMissing = markRecordingMissing(recording, nowMillis = 2_000L)
        val stillMissingMuchLater = markRecordingMissing(firstMissing, nowMillis = Long.MAX_VALUE)
        val restored = markRecordingPresent(stillMissingMuchLater, nowMillis = Long.MAX_VALUE)

        assertEquals(2_000L, firstMissing.missingSinceMillis)
        assertEquals(firstMissing, stillMissingMuchLater)
        assertEquals(null, restored.missingSinceMillis)
        assertEquals(Long.MAX_VALUE, restored.lastSeenAtMillis)
    }

    @Test
    fun mergeObservedRecording_preservesOriginalCreationTime_andClearsMissingState() {
        val existing = RecordingEntity(
            id = "id",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 500L,
            durationMillis = 1_000L,
            sizeBytes = 2_000L,
            codecSummary = "PCM 16-bit",
            storageType = RecordingStorageType.FILE.name,
            directoryId = "dir",
            createdAtMillis = 10L,
            lastSeenAtMillis = 20L,
            missingSinceMillis = 30L,
        )
        val observed = existing.copy(createdAtMillis = 999L, lastSeenAtMillis = 999L, missingSinceMillis = 999L)

        val merged = mergeObservedRecording(existing, observed, nowMillis = 40L)

        assertEquals(10L, merged.createdAtMillis)
        assertEquals(40L, merged.lastSeenAtMillis)
        assertEquals(null, merged.missingSinceMillis)
    }

    @Test
    fun mergeObservedRecording_doesNotRewriteHealthyLastSeenTimestamp() {
        val existing = RecordingEntity(
            id = "id",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 500L,
            durationMillis = 1_000L,
            sizeBytes = 2_000L,
            codecSummary = "PCM 16-bit",
            storageType = RecordingStorageType.FILE.name,
            directoryId = "dir",
            createdAtMillis = 10L,
            lastSeenAtMillis = 20L,
            missingSinceMillis = null,
        )

        val merged = mergeObservedRecording(existing, existing, nowMillis = 40L)

        assertEquals(existing, merged)
    }

    @Test
    fun formatShortTimer_handlesMinuteAndHourBoundaries() {
        assertEquals("0:00", formatShortTimer(0f))
        assertEquals("1:05", formatShortTimer(65.9f))
        assertEquals("1:01:01", formatShortTimer(3661.2f))
    }

    @Test
    fun formatShortFileSize_usesStableHumanReadableUnits() {
        assertEquals("0.0 MiB", formatShortFileSize(0))
        assertEquals("0.0 MiB", formatShortFileSize(1024))
        assertEquals("0.0 MiB", formatShortFileSize(1536))
        assertEquals("1.0 MiB", formatShortFileSize(1024 * 1024))
    }

    @Test
    fun recordingEndTimestampMillis_usesRangeEnd_andSaturatesOverflow() {
        assertEquals(3_500L, recordingEndTimestampMillis(1_000L, 2.5))
        assertEquals(1_000L, recordingEndTimestampMillis(1_000L, -1.0))
        assertEquals(Long.MAX_VALUE, recordingEndTimestampMillis(Long.MAX_VALUE - 500L, 1.0))
    }

    @Test
    fun oneShotWritableBytes_zeroCapacityDisablesWrites() {
        assertEquals(0L, oneShotWritableBytes(RetentionMode.SIZE, 0L, 0L, 0.0, 48_000, 2))
        assertEquals(0L, oneShotWritableBytes(RetentionMode.TIME, 0L, 0L, 0.0, 48_000, 2))
    }

    @Test
    fun defaultStartupBufferSlot_prefersOneShotUntilItIsFull() {
        assertEquals(
            ReverbService.BufferSlot.ONE_SHOT,
            defaultStartupBufferSlot(oneShotEnabled = true, oneShotFull = false, loopingEnabled = true),
        )
        assertEquals(
            ReverbService.BufferSlot.LOOPING,
            defaultStartupBufferSlot(oneShotEnabled = true, oneShotFull = true, loopingEnabled = true),
        )
    }

    @Test
    fun defaultStartupBufferSlot_respectsDisabledBuffers() {
        assertEquals(
            ReverbService.BufferSlot.LOOPING,
            defaultStartupBufferSlot(oneShotEnabled = false, oneShotFull = false, loopingEnabled = true),
        )
        assertEquals(
            ReverbService.BufferSlot.ONE_SHOT,
            defaultStartupBufferSlot(oneShotEnabled = true, oneShotFull = true, loopingEnabled = false),
        )
    }

    @Test
    fun captureTarget_respectsManualChoice_andFallsBackOnlyWhenUnavailable() {
        assertEquals(
            ReverbService.BufferSlot.ONE_SHOT,
            resolveCaptureBufferSlot(
                requested = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            ReverbService.BufferSlot.LOOPING,
            resolveCaptureBufferSlot(
                requested = ReverbService.BufferSlot.LOOPING,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            ReverbService.BufferSlot.LOOPING,
            resolveCaptureBufferSlot(
                requested = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = true,
                oneShotFull = true,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            null,
            resolveCaptureBufferSlot(
                requested = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = true,
                oneShotFull = true,
                loopingEnabled = false,
            ),
        )
        assertEquals(
            null,
            resolveCaptureBufferSlot(
                requested = ReverbService.BufferSlot.LOOPING,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = false,
            ),
        )
        assertEquals(
            null,
            resolveCaptureBufferSlot(
                requested = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = false,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
    }

    @Test
    fun availableCaptureTarget_fallsBackWhenThePersistedTargetBecomesUnavailable() {
        assertEquals(
            ReverbService.BufferSlot.LOOPING,
            resolveAvailableCaptureBufferSlot(
                preferred = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = false,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            ReverbService.BufferSlot.LOOPING,
            resolveAvailableCaptureBufferSlot(
                preferred = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = true,
                oneShotFull = true,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            ReverbService.BufferSlot.ONE_SHOT,
            resolveAvailableCaptureBufferSlot(
                preferred = ReverbService.BufferSlot.LOOPING,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = false,
            ),
        )
        assertEquals(
            null,
            resolveAvailableCaptureBufferSlot(
                preferred = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = false,
                oneShotFull = false,
                loopingEnabled = false,
            ),
        )
    }

    @Test
    fun captureTarget_activationRequiresTheRequestedBufferToBeUsable() {
        assertTrue(
            canActivateCaptureBuffer(
                requested = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertFalse(
            canActivateCaptureBuffer(
                requested = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = false,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertFalse(
            canActivateCaptureBuffer(
                requested = ReverbService.BufferSlot.ONE_SHOT,
                oneShotEnabled = true,
                oneShotFull = true,
                loopingEnabled = true,
            ),
        )
        assertTrue(
            canActivateCaptureBuffer(
                requested = ReverbService.BufferSlot.LOOPING,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
    }

    @Test
    fun captureReaderTransition_adoptsNewGenerationWithoutSilentlyStoppingCapture() {
        assertEquals(
            CaptureReaderTransition.ADOPT,
            captureReaderTransition(8L, 8L, listening = true, recordRunning = true),
        )
        assertEquals(
            CaptureReaderTransition.RESTART,
            captureReaderTransition(8L, 8L, listening = true, recordRunning = false),
        )
        assertEquals(
            CaptureReaderTransition.IGNORE,
            captureReaderTransition(7L, 8L, listening = true, recordRunning = true),
        )
        assertEquals(
            CaptureReaderTransition.IGNORE,
            captureReaderTransition(8L, 8L, listening = false, recordRunning = true),
        )
    }

    @Test
    fun recorderIntentPersistence_onlyWritesWhenDurableIntentChanges() {
        assertFalse(
            captureSlotNeedsPersistence(
                ReverbService.BufferSlot.LOOPING.name,
                ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(captureSlotNeedsPersistence(null, ReverbService.BufferSlot.LOOPING))
        assertTrue(
            captureSlotNeedsPersistence(
                ReverbService.BufferSlot.ONE_SHOT.name,
                ReverbService.BufferSlot.LOOPING,
            ),
        )

        assertFalse(
            captureIntentNeedsPersistence(
                previousEnabled = true,
                requestedEnabled = true,
                previousStoredSlot = ReverbService.BufferSlot.LOOPING.name,
                requestedSlot = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(
            captureIntentNeedsPersistence(
                previousEnabled = true,
                requestedEnabled = true,
                previousStoredSlot = ReverbService.BufferSlot.ONE_SHOT.name,
                requestedSlot = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(
            captureIntentNeedsPersistence(
                previousEnabled = false,
                requestedEnabled = true,
                previousStoredSlot = ReverbService.BufferSlot.LOOPING.name,
                requestedSlot = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(
            captureIntentNeedsPersistence(
                previousEnabled = true,
                requestedEnabled = false,
                previousStoredSlot = ReverbService.BufferSlot.LOOPING.name,
                requestedSlot = ReverbService.BufferSlot.LOOPING,
            ),
        )
    }

    @Test
    fun foregroundServiceTypes_coverRecorderAndExportLifetimeIndependently() {
        assertEquals(0, foregroundServiceTypesForWork(listening = false, exporting = false))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypesForWork(listening = true, exporting = false),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundServiceTypesForWork(listening = false, exporting = true),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypesForWork(listening = true, exporting = true),
        )
    }

    @Test
    fun foregroundRestriction_blocksAutomaticRetry_withoutErasingDurableIntent() {
        assertTrue(shouldAttemptAutomaticListeningStart(listeningIntentEnabled = true, foregroundStartBlocked = false))
        assertFalse(shouldAttemptAutomaticListeningStart(listeningIntentEnabled = true, foregroundStartBlocked = true))
        assertFalse(shouldAttemptAutomaticListeningStart(listeningIntentEnabled = false, foregroundStartBlocked = false))
        assertFalse(shouldAttemptAutomaticListeningStart(listeningIntentEnabled = false, foregroundStartBlocked = true))
    }

    @Test
    fun foregroundBind_retriesSuspendedDurableIntent() {
        assertTrue(shouldRetrySuspendedListeningOnForegroundBind(true, foregroundStartBlocked = true, foregroundServiceTimedOut = false))
        assertTrue(shouldRetrySuspendedListeningOnForegroundBind(true, foregroundStartBlocked = false, foregroundServiceTimedOut = true))
        assertFalse(shouldRetrySuspendedListeningOnForegroundBind(true, foregroundStartBlocked = false, foregroundServiceTimedOut = false))
        assertFalse(shouldRetrySuspendedListeningOnForegroundBind(false, foregroundStartBlocked = true, foregroundServiceTimedOut = true))
    }

    @Test
    fun logicalListeningState_followsNewestUserIntentDuringAsyncStop() {
        assertTrue(isLogicalListeningState(ReverbService.STATE_LISTENING, listeningIntentEnabled = true))
        assertFalse(isLogicalListeningState(ReverbService.STATE_LISTENING, listeningIntentEnabled = false))
        assertFalse(isLogicalListeningState(ReverbService.STATE_READY, listeningIntentEnabled = true))
    }

    @Test
    fun recorderStateSnapshot_rejectsStaleCommandsAndPreviousServiceConnections() {
        assertTrue(shouldApplyRecorderStateSnapshot(3L, 3L, 7L, 7L))
        assertTrue(shouldApplyRecorderStateSnapshot(3L, 3L, 8L, 7L))
        assertFalse(shouldApplyRecorderStateSnapshot(3L, 3L, 6L, 7L))
        assertFalse(shouldApplyRecorderStateSnapshot(2L, 3L, 100L, 7L))
        assertTrue(shouldApplyRecorderStateSnapshot(4L, 4L, 0L, Long.MIN_VALUE))
    }

    @Test
    fun bufferRecordingIndicator_requiresListeningEvenWhenLastActiveSlotMatches() {
        assertFalse(
            isBufferActivelyRecording(
                ReverbService.BufferSlot.ONE_SHOT,
                isListening = false,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            ),
        )
        assertTrue(
            isBufferActivelyRecording(
                ReverbService.BufferSlot.ONE_SHOT,
                isListening = true,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            ),
        )
    }

    @Test
    fun captureUiState_distinguishesRunningFilledDisabled_andOtherBufferBlock() {
        assertEquals(
            CaptureBufferUiState.RECORDING,
            captureBufferUiState(
                ReverbService.BufferSlot.ONE_SHOT,
                enabled = true,
                oneShotFull = false,
                isListening = true,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            ),
        )
        assertTrue(
            isCaptureBlockedByOtherBuffer(
                ReverbService.BufferSlot.LOOPING,
                isListening = true,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            ),
        )
        assertEquals(
            CaptureBufferUiState.FILLED,
            captureBufferUiState(
                ReverbService.BufferSlot.ONE_SHOT,
                enabled = true,
                oneShotFull = true,
                isListening = true,
                activeBuffer = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(
            isCaptureBlockedByOtherBuffer(
                ReverbService.BufferSlot.ONE_SHOT,
                isListening = true,
                activeBuffer = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertEquals(
            CaptureBufferUiState.FILLED,
            captureBufferUiState(
                ReverbService.BufferSlot.ONE_SHOT,
                enabled = true,
                oneShotFull = true,
                isListening = false,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            ),
        )
        assertEquals(
            CaptureBufferUiState.DISABLED,
            captureBufferUiState(
                ReverbService.BufferSlot.LOOPING,
                enabled = false,
                oneShotFull = false,
                isListening = false,
                activeBuffer = null,
            ),
        )
        assertEquals(
            CaptureBufferUiState.READY,
            captureBufferUiState(
                ReverbService.BufferSlot.LOOPING,
                enabled = true,
                oneShotFull = false,
                isListening = false,
                activeBuffer = null,
            ),
        )
    }

    @Test
    fun quickTileRecording_requiresBothDurableIntentAndActualRuntimeCapture() {
        assertTrue(isTileCaptureActuallyRecording(listeningIntentEnabled = true, runtimeCaptureActive = true))
        assertFalse(isTileCaptureActuallyRecording(listeningIntentEnabled = true, runtimeCaptureActive = false))
        assertFalse(isTileCaptureActuallyRecording(listeningIntentEnabled = false, runtimeCaptureActive = true))
        assertFalse(isTileCaptureActuallyRecording(listeningIntentEnabled = false, runtimeCaptureActive = false))
    }

    @Test
    fun quickTileUiState_tracksActiveDestinationAndOnlyDisablesUnavailableBuffers() {
        val idleOneShot = RecordingTileSnapshot(
            listening = false,
            activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
        )
        assertEquals(RecordingTileUiState.ACTIVE, recordingTileUiState(ReverbService.BufferSlot.ONE_SHOT, idleOneShot))
        assertEquals(RecordingTileUiState.AVAILABLE, recordingTileUiState(ReverbService.BufferSlot.LOOPING, idleOneShot))

        val oneShotRunning = idleOneShot.copy(listening = true)
        assertEquals(RecordingTileUiState.RECORDING, recordingTileUiState(ReverbService.BufferSlot.ONE_SHOT, oneShotRunning))
        assertEquals(RecordingTileUiState.AVAILABLE, recordingTileUiState(ReverbService.BufferSlot.LOOPING, oneShotRunning))

        val oneShotFull = idleOneShot.copy(oneShotFull = true)
        assertEquals(RecordingTileUiState.FULL, recordingTileUiState(ReverbService.BufferSlot.ONE_SHOT, oneShotFull))

        val loopingDisabled = idleOneShot.copy(loopingEnabled = false)
        assertEquals(RecordingTileUiState.DISABLED, recordingTileUiState(ReverbService.BufferSlot.LOOPING, loopingDisabled))
    }

    @Test
    fun quickTileClick_togglesCurrentBufferAndSwitchesToOtherUsableBuffer() {
        val idleOneShot = RecordingTileSnapshot(
            listening = false,
            activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
        )
        assertEquals(
            RecordingTileClickAction.START,
            recordingTileClickAction(ReverbService.BufferSlot.ONE_SHOT, idleOneShot),
        )
        assertEquals(
            RecordingTileClickAction.START,
            recordingTileClickAction(ReverbService.BufferSlot.LOOPING, idleOneShot),
        )

        val oneShotRunning = idleOneShot.copy(listening = true)
        assertEquals(
            RecordingTileClickAction.STOP,
            recordingTileClickAction(ReverbService.BufferSlot.ONE_SHOT, oneShotRunning),
        )
        assertEquals(
            RecordingTileClickAction.SWITCH,
            recordingTileClickAction(ReverbService.BufferSlot.LOOPING, oneShotRunning),
        )
        assertEquals(
            RecordingTileClickAction.NONE,
            recordingTileClickAction(ReverbService.BufferSlot.ONE_SHOT, idleOneShot.copy(oneShotFull = true)),
        )
        assertEquals(
            RecordingTileClickAction.NONE,
            recordingTileClickAction(ReverbService.BufferSlot.LOOPING, idleOneShot.copy(loopingEnabled = false)),
        )
    }

    @Test
    fun quickTileClick_keepsPreBindUserIntentAcrossRecorderRecovery() {
        val idleOneShot = RecordingTileSnapshot(
            listening = false,
            activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
        )
        val oneShotRunning = idleOneShot.copy(listening = true)
        val loopingRunning = oneShotRunning.copy(activeBuffer = ReverbService.BufferSlot.LOOPING)

        assertEquals(
            RecordingTileClickAction.NONE,
            revalidateRecordingTileClickAction(
                requestedAction = RecordingTileClickAction.START,
                bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
                liveSnapshot = oneShotRunning,
            ),
        )
        assertEquals(
            RecordingTileClickAction.SWITCH,
            revalidateRecordingTileClickAction(
                requestedAction = RecordingTileClickAction.START,
                bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
                liveSnapshot = loopingRunning,
            ),
        )
        assertEquals(
            RecordingTileClickAction.START,
            revalidateRecordingTileClickAction(
                requestedAction = RecordingTileClickAction.SWITCH,
                bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
                liveSnapshot = idleOneShot,
            ),
        )
        assertEquals(
            RecordingTileClickAction.NONE,
            revalidateRecordingTileClickAction(
                requestedAction = RecordingTileClickAction.STOP,
                bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
                liveSnapshot = loopingRunning,
            ),
        )
        assertEquals(
            RecordingTileClickAction.STOP,
            revalidateRecordingTileClickAction(
                requestedAction = RecordingTileClickAction.STOP,
                bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
                liveSnapshot = oneShotRunning,
            ),
        )
        assertEquals(
            RecordingTileClickAction.NONE,
            revalidateRecordingTileClickAction(
                requestedAction = RecordingTileClickAction.START,
                bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
                liveSnapshot = idleOneShot.copy(oneShotFull = true),
            ),
        )
    }

    @Test
    fun oneShotFullCache_isInvalidatedWhenSettingsCanMakeAFullBufferWritableAgain() {
        assertTrue(
            shouldInvalidateCachedOneShotFull(
                previousMode = RetentionMode.SIZE, newMode = RetentionMode.SIZE,
                previousTimeSeconds = 60, newTimeSeconds = 60,
                previousSizeBytes = 64L, newSizeBytes = 128L,
                previousSampleRate = 48_000, newSampleRate = 48_000,
                previousChannelMode = ChannelMode.MONO, newChannelMode = ChannelMode.MONO,
                previousSampleFormat = PcmSampleFormat.PCM_16, newSampleFormat = PcmSampleFormat.PCM_16,
            ),
        )
        assertFalse(
            shouldInvalidateCachedOneShotFull(
                previousMode = RetentionMode.SIZE, newMode = RetentionMode.SIZE,
                previousTimeSeconds = 60, newTimeSeconds = 60,
                previousSizeBytes = 128L, newSizeBytes = 64L,
                previousSampleRate = 48_000, newSampleRate = 48_000,
                previousChannelMode = ChannelMode.MONO, newChannelMode = ChannelMode.MONO,
                previousSampleFormat = PcmSampleFormat.PCM_16, newSampleFormat = PcmSampleFormat.PCM_16,
            ),
        )
        assertTrue(
            shouldInvalidateCachedOneShotFull(
                previousMode = RetentionMode.TIME, newMode = RetentionMode.TIME,
                previousTimeSeconds = 60, newTimeSeconds = 120,
                previousSizeBytes = 64L, newSizeBytes = 64L,
                previousSampleRate = 48_000, newSampleRate = 48_000,
                previousChannelMode = ChannelMode.MONO, newChannelMode = ChannelMode.MONO,
                previousSampleFormat = PcmSampleFormat.PCM_16, newSampleFormat = PcmSampleFormat.PCM_16,
            ),
        )
        assertTrue(
            shouldInvalidateCachedOneShotFull(
                previousMode = RetentionMode.TIME, newMode = RetentionMode.SIZE,
                previousTimeSeconds = 60, newTimeSeconds = 60,
                previousSizeBytes = 64L, newSizeBytes = 64L,
                previousSampleRate = 48_000, newSampleRate = 48_000,
                previousChannelMode = ChannelMode.MONO, newChannelMode = ChannelMode.MONO,
                previousSampleFormat = PcmSampleFormat.PCM_16, newSampleFormat = PcmSampleFormat.PCM_16,
            ),
        )
    }

    @Test
    fun oneShotWritableBytes_stopsAtCapacity_withoutOverwriting() {
        assertEquals(
            4L,
            oneShotWritableBytes(RetentionMode.SIZE, 10L, 5L, 0.0, 48_000, 2),
        )
        assertEquals(
            0L,
            oneShotWritableBytes(RetentionMode.SIZE, 10L, 10L, 0.0, 48_000, 2),
        )
        assertEquals(
            48_000L,
            oneShotWritableBytes(RetentionMode.TIME, 2L, 0L, 1.5, 48_000, 2),
        )
    }

    @Test
    fun oneShotRetainedChunkBytes_truncatesTailToFrameAlignedCapacity() {
        assertEquals(8L, oneShotRetainedChunkBytes(12L, 4L, 10L, 2))
        assertEquals(6L, oneShotRetainedChunkBytes(11L, 4L, 10L, 2))
        assertEquals(0L, oneShotRetainedChunkBytes(4L, 4L, 10L, 2))
        assertEquals(10L, oneShotRetainedChunkBytes(20L, 4L, 10L, 2))
    }

    @Test
    fun oneShotRetainedChunkBytesForTime_truncatesTailWithoutExtendingDuration() {
        assertEquals(
            48_000L,
            oneShotRetainedChunkBytesForTime(
                retentionSeconds = 2L,
                retainedDurationBeforeChunk = 1.5,
                chunkSampleFrames = 48_000L,
                sampleRate = 48_000,
                frameBytes = 2,
            ),
        )
        assertEquals(
            96_000L,
            oneShotRetainedChunkBytesForTime(
                retentionSeconds = 2L,
                retainedDurationBeforeChunk = 1.0000000000000002,
                chunkSampleFrames = 48_000L,
                sampleRate = 48_000,
                frameBytes = 2,
            ),
        )
        assertEquals(
            0L,
            oneShotRetainedChunkBytesForTime(2L, 2.0, 48_000L, 48_000, 2),
        )
    }

    @Test
    fun wavSampleFormatBytes_roundTripCorrectly() {
        val stereo48k = { sr: Int, ch: Int, fmt: PcmSampleFormat ->
            val bytes = bytesForRetentionSeconds(100, sr, ch, fmt)
            val secs = retentionSecondsForBytes(bytes, sr, ch, fmt)
            assertEquals(100L, secs)
        }
        stereo48k(48_000, 2, PcmSampleFormat.PCM_16)
        stereo48k(48_000, 2, PcmSampleFormat.PCM_8)
        stereo48k(44_100, 1, PcmSampleFormat.PCM_FLOAT)
    }

    @Test
    fun durationInput_roundTrips_expectedFormats() {
        assertEquals(5 * 60, parseDurationInput("5"))
        assertEquals(65, parseDurationInput("1:05"))
        assertEquals(3_661, parseDurationInput("1:01:01"))
        assertEquals("5:00", formatDurationInput(5 * 60))
        assertEquals("1:05", formatDurationInput(65))
        assertEquals("1:01:01", formatDurationInput(3_661))
    }

    @Test
    fun pcmSampleFormat_constantsAreCorrect() {
        assertEquals(1, PcmSampleFormat.PCM_8.bytesPerSample)
        assertEquals(2, PcmSampleFormat.PCM_16.bytesPerSample)
        assertEquals(4, PcmSampleFormat.PCM_FLOAT.bytesPerSample)
        assertEquals(8, PcmSampleFormat.PCM_8.bitsPerSample)
        assertEquals(16, PcmSampleFormat.PCM_16.bitsPerSample)
        assertEquals(32, PcmSampleFormat.PCM_FLOAT.bitsPerSample)
    }

    @Test
    fun sampleRatePreference_prefers44k1_thenNearestHigher() {
        assertEquals(
            listOf(44_100, 48_000, 32_000),
            orderSampleRatesByPreference(listOf(48_000, 32_000, 44_100), 44_100),
        )
        assertEquals(
            listOf(48_000, 32_000, 24_000),
            orderSampleRatesByPreference(listOf(48_000, 24_000, 32_000), 44_100),
        )
    }

    @Test
    fun estimateExportDurationPcm_scalesWithSizeAndFormat() {
        val smaller = estimateExportDurationSeconds(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleRate = 44_100,
            channelCount = 1,
            sizeBytes = 1_000_000L,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        val larger = estimateExportDurationSeconds(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleRate = 44_100,
            channelCount = 1,
            sizeBytes = 10_000_000L,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        assertTrue(larger > smaller)

        val pcm8 = estimateExportDurationSeconds(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleRate = 44_100,
            channelCount = 1,
            sizeBytes = 1_000_000L,
            sampleFormat = PcmSampleFormat.PCM_8,
        )
        assertTrue(pcm8 > smaller)
    }

    @Test
    fun bytesForRetentionSeconds_handlesLongDurationsWithoutHeapClamp() {
        val fortyEightHours = 48L * 60L * 60L
        val bytes = bytesForRetentionSeconds(fortyEightHours, 48_000, 1)

        assertEquals(16_588_800_000L, bytes)
        assertEquals(fortyEightHours, retentionSecondsForBytes(bytes, 48_000, 1))
    }

    @Test
    fun wavCodecFormatMatrix_hasPcmOnly() {
        assertTrue(ExportFormat.WAV in supportedFormats())
        assertTrue(isCodecCompatibleWithFormat(ExportFormat.WAV, ExportCodec.PCM_16))
    }

    @Test
    fun audioSourceMode_prefersVoiceModesBeforeMicAndUnprocessed() {
        val ordered = AudioSourceMode.availableModes()

        assertEquals(AudioSourceMode.VOICE_RECOGNITION, AudioSourceMode.defaultMode())
        assertTrue(ordered.indexOf(AudioSourceMode.VOICE_RECOGNITION) < ordered.indexOf(AudioSourceMode.MIC))
        assertTrue(ordered.indexOf(AudioSourceMode.VOICE_COMMUNICATION) < ordered.indexOf(AudioSourceMode.UNPROCESSED))
        assertFalse(ordered.contains(AudioSourceMode.VOICE_CALL))
        assertFalse(ordered.contains(AudioSourceMode.VOICE_UPLINK))
        assertFalse(ordered.contains(AudioSourceMode.VOICE_DOWNLINK))
        assertFalse(ordered.contains(AudioSourceMode.REMOTE_SUBMIX))
    }

    @Test
    fun wavConfigurationConstraints_onlyPcm() {
        assertTrue(isExportConfigurationSupported(ExportFormat.WAV, ExportCodec.PCM_16, 44_100, 1))
        assertTrue(isExportConfigurationSupported(ExportFormat.WAV, ExportCodec.PCM_16, 48_000, 2))
        assertTrue(isExportConfigurationSupported(ExportFormat.WAV, ExportCodec.PCM_16, 8_000, 1))
        assertFalse(isExportConfigurationSupported(ExportFormat.WAV, ExportCodec.PCM_16, 0, 1))
        assertFalse(isExportConfigurationSupported(ExportFormat.WAV, ExportCodec.PCM_16, 44_100, 3))
        assertEquals(listOf(ExportFormat.WAV), supportedFormats())
        assertEquals(listOf(ExportCodec.PCM_16), supportedCodecs(ExportFormat.WAV))
    }

    @Test
    fun wavExportSizeLimit_isJustUnderFourGiB() {
        val limit = exportFileSizeLimitBytes(ExportFormat.WAV)
        assertEquals(0xFFFF_FFFFL, limit)
        assertEquals(0xFFFF_FFFFL - 44L, exportPayloadLimitBytes(ExportFormat.WAV))
        assertEquals(
            0xFFFF_FFFFL - 58L,
            exportPayloadLimitBytes(ExportFormat.WAV, PcmSampleFormat.PCM_FLOAT),
        )
    }

    @Test
    fun pcm8WavExportSize_accountsForOddDataPadding() {
        assertEquals(
            44L + 11_025L + 1L,
            estimateExportSizeBytes(
                format = ExportFormat.WAV,
                codec = ExportCodec.PCM_16,
                sampleRate = 11_025,
                channelCount = 1,
                durationSeconds = 1,
                sampleFormat = PcmSampleFormat.PCM_8,
            ),
        )
        assertEquals(
            1L,
            estimateExportDurationSeconds(
                format = ExportFormat.WAV,
                codec = ExportCodec.PCM_16,
                sampleRate = 11_025,
                channelCount = 1,
                sizeBytes = 44L + 11_025L + 1L,
                sampleFormat = PcmSampleFormat.PCM_8,
            ),
        )
        assertEquals(
            0L,
            estimateExportDurationSeconds(
                format = ExportFormat.WAV,
                codec = ExportCodec.PCM_16,
                sampleRate = 11_025,
                channelCount = 1,
                sizeBytes = 44L + 11_025L,
                sampleFormat = PcmSampleFormat.PCM_8,
            ),
        )
    }

    @Test
    fun floatWavExportSize_accountsForExtendedFmtChunk() {
        assertEquals(
            58L + 44_100L * PcmSampleFormat.PCM_FLOAT.bytesPerSample,
            estimateExportSizeBytes(
                format = ExportFormat.WAV,
                codec = ExportCodec.PCM_16,
                sampleRate = 44_100,
                channelCount = 1,
                durationSeconds = 1,
                sampleFormat = PcmSampleFormat.PCM_FLOAT,
            ),
        )
    }

    @Test
    fun pcmByteRate_proportionalToSampleFormat() {
        val mono48k_16bit = bytesForRetentionSeconds(1, 48_000, 1, PcmSampleFormat.PCM_16)
        val mono48k_8bit = bytesForRetentionSeconds(1, 48_000, 1, PcmSampleFormat.PCM_8)
        val mono48k_float = bytesForRetentionSeconds(1, 48_000, 1, PcmSampleFormat.PCM_FLOAT)
        assertEquals(96_000L, mono48k_16bit)
        assertEquals(48_000L, mono48k_8bit)
        assertEquals(192_000L, mono48k_float)
    }

    @Test
    fun wavExportDurationLimit_doesNotOverflow() {
        val limit = exportDurationLimitSeconds(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleRate = 48_000,
            channelCount = 2,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        assertTrue(limit > 0L)
        assertTrue(limit < Long.MAX_VALUE / 2)
    }

    @Test
    fun parseDurationInput_rejectsOverflowInput() {
        assertEquals(null, parseDurationInput("35791395"))
        assertEquals(null, parseDurationInput("100000000"))
        assertEquals(null, parseDurationInput("99999:99:99"))
    }

    @Test
    fun parseDurationInput_handlesZeroInput() {
        assertEquals(0, parseDurationInput("0"))
        assertEquals(0, parseDurationInput("0:00"))
        assertEquals(0, parseDurationInput("0:0:0"))
        assertEquals(null, parseDurationInput(""))
        assertEquals(null, parseDurationInput("   "))
    }

    @Test
    fun parseDurationInput_acceptsLargeButValidInput() {
        val maxMinutes = 35791394
        assertEquals(maxMinutes * 60, parseDurationInput(maxMinutes.toString()))
        assertEquals(23 * 3600 + 59 * 60 + 59, parseDurationInput("23:59:59"))
    }

    @Test
    fun parseDurationInput_rejectsInvalidParts() {
        assertEquals(null, parseDurationInput("abc"))
        assertEquals(null, parseDurationInput("5:abc"))
        assertEquals(null, parseDurationInput("1:2:3:4"))
        assertEquals(null, parseDurationInput("1:99"))
        assertEquals(null, parseDurationInput("1:2:99"))
        assertEquals(null, parseDurationInput("-5"))
    }

    @Test
    fun bytesForRetentionSeconds_handlesZeroAndNegativeInput() {
        assertEquals(0L, bytesForRetentionSeconds(0, 44_100, 1))
        assertEquals(0L, bytesForRetentionSeconds(-1, 44_100, 1))
        assertEquals(0L, bytesForRetentionSeconds(100, 0, 1))
        assertEquals(0L, bytesForRetentionSeconds(100, 44_100, 0))
    }

    @Test
    fun retentionSecondsForBytes_handlesZeroAndEdgeCases() {
        assertEquals(0L, retentionSecondsForBytes(0, 44_100, 1))
        assertEquals(0L, retentionSecondsForBytes(100, 0, 1))
        assertEquals(0L, retentionSecondsForBytes(100, 44_100, 0))
        val mono48k16bit = bytesForRetentionSeconds(1, 48_000, 1, PcmSampleFormat.PCM_16)
        assertEquals(10L, retentionSecondsForBytes(mono48k16bit * 10, 48_000, 1))
    }

    @Test
    fun estimateExportSizeRoundTrip_matchesConfiguredSize() {
        val configuredBytes = 100_000_000L
        val seconds = estimateExportDurationSeconds(
            format = ExportFormat.WAV, codec = ExportCodec.PCM_16,
            sampleRate = 44_100, channelCount = 1,
            sizeBytes = configuredBytes,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        assertTrue(seconds > 0L)
        val backToBytes = bytesForRetentionSeconds(seconds, 44_100, 1, PcmSampleFormat.PCM_16)
        // Round-trip should be within one seconds-worth of bytes of configured
        val bps = 44_100L * 1L * PcmSampleFormat.PCM_16.bytesPerSample
        assertTrue(backToBytes <= configuredBytes)
        assertTrue(backToBytes + bps >= configuredBytes)
    }

    @Test
    fun estimateExportDuration_respectsSampleFormat() {
        val pcm8 = estimateExportDurationSeconds(
            format = ExportFormat.WAV, codec = ExportCodec.PCM_16,
            sampleRate = 44_100, channelCount = 1,
            sizeBytes = 1_000_000L,
            sampleFormat = PcmSampleFormat.PCM_8,
        )
        val pcm16 = estimateExportDurationSeconds(
            format = ExportFormat.WAV, codec = ExportCodec.PCM_16,
            sampleRate = 44_100, channelCount = 1,
            sizeBytes = 1_000_000L,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        val pcmFloat = estimateExportDurationSeconds(
            format = ExportFormat.WAV, codec = ExportCodec.PCM_16,
            sampleRate = 44_100, channelCount = 1,
            sizeBytes = 1_000_000L,
            sampleFormat = PcmSampleFormat.PCM_FLOAT,
        )
        // PCM_8 fits 4x more duration than PCM_FLOAT in same byte budget
        assertTrue(pcm8 in (pcm16 * 2 - 1)..(pcm16 * 2 + 1))
        assertTrue(pcmFloat in (pcm16 / 2 - 1)..(pcm16 / 2 + 1))
    }

    @Test
    fun formatDurationInput_handlesEdgeCases() {
        assertEquals("0:00", formatDurationInput(0))
        assertEquals("0:01", formatDurationInput(1))
        assertEquals("59:59", formatDurationInput(3599))
        assertEquals("1:00:00", formatDurationInput(3600))
        assertEquals("99:59:59", formatDurationInput(359999))
    }

    @Test
    fun orderSampleRatesByPreference_handlesEmptyAndDuplicateInput() {
        assertEquals(emptyList<Int>(), orderSampleRatesByPreference(emptyList(), 44_100))
        assertEquals(listOf(44_100), orderSampleRatesByPreference(listOf(44_100, 44_100), 44_100))
        assertEquals(
            listOf(44_100, 48_000, 96_000),
            orderSampleRatesByPreference(listOf(96_000, 48_000, 44_100), 44_100),
        )
    }

    @Test
    fun sizeRetention_normalizesToWholeFrames() {
        assertEquals(0L, normalizeRetentionValue(RetentionMode.SIZE, 1L, 2))
        assertEquals(4L, normalizeRetentionValue(RetentionMode.SIZE, 5L, 2))
        assertEquals(5L, normalizeRetentionValue(RetentionMode.TIME, 5L, 2))
    }

    @Test
    fun rangeTimeInput_preservesSubSecondBounds_withoutRoundingIntoFuture() {
        val available = 60.9999
        val formatted = formatRangeTimeInput(available)
        val parsed = requireNotNull(parseRangeTimeInput(formatted))

        assertEquals("1:00.9", formatted)
        assertTrue(parsed <= available)
        assertTrue(available - parsed < 0.1001)
    }

    @Test
    fun rangeTimeInput_alwaysDisplaysOneFractionDigit() {
        assertEquals("0:00.0", formatRangeTimeInput(0.0))
        assertEquals("0:00.1", formatRangeTimeInput(0.199))
        assertEquals("1:01.0", formatRangeTimeInput(61.0))
        assertEquals("1:01.9", formatRangeTimeInput(61.999))
        assertEquals("1:01:01.1", formatRangeTimeInput(3661.199))
    }

    @Test
    fun rangeTimeInput_parsesClockValuesWithMilliseconds() {
        assertEquals(60.6, requireNotNull(parseRangeTimeInput("1:00.600")), 0.000001)
        assertEquals(3661.125, requireNotNull(parseRangeTimeInput("1:01:01.125")), 0.000001)
        assertEquals(5.25, requireNotNull(parseRangeTimeInput("5.250")), 0.000001)
        assertEquals(null, parseRangeTimeInput("1:60.000"))
        assertEquals(null, parseRangeTimeInput("1:60:00.000"))
        assertEquals(null, parseRangeTimeInput("-1:00.000"))
    }

    @Test
    fun customRangeSnapshot_rejectsStaleGenerationAndWrongTab() {
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val looping = ReverbService.BufferSlot.LOOPING

        assertTrue(shouldApplyCustomRangeSnapshot(7L, 7L, oneShot, oneShot, oneShot))
        assertFalse(shouldApplyCustomRangeSnapshot(6L, 7L, oneShot, oneShot, oneShot))
        assertFalse(shouldApplyCustomRangeSnapshot(7L, 7L, oneShot, null, oneShot))
        assertFalse(shouldApplyCustomRangeSnapshot(7L, 7L, oneShot, oneShot, looping))
    }

    @Test
    fun exportCancelRequest_keepsSavingStatusVisibleAndDisablesRepeatCancel() {
        assertEquals(
            CaptureSaveStatus.Saving(cancellable = false),
            markExportCancelRequested(CaptureSaveStatus.Saving(cancellable = true)),
        )
        assertEquals(
            CaptureSaveStatus.Saving(cancellable = false),
            markExportCancelRequested(CaptureSaveStatus.Saving(cancellable = false)),
        )
        assertEquals(null, markExportCancelRequested(null))
    }

    @Test
    fun retentionPresentation_roundsWithoutChangingIndependentBackingValues() {
        val exactSizeBytes = 123_456_789L
        val snapshot = SettingsSnapshot(
            oneShotRetentionTime = 61,
            oneShotRetentionSizeBytes = exactSizeBytes,
            loopingRetentionTime = 3_599,
            loopingRetentionSizeBytes = 987_654_321L,
        )

        assertEquals("0:01:01", formatRetentionTimeInput(snapshot.oneShotRetentionTime.toLong()))
        assertEquals(61, parseRetentionTimeSeconds(formatRetentionTimeInput(61)))
        assertEquals(3_599, parseRetentionTimeSeconds(formatRetentionTimeInput(3_599)))
        assertEquals("0:59:59", formatRetentionTimeInput(3_599))
        assertEquals("1:00:00", formatRetentionTimeInput(3_600))
        assertEquals(90, parseRetentionTimeSeconds("1.5"))
        assertEquals(90, parseRetentionTimeSeconds("1,5"))
        assertEquals(65, parseRetentionTimeSeconds("1:05"))
        assertEquals(null, parseRetentionTimeSeconds("-1.5"))
        assertEquals(exactSizeBytes, snapshot.oneShotRetentionSizeBytes)
        assertEquals("117.738", formatRetentionSizeBytes(exactSizeBytes))
        assertTrue(
            rawMegabytesToBytes(requireNotNull(parseRetentionSizeMib(formatRetentionSizeBytes(exactSizeBytes)))) !=
                exactSizeBytes,
        )
        assertEquals(exactSizeBytes, snapshot.oneShotRetentionSizeBytes)
    }

    @Test
    fun mergeObservedRecording_doesNotEraseKnownMetadataWhenInspectionIsIncomplete() {
        val existing = RecordingEntity(
            id = "id", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 500L, durationMillis = 9_000L, sizeBytes = 123_456L,
            codecSummary = "PCM 16 · 48 kHz", storageType = RecordingStorageType.FILE.name,
            directoryId = "dir", createdAtMillis = 10L, lastSeenAtMillis = 20L,
        )
        val partial = existing.copy(
            mimeType = "", durationMillis = 0L, sizeBytes = 0L, codecSummary = "", createdAtMillis = 999L,
        )

        val merged = mergeObservedRecording(existing, partial, nowMillis = 30L)

        assertEquals(existing.mimeType, merged.mimeType)
        assertEquals(existing.durationMillis, merged.durationMillis)
        assertEquals(existing.sizeBytes, merged.sizeBytes)
        assertEquals(existing.codecSummary, merged.codecSummary)
        assertEquals(existing.createdAtMillis, merged.createdAtMillis)
    }

    @Test
    fun panelRevealProgress_tracksDragDistanceContinuously() {
        assertEquals(0f, panelRevealProgress(-10f, 100f), 0.0001f)
        assertEquals(0.25f, panelRevealProgress(25f, 100f), 0.0001f)
        assertEquals(1f, panelRevealProgress(150f, 100f), 0.0001f)
        assertEquals(0f, panelRevealProgress(25f, 0f), 0.0001f)
        assertFalse(shouldCommitPanelReveal(0.119f))
        assertTrue(shouldCommitPanelReveal(0.12f))
    }

    @Test
    fun bufferSwipeProgress_tracksOnlyTheForwardDirection() {
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val looping = ReverbService.BufferSlot.LOOPING

        assertEquals(0.25f, bufferSwipeProgress(oneShot, -25f, 100f), 0.0001f)
        assertEquals(0f, bufferSwipeProgress(oneShot, 25f, 100f), 0.0001f)
        assertEquals(0.25f, bufferSwipeProgress(looping, 25f, 100f), 0.0001f)
        assertEquals(0f, bufferSwipeProgress(looping, -25f, 100f), 0.0001f)
        assertEquals(0f, bufferSwipeProgress(oneShot, -25f, 0f), 0.0001f)
        assertFalse(shouldCommitBufferSwipe(0.159f))
        assertTrue(shouldCommitBufferSwipe(0.16f))
    }

    @Test
    fun bufferFlip_tracksProgressAndChangesFaceAtTheMidpoint() {
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val looping = ReverbService.BufferSlot.LOOPING

        assertEquals(oneShot, bufferTransitionDisplayedSlot(oneShot, looping, 0.49f))
        assertEquals(looping, bufferTransitionDisplayedSlot(oneShot, looping, 0.5f))
        assertEquals(-45f, bufferTransitionFlipDegrees(oneShot, looping, 0.25f), 0.0001f)
        assertEquals(-90f, bufferTransitionFlipDegrees(oneShot, looping, 0.5f), 0.0001f)
        assertEquals(45f, bufferTransitionFlipDegrees(oneShot, looping, 0.75f), 0.0001f)
        assertEquals(0f, bufferTransitionFlipDegrees(oneShot, looping, 1f), 0.0001f)
        assertEquals(45f, bufferTransitionFlipDegrees(looping, oneShot, 0.25f), 0.0001f)
    }
}
