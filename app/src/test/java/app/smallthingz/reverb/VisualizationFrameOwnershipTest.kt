package app.smallthingz.reverb

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class VisualizationFrameOwnershipTest {
    @Test fun readsKeepPcmButOnlyTheirAdmissionEpochCanVisualizeThem() {
        val mailbox = IdentityFrameMailbox<Any, ByteArray>()
        val ring = CapturePcmRing(CapturePcmBudget(2, 8, 32, 8))
        val owner = Any()
        val delivered = mutableListOf<ByteArray>()

        fun admitRead(): CapturePcmRing.Lease {
            val identity = CaptureReadIdentity(1, 2, 3, 0, 48_000, 1, 2, 2, 0,
                visualizationEpoch = mailbox.currentEpoch())
            return requireNotNull(ring.reserve(identity, 2))
        }

        fun completeRead(lease: CapturePcmRing.Lease, pcm: ByteArray) {
            assertTrue(ring.complete(lease, ByteBuffer.wrap(pcm), pcm.size, 4, null, 5))
            val packet = requireNotNull(ring.poll())
            val writerBytes = ByteArray(packet.byteCount)
            ring.transferToWriter(packet, writerBytes)
            assertArrayEquals(pcm, writerBytes)
            val registration = requireNotNull(mailbox.currentRegistration())
            if (packet.lease.identity.belongsToVisualizationEpoch(registration.epoch)) {
                assertTrue(mailbox.publish(registration, writerBytes))
            }
            ring.completePacket(packet)
            mailbox.dispatchPending { actual, frame ->
                assertSame(owner, actual)
                delivered += frame
            }
        }

        val background = admitRead()
        mailbox.register(owner)
        completeRead(background, byteArrayOf(1, 2))
        assertTrue(delivered.isEmpty())

        val previousLease = admitRead()
        assertTrue(mailbox.unregister(owner))
        mailbox.register(owner)
        completeRead(previousLease, byteArrayOf(3, 4))
        assertTrue(delivered.isEmpty())

        val currentPcm = byteArrayOf(5, 6)
        completeRead(admitRead(), currentPcm)
        assertArrayEquals(currentPcm, delivered.single())
        assertTrue(ring.isDrained())
    }

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
