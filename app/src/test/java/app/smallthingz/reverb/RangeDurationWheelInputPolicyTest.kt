package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeDurationWheelInputPolicyTest {
    @Test
    fun disabledWheel_rejectsNewPointerInput() {
        assertFalse(
            rangeDurationWheelInputAllowed(
                enabled = false,
                interactionAlreadyActive = false,
            ),
        )
    }

    @Test
    fun disabledWheel_allowsAcceptedPointerInteractionToReachTerminalEvent() {
        assertTrue(
            rangeDurationWheelInputAllowed(
                enabled = false,
                interactionAlreadyActive = true,
            ),
        )
    }

    @Test
    fun enabledWheel_acceptsPointerInput() {
        assertTrue(
            rangeDurationWheelInputAllowed(
                enabled = true,
                interactionAlreadyActive = false,
            ),
        )
    }
}
