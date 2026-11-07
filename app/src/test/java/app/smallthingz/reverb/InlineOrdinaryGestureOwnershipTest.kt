package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineOrdinaryGestureOwnershipTest {
    @Test fun sameRevisionReopenedCannotBeResumedByAnOlderGesture() {
        assertTrue(inlineOrdinaryGestureIsCurrent(14L, 14L, true))
        // The same path and bytes can be reopened as a new playback session.
        assertFalse(inlineOrdinaryGestureIsCurrent(14L, 15L, true))
    }

    @Test fun anotherRecordingCannotReceiveTheOldRowsSeekOrResume() {
        assertFalse(inlineOrdinaryGestureIsCurrent(14L, 14L, false))
    }

    @Test fun detachedOrNeverStartedGestureHasNoPlaybackAuthority() {
        assertFalse(inlineOrdinaryGestureIsCurrent(null, 14L, true))
    }
}
