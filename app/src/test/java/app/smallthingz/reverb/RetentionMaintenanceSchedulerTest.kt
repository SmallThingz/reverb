package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionMaintenanceSchedulerTest {
    @Test
    fun staleNoWorkObservationCannotRevokeQueuedPass() {
        val state = RetentionMaintenanceSchedulerState()

        assertTrue(state.claimObservedNeed(needed = true))
        assertTrue(state.isActive())

        // Another caller may have sampled "no work" before the claimed pass mutates storage.
        // That stale observation must not revoke the already-owned pass.
        assertFalse(state.claimObservedNeed(needed = false))
        assertTrue(state.isActive())

        state.completePass(needsMore = false)
        assertFalse(state.isActive())
    }

    @Test
    fun staleNoWorkObservationCannotRevokeKnownBacklogBetweenPasses() {
        val state = RetentionMaintenanceSchedulerState()

        assertTrue(state.claimObservedNeed(needed = true))
        state.completePass(needsMore = true)
        assertTrue(state.isActive())

        // This result may have been sampled before completePass(true) published known backlog.
        assertFalse(state.claimObservedNeed(needed = false))
        assertTrue(state.isActive())

        assertTrue(state.claimActiveRetry())
        assertTrue(state.isActive())
        state.completePass(needsMore = false)
        assertFalse(state.isActive())
    }

    @Test
    fun blockedBacklogCanBeReclaimedExactlyOnce() {
        val state = RetentionMaintenanceSchedulerState()

        assertTrue(state.claimObservedNeed(needed = true))
        state.completePass(needsMore = true)
        assertTrue(state.isActive())

        assertTrue(state.claimActiveRetry())
        assertFalse(state.claimActiveRetry())
        assertTrue(state.isActive())

        state.completePass(needsMore = false)
        assertFalse(state.isActive())
    }

    @Test
    fun clearRevokesActiveAndQueuedOwnership() {
        val state = RetentionMaintenanceSchedulerState()

        assertTrue(state.claimObservedNeed(needed = true))
        state.clear()
        assertFalse(state.isActive())
        assertFalse(state.claimActiveRetry())
    }
}
