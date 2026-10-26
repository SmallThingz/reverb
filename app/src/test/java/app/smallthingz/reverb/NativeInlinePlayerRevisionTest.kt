package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInlinePlayerRevisionTest {
    @Test
    fun sameRowRevisionComparisonMatchesReleasedKeyAcrossEveryDependency() {
        val original = RecordingEntity(
            id = "content://recordings/item|with-separator",
            displayName = "clip.wav",
            mimeType = "audio/wav",
            startedAtMillis = 100L,
            durationMillis = 2000L,
            sizeBytes = 64044L,
            codecSummary = "WAV",
            storageType = RecordingStorageType.DOCUMENT,
            directoryId = "content://recordings/tree/root",
            fileIdentity = "provider:identity|with|separators",
            createdAtMillis = 200L,
            lastSeenAtMillis = 300L,
        )
        fun releasedKey(recording: RecordingEntity) =
            "${recording.id}|${recording.fileIdentity}|${recording.sizeBytes}|${recording.durationMillis}|${recording.lastSeenAtMillis}"

        val changed = listOf(
            original.copy(fileIdentity = ""), original.copy(fileIdentity = original.fileIdentity + "|0"),
            original.copy(sizeBytes = 0L), original.copy(sizeBytes = Long.MAX_VALUE),
            original.copy(durationMillis = 0L), original.copy(durationMillis = Long.MAX_VALUE),
            original.copy(lastSeenAtMillis = Long.MIN_VALUE), original.copy(lastSeenAtMillis = Long.MAX_VALUE),
        )
        for (next in changed) {
            assertEquals(releasedKey(original) == releasedKey(next), sameNativeInlinePlayerRevision(original, next))
            assertFalse(sameNativeInlinePlayerRevision(original, next))
        }
        val presentationOnly = original.copy(
            displayName = "renamed.wav", mimeType = "audio/x-wav", startedAtMillis = 400L,
            codecSummary = "updated", directoryId = "another directory", waveformData = "cached",
            waveformRevision = "cached revision", createdAtMillis = 500L, missingSinceMillis = 600L,
        )
        assertTrue(sameNativeInlinePlayerRevision(original, original.copy()))
        assertEquals(releasedKey(original), releasedKey(presentationOnly))
        assertTrue(sameNativeInlinePlayerRevision(original, presentationOnly))
    }
}
