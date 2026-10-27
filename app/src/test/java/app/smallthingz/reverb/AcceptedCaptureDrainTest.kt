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
}
