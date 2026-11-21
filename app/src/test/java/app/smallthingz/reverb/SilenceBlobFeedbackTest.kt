package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SilenceBlobFeedbackTest {
    private fun stream(enabled: Boolean = true) = SilenceGateStream(16_000, 1, PcmSampleFormat.PCM_16,
        SilenceGateConfig(enabled, listOf(SilencePoint(20, -50), SilencePoint(20_000, -50))),
        object : SilenceGateOutput {
            override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
            override fun gap() = Unit
        })

    private fun tone(frames: Int): ByteArray = ByteBuffer.allocate(frames * 2)
        .order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { putShort((sin(2 * PI * 1000 * it / 16_000) * 16_000).toInt().toShort()) }
        }.array()

    @Test fun feedbackRequiresCompletedSuppressionAndHonorsHoldTail() {
        val gate = stream()
        assertFalse(gate.suppressingSilence)
        gate.accept(ByteArray(128), 0, 128)
        assertFalse("An incomplete analysis window is not silence proof", gate.suppressingSilence)
        val quiet = ByteArray(16_000)
        gate.accept(quiet, 0, quiet.size)
        assertTrue(gate.suppressingSilence)
        val loud = tone(2048)
        gate.accept(loud, 0, loud.size)
        assertFalse(gate.suppressingSilence)
        gate.accept(quiet, 0, 1024)
        assertFalse("Retained post-trigger audio must still look live", gate.suppressingSilence)
        gate.accept(quiet, 0, quiet.size)
        assertTrue(gate.suppressingSilence)
        gate.finish()
        assertFalse("A retired stream cannot own live feedback", gate.suppressingSilence)
    }

    @Test fun disabledGateNeverReportsSuppression() {
        val gate = stream(enabled = false)
        val quiet = ByteArray(16_000)
        gate.accept(quiet, 0, quiet.size)
        assertFalse(gate.suppressingSilence)
        assertEquals(0L, gate.skippedFrames)
    }
}
