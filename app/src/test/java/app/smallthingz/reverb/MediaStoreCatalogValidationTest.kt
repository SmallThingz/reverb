package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreCatalogValidationTest {
    private fun recording(identity: String = "provider:3:item:120:9") = RecordingEntity(
        id = "content://media/item",
        displayName = "clip.wav",
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 1_000L,
        sizeBytes = 120L,
        codecSummary = "WAV",
        storageType = RecordingStorageType.MEDIASTORE,
        directoryId = "mediastore",
        fileIdentity = identity,
    )

    @Test
    fun mediaStoreCatalog_reusesOnlySameKnownPhysicalRevision() {
        val known = recording()
        assertTrue(canReuseKnownMediaStoreRecording(known, "clip.wav", 120L, known.fileIdentity))
        assertFalse(canReuseKnownMediaStoreRecording(known, "renamed.wav", 120L, known.fileIdentity))
        assertFalse(canReuseKnownMediaStoreRecording(known, "clip.wav", 121L, known.fileIdentity))
        assertFalse(canReuseKnownMediaStoreRecording(known, "clip.wav", 120L, "provider:3:item:120:10"))
        assertFalse(canReuseKnownMediaStoreRecording(null, "clip.wav", 120L, known.fileIdentity))
        assertFalse(canReuseKnownMediaStoreRecording(known, "clip.wav", 120L, ""))
    }
}
