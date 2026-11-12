package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTileDurationPersistenceTest {
    private fun known(oneShot: Float, looping: Float) = RecordingTileSnapshot(
        listening = false, activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
        oneShotEnabled = true, oneShotFull = false, loopingEnabled = true,
        oneShotSeconds = oneShot, loopingSeconds = looping, durationCountersKnown = true,
    )

    @Test fun coldStoppedPlaceholderUsesPersistedDurations() {
        val persisted = known(1396.45f, 8f)
        val cold = failClosedRecordingTileSnapshot()
        assertFalse(cold.durationCountersKnown)
        assertEquals(persisted, stoppedRecordingTileSnapshot(persisted, cold))
    }

    @Test fun readFailureFallbackRetainsTrustOnlyWithItsRetainedValues() {
        val live = known(22f, 33f)
        val fallback = runtimeRecordingTileFallbackSnapshot(live, null, false, false)
        assertTrue(fallback.durationCountersKnown)
        assertEquals(22f, fallback.oneShotSeconds, 0f)
        assertEquals(33f, fallback.loopingSeconds, 0f)
        val stopped = stoppedRecordingTileSnapshot(known(1f, 2f), failClosedRecordingTileSnapshot(fallback))
        assertEquals(22f, stopped.oneShotSeconds, 0f)
        assertEquals(33f, stopped.loopingSeconds, 0f)
    }

    @Test fun freshFailureZeroCannotBorrowTrustFromPreviouslyKnownCounters() {
        val persisted = known(22f, 33f)
        val freshFailure = runtimeRecordingTileFallbackSnapshot(null, null, false, false)
        assertFalse(freshFailure.durationCountersKnown)
        assertEquals(persisted, stoppedRecordingTileSnapshot(persisted, freshFailure))
    }

    @Test fun actualClearOfKnownBufferKeepsZeroAndOtherLatestCounter() {
        val cleared = recordingTileSnapshotAfterClear(known(22f, 33f), ReverbService.BufferSlot.ONE_SHOT)
        assertTrue(cleared.durationCountersKnown)
        val stopped = stoppedRecordingTileSnapshot(known(22f, 2f), failClosedRecordingTileSnapshot(cleared))
        assertEquals(0f, stopped.oneShotSeconds, 0f)
        assertEquals(33f, stopped.loopingSeconds, 0f)
    }

    @Test fun unknownClearCannotAuthorizeOtherPlaceholderCounter() {
        val cleared = recordingTileSnapshotAfterClear(failClosedRecordingTileSnapshot(), ReverbService.BufferSlot.ONE_SHOT)
        assertFalse(cleared.durationCountersKnown)
        // The explicit Clear persists only its own zero before stopped hydration.
        val stopped = stoppedRecordingTileSnapshot(known(0f, 33f), cleared)
        assertEquals(0f, stopped.oneShotSeconds, 0f)
        assertEquals(33f, stopped.loopingSeconds, 0f)
    }

    @Test fun successfulEmptyStoreObservationIsAuthoritativeZero() {
        val stopped = stoppedRecordingTileSnapshot(known(22f, 33f), failClosedRecordingTileSnapshot(known(0f, 0f)))
        assertTrue(stopped.durationCountersKnown)
        assertEquals(0f, stopped.oneShotSeconds, 0f)
        assertEquals(0f, stopped.loopingSeconds, 0f)
    }

    @Test fun unrelatedRuntimePublicationDoesNotInvalidateStoreSample() {
        val owner = Any()
        RecordingQuickTileStateCache.beginRuntime(owner)
        val oldGeneration = RecordingQuickTileStateCache.observationGeneration()
        assertTrue(RecordingQuickTileStateCache.publish(owner, known(1f, 2f), oldGeneration))
        val newer = known(22f, 33f)
        assertTrue(RecordingQuickTileStateCache.publish(owner, newer, oldGeneration, durationsObserved = true))
        assertEquals(newer, RecordingQuickTileStateCache.readNonBlocking())
    }
}
