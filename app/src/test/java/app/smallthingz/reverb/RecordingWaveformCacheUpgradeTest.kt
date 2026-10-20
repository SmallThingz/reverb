package app.smallthingz.reverb

import java.util.Base64
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWaveformCacheUpgradeTest {
    @Test
    fun byteValidationPreservesEveryQuantizedSampleValue() {
        val random = Random(1483L)
        val payloads = listOf(
            ByteArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { it.toByte() },
            ByteArray(RANGE_WAVEFORM_DETAIL_BUCKETS),
            ByteArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { -1 },
        ) + List(32) { ByteArray(RANGE_WAVEFORM_DETAIL_BUCKETS).also(random::nextBytes) }
        for (bytes in payloads) {
            val encoded = Base64.getEncoder().withoutPadding().encodeToString(bytes)
            assertTrue(isValidRecordingWaveformData(encoded))
            val decoded = requireNotNull(decodeRecordingWaveform(encoded))
            assertEquals(bytes.size, decoded.size)
            for (index in bytes.indices) {
                assertEquals((bytes[index].toInt() and 0xff) / 255f, decoded[index], 0f)
            }
        }
    }

    @Test
    fun malformedPayloadCannotSurviveValidationMergeOrRebind() {
        val source = recording("wav")
        val valid = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.25f })
        val payloads = listOf(
            "", valid.dropLast(1), valid + "=", "A".repeat(100_000),
            valid.replaceRange(0, 1, "!"),
            valid.replaceRange(valid.length / 2, valid.length / 2 + 1, " "),
            valid.replaceRange(valid.lastIndex, valid.length, "?"),
            valid.dropLast(2) + "==",
        )
        for (payload in payloads) {
            assertFalse(isValidRecordingWaveformData(payload))
            assertNull(decodeRecordingWaveform(payload))
            val cached = source.copy(waveformData = payload, waveformRevision = recordingWaveformRevision(source))
            assertFalse(isValidRecordingWaveformCache(cached, payload, cached.waveformRevision))
            assertEmptyCache(mergeObservedRecording(cached, source, 20L))
            assertEmptyCache(rebindRecordingWaveformCache(cached, movedTarget(source)))
        }
    }

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
