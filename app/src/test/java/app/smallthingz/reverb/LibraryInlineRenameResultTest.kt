package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryInlineRenameResultTest {
    private fun recording(id: String) = RecordingEntity(id, "$id.wav", "audio/wav", 1L, 2L, 3L,
        "PCM", RecordingStorageType.DOCUMENT, "directory")

    @Test fun acceptedACompletionCannotReplaceBWhenAnotherEditorIsCurrent() {
        val a = recording("A")
        val b = recording("B")
        val renamed = a.copy(id = "renamed-A", displayName = "new.wav")
        val rows = libraryRecordingsAfterRename(listOf(a, b), a, renamed)
        assertEquals(listOf(renamed, b), rows)
        assertSame(b, rows[1])
    }

    @Test fun completionCannotOverwriteReusedIdentityOrReinsertAnAbsentTarget() {
        val original = recording("A")
        val replaced = original.copy(sizeBytes = 99L)
        val renamed = original.copy(id = "renamed-A")
        assertSame(replaced, libraryRecordingsAfterRename(listOf(replaced), original, renamed).single())
        assertEquals(emptyList<RecordingEntity>(), libraryRecordingsAfterRename(emptyList(), original, renamed))
    }
}
