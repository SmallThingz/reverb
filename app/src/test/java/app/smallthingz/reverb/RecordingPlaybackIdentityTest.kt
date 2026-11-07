package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackIdentityTest {
    private val source = RecordingEntity("/recordings/a.wav", "a.wav", "audio/wav", 1L, 2000L,
        100L, "PCM", RecordingStorageType.FILE, "/recordings", "native-revision-a",
        createdAtMillis = 1L)

    @Test fun reusedPathNeverMatchesOldPlayback() {
        assertFalse(RecordingPlaybackState(recording = source).matches(source.copy(fileIdentity = "replacement")))
        assertFalse(RecordingPlaybackState(recording = source).matches(source.copy(sizeBytes = 200)))
        assertFalse(RecordingPlaybackState(recording = source.copy(fileIdentity = "")).matches(source.copy(fileIdentity = "")))
    }

    @Test fun catalogOnlyMetadataDoesNotReplaceTransport() {
        assertTrue(RecordingPlaybackState(recording = source).matches(source.copy(lastSeenAtMillis = 999L,
            waveformData = "updated", displayName = "new label")))
    }

    @Test fun pendingRenameKeepsAcceptedTokenAndPosition() {
        val start = RecordingPlayback.Start(43L, source, 700)
        val renamed = source.copy(id = "/recordings/b.wav", displayName = "b.wav", fileIdentity = "renamed-revision")
        val result = retargetPlaybackStartAfterRename(start, source, renamed)
        assertEquals(43L, result.token)
        assertEquals(700, result.positionMillis)
        assertEquals(renamed, result.recording)
    }

    @Test fun staleRenameCannotRetargetNewerPendingBytes() {
        val start = RecordingPlayback.Start(44L, source.copy(fileIdentity = "replacement"), 900)
        assertSame(start, retargetPlaybackStartAfterRename(start, source, source.copy(id = "/recordings/b.wav")))
    }
}
