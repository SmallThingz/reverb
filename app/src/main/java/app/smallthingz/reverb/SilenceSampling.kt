package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val SILENCE_SAMPLE_TIMED_MILLIS = 4_000L
internal const val SILENCE_SAMPLE_EDGE_MILLIS = 500L
internal const val SILENCE_SAMPLE_MINIMUM_HELD_INTERIOR_MILLIS = 500L
internal const val SILENCE_SAMPLE_TIMING_UNAVAILABLE_MESSAGE = "Microphone timing is unavailable. Try again."

internal data class SilenceSampleState(
    val progress: Float = 0f,
    val sampleRate: Int = 0,
    val peaks: List<Float> = List(SILENCE_GRAPH_BINS) { -120f },
    val complete: Boolean = false,
    val error: String? = null,
    val cancelled: Boolean = false,
    val held: Boolean = false,
    val elapsedMillis: Long = 0L,
    val tooShort: Boolean = false,
)

/** UI signals control changes; only the audio owner touches the analyzer and PCM. */
internal class SilenceSampleRequest(held: Boolean = false, private val startedAtNanos: Long = System.nanoTime()) {
    private sealed interface Control {
        data object Timed : Control
        data object Held : Control
        data class Released(val atNanos: Long) : Control
    }

    val cancelled = AtomicBoolean(false)
    private val controlLock = Any()
    private var audioAdmitted = false
    private var unclockedPcmObserved = false
    private var clockedPcmObserved = false
    private val control = AtomicReference<Control>(if (held) Control.Held else Control.Timed)
    private val mutableDeadline = MutableStateFlow(startedAtNanos + (if (held) 60_000L else 10_000L) * 1_000_000L)
    val deadlineNanos: StateFlow<Long> = mutableDeadline.asStateFlow()
    @Volatile private var controlWakeup: (() -> Unit)? = null
    private val mutableState = MutableStateFlow(SilenceSampleState(held = held))
    val state: StateFlow<SilenceSampleState> = mutableState.asStateFlow()
    private var analyzer: SilenceGateStream? = null
    private var sourceFormat: Triple<Int, Int, PcmSampleFormat>? = null
    private var heldAudio = held
    private var frames = 0L
    private var analyzedFrames = 0L
    private var lastPublishedFrames = 0L
    private var tail = ByteArray(0)
    private var tailStart = 0
    private var tailCount = 0
    private val peaks = FloatArray(SILENCE_GRAPH_BINS) { -120f }

    fun cancel() {
        cancelled.set(true)
        mutableState.update { it.copy(complete = false, cancelled = true) }
        controlWakeup?.invoke()
    }

    fun fail(message: String) {
        mutableState.update { if (it.cancelled || cancelled.get()) it else it.copy(complete = false, error = message) }
    }

    fun running(): Boolean = !cancelled.get() && !mutableState.value.complete && mutableState.value.error == null

    fun remainingTimeoutMillis(nowNanos: Long = System.nanoTime()): Long =
        silenceSampleRemainingMillis(deadlineNanos.value, nowNanos)

    /** Observed PCM without capture-clock proof is different from no PCM or a short hold. */
    fun captureTimingUnavailable(): Boolean = synchronized(controlLock) {
        unclockedPcmObserved && !clockedPcmObserved && !audioAdmitted
    }

    fun timeoutFailureMessage(fallback: String): String = mutableState.value.error ?:
        if (captureTimingUnavailable()) SILENCE_SAMPLE_TIMING_UNAVAILABLE_MESSAGE else fallback

    fun failSamplingTimeout(fallback: String) = synchronized(controlLock) {
        val message = timeoutFailureMessage(fallback)
        mutableState.update {
            if (it.complete || it.cancelled || it.error != null || cancelled.get()) it
            else it.copy(error = message)
        }
    }

    /** Capture the boundary here, before any delayed audio-handler callback. */
    fun finishHold(atNanos: Long = System.nanoTime()) {
        val changed = synchronized(controlLock) {
            if (!running() || !control.compareAndSet(Control.Held, Control.Released(atNanos))) false
            else {
                // No analyzer exists before the first accepted PCM. A stalled bind must not
                // retain a released hold until the audio handler eventually becomes available.
                if (!audioAdmitted) publishTooShort()
                true
            }
        }
        if (changed) controlWakeup?.invoke()
    }

    /** Reuse pointer-down audio for an ordinary tap without restarting capture. */
    fun useTimedSample() {
        val changed = synchronized(controlLock) {
            if (!running() || !control.compareAndSet(Control.Held, Control.Timed)) false
            else {
                mutableDeadline.value = startedAtNanos + 10_000_000_000L
                true
            }
        }
        if (changed) controlWakeup?.invoke()
    }

