package app.smallthingz.reverb

import androidx.compose.ui.platform.AccessibilityManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackAccessibilityTimeoutTest {
    private val error = FeedbackEvent(1L, "Could not save", FeedbackTone.ERROR)
    private val success = FeedbackEvent(2L, "Saved", FeedbackTone.SUCCESS)

    @Test
    fun extendedTimeoutKeepsTheRenderedHeadUntilTheRecommendedDurationCompletes() = runBlocking {
        withTimeout(5_000L) {
            val manager = RecordingAccessibilityManager { 30_000L }
            var pending = listOf(error, success)
            var displayed: FeedbackEvent? = null
            val enteredTimer = CompletableDeferred<Long>()
            val elapsed = CompletableDeferred<Unit>()
            val delivery = launch(start = CoroutineStart.UNDISPATCHED) {
                deliverFeedbackEvent(
                    event = pending.first(),
                    canDisplay = { true },
                    display = { displayed = it },
                    awaitVisibleFrame = {},
                    acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
                    accessibilityManager = manager,
                    waitForDuration = {
                        enteredTimer.complete(it)
                        elapsed.await()
                    },
                )
            }

            assertEquals(30_000L, enteredTimer.await())
            assertEquals(error, displayed)
            assertEquals(listOf(error, success), pending)
            val request = manager.requests.single()
            assertTrue(request.originalTimeoutMillis < 30_000L)
            assertEquals(false, request.containsIcons)
            assertEquals(true, request.containsText)
            assertEquals(false, request.containsControls)

            elapsed.complete(Unit)
            delivery.join()
            assertEquals(listOf(success), pending)
            assertNull(displayed)
        }
    }

    @Test
    fun missingManagerAndPlatformDefaultPreserveTheCurrentEventToneAndQueueOrder() = runBlocking {
        val events = listOf(error, success, FeedbackEvent(3L, "Ready", FeedbackTone.INFO))
        suspend fun deliverAll(manager: AccessibilityManager?): List<Long> {
            var pending = events
            val durations = mutableListOf<Long>()
            val deliveredIds = mutableListOf<Long>()
            for (expected in events) {
                val next = pending.first()
                assertEquals(expected, next)
                deliverFeedbackEvent(
                    event = next,
                    canDisplay = { true },
                    display = { if (it != null) deliveredIds += it.id },
                    awaitVisibleFrame = {},
                    acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
                    accessibilityManager = manager,
                    waitForDuration = { durations += it },
                )
            }
            assertTrue(pending.isEmpty())
            assertEquals(events.map { it.id }, deliveredIds)
            return durations
        }

        val withoutManager = deliverAll(null)
        val defaultManager = RecordingAccessibilityManager { it.originalTimeoutMillis }
        assertEquals(withoutManager, deliverAll(defaultManager))
        assertEquals(withoutManager, defaultManager.requests.map { it.originalTimeoutMillis })
        assertTrue(withoutManager[0] > withoutManager[1])
        assertEquals(withoutManager[1], withoutManager[2])
    }

    @Test
    fun recommendationIsReadAgainForEachCurrentEvent() = runBlocking {
        val manager = RecordingAccessibilityManager { 30_000L }
        var pending = listOf(error, success)
        val durations = mutableListOf<Long>()
        for (next in listOf(error, success)) {
            deliverFeedbackEvent(
                event = next,
                canDisplay = { true },
                display = {},
                awaitVisibleFrame = {},
                acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
                accessibilityManager = manager,
                waitForDuration = {
                    durations += it
                    manager.recommendation = { 60_000L }
                },
            )
        }

        assertEquals(listOf(30_000L, 60_000L), durations)
        assertTrue(manager.requests[0].originalTimeoutMillis > manager.requests[1].originalTimeoutMillis)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun retiredHostLeavesTheSameEventForAReplacementManagersFullTimeout() = runBlocking {
        withTimeout(5_000L) {
            val oldManager = RecordingAccessibilityManager { 30_000L }
            val replacementManager = RecordingAccessibilityManager { 60_000L }
            var pending = listOf(error, success)
            var displayed: FeedbackEvent? = null
            val enteredTimer = CompletableDeferred<Long>()
            val oldElapsed = CompletableDeferred<Unit>()
            val oldDelivery = launch(start = CoroutineStart.UNDISPATCHED) {
                deliverFeedbackEvent(
                    event = error,
                    canDisplay = { true },
                    display = { displayed = it },
                    awaitVisibleFrame = {},
                    acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
                    accessibilityManager = oldManager,
                    waitForDuration = {
                        enteredTimer.complete(it)
                        oldElapsed.await()
                    },
                )
            }
            assertEquals(30_000L, enteredTimer.await())
            oldDelivery.cancelAndJoin()
            assertNull(displayed)
            assertEquals(listOf(error, success), pending)

            var replacementDuration = 0L
            val replacementDisplays = mutableListOf<FeedbackEvent>()
            deliverFeedbackEvent(
                event = pending.first(),
                canDisplay = { true },
                display = { if (it != null) replacementDisplays += it },
                awaitVisibleFrame = {},
                acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
                accessibilityManager = replacementManager,
                waitForDuration = { replacementDuration = it },
            )
            assertEquals(60_000L, replacementDuration)
            assertEquals(listOf(error), replacementDisplays)
            assertEquals(listOf(success), pending)
            assertEquals(1, oldManager.requests.size)
            assertEquals(1, replacementManager.requests.size)
        }
    }

    @Test
    fun hostRetiredBeforeVisibleFrameNeitherQueriesTimeoutNorConsumesTheEvent() = runBlocking {
        val manager = RecordingAccessibilityManager {
            throw AssertionError("Retired host requested a timeout")
        }
        var allowed = true
        var pending = listOf(error)
        var displayed: FeedbackEvent? = null
        deliverFeedbackEvent(
            event = error,
            canDisplay = { allowed },
            display = { displayed = it },
            awaitVisibleFrame = { allowed = false },
            acknowledge = { pending = acknowledgeFeedbackEvent(pending, it) },
            accessibilityManager = manager,
            waitForDuration = { throw AssertionError("Retired host started a timer") },
        )

        assertTrue(manager.requests.isEmpty())
        assertEquals(listOf(error), pending)
        assertNull(displayed)
    }

    private data class TimeoutRequest(
        val originalTimeoutMillis: Long,
        val containsIcons: Boolean,
        val containsText: Boolean,
        val containsControls: Boolean,
    )

    private class RecordingAccessibilityManager(
        var recommendation: (TimeoutRequest) -> Long,
    ) : AccessibilityManager {
        val requests = mutableListOf<TimeoutRequest>()

        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean,
        ): Long {
            val request = TimeoutRequest(
                originalTimeoutMillis, containsIcons, containsText, containsControls,
            )
            requests += request
            return recommendation(request)
        }
    }
}
