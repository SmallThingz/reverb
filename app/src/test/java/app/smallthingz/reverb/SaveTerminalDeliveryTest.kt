package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveTerminalDeliveryTest {
    @Test
    fun terminalDelivery_runsCleanupAfterSuccessfulCallback() {
        val events = mutableListOf<String>()
        val result = deliverTerminalSaveResult(
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
            deliverTerminalSaveResult<Unit>(
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
            deliverTerminalSaveResult<Unit>(
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
            deliverTerminalSaveResult(
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

        deliverVisibleSaveTerminalOrFallback(
            deliverVisible = {
                events += "visible"
                true
            },
            fallback = { events += "fallback" },
        )
        assertEquals(listOf("visible"), events)

        events.clear()
        deliverVisibleSaveTerminalOrFallback(
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
            deliverVisibleSaveTerminalOrFallback(
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
            deliverVisibleSaveTerminalOrFallback(
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
            deliverTerminalSaveResult(
                deliver = {
                    deliverVisibleSaveTerminalOrFallback(
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

}
