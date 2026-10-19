package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDeliveryTest {
    private val error = FeedbackEvent(1L, "Delete failed", FeedbackTone.ERROR)

    @Test
    fun obscuredHostLeavesHeadPendingWithoutDisplayingOrStartingTimer() = runBlocking {
        var pending = listOf(error)
        deliverFeedbackEvent(
            event = error,
            canDisplay = { false },
            display = { throw AssertionError("Obscured host displayed feedback") },
            awaitVisibleFrame = { throw AssertionError("Obscured host awaited rendering") },
            acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
            waitForDuration = { throw AssertionError("Obscured host started its timer") },
        )
        assertEquals(listOf(error), pending)
    }

    @Test
    fun focusLossBeforeFirstFrameDoesNotConsumeFeedback() = runBlocking {
        var allowed = true
        var displayed: FeedbackEvent? = null
        var pending = listOf(error)
        deliverFeedbackEvent(
            event = error,
            canDisplay = { allowed },
            display = { displayed = it },
            awaitVisibleFrame = { allowed = false },
            acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
            waitForDuration = { throw AssertionError("Hidden feedback started its timer") },
        )
        assertNull(displayed)
        assertEquals(listOf(error), pending)
    }

    @Test
    fun visibilityIsRecheckedAtAcknowledgementBoundary() = runBlocking {
        var allowed = true
        var pending = listOf(error)
        deliverFeedbackEvent(
            event = error,
            canDisplay = { allowed },
            display = {},
            awaitVisibleFrame = {},
            acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
            waitForDuration = { allowed = false },
        )
        assertEquals(listOf(error), pending)
    }

    @Test
    fun undoOrLifecycleCancellationPreservesHeadAndRestartsItsFullDuration() = runBlocking {
        var displayed: FeedbackEvent? = null
        var pending = listOf(error)
        val enteredTimer = CompletableDeferred<Long>()
        val incompleteTimer = CompletableDeferred<Unit>()
        val delivery = launch(start = CoroutineStart.UNDISPATCHED) {
            deliverFeedbackEvent(
                event = error,
                canDisplay = { true },
                display = { displayed = it },
                awaitVisibleFrame = {},
                acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
                waitForDuration = {
                    enteredTimer.complete(it)
                    incompleteTimer.await()
                },
            )
        }
        assertEquals(4_500L, enteredTimer.await())
        assertEquals(error, displayed)
        delivery.cancelAndJoin()
        assertNull(displayed)
        assertEquals(listOf(error), pending)

        var resumedDuration = 0L
        deliverFeedbackEvent(
            event = pending.first(),
            canDisplay = { true },
            display = { displayed = it },
            awaitVisibleFrame = {},
            acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
            waitForDuration = { resumedDuration = it },
        )
        assertEquals(4_500L, resumedDuration)
        assertTrue(pending.isEmpty())
        assertNull(displayed)
    }

    @Test
    fun cancellationWhileWaitingForRenderingDoesNotAcknowledge() = runBlocking {
        var pending = listOf(error)
        var displayed: FeedbackEvent? = null
        val frame = CompletableDeferred<Unit>()
        val delivery = launch(start = CoroutineStart.UNDISPATCHED) {
            deliverFeedbackEvent(
                event = error,
                canDisplay = { true },
                display = { displayed = it },
                awaitVisibleFrame = { frame.await() },
                acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
                waitForDuration = { throw AssertionError("No frame was displayed") },
            )
        }
        assertEquals(error, displayed)
        delivery.cancelAndJoin()
        assertNull(displayed)
        assertEquals(listOf(error), pending)
    }

    @Test
    fun visibleDeliveryWaitsForRenderingAndFullDurationBeforeAcknowledgingOnlyItsHead() = runBlocking {
        val later = FeedbackEvent(2L, "Saved", FeedbackTone.SUCCESS)
        var pending = listOf(error, later)
        val steps = mutableListOf<String>()
        deliverFeedbackEvent(
            event = error,
            canDisplay = { true },
            display = { steps += if (it == null) "hidden" else "visible" },
            awaitVisibleFrame = { steps += "rendered" },
            acknowledge = {
                steps += "acknowledged"
                pending = acknowledgeFeedbackEvent(pending, it)
            },
            waitForDuration = {
                assertEquals(listOf(error, later), pending)
                steps += "waited:$it"
            },
        )
        assertEquals(listOf("visible", "rendered", "waited:4500", "acknowledged", "hidden"), steps)
        assertEquals(listOf(later), pending)
    }

    @Test
    fun ordinaryFeedbackUsesItsExistingShorterVisibleDuration() = runBlocking {
        val notice = FeedbackEvent(1L, "Saved", FeedbackTone.SUCCESS)
        var pending = listOf(notice)
        var duration = 0L
        deliverFeedbackEvent(
            event = notice,
            canDisplay = { true },
            display = {},
            awaitVisibleFrame = {},
            acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
            waitForDuration = { duration = it },
        )
        assertEquals(2_800L, duration)
        assertTrue(pending.isEmpty())
    }
}
