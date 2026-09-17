package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingInlinePlayerLogicTest {
    @Test
    fun shuttleFailure_resumesOnlyWhenItHadTakenAudiblePlaybackOwnership() {
        assertTrue(
            inlineShuttleFailureShouldResume(
                shuttleActive = true, resumeAfterScrub = true, lifecycleResumed = true,
            ),
        )
        assertFalse(
            inlineShuttleFailureShouldResume(
                shuttleActive = false, resumeAfterScrub = true, lifecycleResumed = true,
            ),
        )
        assertFalse(
            inlineShuttleFailureShouldResume(
                shuttleActive = true, resumeAfterScrub = false, lifecycleResumed = true,
            ),
        )
        assertFalse(
            inlineShuttleFailureShouldResume(
                shuttleActive = true, resumeAfterScrub = true, lifecycleResumed = false,
            ),
        )
    }
}
