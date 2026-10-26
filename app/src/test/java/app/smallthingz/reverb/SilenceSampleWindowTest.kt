package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SilenceSampleWindowTest {
    private fun pcm(
        rate: Int, frames: Int, channels: Int = 1, format: PcmSampleFormat = PcmSampleFormat.PCM_16,
        amplitude: Double = 0.01, frequency: Int = 1_000,
    ): ByteArray {
        val out = ByteBuffer.allocate(frames * channels * format.bytesPerSample).order(ByteOrder.nativeOrder())
        repeat(frames) { frame ->
            repeat(channels) { channel ->
                val value = amplitude * sin(2 * PI * frequency * frame / rate) * if (channel == 0) 1 else -1
                when (format) {
                    PcmSampleFormat.PCM_8 -> out.put((128 + value * 127).toInt().coerceIn(0, 255).toByte())
                    PcmSampleFormat.PCM_16 -> out.putShort((value * 32767).toInt().toShort())
                    PcmSampleFormat.PCM_FLOAT -> out.putFloat(value.toFloat())
                }
            }
        }
        return out.array()
    }

    private fun peaks(bytes: ByteArray, rate: Int, channels: Int, format: PcmSampleFormat): List<Float> {
        val peaks = FloatArray(SILENCE_GRAPH_BINS) { -120f }
        val stream = SilenceGateStream(rate, channels, format, SilenceGateConfig(), object : SilenceGateOutput {
            override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
            override fun gap() = Unit
        }) { spectrum, _ ->
            val graph = spectrum.graph()
            for (i in peaks.indices) peaks[i] = maxOf(peaks[i], graph[i])
        }
        stream.accept(bytes, 0, bytes.size)
        stream.finish()
        return peaks.toList()
    }

    private fun feed(
        request: SilenceSampleRequest, bytes: ByteArray, rate: Int, channels: Int = 1,
        format: PcmSampleFormat = PcmSampleFormat.PCM_16, batchFrames: Int = 137,
    ) {
        val batch = batchFrames * channels * format.bytesPerSample
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(batch, bytes.size - offset)
            request.accept(bytes, offset, count, rate, channels, format, readEndedAtNanos = 0L)
            offset += count
        }
    }

    @Test(timeout = 10_000)
    fun timedSampleLastsFourSecondsAndDropsOnlyItsFirstHalfSecond() {
        val rate = 16_000
        val request = SilenceSampleRequest()
        val prefix = pcm(rate, rate / 2, amplitude = 0.8, frequency = 300)
        val middle = pcm(rate, rate * 3, amplitude = 0.01)
        val ending = pcm(rate, rate / 2, amplitude = 0.2, frequency = 3_000)
        feed(request, prefix + middle, rate)
        assertFalse(request.state.value.complete)
        feed(request, ending, rate)
        assertTrue(request.state.value.complete)
        assertEquals(4_000L, request.state.value.elapsedMillis)
        assertEquals(peaks(middle + ending, rate, 1, PcmSampleFormat.PCM_16), request.state.value.peaks)
    }

    @Test(timeout = 10_000)
    fun holdHasNoFourSecondCompletionAndTrimsBothEdgesAcrossRingWraps() {
        val rate = 16_000
        val request = SilenceSampleRequest(held = true)
        val interior = pcm(rate, rate * 5, amplitude = 0.02)
        val bytes = pcm(rate, rate / 2, amplitude = 0.8, frequency = 300) +
            interior + pcm(rate, rate / 2, amplitude = 0.8, frequency = 3_000)
        val original = bytes.copyOf()
        feed(request, bytes, rate)
        assertTrue(request.running())
        assertFalse(request.state.value.complete)
        request.finishHold(atNanos = 0L)
        request.processPendingControl()
        assertTrue(request.state.value.complete)
        assertEquals(6_000L, request.state.value.elapsedMillis)
        assertEquals(peaks(interior, rate, 1, PcmSampleFormat.PCM_16), request.state.value.peaks)
        assertArrayEquals(original, bytes)
    }

    @Test(timeout = 20_000)
    fun oddRateStereoAndEveryPcmFormatRoundBothDiscardEdgesOutward() {
        val rate = 11_025
        val edge = (rate + 1) / 2
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val interior = pcm(rate, rate, channels, format, amplitude = 0.03)
            val bytes = pcm(rate, edge, channels, format, amplitude = 0.7, frequency = 300) +
                interior + pcm(rate, edge, channels, format, amplitude = 0.7, frequency = 3_000)
            val request = SilenceSampleRequest(held = true)
            feed(request, bytes, rate, channels, format, batchFrames = 389)
            request.finishHold(atNanos = 0L)
            request.processPendingControl()
            assertTrue("$format/$channels", request.state.value.complete)
            assertEquals(peaks(interior, rate, channels, format), request.state.value.peaks)
        }
    }

    @Test(timeout = 10_000)
    fun shortTapConversionRetainsAlreadyBufferedAudioAndDoesNotTrimTimedEnding() {
        val rate = 16_000
        val request = SilenceSampleRequest(held = true)
        val first = pcm(rate, rate / 2, amplitude = 0.9, frequency = 300)
        val buffered = pcm(rate, rate / 4, amplitude = 0.1, frequency = 2_000)
        val rest = pcm(rate, rate * 3 + rate / 4, amplitude = 0.01)
        feed(request, first + buffered, rate)
        request.useTimedSample()
        request.processPendingControl()
        feed(request, rest, rate)
        assertTrue(request.state.value.complete)
        assertFalse(request.state.value.held)
        assertEquals(4_000L, request.state.value.elapsedMillis)
        assertEquals(peaks(buffered + rest, rate, 1, PcmSampleFormat.PCM_16), request.state.value.peaks)
    }

    @Test(timeout = 10_000)
    fun discardedFloatEdgesMayContainInvalidSamplesButInteriorMayNot() {
        val rate = 16_000
        val format = PcmSampleFormat.PCM_FLOAT
        val invalidEdge = ByteBuffer.allocate(rate / 2 * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(rate / 2) { putFloat(Float.NaN) }
        }.array()
        val interior = pcm(rate, rate, format = format)
        val request = SilenceSampleRequest(held = true)
        feed(request, invalidEdge + interior + invalidEdge, rate, format = format)
        request.finishHold(atNanos = 0L)
        request.processPendingControl()
        assertTrue(request.state.value.complete)
        assertEquals(peaks(interior, rate, 1, format), request.state.value.peaks)

        val invalidInterior = SilenceSampleRequest(held = true)
        assertThrows(java.io.IOException::class.java) {
            feed(invalidInterior, interior + invalidEdge + interior, rate, format = format)
        }
    }

    @Test(timeout = 10_000)
    fun releaseClipsCrossingReadBeforeDiscardingTrailingHalfSecond() {
        val rate = 16_000
        val format = PcmSampleFormat.PCM_FLOAT
        val request = SilenceSampleRequest(held = true)
        val first = pcm(rate, rate, format = format, amplitude = 0.01)
        feed(request, first, rate, format = format)
        val interiorEnd = pcm(rate, rate * 3 / 10, format = format, amplitude = 0.02)
        val trailing = pcm(rate, rate / 2, format = format, amplitude = 0.8)
        val afterRelease = ByteBuffer.allocate(rate / 5 * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(rate / 5) { putFloat(Float.NaN) }
        }.array()
        val crossing = interiorEnd + trailing + afterRelease
        request.finishHold(atNanos = 1_800_000_000L)
        request.accept(crossing, 0, crossing.size, rate, 1, format, readEndedAtNanos = 2_000_000_000L)
        assertTrue(request.state.value.complete)
        assertEquals(1_800L, request.state.value.elapsedMillis)
        assertEquals(peaks(first.copyOfRange(rate / 2 * 4, first.size) + interiorEnd, rate, 1, format),
            request.state.value.peaks)
    }

    @Test(timeout = 10_000)
    fun tooShortHoldNeverProducesDraftAndMinimumInteriorDoes() {
        val rate = 16_000
        val short = SilenceSampleRequest(held = true)
        feed(short, pcm(rate, rate * 3 / 2 - 1), rate)
        short.finishHold(atNanos = 0L)
        short.processPendingControl()
        assertFalse(short.state.value.complete)
        assertTrue(short.state.value.tooShort)
        assertNotNull(short.state.value.error)

        val enough = SilenceSampleRequest(held = true)
        feed(enough, pcm(rate, rate * 3 / 2), rate)
        enough.finishHold(atNanos = 0L)
        enough.processPendingControl()
        assertTrue(enough.state.value.complete)
        assertFalse(enough.state.value.tooShort)
    }

    @Test(timeout = 10_000)
    fun releaseOnlySignalsAudioOwnerAndLateFeedsCannotChangeCompletedResult() {
        val rate = 16_000
        val request = SilenceSampleRequest(held = true)
        val signals = AtomicInteger()
        request.setControlWakeup { signals.incrementAndGet() }
        feed(request, pcm(rate, rate * 2), rate)
        val releaseThread = Thread { request.finishHold(atNanos = 0L) }
        releaseThread.start()
        releaseThread.join()
        assertEquals(1, signals.get())
        assertFalse(request.state.value.complete)
        request.processPendingControl()
        val result = request.state.value
        assertTrue(result.complete)
        feed(request, pcm(rate, rate, amplitude = 0.9), rate)
        request.finishHold()
        request.useTimedSample()
        request.processPendingControl()
        assertEquals(result, request.state.value)
    }

    @Test(timeout = 10_000)
    fun cancellationWinsOverQueuedReleaseAndLateAudio() {
        val rate = 16_000
        val request = SilenceSampleRequest(held = true)
        feed(request, pcm(rate, rate * 2), rate)
        request.finishHold(atNanos = 0L)
        request.cancel()
        request.processPendingControl()
        feed(request, pcm(rate, rate), rate)
        assertTrue(request.state.value.cancelled)
        assertFalse(request.state.value.complete)
        assertNull(request.state.value.error)
    }

    @Test(timeout = 10_000)
    fun nativeClockExcludesQueuedPrePressAudioBeforeHeldEdgesAreTrimmed() {
        val rate = 8_000
        val request = SilenceSampleRequest(held = true, startedAtNanos = 5_000_000_000L)
        val backlog = pcm(rate, rate * 7 / 4, amplitude = 0.9, frequency = 300)
        request.acceptCaptured(backlog, 0, backlog.size, rate, 1, PcmSampleFormat.PCM_16,
            captureStartedAtNanos = 3_000_000_000L, readEndedAtNanos = 5_040_000_000L)
        assertEquals(0, request.state.value.sampleRate)
        val interior = pcm(rate, rate, amplitude = 0.01)
        val crossingPress = pcm(rate, rate * 3 / 4, amplitude = 0.8, frequency = 300) +
            interior + pcm(rate, rate / 2, amplitude = 0.8, frequency = 3_000)
        request.acceptCaptured(crossingPress, 0, crossingPress.size, rate, 1, PcmSampleFormat.PCM_16,
            captureStartedAtNanos = 4_750_000_000L, readEndedAtNanos = 7_000_000_000L)
        request.finishHold(atNanos = 7_000_000_000L)
        request.processPendingControl()
        assertTrue(request.state.value.complete)
        assertEquals(2_000L, request.state.value.elapsedMillis)
        assertEquals(peaks(interior, rate, 1, PcmSampleFormat.PCM_16), request.state.value.peaks)
    }

    @Test(timeout = 10_000)
    fun timedWindowDoesNotCountPrePressNativeBacklogTowardItsFourSeconds() {
        val rate = 8_000
        val request = SilenceSampleRequest(startedAtNanos = 5_000_000_000L)
        val interior = pcm(rate, rate * 7 / 2, amplitude = 0.02)
        val bytes = pcm(rate, rate * 5 / 2, amplitude = 0.8, frequency = 300) + interior
        val original = bytes.copyOf()
        request.acceptCaptured(bytes, 0, bytes.size, rate, 1, PcmSampleFormat.PCM_16,
            captureStartedAtNanos = 3_000_000_000L, readEndedAtNanos = 9_000_000_000L)
        assertTrue(request.state.value.complete)
        assertEquals(4_000L, request.state.value.elapsedMillis)
        assertEquals(peaks(interior, rate, 1, PcmSampleFormat.PCM_16), request.state.value.peaks)
        assertArrayEquals(original, bytes)
    }

    @Test(timeout = 10_000)
    fun missingNativeClockCannotCreateAQuietDraftAndLaterProofMayResumeSampling() {
        val rate = 8_000
        val bytes = pcm(rate, rate * 4)
        val request = SilenceSampleRequest(startedAtNanos = 5_000_000_000L)
        request.acceptCaptured(bytes, 0, bytes.size, rate, 1, PcmSampleFormat.PCM_16,
            captureStartedAtNanos = null, readEndedAtNanos = 9_000_000_000L)
        assertTrue(request.running())
        assertFalse(request.state.value.complete)
        assertEquals(0, request.state.value.sampleRate)
        request.acceptCaptured(bytes, 0, bytes.size, rate, 1, PcmSampleFormat.PCM_16,
            captureStartedAtNanos = 9_000_000_000L, readEndedAtNanos = 13_000_000_000L)
        assertTrue(request.state.value.complete)

        val held = SilenceSampleRequest(held = true, startedAtNanos = 5_000_000_000L)
        held.finishHold(atNanos = 9_000_000_000L)
        held.acceptCaptured(bytes, 0, bytes.size, rate, 1, PcmSampleFormat.PCM_16,
            captureStartedAtNanos = null, readEndedAtNanos = 9_000_000_000L)
        assertTrue(held.state.value.tooShort)
        assertFalse(held.state.value.complete)
    }

    @Test
    fun nativeFrameMappingUsesRelativePositionAndRemainsConservativeWhenReadsLag() {
        assertEquals(3_000_000_000L, captureReadStartNanos(0, 16_000, 5_000_000_000L, 8_000))
        assertEquals(4_750_000_000L, captureReadStartNanos(14_000, 32_000, 7_000_000_000L, 8_000))
        // A native overrun never advances the delivered read position by a guessed backlog size.
        assertEquals(3_750_000_000L, captureReadStartNanos(14_000, 40_000, 7_000_000_000L, 8_000))
        assertEquals(999_909_297L, captureReadStartNanos(0, 1, 1_000_000_000L, 11_025))
        assertEquals(4_000_000_000L,
            captureReadStartNanos(10_000_000_000L, 10_000_008_000L, 5_000_000_000L, 8_000))
        assertNull(captureReadStartNanos(-1, 0, 1L, 8_000))
        assertNull(captureReadStartNanos(0, 0, 0L, 8_000))
        assertNull(captureReadStartNanos(Long.MAX_VALUE, 0, Long.MAX_VALUE, 1))
    }

    @Test
    fun heldPcmWithoutUsableTimingReportsTimingInsteadOfShortHold() {
        val rate = 8_000
        val bytes = pcm(rate, rate * 4)
        for (timestamp in listOf<Long?>(null, 10_000_000_000L)) {
            val request = SilenceSampleRequest(held = true, startedAtNanos = 5_000_000_000L)
            request.acceptCaptured(bytes, 0, bytes.size, rate, 1, PcmSampleFormat.PCM_16,
                captureStartedAtNanos = timestamp, readEndedAtNanos = 9_000_000_000L)
            request.finishHold(atNanos = 9_000_000_000L)
            assertEquals(SILENCE_SAMPLE_TIMING_UNAVAILABLE_MESSAGE, request.state.value.error)
            assertFalse(request.state.value.tooShort)
            assertFalse(request.state.value.complete)
            assertEquals(0, request.state.value.sampleRate)
        }
    }

    @Test
    fun zeroPcmAndClockedShortSamplesDoNotClaimMissingTimingCapability() {
        val rate = 8_000
        val empty = SilenceSampleRequest(held = true, startedAtNanos = 5_000_000_000L)
        empty.acceptCaptured(ByteArray(0), 0, 0, rate, 1, PcmSampleFormat.PCM_16,
            captureStartedAtNanos = null, readEndedAtNanos = 9_000_000_000L)
        empty.finishHold(atNanos = 9_000_000_000L)
        assertTrue(empty.state.value.tooShort)
        assertFalse(empty.captureTimingUnavailable())

        for (captureStart in listOf(3_000_000_000L, 5_000_000_000L)) {
            val request = SilenceSampleRequest(held = true, startedAtNanos = 5_000_000_000L)
            val bytes = pcm(rate, rate)
            request.acceptCaptured(bytes, 0, bytes.size, rate, 1, PcmSampleFormat.PCM_16,
                captureStartedAtNanos = null, readEndedAtNanos = 6_000_000_000L)
            request.acceptCaptured(bytes, 0, bytes.size, rate, 1, PcmSampleFormat.PCM_16,
                captureStartedAtNanos = captureStart, readEndedAtNanos = 6_000_000_000L)
            request.finishHold(atNanos = 6_000_000_000L)
            request.processPendingControl()
            assertTrue(request.state.value.tooShort)
            assertFalse(request.captureTimingUnavailable())
        }
    }

    @Test
    fun samplingTimeoutClassifiesOnlyUnclockedPcmAndPreservesPriorTerminalOutcomes() {
        val bytes = pcm(8_000, 8_000)
        fun unclocked() = SilenceSampleRequest(startedAtNanos = 5_000_000_000L).also {
            it.acceptCaptured(bytes, 0, bytes.size, 8_000, 1, PcmSampleFormat.PCM_16,
                captureStartedAtNanos = null, readEndedAtNanos = 6_000_000_000L)
        }
        val timed = unclocked()
        timed.failSamplingTimeout("deadline")
        assertEquals(SILENCE_SAMPLE_TIMING_UNAVAILABLE_MESSAGE, timed.state.value.error)
        assertFalse(timed.state.value.complete)

        val empty = SilenceSampleRequest()
        empty.failSamplingTimeout("deadline")
        assertEquals("deadline", empty.state.value.error)

        val failedRead = unclocked()
        failedRead.fail("microphone read failed")
        val failure = failedRead.state.value
        failedRead.failSamplingTimeout("deadline")
        assertEquals(failure, failedRead.state.value)

        val cancelled = unclocked()
        cancelled.cancel()
        val cancellation = cancelled.state.value
        cancelled.failSamplingTimeout("deadline")
        assertEquals(cancellation, cancelled.state.value)
        assertNull(cancelled.state.value.error)

        val completed = SilenceSampleRequest()
        feed(completed, pcm(8_000, 8_000 * 4), 8_000)
        val success = completed.state.value
        assertTrue(success.complete)
        completed.failSamplingTimeout("deadline")
        assertEquals(success, completed.state.value)
    }

    @Test
    fun tapConversionChangesTheAlreadyObservedAbsoluteDeadlineAndWakesItsOwner() {
        val press = 1_000_000_000L
        val held = SilenceSampleRequest(held = true, startedAtNanos = press)
        val semanticTap = SilenceSampleRequest(startedAtNanos = press)
        val wakeups = AtomicInteger()
        held.setControlWakeup { wakeups.incrementAndGet() }
        val originalDeadline = held.deadlineNanos.value
        assertEquals(61_000_000_000L, originalDeadline)
        assertEquals(58_000L, held.remainingTimeoutMillis(nowNanos = 3_000_000_000L))
        held.useTimedSample()
        assertEquals(1, wakeups.get())
        assertEquals(semanticTap.deadlineNanos.value, held.deadlineNanos.value)
        assertEquals(8_000L, held.remainingTimeoutMillis(nowNanos = 3_000_000_000L))
        assertEquals(0L, held.remainingTimeoutMillis(nowNanos = 11_000_000_000L))
        assertEquals(50_000L, silenceSampleRemainingMillis(originalDeadline, 11_000_000_000L))
    }

    @Test
    fun heldReleaseWithoutAnyAdmittedPcmIsTerminalWithoutAnAudioCallback() {
        val request = SilenceSampleRequest(held = true)
        val releaseThread = Thread { request.finishHold() }.apply { isDaemon = true }
        releaseThread.start()
        releaseThread.join(5_000L)
        assertFalse("finishHold did not terminate", releaseThread.isAlive)
        assertFalse(request.running())
        assertTrue(request.state.value.tooShort)
        assertFalse(request.state.value.complete)
        val terminal = request.state.value
        request.setControlWakeup { request.processPendingControl() }
        assertEquals(terminal, request.state.value)

        val cancelled = SilenceSampleRequest(held = true)
        cancelled.cancel()
        cancelled.finishHold()
        assertTrue(cancelled.state.value.cancelled)
        assertFalse(cancelled.state.value.tooShort)
        assertNull(cancelled.state.value.error)
    }
}
