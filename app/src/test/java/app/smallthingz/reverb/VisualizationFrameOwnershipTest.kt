package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class VisualizationFrameOwnershipTest {
    private data class Frame(val command: Long, val continuity: Long, val slot: String)

    @Test fun queuedFrameCannotCrossSwitchStopRestartOrTeardown() {
        val mailbox = IdentityFrameMailbox<Any, Frame>()
        val registration = requireNotNull(mailbox.register(Any()))
        var command = 1L
        var continuity = 1L
        var destroying = false
        val delivered = mutableListOf<String>()
        fun dispatch() = mailbox.dispatchPending { _, frame ->
            if (visualizationFrameMayDeliver(frame.command, frame.continuity, command, continuity, destroying))
                delivered += frame.slot
        }
        mailbox.publish(registration, Frame(1, 1, "one-shot"))
        command = 2 // Destination switches after publication, before main-thread delivery.
        dispatch()
        assertTrue(delivered.isEmpty())
        mailbox.publish(registration, Frame(2, 1, "looping"))
        continuity = 2 // Stop retires the read even if command delivery has not caught up.
        dispatch()
        assertTrue(delivered.isEmpty())
        mailbox.publish(registration, Frame(2, 1, "looping"))
        command = 3 // Restart must not make the old pending frame current again.
        dispatch()
        assertTrue(delivered.isEmpty())
        mailbox.publish(registration, Frame(3, 2, "looping"))
        dispatch()
        assertEquals(listOf("looping"), delivered)
        mailbox.publish(registration, Frame(3, 2, "looping"))
        destroying = true
        dispatch()
        assertEquals(listOf("looping"), delivered)
    }
}
