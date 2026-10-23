package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.Velocity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Source-only ownership tests for later coordinated compile; these have not run. */
class NativePagerDragChannelTest {
    @Test
    fun queuedDeltasCompleteUnderOneDragBeforeNormalSettling() = runBlocking {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val pager = NativeOnboardingPager(owner, Density(1f)).also { it.updateLayout(1000) }
        val terminal = CompletableDeferred<Velocity>()
        val interactions = mutableListOf<Boolean>()
        val channel = NativePagerDragChannel(
            pager, owner,
            deliverDelta = { scrollBy(pager.physicalToLogicalDelta(it.x)) },
            settle = { assertFalse(pager.isScrollInProgress); terminal.complete(it) },
            interactionStopped = { interactions += it },
        )
        try {
            channel.start(Offset.Zero)
            channel.delta(Offset(-20f, 0f))
            channel.delta(Offset(-30f, 0f))
            channel.stop(Velocity(-400f, 20f))
            assertEquals(Velocity(-400f, 20f), withTimeout(1000) { terminal.await() })
            assertEquals(50L, pager.layoutAbsoluteOffsetPx)
            assertEquals(listOf(false), interactions)
        } finally {
            channel.close()
            owner.cancel()
        }
    }

    @Test
    fun cancellationKeepsAppliedDragButSettlesWithZeroVelocityAfterReleasingOwnership() = runBlocking {
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val pager = NativeOnboardingPager(owner, Density(1f)).also { it.updateLayout(1000) }
        val terminal = CompletableDeferred<Velocity>()
        val interactions = mutableListOf<Boolean>()
        val channel = NativePagerDragChannel(
            pager, owner,
            deliverDelta = { scrollBy(pager.physicalToLogicalDelta(it.x)) },
            settle = { assertFalse(pager.isScrollInProgress); terminal.complete(it) },
            interactionStopped = { interactions += it },
        )
        try {
            channel.start(Offset.Zero)
            channel.delta(Offset(-20f, 0f))
            channel.cancel()
            assertEquals(Velocity.Zero, withTimeout(1000) { terminal.await() })
            assertEquals(20L, pager.layoutAbsoluteOffsetPx)
            assertEquals(listOf(true), interactions)
        } finally {
            channel.close()
            owner.cancel()
        }
    }
}
