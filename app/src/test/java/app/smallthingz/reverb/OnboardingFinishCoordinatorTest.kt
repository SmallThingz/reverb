package app.smallthingz.reverb

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFinishCoordinatorTest {
    @Test
    fun replacementOwnerReceivesAcceptedSuccessWithoutAnotherSubmission() = runBlocking {
        withTimeout(5_000) {
            val coordinator = OnboardingFinishCoordinator()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = AtomicInteger()
            var retiredOwnerNavigations = 0
            val retiredOwner = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.state.collect { state ->
                    if (state == OnboardingFinishState.SUCCEEDED) retiredOwnerNavigations++
                }
            }
            assertTrue(coordinator.submit(
                block = {
                    writes.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    true
                },
                onFailure = { throw AssertionError("Successful Finish reported failure") },
            ))
            started.await()
            retiredOwner.cancelAndJoin()

            assertTrue(onboardingPendingForUi(coordinator.state.value) { true })
            val replacementOwner = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.state.first { it == OnboardingFinishState.SUCCEEDED }
            }
            release.complete(Unit)
            assertEquals(OnboardingFinishState.SUCCEEDED, replacementOwner.await())
            assertEquals(0, retiredOwnerNavigations)
            assertEquals(1, writes.get())
            // A later resumed owner must receive completion even after the original result delivery.
            assertEquals(OnboardingFinishState.SUCCEEDED, coordinator.state.first())
            assertFalse(onboardingPendingForUi(coordinator.state.value) { true })
        }
    }

    @Test
    fun runningAndSuccessfulAttemptRejectRepeatedFinish() = runBlocking {
        withTimeout(5_000) {
            val coordinator = OnboardingFinishCoordinator()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = AtomicInteger()
            assertTrue(coordinator.submit(
                block = {
                    writes.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    true
                },
                onFailure = {},
            ))
            started.await()
            assertFalse(coordinator.submit(block = { writes.incrementAndGet(); true }, onFailure = {}))
            release.complete(Unit)
            coordinator.state.first { it == OnboardingFinishState.SUCCEEDED }
            assertFalse(coordinator.submit(block = { writes.incrementAndGet(); true }, onFailure = {}))
            assertEquals(1, writes.get())
        }
    }

    @Test
    fun failureWithoutAnAttachedOwnerReportsOnceAndAllowsRetry() = runBlocking {
        withTimeout(5_000) {
            val coordinator = OnboardingFinishCoordinator()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failures = AtomicInteger()
            assertTrue(coordinator.submit(
                block = {
                    started.complete(Unit)
                    release.await()
                    throw IOException("Onboarding storage unavailable")
                },
                onFailure = { failures.incrementAndGet() },
            ))
            started.await()
            assertEquals(OnboardingFinishState.RUNNING, coordinator.state.value)
            release.complete(Unit)
            coordinator.state.first { it == OnboardingFinishState.IDLE }
            assertEquals(1, failures.get())
            assertTrue(onboardingPendingForUi(coordinator.state.value) { true })

            assertTrue(coordinator.submit(block = { true }, onFailure = { failures.incrementAndGet() }))
            coordinator.state.first { it == OnboardingFinishState.SUCCEEDED }
            assertEquals(1, failures.get())
        }
    }

    @Test
    fun synchronousFailureCannotLeaveTheUiBusyAfterStateFlowConflation() {
        var failures = 0
        val coordinator = OnboardingFinishCoordinator { _, terminal -> terminal(false) }

        assertTrue(coordinator.submit(block = { false }, onFailure = { failures++ }))
        assertEquals(OnboardingFinishState.IDLE, coordinator.state.value)
        assertTrue(coordinator.submit(block = { false }, onFailure = { failures++ }))
        assertEquals(OnboardingFinishState.IDLE, coordinator.state.value)
        assertEquals(2, failures)
    }

    @Test
    fun synchronousSubmissionFailureReturnsToIdleAndKeepsLateCallbacksRetired() {
        var submissionCount = 0
        var firstTerminal: ((Boolean) -> Unit)? = null
        var secondTerminal: ((Boolean) -> Unit)? = null
        var failures = 0
        val coordinator = OnboardingFinishCoordinator { _, terminal ->
            submissionCount++
            if (submissionCount == 1) {
                firstTerminal = terminal
                throw IOException("Finish submission failed")
            }
            secondTerminal = terminal
        }

        assertFalse(coordinator.submit(block = { true }, onFailure = { failures++ }))
        assertEquals(OnboardingFinishState.IDLE, coordinator.state.value)
        assertEquals(1, failures)
        assertTrue(coordinator.submit(block = { true }, onFailure = { failures++ }))
        requireNotNull(firstTerminal).invoke(true)
        assertEquals(OnboardingFinishState.RUNNING, coordinator.state.value)
        requireNotNull(secondTerminal).invoke(true)
        assertEquals(OnboardingFinishState.SUCCEEDED, coordinator.state.value)
        assertEquals(1, failures)
    }

    @Test
    fun activeCommitAndDurableSuccessDoNotReadAnOptimisticPreferenceMap() {
        val readWhileOwned = { throw AssertionError("Owned Finish consulted pending preferences") }
        assertTrue(onboardingPendingForUi(OnboardingFinishState.RUNNING, readWhileOwned))
        assertFalse(onboardingPendingForUi(OnboardingFinishState.SUCCEEDED, readWhileOwned))
        assertTrue(onboardingPendingForUi(OnboardingFinishState.IDLE) { true })
        assertFalse(onboardingPendingForUi(OnboardingFinishState.IDLE) { false })
    }
}
