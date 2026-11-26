package app.smallthingz.reverb

import android.app.Application
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ServiceStateOsShadow::class])
class ShuttleTimelinePhaseOracleTest {
    @Test fun arbitrarySelectionKeepsAnAbsoluteCapturePhaseAcrossNormalizedCacheRefills() {
        val sourceRate = 44_100
        val outputRate = 96_000
        val duration = .520
        val lower = .021 // 926.1 source frames and exactly 2,016 output frames.
        val upper = .437 // Also not a source-frame boundary or part of the 40 ms cache lattice.
        val sourceFrames = (sourceRate * duration).roundToInt()
        val pcm = ByteBuffer.allocate(sourceFrames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (frame in 0 until sourceFrames) {
                val time = frame.toDouble() / sourceRate
                val left = 17_000 * sin(2 * PI * (823 * time + 173 * time * time))
                val right = 12_000 * sin(2 * PI * (2_117 * time + 319 * time * time) + .31)
                putShort(left.roundToInt().toShort())
                putShort(right.roundToInt().toShort())
            }
        }.array()
        val root = Files.createTempDirectory("shuttle-capture-phase-")
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        ServiceStateOsShadow.fixtureRoot = root
        try {
            PersistentAudioChunkStore(root.toFile(), overwriteOldest = false, directorySync = {}).use { store ->
                store.configure(RetentionMode.SIZE, 2_000_000L, sourceRate, 2, PcmSampleFormat.PCM_16)
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                store.sealActiveChunk()
                ReverbService.TimelineSnapshot(requireNotNull(store.acquireRange(0.0, duration))).use { snapshot ->
                    fun readWindow(from: Double, until: Double): ByteArray = requireNotNull(readTimelineUnityPcm16(
                        snapshot, from, until, lower, upper, outputRate, 2, checkAdmission = {},
                    ))
                    // Take one continuous normalization at the aligned timeline origin.
                    // Its output grid supplies the reference independently of cache windows.
                    val reference = pcmSamples(requireNotNull(readTimelineShuttlePcm16(
                        snapshot, 0.0, duration, outputRate, 2, checkAdmission = {},
                    )))
                    assertEquals((duration * outputRate).roundToInt() * 2, reference.limit())
                    val first = (lower * outputRate).roundToInt()
                    val end = (upper * outputRate).roundToInt()
                    for (reverse in listOf(false, true)) {
                        val cursor = ShuttleUnityCursor(outputRate, reverse)
                        val reader = ShuttleUnityPcm(outputRate, 2)
                        val sink = ByteArrayOutputStream()
                        var windowReads = 0
                        for (elapsedMs in 0..448 step 8) {
                            val seconds = elapsedMs / 1_000.0
                            val target = if (reverse) upper - seconds else lower + seconds
                            for (attempt in 0 until 4) {
                                val block = cursor.next(target, lower, upper) ?: break
                                sink.write(reader.render(block) { from, until ->
                                    assertTrue(from >= lower && until <= upper)
                                    windowReads++
                                    readWindow(from, until)
                                })
                            }
                        }
                        val observed = pcmSamples(sink.toByteArray())
                        assertEquals((end - first) * 2, observed.limit())
                        assertTrue("The test must force more than one independent refill", windowReads >= 4)
                        val guard = outputRate * 4 / 1_000
                        for (frame in guard until end - first - guard) for (channel in 0..1) {
                            val absoluteFrame = if (reverse) end - 1 - frame else first + frame
                            val expected = reference.get(absoluteFrame * 2 + channel).toInt()
                            val actual = observed.get(frame * 2 + channel).toInt()
                            assertTrue("Absolute capture phase shifted at $absoluteFrame channel $channel reverse=$reverse: " +
                                "expected=$expected actual=$actual", abs(expected - actual) <= 2)
                        }
                    }
                }
            }
        } finally {
            ServiceStateOsShadow.fixtureRoot = previousRoot
            root.toFile().deleteRecursively()
        }
    }

    private fun pcmSamples(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
}
