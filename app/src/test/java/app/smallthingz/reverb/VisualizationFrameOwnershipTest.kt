package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class VisualizationFrameOwnershipTest {
    @Test fun queuedFrameCannotCrossSwitchStopRestartOrTeardown() {
        val mailbox = IdentityFrameMailbox<Any, ReverbService.VisualizationFrame>()
        val registration = requireNotNull(mailbox.register(Any()))
        val analyzer = AudioVisualizationAnalyzer()
        val pcm = ByteArray(Short.SIZE_BYTES)
        fun analyze(command: Long, continuity: Long, slot: ReverbService.BufferSlot) = analyzer.analyze(
            pcm, 0, pcm.size, PcmSampleFormat.PCM_16, 1, 48_000,
            bufferSlot = slot,
            captureCommandGeneration = command,
            captureContinuityGeneration = continuity,
        )

        var command = 1L
        var continuity = 1L
        var destroying = false
        val delivered = mutableListOf<ReverbService.VisualizationFrame>()
        fun dispatch() = mailbox.dispatchPending { _, frame ->
            if (visualizationFrameMayDeliver(
                    frame.captureCommandGeneration, frame.captureContinuityGeneration, command, continuity, destroying,
                )
            ) {
                delivered += frame
            }
        }
        mailbox.publish(registration, analyze(1, 1, ReverbService.BufferSlot.ONE_SHOT))
        command = 2 // Destination switches after publication, before main-thread delivery.
        dispatch()
        assertTrue(delivered.isEmpty())
        mailbox.publish(registration, analyze(2, 1, ReverbService.BufferSlot.LOOPING))
        continuity = 2 // Stop retires the read even if command delivery has not caught up.
        dispatch()
        assertTrue(delivered.isEmpty())
        mailbox.publish(registration, analyze(2, 1, ReverbService.BufferSlot.LOOPING))
        command = 3 // Restart must not make the old pending frame current again.
        dispatch()
        assertTrue(delivered.isEmpty())
        val currentFrame = analyze(3, 2, ReverbService.BufferSlot.LOOPING)
        mailbox.publish(registration, currentFrame)
        dispatch()
        assertSame(currentFrame, delivered.single())
        mailbox.publish(registration, analyze(3, 2, ReverbService.BufferSlot.LOOPING))
        destroying = true
        dispatch()
        assertSame(currentFrame, delivered.single())
    }
}
