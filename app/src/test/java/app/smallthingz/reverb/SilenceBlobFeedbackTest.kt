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

    @Test fun drainedColorIsVisibleForLightAndDarkPalettesAndPreservesAlpha() {
        for (color in intArrayOf(0xFFA8C7FA.toInt(), 0xFF415F91.toInt(), 0xFF8C4A60.toInt(), 0x7F80AB90)) {
            val muted = silenceBlobMutedColor(color)
            fun red(c: Int) = c ushr 16 and 255
            fun green(c: Int) = c ushr 8 and 255
            fun blue(c: Int) = c and 255
            fun light(c: Int) = .2126 * red(c) + .7152 * green(c) + .0722 * blue(c)
            fun chroma(c: Int) = maxOf(red(c), green(c), blue(c)) - minOf(red(c), green(c), blue(c))
            assertEquals(color ushr 24, muted ushr 24)
            assertTrue("Quiet body remains visible", light(muted) >= light(color) * .70)
            assertTrue("Quiet body still visibly dims", light(muted) <= light(color) * .74)
            assertTrue("Quiet color stays colored", chroma(muted) >= chroma(color) * .40 - 1)
            assertTrue("Quiet color softens without becoming gray", chroma(muted) <= chroma(color) * .44 + 1)
        }
    }

    @Test fun silencePullsColorAndLightIntoASubduedCore() {
        assertEquals(.35f, silenceBlobDrainWeight(0f), .0001f)
        assertEquals(.675f, silenceBlobDrainWeight(.35f), .0001f)
        assertEquals(1f, silenceBlobDrainWeight(.7f), 0f)
        assertEquals(1f, silenceBlobDrainWeight(1.5f), 0f)
        var previous = 0f
        repeat(101) { index ->
            val weight = silenceBlobDrainWeight(index / 100f)
            assertTrue("The rim must drain more strongly than the core", weight >= previous)
            assertTrue(weight in .35f..1f)
            assertTrue(kotlin.math.abs((weight * 255f).toInt() -
                (silenceBlobDrainMaskColor(index / 100f) ushr 24)) <= 1)
            previous = weight
        }
    }

    @Test fun silentTransitionAndRecoverySettleWithoutAudioActivity() {
        for (hz in intArrayOf(30, 60, 120)) {
            var amount = 0f
            repeat(hz * 2) {
                val next = advanceSilenceBlob(amount, 1f, 1f / hz)
                assertTrue(next in amount..1f)
                amount = next
            }
            assertEquals(1f, amount, 0f)
            repeat(hz * 2) {
                val next = advanceSilenceBlob(amount, 0f, 1f / hz)
                assertTrue(next in 0f..amount)
                amount = next
            }
            assertEquals(0f, amount, 0f)
        }
    }

    @Test fun silenceDimsGentlyAndRecoversFasterAcrossRefreshRates() {
        for (hz in intArrayOf(30, 60, 120)) {
            var dim = 0f
            var recovery = 1f
            repeat(hz / 5) {
                dim = advanceSilenceBlob(dim, 1f, 1f / hz)
                recovery = advanceSilenceBlob(recovery, 0f, 1f / hz)
            }
            assertEquals("Gradual dimming at $hz Hz", .465f, dim, .003f)
            assertEquals("Prompt recovery at $hz Hz", .135f, recovery, .003f)
            assertTrue(1f - recovery > dim)
            val reversed = advanceSilenceBlob(dim, 0f, 1f / hz)
            assertTrue("A gate reversal continues from current light", reversed in 0f..dim)
        }
    }

    @Test fun changedSilenceTimingDoesNotChangeCapturePresence() {
        assertEquals(1f - kotlin.math.exp(-.1f / .18f), advanceBlobPresence(0f, 1f, .1f), .000001f)
        assertEquals(kotlin.math.exp(-.1f / .12f), advanceBlobPresence(1f, 0f, .1f), .000001f)
        assertEquals(advanceSilenceBlob(0f, 1f, .1f), advanceSilenceBlob(0f, 1f, 1f), 0f)
        assertEquals(.4f, advanceSilenceBlob(.4f, 1f, 0f), 0f)
    }
}
