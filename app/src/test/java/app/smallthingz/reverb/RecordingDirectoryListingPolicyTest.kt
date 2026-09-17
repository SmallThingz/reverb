package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDirectoryListingPolicyTest {
    @Test
    fun failedDirectoryListingIsEmptyOnlyWhenDirectoryIsPositivelyMissing() {
        assertTrue(fileDirectoryListingFailureIsAuthoritativeEmpty(StoragePathState.MISSING))
        assertFalse(fileDirectoryListingFailureIsAuthoritativeEmpty(StoragePathState.PRESENT))
        assertFalse(fileDirectoryListingFailureIsAuthoritativeEmpty(StoragePathState.UNAVAILABLE))
    }
}
