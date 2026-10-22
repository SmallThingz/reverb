package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFileCatalogIdentityTest {
    private val legacyIdentity = "stat:10:20:30:40:50"
    private val currentIdentity = "stat:10:20:30:40:0"

    private fun recording(identity: String) = RecordingEntity(
        id = "/managed/recording.wav",
        displayName = "recording.wav",
        mimeType = "audio/wav",
        startedAtMillis = 100L,
        durationMillis = 1_000L,
        sizeBytes = 2_000L,
        codecSummary = "PCM",
        storageType = RecordingStorageType.FILE,
        directoryId = "/managed",
        fileIdentity = identity,
        createdAtMillis = 100L,
        lastSeenAtMillis = 200L,
    )

    @Test
    fun legacyBirthNormalizationRetainsSelectedFileInBothDirections() {
        val legacy = recording(legacyIdentity)
        val current = recording(currentIdentity)
        assertTrue(sameRecordingActionTarget(legacy, current))
        assertTrue(sameRecordingActionTarget(current, legacy))
        assertTrue(observedRecordingIsSameAsset(legacy, current))
        assertTrue(observedRecordingIsSameAsset(current, legacy))
    }

    @Test
    fun metadataRepairRetainsSelectedFileAndCreationHistory() {
        val selected = recording(legacyIdentity).copy(durationMillis = 0L).let { row ->
            row.copy(
                waveformData = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.5f }),
                waveformRevision = recordingWaveformRevision(row),
            )
        }
        val rescanned = recording(currentIdentity).copy(createdAtMillis = 900L)
        val merged = mergeObservedRecording(selected, rescanned, nowMillis = 1_000L)
        assertEquals(currentIdentity, merged.fileIdentity)
        assertEquals(1_000L, merged.durationMillis)
        assertEquals(selected.createdAtMillis, merged.createdAtMillis)
        assertEquals(selected.lastSeenAtMillis, merged.lastSeenAtMillis)
        assertTrue(selected.waveformData.isNotBlank())
        assertEquals("", merged.waveformData)
        assertEquals("", merged.waveformRevision)
        assertTrue(sameRecordingActionTarget(selected, merged))
    }

    @Test
    fun changedPhysicalRevisionAndConflictingKnownBirthRemainRejected() {
        val selected = recording(legacyIdentity)
        val changed = listOf(
            "stat:11:20:30:40:0",
            "stat:10:21:30:40:0",
            "stat:10:20:31:40:0",
            "stat:10:20:30:41:0",
            "stat:10:20:30:40:51",
        )
        for (identity in changed) {
            val observed = recording(identity)
            assertFalse(identity, sameRecordingActionTarget(selected, observed))
            assertFalse(identity, observedRecordingIsSameAsset(selected, observed))
        }
    }

    @Test
    fun unequalMalformedIdentityPairsNeverGainBirthCompatibility() {
        val malformed = listOf(
            "stat:x:20:30:40:50" to "stat:x:20:30:40:0",
            "stat:10:x:30:40:50" to "stat:10:x:30:40:0",
            "stat:10:20:x:40:50" to "stat:10:20:x:40:0",
            "stat:10:20:30:x:50" to "stat:10:20:30:x:0",
            "stat:10:20:30:40:x" to currentIdentity,
            "stat:9223372036854775808:20:30:40:50" to "stat:9223372036854775808:20:30:40:0",
            "stat:10:20:30:40:9223372036854775808" to currentIdentity,
            "stat:10:20:30:50" to "stat:10:20:30:0",
            "stat:10:20:30:40:50:60" to "stat:10:20:30:40:50:0",
            "nio:key:50" to "nio:key:0",
        )
        for ((before, after) in malformed) {
            assertFalse(before, sameRecordingActionTarget(recording(before), recording(after)))
            assertFalse(before, sameRecordingActionTarget(recording(after), recording(before)))
            assertFalse(before, observedRecordingIsSameAsset(recording(before), recording(after)))
            assertFalse(before, observedRecordingIsSameAsset(recording(after), recording(before)))
        }
    }

    @Test
    fun exactOpaqueAndNioEqualityRemainCompatible() {
        for (identity in listOf("stat:a", "opaque-legacy-key", "nio:a2V5:50", legacyIdentity)) {
            val selected = recording(identity)
            assertTrue(identity, sameRecordingActionTarget(selected, selected.copy()))
            assertTrue(identity, observedRecordingIsSameAsset(selected, selected.copy()))
        }
    }

    @Test
    fun existingBlankIdentityPoliciesRemainUnchanged() {
        val blank = recording("")
        val known = recording(currentIdentity)
        assertTrue(sameRecordingActionTarget(blank, blank.copy()))
        assertFalse(sameRecordingActionTarget(blank, blank.copy(durationMillis = 2_000L)))
        assertFalse(sameRecordingActionTarget(blank, known))
        assertFalse(sameRecordingActionTarget(known, blank))
        assertTrue(observedRecordingIsSameAsset(blank, known))
        assertTrue(observedRecordingIsSameAsset(known, blank))
    }

    @Test
    fun idStorageAndProviderIdentityPoliciesRemainUnchanged() {
        val selected = recording(legacyIdentity)
        assertFalse(sameRecordingActionTarget(selected, selected.copy(id = "/managed/other.wav")))
        assertFalse(observedRecordingIsSameAsset(selected, selected.copy(id = "/managed/other.wav")))
        assertFalse(sameRecordingActionTarget(selected, selected.copy(storageType = RecordingStorageType.DOCUMENT)))
        assertFalse(observedRecordingIsSameAsset(selected, selected.copy(storageType = RecordingStorageType.DOCUMENT)))
        for (storage in listOf(RecordingStorageType.DOCUMENT, RecordingStorageType.MEDIASTORE)) {
            val identity = "provider:${storage.storageCode}:ZG9j:2000:300"
            val provider = selected.copy(storageType = storage, fileIdentity = identity)
            val changed = provider.copy(fileIdentity = "provider:${storage.storageCode}:ZG9j:2000:301")
            assertTrue(sameRecordingActionTarget(provider, provider.copy()))
            assertTrue(observedRecordingIsSameAsset(provider, provider.copy()))
            assertFalse(sameRecordingActionTarget(provider, changed))
            assertFalse(observedRecordingIsSameAsset(provider, changed))
        }
    }
}
