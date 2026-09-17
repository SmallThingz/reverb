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
}
