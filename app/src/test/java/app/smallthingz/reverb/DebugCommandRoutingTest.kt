package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCommandRoutingTest {
    @Test
    fun stoppedRecorder_acceptsDebugQaCommands_withoutChangingProductionRouting() {
        assertTrue(debugCommandRunsWithoutListening(ReverbService.ACTION_DEBUG_EXPORT_SECONDS, true))
        assertTrue(debugCommandRunsWithoutListening(ReverbService.ACTION_DEBUG_LOG_STATE, true))
        assertTrue(debugCommandRunsWithoutListening(ReverbService.ACTION_DEBUG_INJECT_BUFFER, true))
        assertTrue(debugCommandRunsWithoutListening(ReverbService.ACTION_DEBUG_DISABLE_LISTENING, true))
        assertFalse(debugCommandRunsWithoutListening(ReverbService.ACTION_DEBUG_ENABLE_LISTENING, true))
        assertFalse(debugCommandRunsWithoutListening("app.smallthingz.reverb.NORMAL", true))
        assertFalse(debugCommandRunsWithoutListening(ReverbService.ACTION_DEBUG_EXPORT_SECONDS, false))
        assertFalse(debugCommandRunsWithoutListening(null, true))
    }
}
