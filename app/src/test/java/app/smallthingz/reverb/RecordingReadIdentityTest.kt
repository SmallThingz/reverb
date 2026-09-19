package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingReadIdentityTest {
    private fun recording(
        storageType: RecordingStorageType,
        identity: String,
    ) = RecordingEntity(
        id = if (storageType == RecordingStorageType.FILE) "/recordings/clip.wav" else "content://provider/clip",
        displayName = "clip.wav",
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 1_000L,
        sizeBytes = 120L,
        codecSummary = "WAV",
        storageType = storageType,
        directoryId = "recordings",
        fileIdentity = identity,
    )

    @Test
    fun providerRead_requiresStableIdentityBeforeOpeningBytes() {
        assertFalse(recordingReadIdentityIsStable(recording(RecordingStorageType.MEDIASTORE, "")))
        assertFalse(recordingReadIdentityIsStable(recording(RecordingStorageType.DOCUMENT, "")))
        assertTrue(
            recordingReadIdentityIsStable(
                recording(RecordingStorageType.MEDIASTORE, "provider:3:item:120:9"),
            ),
        )
        assertTrue(
            recordingReadIdentityIsStable(
                recording(RecordingStorageType.DOCUMENT, "provider:2:item:120:9"),
            ),
        )
    }

    @Test
    fun fileRead_alsoRequiresStableIdentity() {
        assertFalse(recordingReadIdentityIsStable(recording(RecordingStorageType.FILE, "")))
        assertTrue(recordingReadIdentityIsStable(recording(RecordingStorageType.FILE, "stat:1:2:120:3:4")))
    }
}
