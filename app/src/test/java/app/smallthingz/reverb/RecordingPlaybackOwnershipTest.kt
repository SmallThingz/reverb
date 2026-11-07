package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackOwnershipTest {
    @Test fun explicitPlayOrSeekRevokesHeldGestureAndSupersedesOlderQueuedCommand() {
        val owner = RecordingPlaybackOwnership()
        val generation = owner.replace()
        val gestureRevision = owner.transportIntentRevision
        owner.suspendForGesture()
        val playRevision = owner.beginExternalCommand()
        assertFalse(owner.currentGesture(generation, gestureRevision))
        assertTrue(owner.currentGesture(generation, playRevision))
        val seekRevision = owner.beginExternalCommand()
        assertFalse(owner.currentGesture(generation, playRevision))
        assertTrue(owner.currentGesture(generation, seekRevision))
    }

    @Test fun pauseSupersedesAQueuedExternalResume() {
        val owner = RecordingPlaybackOwnership()
        val generation = owner.replace()
        val queuedRevision = owner.beginExternalCommand()
        owner.pause()
        assertFalse(owner.currentGesture(generation, queuedRevision))
        assertFalse(owner.wantsPlayback)
    }

    @Test fun gestureHandoffPreservesAuthorityButExternalPauseRevokesIt() {
        val owner = RecordingPlaybackOwnership()
        val generation = owner.replace()
        val transportIntentRevision = owner.transportIntentRevision
        owner.suspendForGesture()
        assertFalse(owner.wantsPlayback)
        assertTrue(owner.currentGesture(generation, transportIntentRevision))
        // An explicit pause while already suspended must still revoke the held gesture.
        owner.pause()
        assertFalse(owner.currentGesture(generation, transportIntentRevision))
        assertFalse(owner.wantsPlayback)
        assertFalse(owner.seeks.resume)
    }

    @Test fun externalPauseBeforeFirstShuttleGrainRejectsLateHandoff() {
        val owner = RecordingPlaybackOwnership()
        val generation = owner.replace()
        val transportIntentRevision = owner.transportIntentRevision
        owner.pause()
        assertFalse(owner.currentGesture(generation, transportIntentRevision))
        // A later explicit Play does not restore an old gesture's resume authority.
        owner.resume()
        assertFalse(owner.currentGesture(generation, transportIntentRevision))
    }

    @Test fun replacementCannotAcceptTheOldGestureEvenAtTheSamePauseRevision() {
        val owner = RecordingPlaybackOwnership()
        val generation = owner.replace()
        val transportIntentRevision = owner.transportIntentRevision
        owner.replace()
        assertFalse(owner.currentGesture(generation, transportIntentRevision))
    }

    // These cover transport intent through the seek queue; MediaSession callback binding needs integration coverage.
    @Test fun seekDuringPreparationAndQueuedSeekKeepAcceptedPlayIntent() {
        val owner = RecordingPlaybackOwnership()
        owner.replace()
        assertEquals(100, owner.seeks.request(100, owner.wantsPlayback))
        assertNull(owner.seeks.request(900, owner.wantsPlayback))
        assertEquals(900, owner.seeks.completed())
        assertNull(owner.seeks.completed())
        assertTrue(owner.seeks.resume)
        assertTrue(owner.wantsPlayback)
    }

    @Test fun seekAfterPauseCannotResumeAnOutstandingSeek() {
        val owner = RecordingPlaybackOwnership()
        owner.replace()
        owner.seeks.request(100, owner.wantsPlayback)
        owner.pause()
        assertNull(owner.seeks.request(900, owner.wantsPlayback))
        assertEquals(900, owner.seeks.completed())
        assertNull(owner.seeks.completed())
        assertFalse(owner.seeks.resume)
        assertFalse(owner.wantsPlayback)
    }

    @Test fun acceptedResumeBeforeSeekCompletionRevokesIdleRetirement() {
        val owner = RecordingPlaybackOwnership()
        owner.replace()
        owner.pause()
        assertTrue(owner.idleRetirementAllowed)
        owner.resume()
        owner.seeks.request(700, true)
        assertFalse(owner.idleRetirementAllowed)
        assertTrue(owner.seeks.pending)
    }

    @Test fun retiredCallbacksCannotOwnReplacement() {
        val owner = RecordingPlaybackOwnership()
        val first = owner.replace()
        owner.retire()
        val second = owner.replace()
        assertFalse(owner.current(first))
        assertTrue(owner.current(second))
        assertTrue(owner.wantsPlayback)
    }

    @Test fun pauseRevokesResumeWhileLatestQueuedSeekStillCompletes() {
        val owner = RecordingPlaybackOwnership()
        owner.replace()
        assertEquals(100, owner.seeks.request(100, true))
        assertNull(owner.seeks.request(900, true))
        owner.pause()
        assertEquals(900, owner.seeks.completed())
        assertNull(owner.seeks.completed())
        assertFalse(owner.seeks.resume)
        assertFalse(owner.wantsPlayback)
    }

    @Test fun replacementDropsOldQueuedSeekAndResume() {
        val owner = RecordingPlaybackOwnership()
        val old = owner.replace()
        owner.seeks.request(100, true)
        owner.seeks.request(900, true)
        val current = owner.replace()
        assertFalse(owner.current(old))
        assertTrue(owner.current(current))
        assertFalse(owner.seeks.pending)
        assertFalse(owner.seeks.resume)
        assertNull(owner.seeks.completed())
    }

    @Test fun resumeAfterTerminalCannotResurrectOwner() {
        val owner = RecordingPlaybackOwnership()
        val old = owner.replace()
        owner.retire()
        owner.resume()
        assertFalse(owner.current(old))
        assertFalse(owner.wantsPlayback)
        assertFalse(owner.active)
    }
}
