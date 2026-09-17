package app.smallthingz.reverb

import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedRecordingFileProviderPolicyTest {
    @Test
    fun queryMetadata_exposesOnlyOpenableColumnsFromVerifiedDescriptorState() {
        val defaults = verifiedFileProviderQueryColumns(null)
        assertEquals(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), defaults)
        assertEquals(listOf("clip.wav", 123L), verifiedFileProviderQueryValues(defaults, "clip.wav", 123L))

        val requested = verifiedFileProviderQueryColumns(
            arrayOf(OpenableColumns.SIZE, "ignored", OpenableColumns.DISPLAY_NAME),
        )
        assertEquals(listOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), requested)
        assertEquals(listOf(123L, "clip.wav"), verifiedFileProviderQueryValues(requested, "clip.wav", 123L))
        assertEquals(listOf(null, "clip.wav"), verifiedFileProviderQueryValues(requested, "clip.wav", null))
    }
}
