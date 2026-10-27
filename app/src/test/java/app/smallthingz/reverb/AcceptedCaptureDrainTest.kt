package app.smallthingz.reverb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AcceptedCaptureDrainTest {
    private fun admitted(
        continuity: Long = 11,
        command: Long = 23,
        release: Long = 37,
        inputOwned: Boolean = true,
        listening: Boolean = true,
        enabled: Boolean = true,
    ) = acceptedCaptureDrainIsCurrent(11, continuity, 23, command, 37, release, inputOwned, listening, enabled)

    @Test fun unchangedPositiveReadCanFinishItsTerminalHandoff() { assertTrue(admitted()) }
    @Test fun stopOrStopThenStartRetiresTheAcceptedContinuity() { assertFalse(admitted(continuity = 12)) }
    @Test fun newerDestinationCommandCannotBeOverwrittenByOldRead() { assertFalse(admitted(command = 24)) }
    @Test fun releaseThenReplacementCannotReuseEarlierReadAuthority() { assertFalse(admitted(release = 38)) }
    @Test fun differentInputCannotReuseMatchingNumericRevisions() { assertFalse(admitted(inputOwned = false)) }
    @Test fun forcedShutdownReleaseCannotDrainAsListening() { assertFalse(admitted(listening = false)) }
    @Test fun disabledIntentCannotBeRearmedByOverflow() { assertFalse(admitted(enabled = false)) }

    @Test fun shortEnabledGateRetainsExactFramesOnlyWhenSealed() {
        val pcm = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (sample in shortArrayOf(10001, -10002, 10003, -10004, 10005)) putShort(sample)
        }.array()
        val output = ByteArrayOutputStream()
        val gate = SilenceGateStream(16_000, 1, PcmSampleFormat.PCM_16,
            SilenceGateConfig(true, listOf(SilencePoint(20, -96), SilencePoint(20_000, -96))),
            object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                    output.write(bytes, offset, count)
                }
                override fun gap() = error("Loud synthetic gate tail was dropped")
            })
        gate.accept(pcm, 0, pcm.size)
        assertTrue(output.size() == 0)
        gate.finish()
        assertArrayEquals(pcm, output.toByteArray())
        gate.finish()
        assertArrayEquals(pcm, output.toByteArray())
    }

    @Test fun failedFirstHandoffRetiresLaterRealGateCallbacksAndFinish() {
        for (failFirst in listOf(false, true)) {
            val pcm = ByteBuffer.allocate(1536 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(1536) { putShort((if (it % 2 == 0) 12_000 else -12_000).toShort()) }
            }.array()
            val epoch = 11L
            var interruptedEpoch = Long.MIN_VALUE
            var callbacks = 0
            var handoffAttempts = 0
            var oneShotFrames = 0
            var loopFrames = 0
            var loopOwns = false
            val gate = SilenceGateStream(16_000, 1, PcmSampleFormat.PCM_16,
                SilenceGateConfig(true, listOf(SilencePoint(20, -96), SilencePoint(20_000, -96))),
                object : SilenceGateOutput {
                    override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                        callbacks++
                        if (!captureGateOutputMayCommit(epoch, interruptedEpoch)) return
                        val toOne = minOf(2 - oneShotFrames, count / 2)
                        oneShotFrames += toOne
                        if (!loopOwns) {
                            handoffAttempts++
                            if (failFirst) {
                                // Successful rollback preserves intent, but never permission to retry this batch.
                                interruptedEpoch = epoch
                                return
                            }
                            loopOwns = true
                        }
                        loopFrames += count / 2 - toOne
                    }
                    override fun gap() = error("Loud synthetic gate batch was dropped")
                })
            gate.accept(pcm, 0, pcm.size)
            assertTrue(callbacks == 2)
            gate.finish()
            assertTrue(callbacks == 3 && handoffAttempts == 1 && oneShotFrames == 2)
            assertTrue(loopFrames == if (failFirst) 0 else 1534)
        }
    }

}
