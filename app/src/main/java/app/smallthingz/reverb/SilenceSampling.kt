package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class SilenceSampleState(
    val progress: Float = 0f,
    val sampleRate: Int = 0,
    val peaks: List<Float> = List(SILENCE_GRAPH_BINS) { -120f },
    val complete: Boolean = false,
    val error: String? = null,
    val cancelled: Boolean = false,
)

/** UI owns only cancellation and immutable observations, never the microphone or PCM. */
internal class SilenceSampleRequest {
    val cancelled = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(SilenceSampleState())
    val state: StateFlow<SilenceSampleState> = mutableState.asStateFlow()
    private var analyzer: SilenceGateStream? = null
    private var sourceFormat: Triple<Int, Int, PcmSampleFormat>? = null
    private var frames = 0L
    private var lastPublishedFrames = 0L
    private val peaks = FloatArray(SILENCE_GRAPH_BINS) { -120f }

    fun cancel() {
        cancelled.set(true)
        mutableState.update { it.copy(complete = false, cancelled = true) }
    }
    fun fail(message: String) {
        mutableState.update { if (it.cancelled || cancelled.get()) it else it.copy(complete = false, error = message) }
    }
    fun running(): Boolean = !cancelled.get() && !mutableState.value.complete && mutableState.value.error == null

    /** Audio-thread only. No samples are stored on disk, and no capture intent is changed. */
    fun accept(bytes: ByteArray, offset: Int, count: Int, rate: Int, channels: Int, format: PcmSampleFormat) {
        if (!running()) return
        val configuration = Triple(rate, channels, format)
        if (sourceFormat != null && sourceFormat != configuration) {
            fail("Audio input changed. Sample the room again.")
            return
        }
        if (analyzer == null) {
            sourceFormat = configuration
            analyzer = SilenceGateStream(rate, channels, format, SilenceGateConfig(), object : SilenceGateOutput {
                override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
                override fun gap() = Unit
            }) { spectrum, _ ->
                if (spectrum.invalidSamples) throw java.io.IOException("Invalid microphone samples. Sample the room again.")
                val graph = spectrum.graph()
                for (i in peaks.indices) peaks[i] = maxOf(peaks[i], graph[i])
            }
        }
        val frameBytes = channels * format.bytesPerSample
        val accepted = minOf(count / frameBytes, (rate * 3L - frames).coerceAtLeast(0L).toInt())
        requireNotNull(analyzer).accept(bytes, offset, accepted * frameBytes)
        frames += accepted
        val complete = frames >= rate * 3L
        if (complete) requireNotNull(analyzer).finish()
        if (complete || frames - lastPublishedFrames >= rate / 10) {
            lastPublishedFrames = frames
            val observation = SilenceSampleState((frames.toFloat() / (rate * 3)).coerceIn(0f, 1f),
                rate, peaks.toList(), complete)
            mutableState.update { if (it.cancelled || it.error != null || cancelled.get()) it else observation }
        }
    }
}
