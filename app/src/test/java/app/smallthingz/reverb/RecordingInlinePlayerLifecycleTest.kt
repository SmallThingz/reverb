package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingInlinePlayerLifecycleTest {
    @Test
    fun hideOrPause_revokesBothWaveformAndPendingSeekResume() {
        val state = InlinePlayerBookkeeping()
        state.resumeAfterScrub = true
        state.seeks.request(1_000, true)
        state.revokeResume()
        assertFalse(state.resumeAfterScrub)
        assertFalse(state.seeks.resume)
        // The in-flight seek still needs its callback drained; revocation must not
        // falsely admit another simultaneous native seek.
        assertTrue(state.seeks.pending)
        state.seeks.completed()
        assertFalse(state.seeks.resume)
    }

    @Test
    fun initialPlayback_waitsWhileHiddenOrEditing() {
        assertFalse(inlinePlaybackShouldAutoStart(true, true, true, blocked = true))
        assertTrue(inlinePlaybackShouldAutoStart(true, true, true, blocked = false))
        assertFalse(inlinePlaybackShouldAutoStart(true, false, true, blocked = false))
    }

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
