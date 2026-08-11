package app.smallthingz.timetravel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingAndHistoryMathTest {
    @Test
    fun missingRecordingTtl_startsFromFirstObservedMiss_notCreationTime() {
        val createdAt = 1_000L
        val firstMissingAt = createdAt + RecordingRepository.MISSING_RECORDING_TTL_MILLIS - 1L
        val oldRecording = RecordingEntity(
            id = "id",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 500L,
            durationMillis = 1_000L,
            sizeBytes = 2_000L,
            codecSummary = "PCM 16-bit",
            storageType = RecordingStorageType.FILE.name,
            directoryId = "dir",
            createdAtMillis = createdAt,
            lastSeenAtMillis = createdAt,
            missingSinceMillis = firstMissingAt,
        )

        assertFalse(
            isMissingRecordingExpired(
                oldRecording,
                firstMissingAt + RecordingRepository.MISSING_RECORDING_TTL_MILLIS - 1L,
            ),
        )
        assertTrue(
            isMissingRecordingExpired(
                oldRecording,
                firstMissingAt + RecordingRepository.MISSING_RECORDING_TTL_MILLIS,
            ),
        )
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
        assertTrue(ExportFormat.WAV in ExportCodec.PCM_16.supportedFormats)
        assertTrue(ExportFormat.WAV.isPcmContainer)
    }

    @Test
    fun audioSourceMode_prefersVoiceModesBeforeMicAndUnprocessed() {
        val ordered = AudioSourceMode.availableModes()

        assertEquals(AudioSourceMode.VOICE_RECOGNITION, AudioSourceMode.defaultMode())
        assertTrue(ordered.indexOf(AudioSourceMode.VOICE_RECOGNITION) < ordered.indexOf(AudioSourceMode.MIC))
        assertTrue(ordered.indexOf(AudioSourceMode.VOICE_COMMUNICATION) < ordered.indexOf(AudioSourceMode.UNPROCESSED))
        assertTrue(ordered.contains(AudioSourceMode.VOICE_CALL))
        assertTrue(ordered.contains(AudioSourceMode.VOICE_UPLINK))
        assertTrue(ordered.contains(AudioSourceMode.VOICE_DOWNLINK))
        assertTrue(ordered.contains(AudioSourceMode.REMOTE_SUBMIX))
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
        assertEquals(0xFFFF_FFFFL - 44L, limit)
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

}
