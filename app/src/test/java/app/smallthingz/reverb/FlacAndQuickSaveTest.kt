package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class FlacAndQuickSaveTest {
    @Test fun absentExternalFlacChecksumIsLegalAndPresentChecksumMustMatch() {
        val actual = ByteArray(16) { (it + 1).toByte() }
        assertTrue(flacPcmChecksumMatches(ByteArray(16), actual))
        assertTrue(flacPcmChecksumMatches(actual.copyOf(), actual))
        assertFalse(flacPcmChecksumMatches(actual.copyOf().also { it[3] = 0 }, actual))
    }

    private fun streamInfo(rate: Int = 48000, channels: Int = 2, bits: Int = 16, frames: Long = 48000,
                           last: Boolean = true): ByteArray {
        val data = ByteArray(42)
        byteArrayOf(102, 76, 97, 67).copyInto(data)
        data[4] = if (last) 128.toByte() else 0
        data[7] = 34
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).apply {
            position(8); putShort(4096); putShort(4096)
            position(18)
            putLong((rate.toLong() shl 44) or ((channels - 1L) shl 41) or ((bits - 1L) shl 36) or frames)
        }
        return data
    }

    @Test fun compressionBoundsDoNotAlterAudioFormat() {
        for (level in -2..10) assertEquals(level in 0..8, validFlacCompression(level))
        assertEquals(5, DEFAULT_FLAC_COMPRESSION)
        assertEquals("flac", ExportFormat.FLAC.extension)
        assertEquals("audio/flac", ExportFormat.FLAC.outputMimeType)
        assertEquals(ExportFormat.FLAC, ExportFormat.fromStorageCode(2))
        assertEquals(ExportFormat.WAV, ExportFormat.fromStorageCode(1))
    }

    @Test fun metadataGeometryAndDurationAreExact() {
        val info = readFlacInfo(ByteArrayInputStream(streamInfo()))
        assertEquals(48000, info.sampleRate)
        assertEquals(2, info.channelCount)
        assertEquals(16, info.bitsPerSample)
        assertEquals(48000L, info.frameCount)
        assertEquals(1000L, info.durationMillis)
        assertEquals(42, info.headerBytes)
        assertEquals(192000L, info.pcmLayout.dataBytes)
    }

    @Test fun incompleteUnsupportedAndDuplicateMetadataAreRejected() {
        for (bytes in listOf(
            ByteArray(0), streamInfo().copyOf(41), streamInfo(rate = 0), streamInfo(channels = 3),
            streamInfo(bits = 32), streamInfo(frames = 0),
            streamInfo(last = false) + streamInfo().copyOfRange(4, 42),
        )) assertThrows(InvalidFlacException::class.java) { readFlacInfo(ByteArrayInputStream(bytes)) }
    }

    @Test fun oversizedMetadataRejectedBeforePayloadAllocation() {
        val oversized = streamInfo(last = false) + byteArrayOf(0x84.toByte(), 127, -1, -1)
        assertThrows(InvalidFlacException::class.java) { readFlacInfo(ByteArrayInputStream(oversized)) }
    }

    @Test fun compressionMetadataRoundTrips() {
        for (level in 0..8) {
            val text = "REVERB_COMPRESSION_LEVEL=$level".toByteArray()
            val payload = ByteBuffer.allocate(12 + text.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(1).putInt(text.size).put(text).array()
            val metadata = byteArrayOf(0x84.toByte(), 0, 0, payload.size.toByte()) + payload
            assertEquals(level, readFlacInfo(ByteArrayInputStream(streamInfo(last = false) + metadata)).compressionLevel)
        }
    }

    @Test fun compressionLevelMustBeOneExactUnambiguousComment() {
        fun comments(vararg values: String): ByteArray {
            val strings = values.map { it.toByteArray() }
            return ByteBuffer.allocate(8 + strings.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0); putInt(strings.size)
                strings.forEach { putInt(it.size); put(it) }
            }.array()
        }
        assertEquals(8, readFlacCompressionComment(comments("REVERB_COMPRESSION_LEVEL=8")))
        assertNull(readFlacCompressionComment(comments("REVERB_COMPRESSION_LEVEL=80")))
        assertNull(readFlacCompressionComment(comments("NOTE=REVERB_COMPRESSION_LEVEL=8")))
        assertNull(readFlacCompressionComment(comments("REVERB_COMPRESSION_LEVEL=0", "REVERB_COMPRESSION_LEVEL=8")))
        assertThrows(InvalidFlacException::class.java) { readFlacCompressionComment(byteArrayOf(0, 0, 0)) }
        assertThrows(InvalidFlacException::class.java) { readFlacCompressionComment(comments("ARTIST=Test") + byteArrayOf(0)) }
    }

    @Test fun duplicateCommentBlocksCannotOverrideCompressionMetadata() {
        fun commentBlock(level: Int, last: Boolean): ByteArray {
            val comment = "REVERB_COMPRESSION_LEVEL=$level".toByteArray()
            val payload = ByteBuffer.allocate(12 + comment.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(1).putInt(comment.size).put(comment).array()
            return byteArrayOf(if (last) 0x84.toByte() else 4, 0, 0, payload.size.toByte()) + payload
        }
        for (secondLevel in listOf(0, 8)) {
            val bytes = streamInfo(last = false) + commentBlock(0, false) + commentBlock(secondLevel, true)
            assertThrows(InvalidFlacException::class.java) { readFlacInfo(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun quickSaveClampsToRetainedAudioAndKeepsNewestEnd() {
        assertEquals(180.0 to 300.0, quickSaveRange(300.0, 120))
        assertEquals(0.0 to 32.5, quickSaveRange(32.5, 120))
        assertEquals(0.0 to 0.0, quickSaveRange(Double.NaN, 120))
        assertEquals(0.0 to 0.0, quickSaveRange(-2.0, 120))
        for (seconds in listOf(0, -1, 86401, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { quickSaveRange(100.0, seconds) }
        }
    }

    @Test fun outputChangesDoNotRestartCapture() {
        val current = ReverbService.RecorderConfigurationSnapshot(ExportFormat.WAV, ExportCodec.PCM_16,
            PcmSampleFormat.PCM_16, 48000, AudioSourceMode.MIC, ChannelMode.MONO, InputRouteMode.AUTO)
        assertFalse(captureInputConfigurationChanged(current, current.copy(format = ExportFormat.FLAC, flacCompression = 8)))
        assertTrue(captureInputConfigurationChanged(current, current.copy(sampleRate = 16000)))
        assertTrue(captureInputConfigurationChanged(current, current.copy(sampleFormat = PcmSampleFormat.PCM_FLOAT)))
        assertTrue(captureInputConfigurationChanged(null, current))
    }

    @Test fun auxiliaryActionsPreserveOnlyHealthyCaptureRestartOwnership() {
        for (mask in 0 until 16) {
            val enabled = mask and 1 != 0
            val foregroundBlocked = mask and 2 != 0
            val timedOut = mask and 4 != 0
            val storageBlocked = mask and 8 != 0
            val expected = if (enabled && !foregroundBlocked && !timedOut && !storageBlocked)
                android.app.Service.START_STICKY else android.app.Service.START_NOT_STICKY
            assertEquals(expected, retainedCaptureServiceStartMode(enabled, foregroundBlocked, timedOut, storageBlocked))
        }
    }

    @Test fun restoredFeatureDraftsAreDirtyAndReturningToBaselineClearsThem() {
        val baseline = SettingsSnapshot(format = ExportFormat.WAV,
            sampleFormat = PcmSampleFormat.PCM_16, retentionMode = RetentionMode.TIME)
        val drafts = listOf(
            baseline.copy(sampleFormat = PcmSampleFormat.PCM_FLOAT),
            baseline.copy(retentionMode = RetentionMode.SIZE),
            baseline.copy(format = ExportFormat.FLAC),
            baseline.copy(flacCompression = 8),
            baseline.copy(quickSaveEnabled = false),
            baseline.copy(quickSaveSeconds = 300),
            baseline.copy(silenceGate = encodeSilenceGate(SilenceGateConfig(enabled = true))),
        )
        for (draft in drafts) {
            assertTrue(SettingsUiDraft.normalized(draft).hasUnsavedChanges(baseline))
        }
        assertFalse(SettingsUiDraft.normalized(baseline.copy()).hasUnsavedChanges(baseline))
    }

    @Test fun settingsRollbackOwnsRestoredFeatureKeysWithoutClaimingTileDefinitions() {
        val featureKeys = setOf(PrefKey.OUTPUT_FORMAT, PrefKey.FLAC_COMPRESSION_LEVEL,
            PrefKey.QUICK_SAVE_ENABLED, PrefKey.QUICK_SAVE_SECONDS, PrefKey.SILENCE_GATE)
        val independentKeys = setOf(PrefKey.QUICK_SAVE_TILE_OPTIONS, PrefKey.QUICK_SAVE_TILE_ADDED_STATES)
        for (invalidateFull in listOf(false, true)) {
            val keys = settingsPreferenceRollbackKeys(invalidateFull)
            assertTrue(PrefKey.PCM_SAMPLE_FORMAT in keys)
            assertTrue(PrefKey.RETENTION_MODE in keys)
            assertTrue(keys.containsAll(featureKeys))
            assertTrue(keys.none { it in independentKeys })
            val snapshot = settingsPreferenceRollbackSnapshot(
                mapOf(PrefKey.OUTPUT_FORMAT.name to "FLAC", PrefKey.FLAC_COMPRESSION_LEVEL.name to 8,
                    PrefKey.QUICK_SAVE_ENABLED.name to false, PrefKey.QUICK_SAVE_SECONDS.name to 300,
                    PrefKey.SILENCE_GATE.name to encodeSilenceGate(SilenceGateConfig(enabled = true)),
                    PrefKey.PCM_SAMPLE_FORMAT.name to "PCM_16"),
                invalidateFull,
            )
            assertTrue(snapshot.keys.containsAll(featureKeys))
            assertTrue(snapshot.keys.none { it in independentKeys })
            assertTrue(PrefKey.PCM_SAMPLE_FORMAT in snapshot)
            assertEquals(invalidateFull, PrefKey.QUICK_TILE_ONE_SHOT_FULL in snapshot)
            // Rollback must retain exact old representation, including malformed legacy types.
            assertEquals(DurablePreferenceValueSnapshot(true, "FLAC"), snapshot[PrefKey.OUTPUT_FORMAT])
            assertEquals(DurablePreferenceValueSnapshot(true, 8), snapshot[PrefKey.FLAC_COMPRESSION_LEVEL])
            assertEquals(DurablePreferenceValueSnapshot(true, false), snapshot[PrefKey.QUICK_SAVE_ENABLED])
            assertEquals(DurablePreferenceValueSnapshot(true, 300), snapshot[PrefKey.QUICK_SAVE_SECONDS])
            assertEquals(DurablePreferenceValueSnapshot(true, encodeSilenceGate(SilenceGateConfig(enabled = true))),
                snapshot[PrefKey.SILENCE_GATE])

            val absent = settingsPreferenceRollbackSnapshot(emptyMap<String, Any?>(), invalidateFull)
            for (key in featureKeys) {
                assertEquals(DurablePreferenceValueSnapshot(false, null), absent[key])
            }
            val presentNull = settingsPreferenceRollbackSnapshot(
                mapOf(PrefKey.SILENCE_GATE.name to null), invalidateFull,
            )
            assertEquals(DurablePreferenceValueSnapshot(true, null), presentNull[PrefKey.SILENCE_GATE])
        }
    }

    @Test fun flacHasSampleCountLimitRatherThanRiffSizeLimit() {
        val maxSeconds = exportDurationLimitExactSeconds(ExportFormat.FLAC, ExportCodec.PCM_16, 48000, 2)
        assertEquals(0xF_FFFF_FFFFL.toDouble() / 48000, maxSeconds, 0.0)
        assertTrue(maxSeconds > exportDurationLimitExactSeconds(ExportFormat.WAV, ExportCodec.PCM_16, 48000, 2))
    }
}