    internal fun setControlWakeup(wakeup: () -> Unit) {
        controlWakeup = wakeup
        if (control.get() !is Control.Held) wakeup()
    }

    /** Audio-thread only, including the idle loop which owns that handler until terminal. */
    internal fun processPendingControl() {
        if (!running()) return
        applyTimedConversion()
        if (control.get() is Control.Released) finishHeldAudio()
    }

    /** Only capture-clock-proven audio may enter the calibration window. Ordinary recording is unaffected. */
    fun acceptCaptured(
        bytes: ByteArray, offset: Int, count: Int, rate: Int, channels: Int, format: PcmSampleFormat,
        captureStartedAtNanos: Long?, readEndedAtNanos: Long,
    ) {
        if (!running()) return
        require(rate > 0 && channels > 0)
        val frameBytes = Math.multiplyExact(channels, format.bytesPerSample)
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count && count % frameBytes == 0)
        val usableTiming = captureStartedAtNanos != null && captureStartedAtNanos <= readEndedAtNanos
        if (count > 0) synchronized(controlLock) {
            if (!running()) return
            if (usableTiming) clockedPcmObserved = true else unclockedPcmObserved = true
        }
        if (!usableTiming) {
            processPendingControl()
            return
        }
        val captureStart = requireNotNull(captureStartedAtNanos)
        val readFrames = count / frameBytes
        val beforePress = if (captureStart < startedAtNanos) {
            val delta = startedAtNanos - captureStart
            val duration = (readFrames * 1_000_000_000L + rate - 1L) / rate
            if (delta >= duration) readFrames else ((delta * rate + 999_999_999L) / 1_000_000_000L).toInt()
        } else 0
        // This excludes queued pre-press PCM before the first 500ms is discarded.
        // A lagging native read position over-trims instead of admitting unproven audio.
        accept(bytes, offset + beforePress * frameBytes, count - beforePress * frameBytes,
            rate, channels, format, readEndedAtNanos)
    }

    /** Audio-thread only. Read-end is captured before visualization/FFT work. */
    fun accept(
        bytes: ByteArray, offset: Int, count: Int, rate: Int, channels: Int, format: PcmSampleFormat,
        readEndedAtNanos: Long = System.nanoTime(),
    ) {
        if (!running()) return
        require(rate > 0 && channels > 0)
        val frameBytes = Math.multiplyExact(channels, format.bytesPerSample)
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count && count % frameBytes == 0)
        val configuration = Triple(rate, channels, format)
        if (sourceFormat != null && sourceFormat != configuration) {
            fail("Audio input changed. Sample the room again.")
            return
        }
        applyTimedConversion()
        if (!running()) return

        val released = control.get() as? Control.Released
        // A blocking read can straddle release. Exclude its post-release frames before
        // replacing the rolling tail. Delayed/backlogged delivery is trimmed conservatively.
        val lateFrames = if (released != null && readEndedAtNanos > released.atNanos) {
            val elapsed = (readEndedAtNanos - released.atNanos).coerceAtMost(60_000_000_000L)
            (elapsed * rate + 999_999_999L) / 1_000_000_000L
        } else 0L
        val available = (count / frameBytes - lateFrames).coerceAtLeast(0L)
        val limit = if (heldAudio) rate * 60L else rate * SILENCE_SAMPLE_TIMED_MILLIS / 1000L
        val accepted = minOf(available, (limit - frames).coerceAtLeast(0L)).toInt()
        if (accepted > 0 && analyzer == null) {
            val admitted = synchronized(controlLock) {
                if (!running()) false else { audioAdmitted = true; true }
            }
            if (!admitted) return
            initialize(rate, channels, format)
        }

        val discard = minOf(accepted.toLong(), (edgeFrames(rate) - frames).coerceAtLeast(0L)).toInt()
        val useful = accepted - discard
        if (useful > 0) {
            if (heldAudio) retainTail(bytes, offset + discard * frameBytes, useful * frameBytes)
            else analyze(bytes, offset + discard * frameBytes, useful * frameBytes)
        }
        frames += accepted

        if (control.get() is Control.Released) {
            finishHeldAudio()
        } else if (frames >= limit) {
            if (heldAudio) fail("Sampling timed out. Try again.") else finishAnalyzing()
        } else if (frames - lastPublishedFrames >= rate / 10) publish()
    }

    private fun edgeFrames(rate: Int): Long = (rate * SILENCE_SAMPLE_EDGE_MILLIS + 999L) / 1000L

    private fun initialize(rate: Int, channels: Int, format: PcmSampleFormat) {
        sourceFormat = Triple(rate, channels, format)
        if (heldAudio) tail = ByteArray(edgeFrames(rate).toInt() * channels * format.bytesPerSample)
        analyzer = SilenceGateStream(rate, channels, format, SilenceGateConfig(), object : SilenceGateOutput {
            override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
            override fun gap() = Unit
        }) { spectrum, _ ->
            if (spectrum.invalidSamples) throw java.io.IOException("Invalid microphone samples. Sample the room again.")
            spectrum.accumulateGraphPeaks(peaks)
        }
    }

    private fun applyTimedConversion() {
        if (!heldAudio || control.get() !is Control.Timed) return
        heldAudio = false
        val rate = sourceFormat?.first
        if (rate != null && frames > rate * SILENCE_SAMPLE_TIMED_MILLIS / 1000L) {
            fail("Sample the room again.")
            return
        }
        drainTail(tailCount)
        tail = ByteArray(0)
        publish()
        if (rate != null && frames == rate * SILENCE_SAMPLE_TIMED_MILLIS / 1000L) finishAnalyzing()
    }

    /** Retain only 500ms of interleaved PCM, independent of the length of a hold. */
    private fun retainTail(bytes: ByteArray, offset: Int, count: Int) {
        if (count >= tail.size) {
            drainTail(tailCount)
            analyze(bytes, offset, count - tail.size)
            bytes.copyInto(tail, 0, offset + count - tail.size, offset + count)
            tailStart = 0
            tailCount = tail.size
            return
        }
        drainTail((tailCount + count - tail.size).coerceAtLeast(0))
        val end = (tailStart + tailCount) % tail.size
        val first = minOf(count, tail.size - end)
        bytes.copyInto(tail, end, offset, offset + first)
        bytes.copyInto(tail, 0, offset + first, offset + count)
        tailCount += count
    }

    private fun drainTail(count: Int) {
        if (count == 0) return
        val first = minOf(count, tail.size - tailStart)
        analyze(tail, tailStart, first)
        analyze(tail, 0, count - first)
        tailStart = (tailStart + count) % tail.size
        tailCount -= count
    }

    private fun analyze(bytes: ByteArray, offset: Int, count: Int) {
        if (count == 0) return
        val (_, channels, format) = requireNotNull(sourceFormat)
        requireNotNull(analyzer).accept(bytes, offset, count)
        analyzedFrames += count / (channels * format.bytesPerSample)
    }

    private fun finishHeldAudio() {
        if (!running()) return
        val rate = sourceFormat?.first ?: 0
        // This tail never enters the analyzer on hold release.
        tail = ByteArray(0)
        tailStart = 0
        tailCount = 0
        if (rate == 0 || analyzedFrames < (rate * SILENCE_SAMPLE_MINIMUM_HELD_INTERIOR_MILLIS + 999L) / 1000L) {
            publishTooShort()
        } else finishAnalyzing()
    }

    private fun publishTooShort() {
        val timingUnavailable = captureTimingUnavailable()
        mutableState.update {
            if (it.cancelled || it.complete || it.error != null || cancelled.get()) it
            else if (timingUnavailable) it.copy(complete = false, error = SILENCE_SAMPLE_TIMING_UNAVAILABLE_MESSAGE)
            else it.copy(complete = false, error = "Hold a little longer, then release.", tooShort = true)
        }
    }

    private fun finishAnalyzing() {
        requireNotNull(analyzer).finish()
        publish(complete = true)
    }

    private fun publish(complete: Boolean = false) {
        val rate = sourceFormat?.first ?: 0
        lastPublishedFrames = frames
        val elapsed = if (rate > 0) frames * 1000L / rate else 0L
        val duration = if (heldAudio) SILENCE_SAMPLE_EDGE_MILLIS * 2 + SILENCE_SAMPLE_MINIMUM_HELD_INTERIOR_MILLIS
            else SILENCE_SAMPLE_TIMED_MILLIS
        val progress = if (complete) 1f else (elapsed.toFloat() / duration).coerceIn(0f, 0.99f)
        val observation = SilenceSampleState(progress, rate, peaks.toList(), complete,
            held = heldAudio, elapsedMillis = elapsed)
        mutableState.update { if (it.cancelled || it.error != null || cancelled.get()) it else observation }
    }
}

internal fun silenceSampleRemainingMillis(deadlineNanos: Long, nowNanos: Long): Long {
    val remaining = deadlineNanos - nowNanos
    return if (remaining <= 0L) 0L else (remaining + 999_999L) / 1_000_000L
}
