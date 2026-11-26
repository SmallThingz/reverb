package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceGatePacketCoalescingTest {
    private companion object {
        const val FRAME_BYTES = 4 // Mono PCM_FLOAT.
    }

    private val config = SilenceGateConfig(true, listOf(SilencePoint(20, -40), SilencePoint(20_000, -40)))

    private sealed interface Event

    private data class Kept(val sourceFrame: Long, val frames: Int) : Event {
        val endFrame: Long get() = sourceFrame + frames
    }

    private object Gap : Event

    private class RecordingOutput(private val expectedHop: Int) : SilenceGateOutput {
        val events = mutableListOf<Event>()
        val runs = mutableListOf<Kept>()
        val pcm = ByteArrayOutputStream()

        override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
            assertTrue(count > 0 && count % FRAME_BYTES == 0)
            events += Kept(sourceFrame, count / FRAME_BYTES)
            pcm.write(bytes, offset, count)
        }

        override fun audioRun(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long, hopFrames: Int) {
            assertEquals(expectedHop, hopFrames)
            runs += Kept(sourceFrame, count / FRAME_BYTES)
            audio(bytes, offset, count, sourceFrame)
        }

        override fun gap() {
            events += Gap
        }
    }

    private fun pcm(rate: Int, vararg sections: Pair<Int, Double>, toneHz: Int = 1000): ByteArray {
        val output = ByteBuffer.allocate(sections.sumOf { it.first } * FRAME_BYTES).order(ByteOrder.nativeOrder())
        var frame = 0
        for ((frames, db) in sections) {
            val amplitude = 10.0.pow(db / 20)
            repeat(frames) { output.putFloat((amplitude * sin(2 * PI * toneHz * frame++ / rate)).toFloat()) }
        }
        return output.array()
    }

    private fun gate(rate: Int, output: SilenceGateOutput) =
        SilenceGateStream(rate, 1, PcmSampleFormat.PCM_FLOAT, config, output)

    private fun normalize(events: List<Event>): List<Event> {
        val normalized = mutableListOf<Event>()
        for (event in events) {
            val previous = normalized.lastOrNull()
            if (previous is Kept && event is Kept && previous.endFrame == event.sourceFrame) {
                normalized[normalized.lastIndex] = previous.copy(frames = previous.frames + event.frames)
            } else {
                normalized += event
            }
        }
        return normalized
    }

    @Test fun oneSecondPacketAppendsOnceAndStopFlushesExactHeldTail() {
        val rate = 48_000
        val hop = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).hop
        val source = pcm(rate, rate to -20.0, toneHz = 997)
        val packet = ByteArray(source.size + 2 * FRAME_BYTES) { 0x5a.toByte() }
        source.copyInto(packet, destinationOffset = FRAME_BYTES)

        val direct = RecordingOutput(hop)
        // Model the disabled capture path's direct append of the admitted slice.
        direct.audio(packet, FRAME_BYTES, source.size, 0L)
        assertEquals(listOf(Kept(0L, rate)), direct.events)
        assertArrayEquals(source, direct.pcm.toByteArray())

        val legacy = RecordingOutput(hop)
        val coalesced = RecordingOutput(hop)
        val legacyGate = gate(rate, legacy)
        val coalescedGate = gate(rate, coalesced)
        legacyGate.accept(packet, FRAME_BYTES, source.size)
        coalescedGate.accept(packet, FRAME_BYTES, source.size, coalesceKeptHops = true)

        val completedHops = rate / hop - 1
        val emittedFrames = completedHops * hop
        val packetRun = Kept(0L, emittedFrames)
        assertEquals(List(completedHops) { Kept(it.toLong() * hop, hop) }, legacy.events)
        assertTrue(legacy.runs.isEmpty())
        assertTrue(coalesced.events.size < legacy.events.size)
        assertEquals(direct.events.size, coalesced.events.size)
        assertEquals(listOf(packetRun), coalesced.events)
        assertEquals(listOf(packetRun), coalesced.runs)
        assertArrayEquals(source.copyOfRange(0, emittedFrames * FRAME_BYTES), coalesced.pcm.toByteArray())

        packet.fill(0) // The microphone read buffer can be reused before Stop.
        val tail = Kept(emittedFrames.toLong(), rate - emittedFrames)
        legacyGate.finish()
        coalescedGate.finish()
        assertEquals(tail, legacy.events.last())
        assertEquals(listOf(packetRun, tail), coalesced.events)
        assertEquals(listOf(packetRun), coalesced.runs)
        assertArrayEquals(source, coalesced.pcm.toByteArray())
        assertArrayEquals(legacy.pcm.toByteArray(), coalesced.pcm.toByteArray())
        coalescedGate.finish()
        assertEquals(listOf(packetRun, tail), coalesced.events)
    }

    @Test fun coalescingPreservesPartialHopGapAndReopeningSourceFrames() {
        val rate = 16_000
        val hop = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).hop
        // The near-threshold continuation from SilenceGateContinuityTest expires mid-hop.
        val source = pcm(rate, 2048 to -20.0, 24_000 to -40.02, 8192 to Double.NEGATIVE_INFINITY, 4096 to -20.0)
        val legacy = RecordingOutput(hop)
        val coalesced = RecordingOutput(hop)
        val legacyGate = gate(rate, legacy)
        val coalescedGate = gate(rate, coalesced)
        legacyGate.accept(source, 0, source.size)
        coalescedGate.accept(source, 0, source.size, coalesceKeptHops = true)

        val gapIndex = legacy.events.indexOf(Gap)
        assertTrue("The packet must keep audio on both sides of the gap", gapIndex in 1 until legacy.events.lastIndex)
        val partial = legacy.events[gapIndex - 1] as Kept
        val reopening = legacy.events[gapIndex + 1] as Kept
        assertTrue("The continuation deadline must split a hop", partial.frames in 1 until hop)
        assertEquals("The one-second continuation deadline must stay sample-exact", rate % hop, partial.frames)
        assertTrue("The gap must drop source frames", reopening.sourceFrame > partial.endFrame)
        val emittedFrames = (source.size / FRAME_BYTES / hop - 1) * hop
        val reopenedRun = Kept(reopening.sourceFrame, emittedFrames - reopening.sourceFrame.toInt())
        val acceptedEvents = normalize(legacy.events)
        assertEquals(listOf(Kept(0L, partial.endFrame.toInt()), Gap, reopenedRun), acceptedEvents)
        assertEquals(acceptedEvents, normalize(coalesced.events))
        assertTrue(coalesced.runs.any { it.endFrame <= partial.endFrame && it.frames > hop })
        assertTrue(coalesced.runs.any { it.sourceFrame >= reopening.sourceFrame && it.frames > hop })

        legacyGate.finish()
        coalescedGate.finish()
        val finishedEvents = normalize(legacy.events)
        assertEquals(finishedEvents, normalize(coalesced.events))
        val expected = ByteArrayOutputStream()
        for (kept in finishedEvents.filterIsInstance<Kept>()) {
            expected.write(source, kept.sourceFrame.toInt() * FRAME_BYTES, kept.frames * FRAME_BYTES)
        }
        assertArrayEquals(expected.toByteArray(), legacy.pcm.toByteArray())
        assertArrayEquals(expected.toByteArray(), coalesced.pcm.toByteArray())
        assertEquals(legacyGate.retainedFrames, coalescedGate.retainedFrames)
        assertEquals(legacyGate.skippedFrames, coalescedGate.skippedFrames)
    }

    @Test fun rejectedRunIsNeverReplayedByAcceptOrStop() {
        val rate = 48_000
        val hop = SilenceSpectrum(rate, 1, PcmSampleFormat.PCM_FLOAT).hop
        val source = pcm(rate, rate to -20.0)
        val rejection = IOException("Injected failure after a partial append")
        val attempted = mutableListOf<Kept>()
        val written = ByteArrayOutputStream()
        val prefixBytes = 17 * FRAME_BYTES
        val stream = gate(rate, object : SilenceGateOutput {
            // Exercise the default audioRun delegation through the existing sink callback.
            override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
                attempted += Kept(sourceFrame, count / FRAME_BYTES)
                written.write(bytes, offset, prefixBytes)
                throw rejection
            }

            override fun gap() = throw AssertionError("Unexpected gap after append rejection")
        })

        assertSame(rejection, assertThrows(IOException::class.java) {
            stream.accept(source, 0, source.size, coalesceKeptHops = true)
        })
        val run = attempted.single()
        assertEquals(0L, run.sourceFrame)
        assertTrue("The rejected append must contain multiple hops", run.frames > hop)
        assertThrows(IllegalStateException::class.java) {
            stream.accept(source, 0, source.size, coalesceKeptHops = true)
        }
        stream.finish()
        stream.finish()
        assertEquals(listOf(run), attempted)
        assertArrayEquals(source.copyOfRange(0, prefixBytes), written.toByteArray())
    }
}
