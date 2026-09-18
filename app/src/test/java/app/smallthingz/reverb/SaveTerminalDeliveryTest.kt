package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveTerminalDeliveryTest {
    @Test
    fun terminalDelivery_runsCleanupAfterSuccessfulCallback() {
        val events = mutableListOf<String>()
        val result = deliverTerminalResult(
            deliver = {
                events += "deliver"
                7
            },
            finish = { events += "finish" },
        )
        assertEquals(7, result)
        assertEquals(listOf("deliver", "finish"), events)
    }

    @Test
    fun terminalDelivery_runsCleanupBeforePropagatingCallbackFailure() {
        val events = mutableListOf<String>()
        var threw = false
        try {
            deliverTerminalResult<Unit>(
                deliver = {
                    events += "deliver"
                    error("ui callback failed")
                },
                finish = { events += "finish" },
            )
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(listOf("deliver", "finish"), events)
    }

    @Test
    fun terminalDelivery_preservesPrimaryFailureWhenCleanupAlsoFails() {
        val primary = IllegalStateException("ui callback failed")
        val cleanup = IllegalArgumentException("terminal cleanup failed")
        var observed: Throwable? = null
        try {
            deliverTerminalResult<Unit>(
                deliver = { throw primary },
                finish = { throw cleanup },
            )
        } catch (error: Throwable) {
            observed = error
        }
        assertTrue(observed === primary)
        assertEquals(listOf(cleanup), primary.suppressed.toList())
    }

    @Test
    fun terminalDelivery_propagatesCleanupFailureAfterSuccessfulCallback() {
        val cleanup = IllegalArgumentException("terminal cleanup failed")
        var observed: Throwable? = null
        try {
            deliverTerminalResult(
                deliver = { 7 },
                finish = { throw cleanup },
            )
        } catch (error: Throwable) {
            observed = error
        }
        assertTrue(observed === cleanup)
    }

    @Test
    fun visibleTerminalFallback_runsOnlyWhenUiDeliveryDoesNotComplete() {
        val events = mutableListOf<String>()

        deliverVisibleTerminalOrFallback(
            deliverVisible = {
                events += "visible"
                true
            },
            fallback = { events += "fallback" },
        )
        assertEquals(listOf("visible"), events)

        events.clear()
        deliverVisibleTerminalOrFallback(
            deliverVisible = {
                events += "detached"
                false
            },
            fallback = { events += "fallback" },
        )
        assertEquals(listOf("detached", "fallback"), events)
    }

    @Test
    fun visibleTerminalFallback_runsFallbackBeforePropagatingUiCallbackFailure() {
        val events = mutableListOf<String>()
        val primary = IllegalStateException("ui callback failed")
        var observed: Throwable? = null

        try {
            deliverVisibleTerminalOrFallback(
                deliverVisible = {
                    events += "visible"
                    throw primary
                },
                fallback = { events += "fallback" },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertTrue(observed === primary)
        assertEquals(listOf("visible", "fallback"), events)
    }

    @Test
    fun visibleTerminalFallback_suppressesFallbackFailureOnUiCallbackFailure() {
        val primary = IllegalStateException("ui callback failed")
        val fallback = IllegalArgumentException("fallback failed")
        var observed: Throwable? = null

        try {
            deliverVisibleTerminalOrFallback(
                deliverVisible = { throw primary },
                fallback = { throw fallback },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertTrue(observed === primary)
        assertEquals(listOf(fallback), primary.suppressed.toList())
    }


    @Test
    fun terminalDelivery_runsFallbackBeforeCleanupForThrowingUiCallback() {
        val events = mutableListOf<String>()
        val primary = IllegalStateException("ui callback failed")
        var observed: Throwable? = null

        try {
            deliverTerminalResult(
                deliver = {
                    deliverVisibleTerminalOrFallback(
                        deliverVisible = {
                            events += "visible"
                            throw primary
                        },
                        fallback = { events += "fallback" },
                    )
                },
                finish = { events += "finish" },
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertTrue(observed === primary)
        assertEquals(listOf("visible", "fallback", "finish"), events)
    }


    @Test
    fun detachedSaveNotificationIdentity_isStableAndDistinctPerRecording() {
        // "FB" and "Ea" deliberately collide under String.hashCode(); notification identity
        // must not collapse to a 32-bit request-code/hash scheme.
        assertEquals("FB".hashCode(), "Ea".hashCode())

        val firstKey = recordingSavedNotificationKey("FB")
        val secondKey = recordingSavedNotificationKey("Ea")
        assertEquals(firstKey, recordingSavedNotificationKey("FB"))
        assertTrue(firstKey != secondKey)
        assertTrue(recordingSavedNotificationTag("FB") != recordingSavedNotificationTag("Ea"))
        assertTrue(
            recordingSavedPendingIntentAction("app.smallthingz.reverb", "FB") !=
                recordingSavedPendingIntentAction("app.smallthingz.reverb", "Ea"),
        )
    }
}
