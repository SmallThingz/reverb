package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingOpenDeliveryTest {
    private class Host {
        var resumed = false
        var focused = false
        val events = mutableListOf<String>()
        val delivery = RecordingOpenResultDelivery<String>(
            navigationAllowed = { resumed && focused },
            deliver = { events += if (it == null) "visible failure" else "open $it" },
            reportUndeliveredFailure = { events += "queued failure" },
            finish = { events += "finish" },
        )

        fun resume() {
            resumed = true
            delivery.foregroundChanged()
        }

        fun focus() {
            focused = true
            delivery.foregroundChanged()
        }

        fun pause() {
            resumed = false
            focused = false
            delivery.retire()
        }
    }

    @Test
    fun notificationVerificationBeforeResume_waitsForInitialFocusedHandoff() {
        val host = Host()
        host.delivery.complete("notification")
        assertEquals(emptyList<String>(), host.events)
        host.resume()
        assertEquals(emptyList<String>(), host.events)
        host.focus()
        host.delivery.foregroundChanged()
        host.delivery.complete("duplicate")
        assertEquals(listOf("open notification", "finish"), host.events)
    }

    @Test
    fun popupShareResultBeforeWindowFocus_returnsToItsCurrentForegroundOwner() {
        val host = Host()
        host.resume()
        host.delivery.complete("share")
        assertEquals(emptyList<String>(), host.events)
        host.focus()
        assertEquals(listOf("open share", "finish"), host.events)
    }

    @Test
    fun focusBeforeResume_doesNotNavigateUntilBothInitialSignalsArrive() {
        val host = Host()
        host.focus()
        host.delivery.complete("recording")
        assertEquals(emptyList<String>(), host.events)
        host.resume()
        assertEquals(listOf("open recording", "finish"), host.events)
    }

    @Test
    fun pauseDuringVerification_cannotReviveAfterUserReturns() = runBlocking {
        val host = Host()
        host.resume()
        host.focus()
        val verification = CompletableDeferred<String?>()
        val worker = launch { host.delivery.complete(verification.await()) }
        host.pause()
        host.resume()
        host.focus()
        verification.complete("old request")
        worker.join()
        assertEquals(listOf("finish"), host.events)
    }

    @Test
    fun pauseDiscardsAlreadyVerifiedPayloadWaitingForPopupFocus() {
        val host = Host()
        host.resume()
        host.delivery.complete("share")
        host.pause()
        host.resume()
        host.focus()
        assertEquals(listOf("finish"), host.events)
    }

    @Test
    fun genuineVerificationFailureAfterRetirement_queuesFeedbackOnceWithoutNavigation() {
        val host = Host()
        host.resume()
        host.focus()
        host.pause()
        host.delivery.complete(null)
        host.delivery.complete(null)
        host.resume()
        host.focus()
        assertEquals(listOf("queued failure", "finish"), host.events)
    }

    @Test
    fun pendingFailureIsPreservedWhenTheWindowRetiresBeforeFocus() {
        val host = Host()
        host.resume()
        host.delivery.complete(null)
        host.pause()
        assertEquals(listOf("queued failure", "finish"), host.events)
    }

    @Test
    fun replacedShareCannotOpenWhileItsNewRequestStillCan() {
        val first = Host()
        first.resume()
        first.delivery.complete("old share")
        first.delivery.retire()
        val second = Host()
        second.resume()
        second.focus()
        second.delivery.complete("new share")
        first.focus()
        assertEquals(listOf("finish"), first.events)
        assertEquals(listOf("open new share", "finish"), second.events)
    }

    @Test
    fun currentVerificationFailureReachesVisibleDeliveryAndFinishes() {
        val host = Host()
        host.resume()
        host.focus()
        host.delivery.complete(null)
        assertEquals(listOf("visible failure", "finish"), host.events)
    }

    @Test
    fun throwingTerminalCallbackStillFinishesAndCannotReplay() {
        val events = mutableListOf<String>()
        val expected = IllegalStateException("delivery failed")
        val delivery = RecordingOpenResultDelivery<String>(
            navigationAllowed = { true },
            deliver = { events += "deliver"; throw expected },
            reportUndeliveredFailure = { events += "unexpected feedback" },
            finish = { events += "finish" },
        )
        val thrown = assertThrows(IllegalStateException::class.java) { delivery.complete("recording") }
        assertSame(expected, thrown)
        delivery.foregroundChanged()
        delivery.retire()
        delivery.complete("duplicate")
        assertEquals(listOf("deliver", "finish"), events)
    }
}
