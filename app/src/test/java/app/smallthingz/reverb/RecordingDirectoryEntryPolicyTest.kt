package app.smallthingz.reverb

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingDirectoryEntryPolicyTest {
    @Test
    fun listedEntryInspection_distinguishesMissingUnavailableAndRegularFiles() {
        assertEquals(
            FileDirectoryEntryScanAction.SKIP,
            fileDirectoryEntryScanAction(StoragePathObservation(StoragePathState.MISSING)),
        )
        assertEquals(
            FileDirectoryEntryScanAction.FAIL_SCOPE,
            fileDirectoryEntryScanAction(StoragePathObservation(StoragePathState.UNAVAILABLE)),
        )
        assertEquals(
            FileDirectoryEntryScanAction.SKIP,
            fileDirectoryEntryScanAction(StoragePathObservation(StoragePathState.PRESENT, isRegularFile = false)),
        )
        assertEquals(
            FileDirectoryEntryScanAction.SCAN,
            fileDirectoryEntryScanAction(StoragePathObservation(StoragePathState.PRESENT, isRegularFile = true)),
        )
    }

    @Test
    fun recoverableWavParser_propagatesReadFailuresInsteadOfCallingThemInvalidAudio() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("storage unavailable")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("storage unavailable")
        }
        assertThrows(IOException::class.java) { readRecoverableStagingWav(broken) }
    }
}
