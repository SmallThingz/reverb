package app.smallthingz.reverb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartAccessInvitationTest {
    @Test fun existingInstallWithoutGrantGetsOneOptionalVisibleMainOffer() {
        val state = RestartAccessInvitationState()
        assertFalse(state.readHandled { false })
        assertFalse(state.shouldShow(false, true, false))
        assertFalse(state.shouldShow(true, false, false))
        assertTrue(state.shouldShow(true, true, false))
        assertTrue(state.acceptDecision())
        assertFalse(state.acceptDecision())
        assertFalse(state.shouldShow(true, true, false))
    }

    @Test fun firstPageOfferPreventsAnotherInvitationAfterDenialAndOnNextProcess() {
        val firstPage = RestartAccessInvitationState()
        assertTrue(firstPage.acceptDecision())
        assertFalse(firstPage.shouldShow(true, true, false))
        val nextProcess = RestartAccessInvitationState()
        assertTrue(nextProcess.readHandled { true })
        assertFalse(nextProcess.shouldShow(true, true, false))
    }

    @Test fun recreationDuringAcceptedWriteDoesNotReadStaleDiskAndReoffer() {
        val process = RestartAccessInvitationState()
        process.readHandled { false }
        assertTrue(process.acceptDecision())
        assertTrue(process.readHandled { error("A new owner must observe the synchronous accepted reservation") })
        assertFalse(process.shouldShow(true, true, false))
    }

    @Test fun decisionRacingAnOlderPreferenceReadWinsBeforePublication() {
        val state = RestartAccessInvitationState()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val reader = Thread {
            try {
                result.set(state.readHandled {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    false
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        reader.start()
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertTrue(state.acceptDecision())
        } finally { release.countDown(); reader.join(5000) }
        check(!reader.isAlive)
        failure.get()?.let { throw it }
        assertTrue(result.get())
        assertFalse(state.shouldShow(true, true, false))
    }

    @Test fun actualGrantSuppressesTheInvitationWithoutPretendingItWasDismissed() {
        val state = RestartAccessInvitationState()
        state.readHandled { false }
        assertFalse(state.shouldShow(true, true, true))
        assertFalse(state.handledInProcess)
        assertTrue(state.shouldShow(true, true, false))
    }

    @Test fun failedDurableWriteRetainsSessionReservationButNotFalseCrossProcessProof() {
        val current = RestartAccessInvitationState()
        assertTrue(current.acceptDecision())
        assertFalse(current.shouldShow(true, true, false))
        // The UI reports the real write failure; a later process reads the actual uncommitted flag.
        val nextProcess = RestartAccessInvitationState()
        assertFalse(nextProcess.readHandled { false })
        assertTrue(nextProcess.shouldShow(true, true, false))
    }
}
