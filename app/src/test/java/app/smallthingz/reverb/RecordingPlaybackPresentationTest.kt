package app.smallthingz.reverb

import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackPresentationTest {
    @Test fun acceptedPlayOffersPauseBeforePreparationCompletes() {
        val value = recordingPlaybackPhase(playing = false, wantsPlayback = true)
        assertSame(RecordingPlaybackPhase.PREPARING, value)
        assertTrue(value.offersPause)
    }
    @Test fun pauseDuringPreparationImmediatelyOffersPlay() {
        val value = recordingPlaybackPhase(playing = false, wantsPlayback = false)
        assertSame(RecordingPlaybackPhase.PAUSED, value)
        assertFalse(value.offersPause)
    }
    @Test fun audiblePlaybackOffersPause() {
        val value = recordingPlaybackPhase(playing = true, wantsPlayback = true)
        assertSame(RecordingPlaybackPhase.PLAYING, value)
        assertTrue(value.offersPause)
    }
}
