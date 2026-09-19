package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionMaintenanceSchedulerTest {
    @Test
    fun staleNoWorkObservationCannotRevokeQueuedPass() {
        val state = RetentionMaintenanceSchedulerState()

        val passId = requireNotNull(state.claimObservedNeed(needed = true))
        assertTrue(state.isActive())

        // Another caller may have sampled "no work" before the claimed pass mutates storage.
        // That stale observation must not revoke the already-owned pass.
        assertTrue(state.claimObservedNeed(needed = false) == null)
        assertTrue(state.isActive())

        assertTrue(state.completePass(passId, needsMore = false))
        assertFalse(state.isActive())
    }

    @Test
    fun staleNoWorkObservationCannotRevokeKnownBacklogBetweenPasses() {
        val state = RetentionMaintenanceSchedulerState()

        val firstPassId = requireNotNull(state.claimObservedNeed(needed = true))
        assertTrue(state.completePass(firstPassId, needsMore = true))
        assertTrue(state.isActive())

        // This result may have been sampled before the prior pass published known backlog.
        assertTrue(state.claimObservedNeed(needed = false) == null)
        assertTrue(state.isActive())

        val retryPassId = requireNotNull(state.claimActiveRetry())
        assertTrue(state.isActive())
        assertTrue(state.completePass(retryPassId, needsMore = false))
        assertFalse(state.isActive())
    }

    @Test
    fun blockedBacklogCanBeReclaimedExactlyOnce() {
        val state = RetentionMaintenanceSchedulerState()

        val firstPassId = requireNotNull(state.claimObservedNeed(needed = true))
        assertTrue(state.completePass(firstPassId, needsMore = true))
        assertTrue(state.isActive())

        val retryPassId = requireNotNull(state.claimActiveRetry())
        assertTrue(state.claimActiveRetry() == null)
        assertTrue(state.isActive())

        assertTrue(state.completePass(retryPassId, needsMore = false))
        assertFalse(state.isActive())
    }

    @Test
    fun clearRevokesActiveAndQueuedOwnership() {
        val state = RetentionMaintenanceSchedulerState()

        val passId = requireNotNull(state.claimObservedNeed(needed = true))
        state.clear()

        assertFalse(state.isActive())
        assertTrue(state.claimActiveRetry() == null)
        assertFalse(state.completePass(passId, needsMore = true))
        assertFalse(state.isActive())
    }

    @Test
    fun staleCompletionCannotMutateNewerClaim() {
        val state = RetentionMaintenanceSchedulerState()

        val stalePassId = requireNotNull(state.claimObservedNeed(needed = true))
        state.clear()
        val newerPassId = requireNotNull(state.claimObservedNeed(needed = true))

        assertFalse(state.completePass(stalePassId, needsMore = false))
        assertTrue(state.isActive())
        assertTrue(state.claimActiveRetry() == null)

        assertTrue(state.completePass(newerPassId, needsMore = false))
        assertFalse(state.isActive())
    }
}
