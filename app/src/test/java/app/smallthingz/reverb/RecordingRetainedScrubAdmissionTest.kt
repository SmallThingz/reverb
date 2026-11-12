package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class RecordingRetainedScrubAdmissionTest {
    private val retained = RecordingPlaybackState(prepared = false, playing = false,
        durationMillis = 2000, generation = 7, transportIntentRevision = 3)

    @Test fun retainedPausedCursorHasIndependentNonResumingAuthority() {
        val key = checkNotNull(recordingScrubAdmission(retained, true, false, false))
        assertEquals(RecordingScrubKey(7, 3, retainedIdle = true), key)
        val registry = RecordingScrubAuthorities()
        val lease = registry.acquire(key)
        assertTrue(registry.owns(lease))
        assertTrue(lease.key.retainedIdle)
    }

    @Test fun pendingStartOrLivePreparingOwnerCannotGrantRetainedAuthority() {
        assertNull(recordingScrubAdmission(retained, true, false, true))
        assertNull(recordingScrubAdmission(retained, true, true, false))
        assertNull(recordingScrubAdmission(retained.copy(wantsPlayback = true), true, false, false))
        assertNull(recordingScrubAdmission(retained.copy(playing = true), true, false, false))
    }

    @Test fun ReplacementMissingDurationOrOwnerlessPreparedStateCannotGrantAuthority() {
        assertNull(recordingScrubAdmission(retained, false, false, false))
        assertNull(recordingScrubAdmission(retained.copy(durationMillis = 0), true, false, false))
        assertNull(recordingScrubAdmission(retained.copy(prepared = true), true, false, false))
    }

    @Test fun preparedServiceRemainsDistinctAndIdleTransitionRevokesItsGesture() {
        val key = checkNotNull(recordingScrubAdmission(retained.copy(prepared = true), true, true, false))
        assertFalse(key.retainedIdle)
        val registry = RecordingScrubAuthorities()
        val old = registry.acquire(key)
        registry.published(null) // Service release publishes unprepared before retained admission.
        assertFalse(old.active)
        val replacement = registry.acquire(checkNotNull(recordingScrubAdmission(retained, true, false, false)))
        registry.finish(old)
        assertTrue(registry.owns(replacement))
        assertNotEquals(old.key, replacement.key)
    }
    @Test fun oldServiceCleanupPreservesIndependentRetainedLeaseButIntentRevokesIt() {
        val registry = RecordingScrubAuthorities()
        val serviceLease = registry.acquire(RecordingScrubKey(7, 3))
        registry.retireServiceOwner()
        assertFalse(serviceLease.active)
        val retainedLease = registry.acquire(RecordingScrubKey(7, 3, retainedIdle = true))
        registry.retireServiceOwner()
        assertTrue(registry.owns(retainedLease))
        registry.revoke()
        assertFalse(retainedLease.active)
    }

    @Test fun compatibleHandoffKeepsCapturedResumeButNewPausedGestureDoesNotInheritIt() {
        val registry = RecordingScrubAuthorities()
        val playingKey = RecordingScrubKey(7, 3)
        val playing = registry.acquire(playingKey, resumePlayback = true)
        registry.published(playingKey) // Suspension for this gesture keeps its transport revision.
        assertTrue(playing.resumePlayback)
        val pausedKey = playingKey.copy(transportIntentRevision = 4)
        registry.published(pausedKey)
        val paused = registry.acquire(pausedKey, resumePlayback = false)
        assertFalse(playing.active)
        assertFalse(paused.resumePlayback)
        assertTrue(registry.owns(paused))
    }

}
