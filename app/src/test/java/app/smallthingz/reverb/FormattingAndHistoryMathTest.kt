package app.smallthingz.reverb

import android.content.pm.ServiceInfo
import androidx.activity.BackEventCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingAndHistoryMathTest {
    @Test
    fun feedbackQueue_isBoundedOrderedAndStaleAcknowledgeSafe() {
        val first = FeedbackEvent(1L, "first", FeedbackTone.INFO)
        val second = FeedbackEvent(2L, "second", FeedbackTone.ERROR)
        val third = FeedbackEvent(3L, "third", FeedbackTone.SUCCESS)

        val bounded = enqueueFeedbackEvent(listOf(first, second), third, maxEvents = 2)
        assertEquals(listOf(second, third), bounded)
        assertEquals(bounded, acknowledgeFeedbackEvent(bounded, third.id))
        assertEquals(listOf(third), acknowledgeFeedbackEvent(bounded, second.id))
    }

    @Test
    fun libraryEmptyState_waitsForAuthoritativeFirstLoad() {
        assertFalse(libraryEmptyStateVisible(hasLoaded = false, listEmpty = true, isRefreshing = false))
        assertFalse(libraryEmptyStateVisible(hasLoaded = true, listEmpty = true, isRefreshing = true))
        assertFalse(libraryEmptyStateVisible(hasLoaded = true, listEmpty = false, isRefreshing = false))
        assertTrue(libraryEmptyStateVisible(hasLoaded = true, listEmpty = true, isRefreshing = false))
    }

    @Test
    fun directoryReconciliation_skipsDuplicateFallbackProbesOnlyForKnownHandledDirectories() {
        val handled = setOf("configured", "persisted", "legacy")
        assertFalse(recordingNeedsFallbackAssetProbe("configured", handled))
        assertFalse(recordingNeedsFallbackAssetProbe("persisted", handled))
        assertFalse(recordingNeedsFallbackAssetProbe("legacy", handled))
        assertTrue(recordingNeedsFallbackAssetProbe("unavailable-or-unscanned", handled))
    }

    @Test
    fun recordingOperationRegistry_keepsIdentityBusyUntilMatchingTerminal() {
        val registry = RecordingOperationRegistry()
        val first = requireNotNull(registry.tryBegin("recording-a"))
        assertTrue(registry.isActive("recording-a"))
        assertEquals(setOf("recording-a"), registry.activeIds.value)
        assertEquals(null, registry.tryBegin("recording-a"))

        registry.finish(first.copy(generation = first.generation + 1L))
        assertTrue(registry.isActive("recording-a"))
        registry.finish(first)
        assertFalse(registry.isActive("recording-a"))
        assertTrue(registry.activeIds.value.isEmpty())

        val second = requireNotNull(registry.tryBegin("recording-a"))
        assertTrue(second.generation > first.generation)
        registry.finish(second)
    }

    @Test
    fun captureResolvedBufferState_failsClosedUntilRetentionResolves() {
        assertEquals(
            CaptureResolvedBufferState(false, false, false),
            captureResolvedBufferState(null, oneShotEnabled = true, oneShotFull = true, loopingEnabled = true),
        )
        assertEquals(
            CaptureResolvedBufferState(true, true, false),
            captureResolvedBufferState(
                RetentionMode.TIME,
                oneShotEnabled = true,
                oneShotFull = true,
                loopingEnabled = false,
            ),
        )
    }

    @Test
    fun captureBufferReadout_staysNeutralUntilResolvedModeArrives() {
        val loading = captureBufferReadout(
            retentionMode = null,
            disabled = false,
            seconds = 61f,
            bytes = 1_048_576L,
            disabledLabel = "Off",
        )
        assertEquals("—", loading.primary)
        assertEquals(null, loading.secondary)

        val time = captureBufferReadout(RetentionMode.TIME, false, 61f, 1_048_576L, "Off")
        val size = captureBufferReadout(RetentionMode.SIZE, false, 61f, 1_048_576L, "Off")
        assertEquals(formatShortTimer(61f), time.primary)
        assertEquals(formatShortFileSize(1_048_576L), time.secondary)
        assertEquals(formatShortFileSize(1_048_576L), size.primary)
        assertEquals(formatShortTimer(61f), size.secondary)
        assertEquals(CaptureBufferReadout("Off", null), captureBufferReadout(null, true, 61f, 1_048_576L, "Off"))
    }

    @Test
    fun operationalBufferAvailabilityFailsClosedAndRespectsFrameSize() {
        val unavailable = configuredBufferAvailability(
            retention = null,
            channelMode = ChannelMode.STEREO,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        assertFalse(unavailable.oneShotEnabled)
        assertFalse(unavailable.loopingEnabled)

        val timed = configuredBufferAvailability(
            retention = RetentionConfiguration(
                mode = RetentionMode.TIME,
                oneShotSeconds = 0L,
                oneShotSizeBytes = 1L,
                loopingSeconds = 1L,
                loopingSizeBytes = 0L,
            ),
            channelMode = ChannelMode.STEREO,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        assertFalse(timed.oneShotEnabled)
        assertTrue(timed.loopingEnabled)

        val sized = configuredBufferAvailability(
            retention = RetentionConfiguration(
                mode = RetentionMode.SIZE,
                oneShotSeconds = 1L,
                oneShotSizeBytes = 3L,
                loopingSeconds = 0L,
                loopingSizeBytes = 4L,
            ),
            channelMode = ChannelMode.STEREO,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        assertFalse(sized.oneShotEnabled)
        assertTrue(sized.loopingEnabled)
    }

    @Test
    fun safePreferenceRead_fallsBackOnStoredTypeMismatch() {
        assertEquals(48_000, safePreferenceRead(48_000) { throw ClassCastException("wrong type") })
        assertEquals(96_000, safePreferenceRead(48_000) { 96_000 })
        assertFalse(safePreferenceRead(false) { throw ClassCastException("wrong type") })
    }

    @Test
    fun durablePreferenceRead_neverTreatsWrongTypeAsAbsent() {
        assertEquals(emptySet<String>(), requireDurablePreference(false, emptySet(), "journal") { setOf("x") })
        assertEquals(setOf("x"), requireDurablePreference(true, emptySet(), "journal") { setOf("x") })
        try {
            requireDurablePreference(true, emptySet<String>(), "journal") {
                throw ClassCastException("wrong type")
            }
            throw AssertionError("Expected unreadable durable preference to fail closed")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

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
    fun inlineTrimFineSeek_pushesOppositeBoundaryAtMinimumRange() {
        fun adjusted(start: Int, end: Int, target: InlineFineSeekTarget, delta: Int) =
            adjustInlineFineSeekTarget(
                InlineFineSeekValues(cursorMillis = 4_000, startMillis = start, endMillis = end),
                durationMillis = 10_000,
                target = target,
                deltaMillis = delta,
            )

        assertEquals(
            InlineFineSeekValues(4_000, 1_250, 8_000),
            adjusted(1_000, 8_000, InlineFineSeekTarget.TRIM_START, 250),
        )
        assertEquals(
            InlineFineSeekValues(4_000, 1_000, 7_750),
            adjusted(1_000, 8_000, InlineFineSeekTarget.TRIM_END, -250),
        )
        assertEquals(
            InlineFineSeekValues(4_000, 8_400, 8_450),
            adjusted(7_900, 8_000, InlineFineSeekTarget.TRIM_START, 500),
        )
        assertEquals(
            InlineFineSeekValues(4_000, 550, 600),
            adjusted(1_000, 1_100, InlineFineSeekTarget.TRIM_END, -500),
        )
        assertEquals(
            InlineFineSeekValues(4_000, 8_950, 9_000),
            adjusted(9_950, 10_000, InlineFineSeekTarget.TRIM_END, -1_000),
        )
        assertEquals(
            InlineFineSeekValues(4_000, 1_000, 1_050),
            adjusted(0, 50, InlineFineSeekTarget.TRIM_START, 1_000),
        )
    }

    @Test
    fun inlineTrimHasOnlyTwoEditableAuditionCursors_andBodyMovesActiveBoundary() {
        val initial = InlineFineSeekValues(cursorMillis = 4_000, startMillis = 1_000, endMillis = 8_000)
        assertEquals(
            InlineFineSeekValues(cursorMillis = 4_000, startMillis = 1_250, endMillis = 8_000),
            adjustInlineFineSeekTarget(initial, 10_000, InlineFineSeekTarget.TRIM_START, 250),
        )
        assertEquals(
            InlineFineSeekValues(cursorMillis = 4_000, startMillis = 1_000, endMillis = 7_750),
            adjustInlineFineSeekTarget(initial, 10_000, InlineFineSeekTarget.TRIM_END, -250),
        )
        assertEquals(
            InlineFineSeekTarget.TRIM_START,
            inlineTrimGestureTarget(1_020, 1_000, 8_000, 100, InlineFineSeekTarget.TRIM_END),
        )
        assertEquals(
            InlineFineSeekTarget.TRIM_END,
            inlineTrimGestureTarget(7_950, 1_000, 8_000, 100, InlineFineSeekTarget.TRIM_START),
        )
        assertEquals(
            InlineFineSeekTarget.TRIM_START,
            inlineTrimGestureTarget(4_000, 1_000, 8_000, 100, InlineFineSeekTarget.TRIM_START),
        )
        assertEquals(
            InlineFineSeekTarget.TRIM_END,
            inlineTrimGestureTarget(4_000, 1_000, 8_000, 100, InlineFineSeekTarget.TRIM_END),
        )
    }

    @Test
    fun deletionBatch_reportsAnyPartialFailure() {
        assertFalse(deletionBatchFailed(requestedCount = 3, deletedCount = 3, hadError = false))
        assertTrue(deletionBatchFailed(requestedCount = 3, deletedCount = 2, hadError = false))
        assertTrue(deletionBatchFailed(requestedCount = 1, deletedCount = 0, hadError = false))
        assertTrue(deletionBatchFailed(requestedCount = 3, deletedCount = 3, hadError = true))
    }

    @Test
    fun backgroundDeletionRefresh_reportsOnlyTheSelectedAssetStillPresent() {
        val selected = RecordingEntity(
            id = "/recordings/clip.wav",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 100L,
            durationMillis = 2_000L,
            sizeBytes = 4_000L,
            codecSummary = "WAV",
            storageType = RecordingStorageType.FILE,
            directoryId = "/recordings",
            fileIdentity = "stat:selected",
        )

        assertTrue(
            backgroundDeletionBatchFailedAfterRefresh(
                requested = listOf(selected),
                refreshedById = mapOf(selected.id to selected),
            ),
        )
        assertFalse(
            backgroundDeletionBatchFailedAfterRefresh(
                requested = listOf(selected),
                refreshedById = emptyMap(),
            ),
        )
        assertFalse(
            backgroundDeletionBatchFailedAfterRefresh(
                requested = listOf(selected),
                refreshedById = mapOf(
                    selected.id to selected.copy(fileIdentity = "stat:replacement"),
                ),
            ),
        )
    }

    @Test
    fun deletionBackgroundHandoff_doesNotDuplicateCommittedForegroundBatch() {
        assertTrue(
            shouldHandoffPendingDeletionsToBackground(
                hasPending = true, committedInBackground = false, foregroundCommitInFlight = false,
            ),
        )
        assertFalse(
            shouldHandoffPendingDeletionsToBackground(
                hasPending = true, committedInBackground = false, foregroundCommitInFlight = true,
            ),
        )
        assertFalse(
            shouldHandoffPendingDeletionsToBackground(
                hasPending = true, committedInBackground = true, foregroundCommitInFlight = false,
            ),
        )
        assertFalse(
            shouldHandoffPendingDeletionsToBackground(
                hasPending = false, committedInBackground = false, foregroundCommitInFlight = false,
            ),
        )
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
    fun missingRecordingMarker_isStableUntilTheAssetReturns() {
        val recording = RecordingEntity(
            id = "id",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 500L,
            durationMillis = 1_000L,
            sizeBytes = 2_000L,
            codecSummary = "PCM 16-bit",
            storageType = RecordingStorageType.FILE,
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
            storageType = RecordingStorageType.FILE,
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
    fun mergeObservedRecording_preservesOnlyMatchingWaveformCache() {
        val base = RecordingEntity(
            id = "id", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 500L, durationMillis = 1_000L, sizeBytes = 2_000L, codecSummary = "PCM",
            storageType = RecordingStorageType.FILE, directoryId = "dir", fileIdentity = "stat:a",
        )
        val revision = recordingWaveformRevision(base)
        val encoded = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.5f })
        val cached = base.copy(waveformData = encoded, waveformRevision = revision)
        val observed = base.copy(waveformData = "", waveformRevision = "")
        val preserved = mergeObservedRecording(cached, observed, nowMillis = 40L)
        assertEquals(encoded, preserved.waveformData)
        assertEquals(revision, preserved.waveformRevision)

        val changed = mergeObservedRecording(cached, observed.copy(fileIdentity = "stat:b"), nowMillis = 40L)
        assertEquals("", changed.waveformData)
        assertEquals("", changed.waveformRevision)

        val identityUnavailable = mergeObservedRecording(
            cached, observed.copy(fileIdentity = ""), nowMillis = 40L,
        )
        assertEquals("stat:a", identityUnavailable.fileIdentity)
        assertEquals("", identityUnavailable.waveformData)
        assertEquals("", identityUnavailable.waveformRevision)

        val malformed = cached.copy(waveformData = "not-base64!")
        val healed = mergeObservedRecording(malformed, observed, nowMillis = 40L)
        assertEquals("", healed.waveformData)
        assertEquals("", healed.waveformRevision)
    }

    @Test
    fun mergeObservedRecording_preservesProviderMetadataButClearsCacheWhenIdentityCannotBeProven() {
        val existing = RecordingEntity(
            id = "content://media/1", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 500L, durationMillis = 1_000L, sizeBytes = 2_000L, codecSummary = "PCM",
            storageType = RecordingStorageType.MEDIASTORE, directoryId = "dir",
            fileIdentity = "provider:MEDIASTORE:x:2000:9",
            waveformData = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.4f }),
            waveformRevision = "revision", createdAtMillis = 10L, lastSeenAtMillis = 20L,
        )
        val observed = existing.copy(
            mimeType = "", durationMillis = 0L, sizeBytes = 0L, codecSummary = "",
            fileIdentity = "", waveformData = "", waveformRevision = "",
            createdAtMillis = 999L, lastSeenAtMillis = 999L,
        )
        val merged = mergeObservedRecording(existing, observed, nowMillis = 40L)
        assertEquals(existing.fileIdentity, merged.fileIdentity)
        assertEquals(existing.mimeType, merged.mimeType)
        assertEquals(existing.durationMillis, merged.durationMillis)
        assertEquals(existing.sizeBytes, merged.sizeBytes)
        assertEquals(existing.codecSummary, merged.codecSummary)
        assertEquals(existing.createdAtMillis, merged.createdAtMillis)
        assertEquals(existing.lastSeenAtMillis, merged.lastSeenAtMillis)
        assertEquals("", merged.waveformData)
        assertEquals("", merged.waveformRevision)
    }

    @Test
    fun verifiedFileProviderIdentity_roundTripsAndRejectsMalformedPayload() {
        val identity = "stat:17:42:1234:99:777"
        val encoded = encodeVerifiedFileProviderIdentity(identity)
        assertEquals(identity, decodeVerifiedFileProviderIdentity(encoded))
        assertEquals(null, decodeVerifiedFileProviderIdentity("%%%not-base64%%%"))
        assertEquals(null, decodeVerifiedFileProviderIdentity(""))
    }

    @Test
    fun recordingActionTarget_neverRetargetsSelectionAcrossIdentityReuse() {
        val selected = RecordingEntity(
            id = "path", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 10L, durationMillis = 1_000L, sizeBytes = 2_000L, codecSummary = "PCM",
            storageType = RecordingStorageType.FILE, directoryId = "dir", fileIdentity = "stat:a",
        )
        assertTrue(
            sameRecordingActionTarget(
                selected, selected.copy(waveformData = "cache", waveformRevision = "rev"),
            ),
        )
        assertFalse(sameRecordingActionTarget(selected, selected.copy(fileIdentity = "stat:b")))
        assertFalse(sameRecordingActionTarget(selected, selected.copy(fileIdentity = "")))
        assertTrue(sameRecordingActionTarget(selected, selected.copy(displayName = "renamed-label.wav")))

        val provider = selected.copy(
            id = "content://media/external/audio/media/42",
            storageType = RecordingStorageType.MEDIASTORE,
            fileIdentity = "provider:MEDIASTORE:item:2000:7",
        )
        assertTrue(sameRecordingActionTarget(provider, provider.copy(displayName = "other-label.wav")))
        assertTrue(
            sameRecordingActionTarget(
                provider,
                provider.copy(fileIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:item:2000:7"),
            ),
        )
        assertFalse(
            sameRecordingActionTarget(
                provider,
                provider.copy(fileIdentity = "provider:MEDIASTORE:item:2000:8"),
            ),
        )
        assertFalse(sameRecordingActionTarget(provider, provider.copy(fileIdentity = "")))

        val legacy = selected.copy(fileIdentity = "")
        assertTrue(sameRecordingActionTarget(legacy, legacy.copy(displayName = "renamed-label.wav")))
        assertFalse(sameRecordingActionTarget(legacy, legacy.copy(sizeBytes = 2_002L)))
        assertFalse(sameRecordingActionTarget(legacy, legacy.copy(durationMillis = 1_001L)))
        assertFalse(sameRecordingActionTarget(legacy, legacy.copy(startedAtMillis = 11L)))
    }

    @Test
    fun mergeObservedRecording_sameFileWithIncompleteInspectionPreservesKnownMetadata() {
        val existing = RecordingEntity(
            id = "path", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 500L, durationMillis = 9_000L, sizeBytes = 123_456L,
            codecSummary = "PCM 16 · 48 kHz", storageType = RecordingStorageType.FILE,
            directoryId = "dir", fileIdentity = "stat:dev:ino:ctime", createdAtMillis = 10L,
            lastSeenAtMillis = 20L,
        )
        val observed = existing.copy(
            mimeType = "", durationMillis = 0L, sizeBytes = 0L, codecSummary = "",
            fileIdentity = "", createdAtMillis = 999L, lastSeenAtMillis = 999L,
        )

        val merged = mergeObservedRecording(existing, observed, nowMillis = 40L)

        assertTrue(observedRecordingIsSameAsset(existing, observed))
        assertEquals(existing.mimeType, merged.mimeType)
        assertEquals(existing.durationMillis, merged.durationMillis)
        assertEquals(existing.sizeBytes, merged.sizeBytes)
        assertEquals(existing.codecSummary, merged.codecSummary)
        assertEquals(existing.fileIdentity, merged.fileIdentity)
        assertEquals(existing.createdAtMillis, merged.createdAtMillis)
        assertEquals(existing.lastSeenAtMillis, merged.lastSeenAtMillis)
    }

    @Test
    fun mergeObservedRecording_replacedFileDoesNotInheritOldMetadataOrWaveform() {
        val base = RecordingEntity(
            id = "path", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 500L, durationMillis = 9_000L, sizeBytes = 123_456L,
            codecSummary = "PCM 16 · 48 kHz", storageType = RecordingStorageType.FILE,
            directoryId = "dir", fileIdentity = "stat:old", createdAtMillis = 10L, lastSeenAtMillis = 20L,
        )
        val cached = base.copy(
            waveformData = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.5f }),
            waveformRevision = recordingWaveformRevision(base),
        )
        val replacement = base.copy(
            mimeType = "", durationMillis = 0L, sizeBytes = 0L, codecSummary = "",
            fileIdentity = "stat:new", createdAtMillis = 777L, lastSeenAtMillis = 777L,
            waveformData = "", waveformRevision = "",
        )

        val merged = mergeObservedRecording(cached, replacement, nowMillis = 888L)

        assertFalse(observedRecordingIsSameAsset(cached, replacement))
        assertEquals("", merged.mimeType)
        assertEquals(0L, merged.durationMillis)
        assertEquals(0L, merged.sizeBytes)
        assertEquals("", merged.codecSummary)
        assertEquals("stat:new", merged.fileIdentity)
        assertEquals(777L, merged.createdAtMillis)
        assertEquals(888L, merged.lastSeenAtMillis)
        assertEquals("", merged.waveformData)
        assertEquals("", merged.waveformRevision)
    }

    @Test
    fun observedRecordingIdentity_detectsProviderRevisionReplacement() {
        val existing = RecordingEntity(
            id = "content://recording/7", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L, codecSummary = "PCM",
            storageType = RecordingStorageType.MEDIASTORE, directoryId = "media-dir",
            fileIdentity = "provider:MEDIASTORE:item:4000:7",
        )
        val same = existing.copy(
            fileIdentity = "provider:${RecordingStorageType.MEDIASTORE.storageCode.toInt()}:item:4000:7",
        )
        val replaced = existing.copy(fileIdentity = "provider:MEDIASTORE:item:4000:8")
        val identityUnavailable = existing.copy(fileIdentity = "")

        assertTrue(observedRecordingIsSameAsset(existing, same))
        assertFalse(observedRecordingIsSameAsset(existing, replaced))
        assertFalse(observedRecordingIsSameAsset(existing, identityUnavailable))
        assertTrue(observedRecordingIsSameAsset(existing.copy(fileIdentity = ""), same))
    }

    @Test
    fun mergeObservedRecording_storageTypeChangeDoesNotCarryFileIdentity() {
        val existing = RecordingEntity(
            id = "same-id", displayName = "clip.wav", mimeType = "audio/wav",
            startedAtMillis = 1L, durationMillis = 2L, sizeBytes = 4L, codecSummary = "PCM",
            storageType = RecordingStorageType.FILE, directoryId = "dir", fileIdentity = "stat:file",
        )
        val observed = existing.copy(
            storageType = RecordingStorageType.MEDIASTORE,
            fileIdentity = "",
            createdAtMillis = 999L,
        )

        val merged = mergeObservedRecording(existing, observed, nowMillis = 1_000L)

        assertFalse(observedRecordingIsSameAsset(existing, observed))
        assertEquals("", merged.fileIdentity)
        assertEquals(999L, merged.createdAtMillis)
        assertEquals(1_000L, merged.lastSeenAtMillis)
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
            storageType = RecordingStorageType.FILE,
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
    fun blobTap_ownsCaptureSwitching_andUnavailableOneShotNeverStealsLooping() {
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val looping = ReverbService.BufferSlot.LOOPING

        assertEquals(
            CaptureBlobTapAction.SWITCH,
            captureBlobTapAction(
                requested = oneShot,
                isListening = true,
                activeBuffer = looping,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            CaptureBlobTapAction.NONE,
            captureBlobTapAction(
                requested = oneShot,
                isListening = true,
                activeBuffer = looping,
                oneShotEnabled = false,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            CaptureBlobTapAction.NONE,
            captureBlobTapAction(
                requested = oneShot,
                isListening = true,
                activeBuffer = looping,
                oneShotEnabled = true,
                oneShotFull = true,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            CaptureBlobTapAction.STOP,
            captureBlobTapAction(
                requested = looping,
                isListening = true,
                activeBuffer = looping,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
        assertEquals(
            CaptureBlobTapAction.START,
            captureBlobTapAction(
                requested = oneShot,
                isListening = false,
                activeBuffer = looping,
                oneShotEnabled = true,
                oneShotFull = false,
                loopingEnabled = true,
            ),
        )
    }

    @Test
    fun failedStopRollbackPausesWhenPriorIntentCannotBeRestoredDurably() {
        assertTrue(captureStopRollbackRequiresPause(previousEnabled = true, rollbackPersisted = false))
        assertFalse(captureStopRollbackRequiresPause(previousEnabled = true, rollbackPersisted = true))
        assertFalse(captureStopRollbackRequiresPause(previousEnabled = false, rollbackPersisted = false))
    }

    @Test
    fun explicitCaptureStop_rejectsOnlyWhenPriorArmedMarkerWasRestored() {
        assertEquals(
            ExplicitCaptureStopDisposition.KNOWN_STOP,
            explicitCaptureStopDisposition(
                stopIntentChanged = true,
                incidentResult = KnownCaptureStopResult.DURABLE,
            ),
        )
        assertEquals(
            ExplicitCaptureStopDisposition.REJECT_REARMED,
            explicitCaptureStopDisposition(
                stopIntentChanged = true,
                incidentResult = KnownCaptureStopResult.FAILED_REARMED,
            ),
        )
        assertEquals(
            ExplicitCaptureStopDisposition.INCIDENT_STATE_FAILURE,
            explicitCaptureStopDisposition(
                stopIntentChanged = false,
                incidentResult = KnownCaptureStopResult.FAILED_REARMED,
            ),
        )
        assertEquals(
            ExplicitCaptureStopDisposition.INCIDENT_STATE_FAILURE,
            explicitCaptureStopDisposition(
                stopIntentChanged = true,
                incidentResult = KnownCaptureStopResult.FAILED_UNCERTAIN,
            ),
        )
    }

    @Test
    fun automaticCaptureStopNeverConsumesIntentAfterServiceTeardownBegins() {
        assertTrue(automaticCaptureStopMayBegin(false, 7L, 7L, recorderListening = true))
        assertFalse(automaticCaptureStopMayBegin(true, 7L, 7L, recorderListening = true))
        assertFalse(automaticCaptureStopMayBegin(false, 6L, 7L, recorderListening = true))
        assertFalse(automaticCaptureStopMayBegin(false, 7L, 7L, recorderListening = false))
    }

    @Test
    fun automaticCaptureStopIsKnownOnlyAfterDurableIntentAndIncidentCommit() {
        assertEquals(
            AutomaticCaptureStopDisposition.KNOWN_STOP,
            automaticCaptureStopDisposition(
                stopIntentPersisted = true,
                incidentStopPersisted = true,
            ),
        )
        assertEquals(
            AutomaticCaptureStopDisposition.PERSISTENCE_FAILURE,
            automaticCaptureStopDisposition(
                stopIntentPersisted = false,
                incidentStopPersisted = false,
            ),
        )
        assertEquals(
            AutomaticCaptureStopDisposition.INCIDENT_STATE_FAILURE,
            automaticCaptureStopDisposition(
                stopIntentPersisted = true,
                incidentStopPersisted = false,
            ),
        )
    }

    @Test
    fun serviceShutdown_waitsOnlyWhenCaptureMayOwnTheMicrophone() {
        assertFalse(
            shouldWaitForAudioThreadShutdown(
                recorderState = ReverbService.STATE_READY,
                audioRecordPresent = false,
            ),
        )
        assertFalse(
            shouldWaitForAudioThreadShutdown(
                recorderState = ReverbService.STATE_PAUSED,
                audioRecordPresent = false,
            ),
        )
        assertTrue(
            shouldWaitForAudioThreadShutdown(
                recorderState = ReverbService.STATE_LISTENING,
                audioRecordPresent = false,
            ),
        )
        assertTrue(
            shouldWaitForAudioThreadShutdown(
                recorderState = ReverbService.STATE_READY,
                audioRecordPresent = true,
            ),
        )
    }

    @Test
    fun serviceShutdown_keepsQueuedAudioThreadAsStoreCloseOwner() {
        assertFalse(shouldCloseAudioStoresOffThread(AudioThreadShutdownWaitResult.COMPLETED))
        assertFalse(shouldCloseAudioStoresOffThread(AudioThreadShutdownWaitResult.QUEUED_PENDING))
        assertTrue(shouldCloseAudioStoresOffThread(AudioThreadShutdownWaitResult.REJECTED))
    }

    @Test
    fun serviceDestroyKeepsOnlyTheReadAlreadyInFlight() {
        assertTrue(captureReadMayStart(serviceDestroying = false))
        assertFalse(captureReadMayStart(serviceDestroying = true))
        assertTrue(
            captureReadShouldReschedule(
                commandGenerationUnchanged = true,
                serviceDestroying = false,
                recorderListening = true,
                recordStillOwned = true,
            ),
        )
        assertFalse(
            captureReadShouldReschedule(
                commandGenerationUnchanged = true,
                serviceDestroying = true,
                recorderListening = true,
                recordStillOwned = true,
            ),
        )
    }

    @Test
    fun deferredInlinePlaybackStartsOnlyWhenPreparedAndResumed() {
        assertTrue(
            inlinePlaybackShouldAutoStart(
                prepared = true,
                initialAutoStartPending = true,
                lifecycleResumed = true,
            ),
        )
        assertFalse(inlinePlaybackShouldAutoStart(false, true, true))
        assertFalse(inlinePlaybackShouldAutoStart(true, false, true))
        assertFalse(inlinePlaybackShouldAutoStart(true, true, false))
        assertFalse(inlinePlaybackShouldAutoStart(true, true, true, blocked = true))
    }

    @Test
    fun inFlightCaptureReadSurvivesOnlyContinuousSessionChanges() {
        assertTrue(
            captureReadMayCommit(
                readContinuityGeneration = 7L,
                currentContinuityGeneration = 7L,
                listeningIntentEnabled = true,
                recorderListening = true,
                recordStillOwned = true,
            ),
        )
        assertFalse(
            captureReadMayCommit(
                readContinuityGeneration = 7L,
                currentContinuityGeneration = 8L,
                listeningIntentEnabled = true,
                recorderListening = true,
                recordStillOwned = true,
            ),
        )
        assertFalse(
            captureReadMayCommit(
                readContinuityGeneration = 7L,
                currentContinuityGeneration = 7L,
                listeningIntentEnabled = false,
                recorderListening = true,
                recordStillOwned = true,
            ),
        )
        assertFalse(
            captureReadMayCommit(
                readContinuityGeneration = 7L,
                currentContinuityGeneration = 7L,
                listeningIntentEnabled = true,
                recorderListening = true,
                recordStillOwned = false,
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
    fun persistedSettingEnumsUseStableByteCodesAndReadLegacyNames() {
        assertEquals(0, ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt())
        assertEquals(1, ReverbService.BufferSlot.LOOPING.storageCode.toInt())
        assertEquals(ReverbService.BufferSlot.LOOPING, ReverbService.BufferSlot.fromStorageCode(1))
        assertEquals(ReverbService.BufferSlot.ONE_SHOT, ReverbService.BufferSlot.fromLegacyName("ONE_SHOT"))

        assertEquals(0, RetentionMode.SIZE.storageCode.toInt())
        assertEquals(1, RetentionMode.TIME.storageCode.toInt())
        assertEquals(RetentionMode.TIME, RetentionMode.fromStorageOrNull(1))

        assertEquals(1, ExportFormat.WAV.storageCode.toInt())
        assertEquals(ExportFormat.WAV, ExportFormat.fromStorageCode(1))
        assertEquals(ExportFormat.WAV, ExportFormat.fromLegacyPrefValue("wav"))

        assertEquals(2, PcmSampleFormat.PCM_16.storageCode.toInt())
        assertEquals(PcmSampleFormat.PCM_FLOAT, PcmSampleFormat.fromStorageCode(3))
        assertEquals(PcmSampleFormat.PCM_16, PcmSampleFormat.fromLegacyPrefValue("pcm_16"))

        assertEquals(ChannelMode.STEREO, ChannelMode.fromStorageCode(2))
        assertEquals(ChannelMode.MONO, ChannelMode.fromLegacyPrefValue("mono"))
        assertEquals(InputRouteMode.BUILTIN_MIC, InputRouteMode.fromStorageCode(1))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStorageCode(2))

        val codes = buildList {
            addAll(RetentionMode.entries.map { it.storageCode })
            addAll(ExportFormat.entries.map { it.storageCode })
            addAll(ExportCodec.entries.map { it.storageCode })
            addAll(PcmSampleFormat.entries.map { it.storageCode })
            addAll(ChannelMode.entries.map { it.storageCode })
            addAll(InputRouteMode.entries.map { it.storageCode })
            addAll(AppThemeMode.entries.map { it.storageCode })
        }
        assertTrue(codes.all { it.toInt() in 0..255 })
    }

    @Test
    fun recorderIntentPersistence_onlyWritesWhenDurableIntentChanges() {
        assertFalse(
            captureSlotNeedsPersistence(
                ReverbService.BufferSlot.LOOPING,
                ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(captureSlotNeedsPersistence(null, ReverbService.BufferSlot.LOOPING))
        assertTrue(
            captureSlotNeedsPersistence(
                ReverbService.BufferSlot.ONE_SHOT,
                ReverbService.BufferSlot.LOOPING,
            ),
        )

        assertFalse(
            captureIntentNeedsPersistence(
                previousEnabled = true,
                requestedEnabled = true,
                previousStoredSlot = ReverbService.BufferSlot.LOOPING,
                requestedSlot = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(
            captureIntentNeedsPersistence(
                previousEnabled = true,
                requestedEnabled = true,
                previousStoredSlot = ReverbService.BufferSlot.ONE_SHOT,
                requestedSlot = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(
            captureIntentNeedsPersistence(
                previousEnabled = false,
                requestedEnabled = true,
                previousStoredSlot = ReverbService.BufferSlot.LOOPING,
                requestedSlot = ReverbService.BufferSlot.LOOPING,
            ),
        )
        assertTrue(
            captureIntentNeedsPersistence(
                previousEnabled = true,
                requestedEnabled = false,
                previousStoredSlot = ReverbService.BufferSlot.LOOPING,
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
        assertTrue(
            shouldAttemptAutomaticListeningStart(
                listeningIntentEnabled = true,
                serviceDestroying = false,
                foregroundStartBlocked = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            shouldAttemptAutomaticListeningStart(
                listeningIntentEnabled = true,
                serviceDestroying = false,
                foregroundStartBlocked = true,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            shouldAttemptAutomaticListeningStart(
                listeningIntentEnabled = true,
                serviceDestroying = false,
                foregroundStartBlocked = false,
                persistenceFailureBlocked = true,
            ),
        )
        assertFalse(
            shouldAttemptAutomaticListeningStart(
                listeningIntentEnabled = false,
                serviceDestroying = false,
                foregroundStartBlocked = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            shouldAttemptAutomaticListeningStart(
                listeningIntentEnabled = true,
                serviceDestroying = true,
                foregroundStartBlocked = false,
                persistenceFailureBlocked = false,
            ),
        )
    }

    @Test
    fun initializationEnsuresRuntimeCaptureWhenStickyStartAlreadySetLogicalListening() {
        assertTrue(
            shouldEnsureRuntimeCaptureAfterInitialization(
                listeningIntentEnabled = true,
                recorderState = ReverbService.STATE_LISTENING,
                serviceDestroying = false,
                foregroundStartBlocked = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            shouldEnsureRuntimeCaptureAfterInitialization(
                listeningIntentEnabled = true,
                recorderState = ReverbService.STATE_PAUSED,
                serviceDestroying = false,
                foregroundStartBlocked = false,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            shouldEnsureRuntimeCaptureAfterInitialization(
                listeningIntentEnabled = true,
                recorderState = ReverbService.STATE_LISTENING,
                serviceDestroying = false,
                foregroundStartBlocked = true,
                persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            shouldEnsureRuntimeCaptureAfterInitialization(
                listeningIntentEnabled = true,
                recorderState = ReverbService.STATE_LISTENING,
                serviceDestroying = true,
                foregroundStartBlocked = false,
                persistenceFailureBlocked = false,
            ),
        )
    }

    @Test
    fun suspendedDurableIntent_retriesOnlyWithActualForegroundUi() {
        assertTrue(
            shouldRetrySuspendedListeningWithForegroundUi(
                true, serviceDestroying = false, appUiForeground = true, foregroundStartBlocked = true,
                foregroundServiceTimedOut = false, persistenceFailureBlocked = false,
            ),
        )
        assertTrue(
            shouldRetrySuspendedListeningWithForegroundUi(
                true, serviceDestroying = false, appUiForeground = true, foregroundStartBlocked = false,
                foregroundServiceTimedOut = true, persistenceFailureBlocked = false,
            ),
        )
        assertTrue(
            shouldRetrySuspendedListeningWithForegroundUi(
                true, serviceDestroying = false, appUiForeground = true, foregroundStartBlocked = false,
                foregroundServiceTimedOut = false, persistenceFailureBlocked = true,
            ),
        )
        assertFalse(
            shouldRetrySuspendedListeningWithForegroundUi(
                true, serviceDestroying = false, appUiForeground = false, foregroundStartBlocked = true,
                foregroundServiceTimedOut = true, persistenceFailureBlocked = true,
            ),
        )
        assertFalse(
            shouldRetrySuspendedListeningWithForegroundUi(
                true, serviceDestroying = false, appUiForeground = true, foregroundStartBlocked = false,
                foregroundServiceTimedOut = false, persistenceFailureBlocked = false,
            ),
        )
        assertFalse(
            shouldRetrySuspendedListeningWithForegroundUi(
                false, serviceDestroying = false, appUiForeground = true, foregroundStartBlocked = true,
                foregroundServiceTimedOut = true, persistenceFailureBlocked = true,
            ),
        )
        assertFalse(
            shouldRetrySuspendedListeningWithForegroundUi(
                true, serviceDestroying = true, appUiForeground = true, foregroundStartBlocked = true,
                foregroundServiceTimedOut = true, persistenceFailureBlocked = true,
            ),
        )
    }

    @Test
    fun logicalListeningState_followsNewestUserIntentDuringAsyncStop() {
        assertTrue(isLogicalListeningState(ReverbService.STATE_LISTENING, listeningIntentEnabled = true))
        assertFalse(isLogicalListeningState(ReverbService.STATE_LISTENING, listeningIntentEnabled = false))
        assertFalse(isLogicalListeningState(ReverbService.STATE_READY, listeningIntentEnabled = true))
    }

    @Test
    fun settingsServiceBindingCallback_rejectsRetiredBindLifetime() {
        assertTrue(settingsServiceBindingCallbackIsCurrent(4L, 4L, bindingOwned = true))
        assertFalse(settingsServiceBindingCallbackIsCurrent(3L, 4L, bindingOwned = true))
        assertFalse(settingsServiceBindingCallbackIsCurrent(4L, 4L, bindingOwned = false))
    }

    @Test
    fun settingsServiceBinding_survivesOnlyAnInFlightHiddenSave() {
        assertTrue(settingsShouldOwnServiceBinding(active = true, persisting = false))
        assertTrue(settingsShouldOwnServiceBinding(active = true, persisting = true))
        assertTrue(settingsShouldOwnServiceBinding(active = false, persisting = true))
        assertFalse(settingsShouldOwnServiceBinding(active = false, persisting = false))
    }

    @Test
    fun settingsSave_keepsEditsMadeDuringPersistenceUnsaved() {
        val submitted = SettingsSnapshot(sampleRate = 48_000, themeMode = AppThemeMode.SYSTEM)
        val newer = submitted.copy(sampleRate = 44_100)

        assertFalse(settingsEditedDuringPersistence(7L, 7L))
        assertTrue(settingsEditedDuringPersistence(7L, 8L))
        assertFalse(settingsSnapshotHasUnsavedChanges(submitted, submitted, invalidRetentionInput = false))
        assertTrue(settingsSnapshotHasUnsavedChanges(submitted, newer, invalidRetentionInput = false))
        assertTrue(settingsSnapshotHasUnsavedChanges(submitted, submitted, invalidRetentionInput = true))
        assertTrue(settingsSaveMayContinueAfterCommit(hasUnsavedChanges = false))
        assertFalse(settingsSaveMayContinueAfterCommit(hasUnsavedChanges = true))
    }

    @Test
    fun settingsRehydrate_runsOnlyForCleanVisiblePanel() {
        assertTrue(settingsShouldRehydrate(active = true, persisting = false, hasUnsavedChanges = false))
        assertFalse(settingsShouldRehydrate(active = false, persisting = false, hasUnsavedChanges = false))
        assertFalse(settingsShouldRehydrate(active = true, persisting = true, hasUnsavedChanges = false))
        assertFalse(settingsShouldRehydrate(active = true, persisting = false, hasUnsavedChanges = true))
        assertTrue(settingsHydrationMayApply(expectedEditRevision = 8L, currentEditRevision = 8L))
        assertFalse(settingsHydrationMayApply(expectedEditRevision = 8L, currentEditRevision = 9L))
    }

    @Test
    fun settingsMoveAvailability_rejectsHiddenAndStaleResults() {
        assertTrue(settingsMoveAvailabilityResultIsCurrent(active = true, requestGeneration = 3, currentGeneration = 3))
        assertFalse(settingsMoveAvailabilityResultIsCurrent(active = false, requestGeneration = 3, currentGeneration = 3))
        assertFalse(settingsMoveAvailabilityResultIsCurrent(active = true, requestGeneration = 2, currentGeneration = 3))
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
    fun serviceBindingCallback_rejectsRetiredBindLifetime() {
        assertTrue(captureServiceBindingCallbackIsCurrent(7L, 7L, screenAlive = true))
        assertFalse(captureServiceBindingCallbackIsCurrent(6L, 7L, screenAlive = true))
        assertFalse(captureServiceBindingCallbackIsCurrent(7L, 7L, screenAlive = false))
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
    fun quickTileTapAction_isAlwaysDerivedFromTheLiveSnapshot() {
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val idle = RecordingTileSnapshot(
            listening = false, activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            oneShotEnabled = true, oneShotFull = false, loopingEnabled = true,
        )
        val oneShotRunning = idle.copy(listening = true)
        val loopingRunning = oneShotRunning.copy(activeBuffer = ReverbService.BufferSlot.LOOPING)

        assertEquals(RecordingTileClickAction.START, recordingTileClickAction(oneShot, idle))
        assertEquals(RecordingTileClickAction.STOP, recordingTileClickAction(oneShot, oneShotRunning))
        assertEquals(RecordingTileClickAction.SWITCH, recordingTileClickAction(oneShot, loopingRunning))
        assertEquals(RecordingTileClickAction.NONE, recordingTileClickAction(oneShot, idle.copy(oneShotFull = true)))
        assertEquals(RecordingTileClickAction.NONE, recordingTileClickAction(oneShot, idle.copy(oneShotEnabled = false)))
    }

    @Test
    fun quickTileRefresh_deactivatesOldTileBeforeActivatingNewTile() {
        val looping = ReverbService.BufferSlot.LOOPING
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val switchedToOneShot = RecordingTileSnapshot(
            listening = true, activeBuffer = oneShot,
            oneShotEnabled = true, oneShotFull = false, loopingEnabled = true,
        )
        val switchedToLooping = switchedToOneShot.copy(activeBuffer = looping)

        assertTrue(
            recordingTileRefreshPriority(looping, switchedToOneShot) <
                recordingTileRefreshPriority(oneShot, switchedToOneShot),
        )
        assertTrue(
            recordingTileRefreshPriority(oneShot, switchedToLooping) <
                recordingTileRefreshPriority(looping, switchedToLooping),
        )
    }

    @Test
    fun quickTileDuration_isPerBufferAndIndependentOfActiveState() {
        val snapshot = RecordingTileSnapshot(
            listening = true,
            activeBuffer = ReverbService.BufferSlot.LOOPING,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
            oneShotSeconds = 65.9f,
            loopingSeconds = 3_661.2f,
        )

        assertEquals(65.9f, recordingTileDurationSeconds(ReverbService.BufferSlot.ONE_SHOT, snapshot), 0.0001f)
        assertEquals(3_661.2f, recordingTileDurationSeconds(ReverbService.BufferSlot.LOOPING, snapshot), 0.0001f)
        assertEquals("1:05", formatShortTimer(recordingTileDurationSeconds(ReverbService.BufferSlot.ONE_SHOT, snapshot)))
        assertEquals("1:01:01", formatShortTimer(recordingTileDurationSeconds(ReverbService.BufferSlot.LOOPING, snapshot)))
        assertEquals(0f, recordingTileDurationSeconds(ReverbService.BufferSlot.ONE_SHOT, snapshot.copy(oneShotSeconds = Float.NaN)), 0f)
        assertEquals(0f, recordingTileDurationSeconds(ReverbService.BufferSlot.LOOPING, snapshot.copy(loopingSeconds = -10f)), 0f)
    }

    @Test
    fun quickTileDurationVisibility_hidesTheActuallyRecordingBufferOnly() {
        val loopingRunning = RecordingTileSnapshot(
            listening = true,
            activeBuffer = ReverbService.BufferSlot.LOOPING,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
            oneShotSeconds = 65.9f,
            loopingSeconds = 3_661.2f,
        )
        assertTrue(recordingTileShowsDuration(ReverbService.BufferSlot.ONE_SHOT, loopingRunning))
        assertFalse(recordingTileShowsDuration(ReverbService.BufferSlot.LOOPING, loopingRunning))

        val oneShotRunning = loopingRunning.copy(activeBuffer = ReverbService.BufferSlot.ONE_SHOT)
        assertFalse(recordingTileShowsDuration(ReverbService.BufferSlot.ONE_SHOT, oneShotRunning))
        assertTrue(recordingTileShowsDuration(ReverbService.BufferSlot.LOOPING, oneShotRunning))

        val stopped = loopingRunning.copy(listening = false)
        assertTrue(recordingTileShowsDuration(ReverbService.BufferSlot.ONE_SHOT, stopped))
        assertTrue(recordingTileShowsDuration(ReverbService.BufferSlot.LOOPING, stopped))
    }

    @Test
    fun destroyedTileRejectsDeferredUnlockActionBeforeRecorderBind() {
        assertTrue(tileActionMayBegin(actionInFlight = false, tileDestroyed = false))
        assertFalse(tileActionMayBegin(actionInFlight = true, tileDestroyed = false))
        assertFalse(tileActionMayBegin(actionInFlight = false, tileDestroyed = true))
        assertFalse(tileActionMayBegin(actionInFlight = true, tileDestroyed = true))
    }

    @Test
    fun destroyedTileRejectsAlreadyQueuedActionCallbacks() {
        assertTrue(tileActionCallbackIsCurrent(true, 7L, 7L))
        assertFalse(tileActionCallbackIsCurrent(false, 7L, 7L))
        assertFalse(tileActionCallbackIsCurrent(true, 6L, 7L))
        assertFalse(tileActionCallbackIsCurrent(false, 6L, 7L))
    }

    @Test
    fun quickTileFallback_isFailClosedAndMemoryOnly() {
        val live = RecordingTileSnapshot(
            listening = true,
            activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            oneShotEnabled = true,
            oneShotFull = true,
            loopingEnabled = true,
            oneShotSeconds = 12f,
            loopingSeconds = 34f,
        )
        assertEquals(
            RecordingTileSnapshot(
                listening = false,
                activeBuffer = null,
                oneShotEnabled = false,
                oneShotFull = false,
                loopingEnabled = false,
                oneShotSeconds = 12f,
                loopingSeconds = 34f,
            ),
            failClosedRecordingTileSnapshot(live),
        )
        assertEquals(
            RecordingTileSnapshot(
                listening = false,
                activeBuffer = ReverbService.BufferSlot.LOOPING,
                oneShotEnabled = false,
                oneShotFull = false,
                loopingEnabled = true,
                oneShotSeconds = 12f,
                loopingSeconds = 34f,
            ),
            runtimeRecordingTileFallbackSnapshot(
                cached = live,
                activeBuffer = ReverbService.BufferSlot.LOOPING,
                oneShotEnabled = false,
                loopingEnabled = true,
            ),
        )
    }

    @Test
    fun quickTileHydration_rejectsSupersededRuntimeState() {
        val expected = failClosedRecordingTileSnapshot()
        val newer = expected.copy(oneShotEnabled = true)
        assertTrue(tileHydrationCanApply(7L, 7L, expected, expected))
        assertFalse(tileHydrationCanApply(7L, 8L, expected, expected))
        assertFalse(tileHydrationCanApply(7L, 7L, expected, newer))
        assertTrue(tileHydrationCanApply(7L, 7L, null, null))
        assertFalse(tileHydrationCanApply(7L, 7L, null, expected))
    }

    @Test
    fun stoppedQuickTileUsesPersistedSettingsButKeepsLatestDurations() {
        val persisted = RecordingTileSnapshot(
            listening = false,
            activeBuffer = ReverbService.BufferSlot.LOOPING,
            oneShotEnabled = false,
            oneShotFull = false,
            loopingEnabled = true,
            oneShotSeconds = 1f,
            loopingSeconds = 2f,
        )
        val staleLive = RecordingTileSnapshot(
            listening = true,
            activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
            oneShotEnabled = true,
            oneShotFull = true,
            loopingEnabled = false,
            oneShotSeconds = 11f,
            loopingSeconds = 22f,
        )
        assertEquals(
            persisted.copy(oneShotSeconds = 11f, loopingSeconds = 22f),
            stoppedRecordingTileSnapshot(persisted, staleLive),
        )
    }

    @Test
    fun quickTileDurationCache_roundTripsAtDisplayPrecision() {
        assertEquals(0L, quickTileDurationMillis(Float.NaN))
        assertEquals(0L, quickTileDurationMillis(-1f))
        assertEquals(65_900L, quickTileDurationMillis(65.9f))
        assertEquals(65.9f, cachedTileDurationSeconds(65_900L), 0.001f)
        assertEquals(0f, cachedTileDurationSeconds(-1L), 0f)
    }

    @Test
    fun quickTileActionCompletion_waitsForLiveRuntimeState() {
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val looping = ReverbService.BufferSlot.LOOPING
        val idle = RecordingTileSnapshot(
            listening = false,
            activeBuffer = oneShot,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
        )
        val oneShotRunning = idle.copy(listening = true)
        val loopingRunning = oneShotRunning.copy(activeBuffer = looping)

        assertFalse(recordingTileActionSatisfied(RecordingTileClickAction.START, oneShot, idle))
        assertTrue(recordingTileActionSatisfied(RecordingTileClickAction.START, oneShot, oneShotRunning))
        assertFalse(recordingTileActionSatisfied(RecordingTileClickAction.SWITCH, looping, oneShotRunning))
        assertTrue(recordingTileActionSatisfied(RecordingTileClickAction.SWITCH, looping, loopingRunning))
        assertFalse(recordingTileActionSatisfied(RecordingTileClickAction.STOP, oneShot, oneShotRunning))
        assertTrue(recordingTileActionSatisfied(RecordingTileClickAction.STOP, oneShot, idle))
        assertTrue(recordingTileActionSatisfied(RecordingTileClickAction.NONE, oneShot, oneShotRunning))
    }

    @Test
    fun quickTileClick_isAlwaysRecomputedFromCurrentLiveSnapshot() {
        val oneShot = ReverbService.BufferSlot.ONE_SHOT
        val idle = RecordingTileSnapshot(
            listening = false,
            activeBuffer = oneShot,
            oneShotEnabled = true,
            oneShotFull = false,
            loopingEnabled = true,
        )
        val oneShotRunning = idle.copy(listening = true)
        val loopingRunning = oneShotRunning.copy(activeBuffer = ReverbService.BufferSlot.LOOPING)

        assertEquals(RecordingTileClickAction.START, recordingTileClickAction(oneShot, idle))
        assertEquals(RecordingTileClickAction.STOP, recordingTileClickAction(oneShot, oneShotRunning))
        assertEquals(RecordingTileClickAction.SWITCH, recordingTileClickAction(oneShot, loopingRunning))
        assertEquals(RecordingTileClickAction.NONE, recordingTileClickAction(oneShot, idle.copy(oneShotFull = true)))
        assertEquals(RecordingTileClickAction.NONE, recordingTileClickAction(oneShot, idle.copy(oneShotEnabled = false)))
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
        AudioSourceMode.entries.forEach { mode ->
            assertEquals(mode.storageCode.toInt(), mode.sourceValue)
            assertEquals(mode, AudioSourceMode.fromStorageCode(mode.storageCode.toInt()))
        }
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
    fun exactWavDurationLimitEndsOnACompleteOutputFrame() {
        listOf(
            1 to PcmSampleFormat.PCM_16,
            2 to PcmSampleFormat.PCM_16,
            1 to PcmSampleFormat.PCM_FLOAT,
            2 to PcmSampleFormat.PCM_FLOAT,
        ).forEach { (channelCount, sampleFormat) ->
            val frameBytes = channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
            val payloadBudget = exportPayloadLimitBytes(ExportFormat.WAV, sampleFormat)
            val alignedPayload = payloadBudget - payloadBudget % frameBytes
            val bytesPerSecond = 44_100L * frameBytes
            val exactLimit = exportDurationLimitExactSeconds(
                format = ExportFormat.WAV,
                codec = ExportCodec.PCM_16,
                sampleRate = 44_100,
                channelCount = channelCount,
                sampleFormat = sampleFormat,
            )
            assertEquals(alignedPayload.toDouble() / bytesPerSecond.toDouble(), exactLimit, 0.0)
            assertEquals(0L, alignedPayload % frameBytes)
            assertTrue(alignedPayload <= payloadBudget)
            assertTrue(alignedPayload + frameBytes > payloadBudget)
        }
    }

    @Test
    fun exactWavExportDurationLimit_matchesFrameAlignedBudgetAndFlooredWholeSeconds() {
        val exact = exportDurationLimitExactSeconds(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleRate = 48_000,
            channelCount = 2,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
        val payload = exportPayloadLimitBytes(ExportFormat.WAV, PcmSampleFormat.PCM_16)
        val frameBytes = 2L * PcmSampleFormat.PCM_16.bytesPerSample.toLong()
        val alignedPayload = payload - payload % frameBytes
        assertEquals(alignedPayload.toDouble() / (48_000.0 * frameBytes.toDouble()), exact, 0.0)
        assertEquals(
            exportDurationLimitSeconds(
                ExportFormat.WAV, ExportCodec.PCM_16, 48_000, 2, PcmSampleFormat.PCM_16,
            ),
            kotlin.math.floor(exact).toLong(),
        )
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
    fun fullExportSnapshot_rejectsStaleOwnerGeneration() {
        assertTrue(timelineSnapshotRequestIsCurrent(7L, 7L))
        assertFalse(timelineSnapshotRequestIsCurrent(6L, 7L))
        assertFalse(timelineSnapshotRequestIsCurrent(8L, 7L))
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
    fun recorderActionsStayLockedUntilConnectedStateIsHydrated() {
        assertFalse(captureServiceInteractionReady(serviceConnected = false, stateHydrated = false))
        assertFalse(captureServiceInteractionReady(serviceConnected = true, stateHydrated = false))
        assertFalse(captureServiceInteractionReady(serviceConnected = false, stateHydrated = true))
        assertTrue(captureServiceInteractionReady(serviceConnected = true, stateHydrated = true))
    }

    @Test
    fun exportStateSnapshotRestoresDetachedUiBusyState() {
        val restored = reconcileCaptureExportStatus(
            exporting = true,
            receiverAttached = false,
            status = null,
        )
        assertEquals(CaptureSaveStatus.Saving(cancellable = true), restored)
        assertTrue(captureExportUiBusy(exporting = true, receiverAttached = false, status = restored))
        assertEquals(
            null,
            reconcileCaptureExportStatus(
                exporting = false,
                receiverAttached = false,
                status = restored,
            ),
        )
        assertFalse(captureExportUiBusy(exporting = false, receiverAttached = false, status = null))
        val attached = CaptureSaveStatus.Saving(cancellable = false)
        assertEquals(
            attached,
            reconcileCaptureExportStatus(
                exporting = false,
                receiverAttached = true,
                status = attached,
            ),
        )
        assertTrue(captureExportUiBusy(exporting = false, receiverAttached = true, status = attached))
    }

    @Test
    fun detachedExportFailure_usesStableNonBlankNotificationText() {
        assertEquals("Could not save", normalizedCaptureSaveFailureMessage("  ", "Could not save"))
        assertEquals("provider failed", normalizedCaptureSaveFailureMessage("  provider failed  ", "fallback"))
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
    fun detachedSaveUiGateDropsDeadUiCallbacks() {
        val events = mutableListOf<String>()
        val gate = SaveUiCallbackGate(
            setSaving = { events += "saving=$it" },
            onStatus = { events += "status=${it?.javaClass?.simpleName ?: "null"}" },
            onError = { events += "error=$it" },
            onSaved = { events += "saved" },
        )
        val recording = RecordingEntity(
            id = "id",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 1L,
            durationMillis = 1L,
            sizeBytes = 1L,
            codecSummary = "pcm",
            storageType = RecordingStorageType.FILE,
            directoryId = "dir",
        )

        assertTrue(gate.saved(recording))
        assertEquals(listOf("status=Saved", "saving=false", "saved"), events)

        events.clear()
        gate.detach()
        assertFalse(gate.attached)
        assertFalse(gate.failed("late"))
        assertFalse(gate.cancelled())
        assertFalse(gate.saved(recording))
        assertTrue(events.isEmpty())
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
            codecSummary = "PCM 16 · 48 kHz", storageType = RecordingStorageType.FILE,
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
        assertFalse(shouldComposeMainPanel(previouslyComposed = false, visible = false, progress = 0f))
        assertTrue(shouldComposeMainPanel(previouslyComposed = false, visible = true, progress = 0f))
        assertTrue(shouldComposeMainPanel(previouslyComposed = false, visible = false, progress = 0.001f))
        assertTrue(shouldComposeMainPanel(previouslyComposed = true, visible = false, progress = 0f))
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
        assertEquals(90f, bufferTransitionFlipDegrees(oneShot, looping, 0.5f), 0.0001f)
        assertEquals(45f, bufferTransitionFlipDegrees(oneShot, looping, 0.75f), 0.0001f)
        assertEquals(0f, bufferTransitionFlipDegrees(oneShot, looping, 1f), 0.0001f)
        assertEquals(45f, bufferTransitionFlipDegrees(looping, oneShot, 0.25f), 0.0001f)
        assertEquals(-90f, bufferTransitionFlipDegrees(looping, oneShot, 0.5f), 0.0001f)
        assertEquals(-45f, bufferTransitionFlipDegrees(looping, oneShot, 0.75f), 0.0001f)

        assertEquals(0.5f, bufferTransitionPivotFractionX(), 0.0001f)
        assertTrue(bufferTransitionFlipDegrees(oneShot, looping, 0.25f) < 0f)
        assertTrue(bufferTransitionFlipDegrees(looping, oneShot, 0.25f) > 0f)
        assertEquals(1f, bufferTransitionDepthScale(0f), 0.0001f)
        assertEquals(0.94f, bufferTransitionDepthScale(0.5f), 0.0001f)
        assertEquals(1f, bufferTransitionDepthScale(1f), 0.0001f)
    }

    @Test
    fun predictiveBackOpenProgress_reversesAndClampsDestinationReveal() {
        assertEquals(1f, predictiveBackOpenProgress(-1f), 0f)
        assertEquals(1f, predictiveBackOpenProgress(0f), 0f)
        assertEquals(0.75f, predictiveBackOpenProgress(0.25f), 0.0001f)
        assertEquals(0f, predictiveBackOpenProgress(1f), 0f)
        assertEquals(0f, predictiveBackOpenProgress(2f), 0f)
    }


    @Test
    fun predictiveBackHorizontalDirection_followsGestureEdge() {
        assertEquals(1f, predictiveBackHorizontalDirection(BackEventCompat.EDGE_LEFT), 0f)
        assertEquals(-1f, predictiveBackHorizontalDirection(BackEventCompat.EDGE_RIGHT), 0f)
        assertEquals(1f, predictiveBackHorizontalDirection(BackEventCompat.EDGE_NONE), 0f)
    }
}
