package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingInlinePlayerLifecycleTest {
    @Test
    fun mediaPlayerCallback_requiresExactLivePlayerOwnership() {
        assertTrue(
            inlinePlaybackCallbackIsCurrent(
                released = false,
                disposed = false,
                samePlayer = true,
            ),
        )
        assertFalse(
            inlinePlaybackCallbackIsCurrent(
                released = true,
                disposed = false,
                samePlayer = true,
            ),
        )
        assertFalse(
            inlinePlaybackCallbackIsCurrent(
                released = false,
                disposed = true,
                samePlayer = true,
            ),
        )
        assertFalse(
            inlinePlaybackCallbackIsCurrent(
                released = false,
                disposed = false,
                samePlayer = false,
            ),
        )
    }

    @Test
    fun oldPlayerCallback_staysRejectedAfterNewRevisionClearsSharedReleasedFlag() {
        // Disposing a revision sets its local disposed flag permanently. A newer revision may
        // legitimately reset the shared playbackBookkeeping.released flag, but that must never
        // make a queued callback from the old MediaPlayer current again.
        assertFalse(
            inlinePlaybackCallbackIsCurrent(
                released = false,
                disposed = true,
                samePlayer = false,
            ),
        )
    }
}
