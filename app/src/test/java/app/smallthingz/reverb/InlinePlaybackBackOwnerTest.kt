package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InlinePlaybackBackOwnerTest {
    private class Host(owner: InlinePlaybackRowOwner) {
        var expanded: InlinePlaybackRowOwner? = owner
        var trimMode = false
        var trimSaving = false
        val events = mutableListOf<String>()

        fun completeBack(admittedOwner: InlinePlaybackRowOwner?): Boolean = finishInlineBackForCurrentRow(
            owner = admittedOwner,
            isCurrentOwner = { expanded === it },
            trimSaving = trimSaving,
            trimMode = trimMode,
            leaveTrim = {
                trimMode = false
                events += "leave trim"
            },
            collapse = {
                expanded = null
                events += "collapse"
            },
        )
    }

    @Test
    fun currentRowBack_settlesBeforeCollapsing() = runBlocking {
        val owner = InlinePlaybackRowOwner("a")
        val host = Host(owner)
        val settled = CompletableDeferred<Unit>()
        var duration = -1
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0.4f,
                animateToEnd = {
                    duration = it
                    host.events += "settling"
                    settled.await()
                },
                onBack = { host.completeBack(owner) },
            )
        }

        assertSame(owner, host.expanded)
        assertEquals(listOf("settling"), host.events)
        assertEquals(132, duration)
        settled.complete(Unit)
        back.join()

        assertNull(host.expanded)
        assertEquals(listOf("settling", "collapse"), host.events)
    }

    @Test
    fun replacementDuringSettle_oldBackCannotCollapseFreshPlayback() = runBlocking {
        val outgoing = InlinePlaybackRowOwner("a")
        val host = Host(outgoing)
        val settled = CompletableDeferred<Unit>()
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0f,
                animateToEnd = { settled.await() },
                onBack = { host.completeBack(outgoing) },
            )
        }

        val incoming = InlinePlaybackRowOwner("b")
        host.expanded = incoming
        assertTrue(startInlinePlaybackForCurrentRow(incoming, { host.expanded === it }) {
            host.events += "fresh play"
        })
        settled.complete(Unit)
        back.join()

        assertSame(incoming, host.expanded)
        assertEquals(listOf("fresh play"), host.events)
        assertTrue(host.completeBack(incoming))
        assertEquals(listOf("fresh play", "collapse"), host.events)
    }

    @Test
    fun sameRecordingReopenedDuringSettle_cannotLendItsOwnerToOldBack() = runBlocking {
        val outgoing = InlinePlaybackRowOwner("a")
        val host = Host(outgoing)
        val settled = CompletableDeferred<Unit>()
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0.5f,
                animateToEnd = { settled.await() },
                onBack = { host.completeBack(outgoing) },
            )
        }

        host.expanded = InlinePlaybackRowOwner("b")
        val reopened = InlinePlaybackRowOwner("a")
        host.expanded = reopened
        settled.complete(Unit)
        back.join()

        assertSame(reopened, host.expanded)
        assertEquals(emptyList<String>(), host.events)
        assertTrue(host.completeBack(reopened))
        assertEquals(listOf("collapse"), host.events)
    }

    @Test
    fun retiredTrimBack_cannotExitTrimInRetainedReopenedRow() = runBlocking {
        val outgoing = InlinePlaybackRowOwner("a")
        val host = Host(outgoing).apply { trimMode = true }
        val settled = CompletableDeferred<Unit>()
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0.5f,
                animateToEnd = { settled.await() },
                onBack = { host.completeBack(outgoing) },
            )
        }

        host.expanded = null
        val reopened = InlinePlaybackRowOwner("a")
        host.expanded = reopened
        settled.complete(Unit)
        back.join()

        assertSame(reopened, host.expanded)
        assertTrue(host.trimMode)
        assertEquals(emptyList<String>(), host.events)
        assertTrue(host.completeBack(reopened))
        assertFalse(host.trimMode)
        assertSame(reopened, host.expanded)
        assertEquals(listOf("leave trim"), host.events)
    }

    @Test
    fun collapseBeforeRecomposition_rejectsAlreadyCapturedTerminal() {
        val owner = InlinePlaybackRowOwner("a")
        val host = Host(owner)
        val oldTerminal = { host.completeBack(owner) }

        host.expanded = null

        assertFalse(oldTerminal())
        assertFalse(host.completeBack(null))
        assertEquals(emptyList<String>(), host.events)
    }

    @Test
    fun acceptedTrimSaveDuringSettle_remainsLockedAtTerminal() = runBlocking {
        val owner = InlinePlaybackRowOwner("a")
        val host = Host(owner).apply { trimMode = true }
        val settled = CompletableDeferred<Unit>()
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0.5f,
                animateToEnd = { settled.await() },
                onBack = { host.completeBack(owner) },
            )
        }

        host.trimSaving = true
        settled.complete(Unit)
        back.join()

        assertSame(owner, host.expanded)
        assertTrue(host.trimMode)
        assertTrue(host.trimSaving)
        assertEquals(emptyList<String>(), host.events)
    }

    @Test
    fun cancelledContinuation_cannotApplyBackToReplacement() = runBlocking {
        val outgoing = InlinePlaybackRowOwner("a")
        val host = Host(outgoing)
        val settled = CompletableDeferred<Unit>()
        val back = launch(start = CoroutineStart.UNDISPATCHED) {
            continuePredictiveBackCommit(
                currentProgress = 0.5f,
                animateToEnd = { settled.await() },
                onBack = { host.completeBack(outgoing) },
            )
        }

        val incoming = InlinePlaybackRowOwner("b")
        host.expanded = incoming
        back.cancelAndJoin()
        settled.complete(Unit)

        assertSame(incoming, host.expanded)
        assertEquals(emptyList<String>(), host.events)
    }
}
