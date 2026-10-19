package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWaveformCacheUpgradeTest {
    @Test
    fun legacyEnvelopeIsRejectedAndCannotBePromotedByMergeOrRebind() {
        for (extension in listOf("flac", "wav")) {
            val source = recording(extension)
            val payload = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.25f })
            // The persisted version-1 format is intentionally independent of today's revision builder.
            val legacy = source.copy(
                waveformData = payload,
                waveformRevision = "1|${source.storageType.storageCode.toInt()}|${source.fileIdentity}|" +
                    "${source.sizeBytes}|${source.durationMillis}",
            )
            assertNotNull(decodeRecordingWaveform(legacy.waveformData))
            assertFalse(isValidRecordingWaveformCache(legacy, legacy.waveformData, legacy.waveformRevision))

            val merged = mergeObservedRecording(legacy, legacy, 20L)
            assertEmptyCache(merged)
            assertEmptyCache(mergeObservedRecording(null, legacy, 20L))
            assertEmptyCache(rebindRecordingWaveformCache(legacy, movedTarget(source)))
            assertEmptyCache(rebindRecordingWaveformCache(merged, movedTarget(source)))
        }
    }

    @Test
    fun rebuiltEnvelopeSurvivesObservationAndVerifiedRebind() {
        val source = recording("flac")
        val payload = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.75f })
        val rebuilt = source.copy(waveformData = payload, waveformRevision = recordingWaveformRevision(source))
        assertTrue(isValidRecordingWaveformCache(rebuilt, rebuilt.waveformData, rebuilt.waveformRevision))
        val observed = source.copy(displayName = "renamed.flac")
        val merged = mergeObservedRecording(rebuilt, observed, 20L)
        assertEquals(payload, merged.waveformData)
        assertEquals(rebuilt.waveformRevision, merged.waveformRevision)

        val target = movedTarget(source)
        val rebound = rebindRecordingWaveformCache(merged, target)
        assertEquals(payload, rebound.waveformData)
        assertEquals(recordingWaveformRevision(target), rebound.waveformRevision)
        assertTrue(isValidRecordingWaveformCache(rebound, rebound.waveformData, rebound.waveformRevision))
    }

    private fun recording(extension: String) = RecordingEntity(
        id = "old", displayName = "clip.$extension", mimeType = "audio/$extension",
        startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 4_000L,
        codecSummary = extension.uppercase(), storageType = RecordingStorageType.FILE,
        directoryId = "old-dir", fileIdentity = "stat:old",
    )

    private fun movedTarget(source: RecordingEntity) = source.copy(
        id = "content://media/9", storageType = RecordingStorageType.MEDIASTORE,
        directoryId = MEDIA_STORE_DIRECTORY_ID, fileIdentity = "provider:MEDIASTORE:new",
    )

    private fun assertEmptyCache(recording: RecordingEntity) {
        assertEquals("", recording.waveformData)
        assertEquals("", recording.waveformRevision)
    }
}
