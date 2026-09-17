package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryWaveformTrustTest {
    @Test
    fun catalogOnlySnapshot_preservesMetadataButRevokesWaveformTrust() {
        val recording = RecordingEntity(
            id = "/recordings/clip.wav",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 123L,
            durationMillis = 4_000L,
            sizeBytes = 8_000L,
            codecSummary = "PCM",
            storageType = RecordingStorageType.FILE,
            directoryId = "/recordings",
            fileIdentity = "stat:1:2:3:4:5",
            waveformData = "cached-waveform",
            waveformRevision = "cached-revision",
        )

        val sanitized = librarySnapshotWithoutWaveformTrust(listOf(recording)).single()
        assertEquals(recording.copy(waveformData = "", waveformRevision = ""), sanitized)
        assertEquals(recording.fileIdentity, sanitized.fileIdentity)
        assertEquals(recording.durationMillis, sanitized.durationMillis)
        assertEquals(recording.sizeBytes, sanitized.sizeBytes)
    }

    @Test
    fun catalogOnlySnapshot_withoutWaveformCacheReusesOriginalList() {
        val recordings = listOf(
            RecordingEntity(
                id = "/recordings/clip.wav",
                displayName = "clip.wav",
                mimeType = "audio/wav",
                startedAtMillis = 123L,
                durationMillis = 4_000L,
                sizeBytes = 8_000L,
                codecSummary = "PCM",
                storageType = RecordingStorageType.FILE,
                directoryId = "/recordings",
                fileIdentity = "stat:1:2:3:4:5",
            ),
        )
        val sanitized = librarySnapshotWithoutWaveformTrust(recordings)
        assertSame(recordings, sanitized)
        assertTrue(sanitized.single().waveformData.isEmpty())
    }
}
